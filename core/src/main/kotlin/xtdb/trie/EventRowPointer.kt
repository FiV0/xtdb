package xtdb.trie

import org.apache.arrow.memory.util.ArrowBufPointer
import org.apache.arrow.vector.BaseFixedWidthVector
import xtdb.util.compareUnsigned
import xtdb.vector.IVectorReader
import xtdb.vector.RelationReader
import java.nio.ByteBuffer


abstract class EventRowPointer(private val relReader: RelationReader) {
    val iidReader: IVectorReader = relReader.readerForName("xt\$iid")

    private val sysFromReader: IVectorReader = relReader.readerForName("xt\$system_from")
    private val validFromReader: IVectorReader = relReader.readerForName("xt\$valid_from")
    private val validToReader: IVectorReader = relReader.readerForName("xt\$valid_to")

    private val opReader: IVectorReader = relReader.readerForName("op")

    val systemFrom get() = sysFromReader.getLong(index)
    val validFrom get() = validFromReader.getLong(index)
    val validTo get() = validToReader.getLong(index)
    val op get() = opReader.getLeg(index)

    abstract var index: Int internal set
    fun nextIndex() = ++index

    fun isValid(reuse: ArrowBufPointer, path: ByteArray): Boolean =
        index < relReader.rowCount() && HashTrie.compareToPath(getIidPointer(reuse), path) <= 0

    abstract fun getIidPointer(): ByteBuffer
    fun getIidPointer(reuse: ArrowBufPointer): ArrowBufPointer = iidReader.getPointer(index, reuse)

    companion object {
        @JvmStatic
        fun comparator(): Comparator<in EventRowPointer> {
            val leftCmp = ArrowBufPointer()
            val rightCmp = ArrowBufPointer()

            return Comparator { l, r ->
                val cmp = l.getIidPointer(leftCmp).compareTo(r.getIidPointer(rightCmp))
                if (cmp != 0) cmp else r.systemFrom.compareTo(l.systemFrom)
            }
        }

        @JvmStatic
        fun bbComparator(): Comparator<in EventRowPointer> {
            return Comparator { l, r ->
                val cmp = l.getIidPointer().compareUnsigned(r.getIidPointer())
                if (cmp != 0) cmp else r.systemFrom.compareTo(l.systemFrom)
            }
        }

        @JvmStatic
        fun createRowPointer(relReader: RelationReader, path: ByteArray): EventRowPointer {
            return if (relReader.readerForName("xt\$iid").isDirect)
                DirectEventRowPointer(relReader, path)
            else
                IndirectEventRowPointer(relReader, path)
        }
    }
}


class DirectEventRowPointer(relReader: RelationReader, path: ByteArray) : EventRowPointer(relReader) {
    private val iidVec: BaseFixedWidthVector = iidReader.vector as BaseFixedWidthVector
    private val iidByteBuffer: ByteBuffer = iidVec.dataBuffer!!.nioBuffer()
    private val typeWidth: Int = iidVec.typeWidth

    override var index: Int

    init {
        var left = 0
        var right = relReader.rowCount()
        var mid: Int
        while (left < right) {
            mid = (left + right) / 2
            if (HashTrie.compareToPath(iidByteBuffer, mid * typeWidth, path) < 0) left = mid + 1
            else right = mid
        }
        this.index = left
    }

    override fun getIidPointer(): ByteBuffer {
        iidByteBuffer.position(index * typeWidth)
        iidByteBuffer.limit((index + 1) * typeWidth)
        return iidByteBuffer
    }
}

class IndirectEventRowPointer(relReader: RelationReader, path: ByteArray) : EventRowPointer(relReader) {
    private val reuse: ArrowBufPointer = ArrowBufPointer()
//    private val iidVec: BaseFixedWidthVector = iidReader.vector as BaseFixedWidthVector
//    private val iidByteBuffer: ByteBuffer = iidVec.dataBuffer!!.nioBuffer()

    override var index: Int internal set

    init {
        var left = 0
        var right = relReader.rowCount()
        var mid: Int
        while (left < right) {
            mid = (left + right) / 2
            if (HashTrie.compareToPath(iidReader.getPointer(mid), path) < 0) left = mid + 1
            else right = mid
        }
        this.index = left
    }

    override fun getIidPointer(): ByteBuffer {
        val abp = iidReader.getPointer(index, reuse)
//        iidByteBuffer.position(abp.offset.toInt())
//        iidByteBuffer.limit(abp.length.toInt())
//        return iidByteBuffer
        return abp.buf.nioBuffer(abp.offset, abp.length.toInt())
    }
}