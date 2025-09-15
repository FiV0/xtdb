package xtdb.arrow

import org.apache.arrow.memory.ArrowBuf
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.memory.util.ByteFunctionHelpers
import org.apache.arrow.vector.ValueVector
import org.apache.arrow.vector.ipc.message.ArrowFieldNode
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import xtdb.api.query.IKeyFn
import xtdb.arrow.metadata.MetadataFlavour
import xtdb.util.Hasher
import xtdb.util.closeOnCatch
import org.apache.arrow.vector.complex.FixedSizeListVector as ArrowFixedSizeListVector

class FixedSizeListVector private constructor(
    private val al: BufferAllocator,
    override var name: String, override var nullable: Boolean, private val listSize: Int,
    private val validityBuffer: ExtensibleBuffer = ExtensibleBuffer(al),
    private var elVector: Vector,
    override var valueCount: Int = 0
) : Vector(), MetadataFlavour.List {

    constructor(
        al: BufferAllocator, name: String, nullable: Boolean, listSize: Int, elVector: Vector, valueCount: Int = 0
    ) : this(al, name, nullable, listSize, ExtensibleBuffer(al), elVector, valueCount)

    override val type = ArrowType.FixedSizeList(listSize)

    override val vectors: Iterable<Vector> get() = listOf(elVector)

    override fun isNull(idx: Int) = !validityBuffer.getBit(idx)

    override fun writeUndefined() {
        validityBuffer.writeBit(valueCount++, 0)
        repeat(listSize) { elVector.writeUndefined() }
    }

    override fun writeNull() {
        if (!nullable) nullable = true
        writeUndefined()
    }

    private fun writeNotNull() = validityBuffer.writeBit(valueCount++, 1)

    override fun getObject0(idx: Int, keyFn: IKeyFn<*>): List<*> {
        return (idx * listSize until (idx + 1) * listSize).map { elVector.getObject(it, keyFn) }
    }

    override fun writeObject0(value: Any) = when (value) {
        is List<*> -> {
            require(value.size == listSize) { "invalid list size: expected $listSize, got ${value.size}" }
            writeNotNull()
            value.forEach { elVector.writeObject(it) }
        }

        else -> throw InvalidWriteObjectException(fieldType, value)
    }

    override fun writeValue0(v: ValueReader) = writeObject(v.readObject())

    override val listElements get() = elVector

    override fun getListElements(fieldType: FieldType): VectorWriter =
        when {
            elVector.field.fieldType == fieldType -> elVector

            elVector is NullVector && elVector.valueCount == 0 ->
                fromField(al, Field("\$data\$", fieldType, emptyList())).also { elVector = it }

            else -> TODO("promote elVector")
        }

    override fun getListCount(idx: Int) = listSize
    override fun getListStartIndex(idx: Int) = idx * listSize

    override fun endList() {
        writeNotNull()
    }

    override val metadataFlavours get() = listOf(this)

    override fun hashCode0(idx: Int, hasher: Hasher) =
        (0 until listSize).fold(0) { hash, elIdx ->
            ByteFunctionHelpers.combineHash(hash, elVector.hashCode(idx * listSize + elIdx, hasher))
        }

    override fun rowCopier0(src: VectorReader): RowCopier {
        require(src is FixedSizeListVector)
        require(src.listSize == listSize)

        val elCopier = try {
            src.elVector.rowCopier(elVector)
        } catch (_: InvalidCopySourceException) {
            elVector = elVector.maybePromote(al, src.elVector.fieldType)
            src.elVector.rowCopier(elVector)
        }

        return RowCopier { srcIdx ->
            val startIdx = src.getListStartIndex(srcIdx)

            (startIdx until startIdx + listSize).forEach { elIdx ->
                elCopier.copyRow(elIdx)
            }

            valueCount.also { endList() }
        }
    }

    override fun unloadPage(nodes: MutableList<ArrowFieldNode>, buffers: MutableList<ArrowBuf>) {
        nodes.add(ArrowFieldNode(valueCount.toLong(), -1))
        validityBuffer.unloadBuffer(buffers)
        elVector.unloadPage(nodes, buffers)
    }

    override fun loadPage(nodes: MutableList<ArrowFieldNode>, buffers: MutableList<ArrowBuf>) {
        val node = nodes.removeFirst()

        validityBuffer.loadBuffer(buffers.removeFirst())
        elVector.loadPage(nodes, buffers)

        valueCount = node.length
    }

    override fun loadFromArrow(vec: ValueVector) {
        require(vec is ArrowFixedSizeListVector)

        validityBuffer.loadBuffer(vec.validityBuffer)
        elVector.loadFromArrow(vec.dataVector)

        valueCount = vec.valueCount
    }

    override fun clear() {
        validityBuffer.clear()
        elVector.clear()
        valueCount = 0
    }

    override fun close() {
        validityBuffer.close()
        elVector.close()
    }

    override fun openSlice(al: BufferAllocator) =
        validityBuffer.openSlice(al).closeOnCatch { validityBuffer ->
            elVector.openSlice(al).closeOnCatch { elVector ->
                FixedSizeListVector(al, name, nullable, listSize, validityBuffer, elVector, valueCount)
            }
        }
}