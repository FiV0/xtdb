package xtdb.buffer_pool

import com.github.benmanes.caffeine.cache.Cache
import io.micrometer.core.instrument.Counter
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.ipc.SeekableReadChannel
import org.apache.arrow.vector.ipc.message.ArrowFooter
import org.apache.arrow.vector.ipc.message.ArrowRecordBatch
import xtdb.IBufferPool
import xtdb.IEvictBufferTest
import xtdb.arrow.ArrowUtil
import xtdb.arrow.Relation
import xtdb.cache.MemoryCache
import xtdb.cache.PathSlice
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.ClosedByInterruptException
import java.nio.file.*
import java.util.concurrent.CompletableFuture
import kotlin.io.path.exists
import kotlin.io.path.fileSize

class LocalBufferPool(
    private val allocator: BufferAllocator,
    private val memoryCache: MemoryCache,
    private val diskStore: Path,
    private val arrowFooterCache: Cache<Path, ArrowFooter>,
    private val recordBatchRequests: Counter,
    private val memCacheMisses: Counter
) : IBufferPool, IEvictBufferTest, Closeable {

    private fun createTempPath(diskStore: Path): Path {
        val tmpDir = diskStore.resolve(".tmp").also { path ->
            Files.createDirectories(path)
        }
        return Files.createTempFile(tmpDir, "upload", ".arrow")
    }

    private fun pathToSeekableByteChannel(path: Path): SeekableReadChannel {
        return SeekableReadChannel(
            Files.newByteChannel(path, StandardOpenOption.READ)
        )
    }

    private fun resolvePathSlice(pathSlice: PathSlice, newPath: Path): PathSlice {
        return PathSlice(newPath, pathSlice.offset, pathSlice.length)
    }

    private fun objectMissingExcepiton(path: Path): IllegalStateException {
        return IllegalStateException("Object $path doesn't exist.")
    }

    override fun getByteArray(key: Path): ByteArray {
        return key.let { k ->
            memoryCache.get(PathSlice(k)) { pathSlice ->
                memCacheMisses.increment()
                val bufferCachePath = diskStore.resolve(pathSlice.path)

                if (!bufferCachePath.exists()) {
                    throw objectMissingExcepiton(k)
                }

                CompletableFuture.completedFuture(
                    Pair(resolvePathSlice(pathSlice, bufferCachePath), null)
                )
            }.use { arrowBuf ->
                ArrowUtil.arrowBufToByteArray(arrowBuf)
            }
        }
    }

    override fun getFooter(key: Path): ArrowFooter {
        val path = diskStore.resolve(key)
        if (!path.exists()) {
            throw objectMissingExcepiton(path)
        }

        return arrowFooterCache.get(key) {
            Relation.readFooter(pathToSeekableByteChannel(path))
        }
    }

    override fun getRecordBatch(key: Path, blockIdx: Int): ArrowRecordBatch {
        recordBatchRequests.increment()
        val path = diskStore.resolve(key)

        if (!path.exists()) {
            throw objectMissingExcepiton(path)
        }

        val footer = arrowFooterCache.get(key) {
            Relation.readFooter(pathToSeekableByteChannel(path))
        }

        val block = footer.recordBatches.getOrNull(blockIdx)
            ?: throw IndexOutOfBoundsException("Record batch index out of bounds of arrow file")

        return memoryCache.get(
            PathSlice(key, block.offset, block.metadataLength + block.bodyLength)
        ) { pathSlice ->
            memCacheMisses.increment()
            val bufferCachePath = diskStore.resolve(pathSlice.path)

            if (!bufferCachePath.exists()) {
                throw objectMissingExcepiton(path)
            }

            CompletableFuture.completedFuture(
                Pair(resolvePathSlice(pathSlice, bufferCachePath), null)
            )
        }.use { arrowBuf ->
            ArrowUtil.arrowBufToRecordBatch(
                arrowBuf,
                0,
                block.metadataLength,
                block.bodyLength,
                "Failed opening record batch '$path' at block-idx $blockIdx"
            )
        }
    }

    private fun writeBufferToPath(buffer: ByteBuffer, path: Path) {
        Files.newByteChannel(
            path,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.CREATE
        ).use { channel ->
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
        }
    }

    override fun putObject(key: Path, buffer: ByteBuffer) {
        try {
            val tmpPath = createTempPath(diskStore)
            writeBufferToPath(buffer, tmpPath)

            val filePath = diskStore.resolve(key)
            Files.createDirectories(filePath.parent)
            Files.move(tmpPath, filePath, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: ClosedByInterruptException) {
            throw InterruptedException()
        }
    }

    override fun listAllObjects(): List<Path> {
        return Files.walk(diskStore).use { stream ->
            stream.filter { path ->
                Files.isRegularFile(path) &&
                        !diskStore.relativize(path).startsWith(".tmp")
            }
                .map { diskStore.relativize(it) }
                .sorted()
                .toList()
        }
    }

    override fun listObjects(dir: Path): List<Path> {
        val dirPath = diskStore.resolve(dir)
        return if (dirPath.exists()) {
            Files.newDirectoryStream(dirPath).use { stream ->
                stream.map { diskStore.relativize(it) }
                    .sorted()
                    .toList()
            }
        } else {
            emptyList()
        }
    }

    override fun objectSize(key: Path): Long {
        return diskStore.resolve(key).fileSize()
    }

    override fun openArrowWriter(key: Path, rel: Relation): xtdb.ArrowWriter {
        val tmpPath = createTempPath(diskStore)
        val fileChannel = Files.newByteChannel(
            tmpPath,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.CREATE
        )
        val unloader = rel.startUnload(fileChannel)

        return object : xtdb.ArrowWriter {
            override fun writeBatch() {
                try {
                    unloader.writeBatch()
                } catch (e: ClosedByInterruptException) {
                    throw InterruptedException()
                }
            }

            override fun end() {
                unloader.end()
                fileChannel.close()

                val filePath = diskStore.resolve(key)
                Files.createDirectories(filePath.parent)
                Files.move(tmpPath, filePath, StandardCopyOption.ATOMIC_MOVE)
            }

            override fun close() {
                unloader.close()
                if (fileChannel.isOpen) {
                    fileChannel.close()
                }
                Files.deleteIfExists(tmpPath)
            }
        }
    }

    override fun evictCachedBuffer(key: Path) {
        memoryCache.invalidate(PathSlice(key))
    }

    override fun close() {
        memoryCache.close()
        allocator.close()
    }
}