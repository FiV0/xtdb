package xtdb.arrow

import org.apache.arrow.memory.ArrowBuf
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.BitVectorHelper
import kotlin.math.max

internal class BitBuffer private constructor(
    private val allocator: BufferAllocator,
    private var buf: ArrowBuf,
    private var writerBitIndex: Int = 0
) : AutoCloseable {

    companion object {
        private fun bufferSize(bitCount: Int) = BitVectorHelper.getValidityBufferSize(bitCount).toLong()
    }

    constructor(allocator: BufferAllocator) : this(allocator, allocator.empty, 0)

    constructor(
        allocator: BufferAllocator, initialBitCapacity: Int
    ) : this(
        allocator,
        allocator.buffer(bufferSize(initialBitCapacity))
            .apply { setZero(0, capacity()) },
        0
    )

    private fun newCapacity(currentCapacity: Long, targetCapacity: Long): Long {
        var newCapacity = max(currentCapacity, 128)
        while (newCapacity < targetCapacity) newCapacity *= 2
        return newCapacity
    }

    private fun realloc(targetCapacity: Long) {
        val currentCapacity = buf.capacity()

        val newBuf = allocator.buffer(newCapacity(currentCapacity, targetCapacity)).apply {
            setBytes(0, buf, 0, currentCapacity)
            setZero(currentCapacity, capacity() - currentCapacity)
            readerIndex(buf.readerIndex())
            writerIndex(buf.writerIndex())
        }

        buf.close()
        buf = newBuf
    }

    fun ensureCapacity(bitCount: Int): ArrowBuf {
        val capacity = bufferSize(bitCount)
        if (buf.capacity() < capacity) realloc(capacity)
        return buf
    }

    fun getBit(idx: Int) = BitVectorHelper.get(buf, idx) == 1

    fun setBit(bitIdx: Int, bit: Int) = BitVectorHelper.setValidityBit(buf, bitIdx, bit)

    fun writeBit(bitIdx: Int, bit: Int) {
        ensureCapacity(bitIdx + 1)
        setBit(bitIdx, bit)
        writerBitIndex = bitIdx + 1
    }

    internal fun unloadBuffer(buffers: MutableList<ArrowBuf>) {
        val writerByteIndex = bufferSize(writerBitIndex)
        buffers.add(buf.readerIndex(0).writerIndex(writerByteIndex))
    }

    internal fun loadBuffer(arrowBuf: ArrowBuf, bitCount: Int) {
        buf.close()
        val writerIndex = bufferSize(bitCount)
        buf = arrowBuf.writerIndex(writerIndex)
            .let { it.referenceManager.transferOwnership(it, allocator).transferredBuffer }
        writerBitIndex = bitCount
    }

    fun openSlice(al: BufferAllocator) =
        BitBuffer(al, buf.referenceManager.transferOwnership(buf, al).transferredBuffer, writerBitIndex)

    fun clear() {
        buf.setZero(0, buf.capacity())
        buf.readerIndex(0)
        buf.writerIndex(0)
        writerBitIndex = 0
    }

    override fun close() {
        buf.close()
    }
}
