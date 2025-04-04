package xtdb

import org.apache.arrow.vector.ipc.message.ArrowFooter
import org.apache.arrow.vector.ipc.message.ArrowRecordBatch
import xtdb.api.storage.ObjectStore.StoredObject
import xtdb.arrow.Relation
import xtdb.arrow.unsupported
import java.nio.ByteBuffer
import java.nio.file.Path

interface BufferPool : AutoCloseable {
    /**
     * Get the whole file as an on-heap byte array.
     *
     * This should only be used for small files (metadata) or testing purposes.
     */
    fun getByteArray(key: Path): ByteArray

    /**
     * Returns the footer of the given arrow file.
     *
     * Throws if not an arrow file.
     */
    fun getFooter (key: Path): ArrowFooter

    /**
     * Returns a record batch of the given arrow file.
     *
     * Throws if not an arrow file or the record batch is out of bounds.
     */
    fun getRecordBatch(key: Path, idx: Int): ArrowRecordBatch

    fun putObject(key: Path, buffer: ByteBuffer)

    /**
     * Recursively lists all objects in the buffer pool
     *
     * Objects are returned in lexicographic order of their path names.
     */
    fun listAllObjects(): Iterable<StoredObject>

    /**
     * Recursively lists all objects in the buffer pool, under the given directory.
     *
     * Objects are returned in lexicographic order of their path names.
     */
    fun listAllObjects(dir: Path): Iterable<StoredObject>

    fun deleteAllObjects()

    fun deleteIfExists(key: Path)

    fun openArrowWriter(key: Path, rel: Relation): ArrowWriter

    companion object {
        @JvmField
        val UNUSED = object : BufferPool {

            override fun getByteArray(key: Path) = unsupported("getByteArray")
            override fun getFooter(key: Path) = unsupported("getFooter")
            override fun getRecordBatch(key: Path, idx: Int) = unsupported("getRecordBatch")
            override fun putObject(key: Path, buffer: ByteBuffer) = unsupported("putObject")
            override fun listAllObjects() = unsupported("listAllObjects")
            override fun listAllObjects(dir: Path) = unsupported("listAllObjects")
            override fun deleteAllObjects() = unsupported("deleteObject")
            override fun deleteIfExists(key: Path) = unsupported("deleteIfExists")
            override fun openArrowWriter(key: Path, rel: Relation) = unsupported("openArrowWriter")

            override fun close() = Unit
        }
    }
}
