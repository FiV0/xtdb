package xtdb.cache

import com.github.benmanes.caffeine.cache.RemovalCause
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.util.Comparator.comparingLong
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletableFuture.completedFuture
import kotlin.io.path.*
import kotlin.math.max
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger(DiskCache::class.java)

class DiskCache(
    val rootPath: Path,

    @Suppress("MemberVisibilityCanBePrivate")
    val maxSizeBytes: Long
) {
    val pinningCache = PinningCache<Key, Entry>(maxSizeBytes)

    val stats get() = pinningCache.stats

    data class Key(override val path: Path) : PinningCache.IKey

    inner class Entry(
        inner: PinningCache.IEntry,
        val k: Key,
        val path: Path,
    ) : PinningCache.IEntry by inner, AutoCloseable {

        override fun onEvict(k: Path, reason: RemovalCause) {
            path.deleteIfExists()
            LOGGER.trace("Evicted $k due to $reason")
            super.onEvict(k, reason)
        }

        constructor(k: Path, path: Path) : this(pinningCache.Entry(path.fileSize()), Key(k), path)

        override fun close() {
            pinningCache.releaseEntry(k)
        }
    }

    init {
        val syncInnerCache = pinningCache.cache.synchronous()
        Files.walk(rootPath)
            .filter { Files.isRegularFile(it) }
            .sorted(comparingLong { path ->
                Files.readAttributes(path, BasicFileAttributes::class.java).let { attrs ->
                    max(attrs.lastAccessTime().toMillis(), attrs.lastModifiedTime().toMillis())
                }
            })
            .forEach { path ->
                val k = rootPath.relativize(path)
                syncInnerCache.put(Key(k), Entry(k, path))
            }
    }

    @Suppress("MemberVisibilityCanBePrivate")
    fun createTempPath(): Path =
        Files.createTempFile(rootPath.resolve(".tmp").createDirectories(), "upload", ".arrow")

    @FunctionalInterface
    fun interface Fetch {
        operator fun invoke(k: Path, tmpFile: Path): CompletableFuture<Path>
    }

    @Suppress("NAME_SHADOWING")
    fun get(k: Path, fetch: Fetch) =
        pinningCache.get(Key(k)) { k ->
            val diskCachePath = rootPath.resolve(k.path)

            if (diskCachePath.exists())
                completedFuture(Entry(k.path, diskCachePath))
            else
                fetch(k.path, createTempPath())
                    .thenApply { tmpPath ->
                        tmpPath.moveTo(diskCachePath.createParentDirectories(), ATOMIC_MOVE, REPLACE_EXISTING)
                        Entry(k.path, diskCachePath)
                    }
        }

    @Suppress("NAME_SHADOWING")
    fun put(k: Path, tmpFile: Path) {
        pinningCache.cache.asMap()
            .computeIfAbsent(Key(k)) { k ->
                val diskCachePath = rootPath.resolve(k.path)
                tmpFile.moveTo(diskCachePath.createParentDirectories(), ATOMIC_MOVE, REPLACE_EXISTING)
                completedFuture(Entry(pinningCache.Entry(diskCachePath.fileSize()), k, diskCachePath))
            }
    }
}
