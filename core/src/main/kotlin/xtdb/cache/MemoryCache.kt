@file:Suppress("SpellCheckingInspection")

package xtdb.cache

import com.github.benmanes.caffeine.cache.RemovalCause
import io.netty.util.internal.PlatformDependent
import org.apache.arrow.memory.ArrowBuf
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.memory.ForeignAllocation
import org.apache.arrow.memory.util.MemoryUtil
import org.apache.arrow.vector.ipc.message.ArrowRecordBatch
import xtdb.cache.PinningCache.IEntry
import java.nio.ByteBuffer
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/**
 * NOTE: the allocation count metrics in the provided `allocator` WILL NOT be accurate
 *   - we allocate a buffer in here for every _usage_, and don't take shared memory into account.
 */
class MemoryCache
@JvmOverloads constructor(
    private val allocator: BufferAllocator,

    @Suppress("MemberVisibilityCanBePrivate")
    val maxSizeBytes: Long,
    private val pathLoader: PathLoader = PathLoader()
) : AutoCloseable {
    private val pinningCache = PinningCache<Key, Entry>(maxSizeBytes)

    val stats get() = pinningCache.stats

    interface PathLoader {
        fun load(path: Path): ByteBuffer
        fun load(path: Path, start: Long, length: Long): ByteBuffer
        fun tryFree(bbuf: ByteBuffer) {}

        companion object {
            operator fun invoke() = object : PathLoader {
                override fun load(path: Path) =
                    try {
                        val ch = FileChannel.open(path)
                        val size = ch.size()

                        ch.map(FileChannel.MapMode.READ_ONLY, 0, size)
                    } catch (e: ClosedByInterruptException) {
                        throw InterruptedException(e.message)
                    }

                override fun load(path: Path, start: Long, length: Long): ByteBuffer {
                    val ch = FileChannel.open(path)
                    return ch.map(FileChannel.MapMode.READ_ONLY, start, length)

                }

                override fun tryFree(bbuf: ByteBuffer) {
                    PlatformDependent.freeDirectBuffer(bbuf)
                }
            }
        }
    }

    data class Key(override val path: Path, val start: Long, val length: Long) : PinningCache.IKey

    private inner class Entry(
        val inner: IEntry,
        val onEvict: AutoCloseable?,
        val bbuf: ByteBuffer
    ) : IEntry by inner {
        override fun onEvict(k: Path, reason: RemovalCause) {
            pathLoader.tryFree(bbuf)
            onEvict?.close()
        }
    }

    @FunctionalInterface
    fun interface Fetch {
        /**
         * @return a pair containing the on-disk path and an optional cleanup action
         */
        operator fun invoke(k: Key): CompletableFuture<Pair<Key, AutoCloseable?>>
    }

    @Suppress("NAME_SHADOWING")
    fun get(k: Path, start: Long, length: Long, fetch: Fetch): ArrowBuf {
        val key = Key(k, start, length)
        val entry = pinningCache.get(key) { k ->
            fetch(key).thenApplyAsync { (key, onEvict) ->
                val bbuf = pathLoader.load(key.path, key.start, key.length)
                Entry(pinningCache.Entry(bbuf.capacity().toLong()), onEvict, bbuf)
            }
        }.get()!!

        val bbuf = entry.bbuf

        // create a new ArrowBuf for each request.
        // when the ref-count drops to zero, we release a ref-count in the cache.
        return allocator.wrapForeignAllocation(
            object : ForeignAllocation(bbuf.capacity().toLong(), MemoryUtil.getByteBufferAddress(bbuf)) {
                override fun release0() {
                    pinningCache.releaseEntry(key)
                }
            })
    }

    fun invalidate(k: Path, start: Long, length: Long) {
        pinningCache.invalidate(Key(k, start, length))
    }

    override fun close() {
        pinningCache.close()
    }
}