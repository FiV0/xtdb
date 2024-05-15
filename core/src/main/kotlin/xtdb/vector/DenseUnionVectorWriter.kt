package xtdb.vector

import clojure.lang.Keyword
import org.apache.arrow.vector.ValueVector
import org.apache.arrow.vector.complex.DenseUnionVector
import org.apache.arrow.vector.complex.replaceChild
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import xtdb.isSubType
import xtdb.toArrowType
import xtdb.toLeg
import java.nio.ByteBuffer
import java.util.HashMap
import java.util.LinkedHashMap

class DenseUnionVectorWriter(
    override val vector: DenseUnionVector,
    override val notify: FieldChangeListener?,
) : IVectorWriter {
    private val wp = IVectorPosition.build(vector.valueCount)
    override var field: Field = vector.field

    private val childFields: MutableMap<String, Field> =
        field.children.associateByTo(LinkedHashMap()) { childField -> childField.name }

    private inner class ChildWriter(private val inner: IVectorWriter, val typeId: Byte) : IVectorWriter {
        override val vector get() = inner.vector
        override val field: Field get() = inner.field
        override val notify: FieldChangeListener? get() = inner.notify
        private val parentDuv get() = this@DenseUnionVectorWriter.vector
        private val parentWP get() = this@DenseUnionVectorWriter.wp

        override fun writerPosition() = inner.writerPosition()

        override fun clear() = inner.clear()

        private fun writeValue() {
            val pos = parentWP.getPositionAndIncrement()
            parentDuv.setTypeId(pos, typeId)
            parentDuv.setOffset(pos, this.writerPosition().position)
        }

        override fun writeNull() {
            writeValue(); inner.writeNull()
        }

        override fun writeBoolean(v: Boolean) {
            writeValue(); inner.writeBoolean(v)
        }

        override fun writeByte(v: Byte) {
            writeValue(); inner.writeByte(v)
        }

        override fun writeShort(v: Short) {
            writeValue(); inner.writeShort(v)
        }

        override fun writeInt(v: Int) {
            writeValue(); inner.writeInt(v)
        }

        override fun writeLong(v: Long) {
            writeValue(); inner.writeLong(v)
        }

        override fun writeFloat(v: Float) {
            writeValue(); inner.writeFloat(v)
        }

        override fun writeDouble(v: Double) {
            writeValue(); inner.writeDouble(v)
        }

        override fun writeBytes(v: ByteBuffer) {
            writeValue(); inner.writeBytes(v)
        }

        override fun writeObject0(obj: Any) {
            writeValue(); inner.writeObject0(obj)
        }

        override fun structKeyWriter(key: String) = inner.structKeyWriter(key)
        override fun structKeyWriter(key: String, fieldType: FieldType) = inner.structKeyWriter(key, fieldType)

        override fun startStruct() = inner.startStruct()

        override fun endStruct() {
            writeValue(); inner.endStruct()
        }

        override fun listElementWriter() = inner.listElementWriter()
        override fun listElementWriter(fieldType: FieldType) = inner.listElementWriter(fieldType)

        override fun startList() = inner.startList()

        override fun endList() {
            writeValue(); inner.endList()
        }

        override fun writeValue0(v: IValueReader) {
            writeValue(); inner.writeValue0(v)
        }

        override fun maybePromote(field: Field): IVectorWriter {
            val newWriter = ChildWriter(inner.maybePromote(field), typeId)
            val newField = newWriter.field
            this@DenseUnionVectorWriter.writersByLeg[newField.type.toLeg()] = newWriter
            upsertChildField(newField)
            return newWriter
        }

        override fun rowCopier(src: ValueVector): IRowCopier {
            assert(isSubType(src.field, field))
            val innerCopier = inner.rowCopier(src)

            return IRowCopier { srcIdx ->
                writeValue()
                innerCopier.copyRow(srcIdx)
            }
        }

        override fun rowCopier(src: RelationReader): IRowCopier {
            assert(isSubType(src.field, field))
            val innerCopier = inner.rowCopier(src)

            return IRowCopier { srcIdx ->
                writeValue()
                innerCopier.copyRow(srcIdx)
            }
        }
    }

    private fun upsertChildField(childField: Field) {
        childFields[childField.name] = childField
        field = Field(field.name, field.fieldType, childFields.values.toList())
        notify(field)
    }

    private fun writerFor(child: ValueVector, typeId: Byte) =
        ChildWriter(writerFor(child, ::upsertChildField), typeId)

    private val writersByLeg: MutableMap<Keyword, IVectorWriter> = vector.mapIndexed { typeId, child ->
        Keyword.intern(child.name) to writerFor(child, typeId.toByte())
    }.toMap(HashMap())

    override fun writerPosition() = wp

    override fun clear() {
        super.clear()
        writersByLeg.values.forEach(IVectorWriter::clear)
    }

    override fun writeNull() {
        // DUVs can't technically contain null, but when they're stored within a nullable struct/list vector,
        // we don't have anything else to write here :/

        wp.getPositionAndIncrement()
    }

    override fun writeObject(obj: Any?): Unit = if (obj == null) legWriter(ArrowType.Null.INSTANCE).writeNull() else writeObject0(obj)
    override fun writeObject0(obj: Any): Unit = legWriter(obj.toArrowType()).writeObject0(obj)

    // DUV overrides the nullable one because DUVs themselves can't be null.
    override fun writeValue(v: IValueReader) {
        legWriter(v.leg!!).writeValue(v)
    }

    override fun writeValue0(v: IValueReader) = throw UnsupportedOperationException()

    private data class MissingLegException(val available: Set<Keyword>, val requested: Keyword) : NullPointerException()

    override fun legWriter(leg: Keyword) =
        writersByLeg[leg] ?: throw MissingLegException(writersByLeg.keys, leg)

    private fun promoteLeg(legWriter: IVectorWriter, fieldType: FieldType): IVectorWriter {
        val typeId = (legWriter as ChildWriter).typeId

        return writerFor(legWriter.promote(fieldType, vector.allocator), typeId).also { newLegWriter ->
            vector.replaceChild(typeId, newLegWriter.vector)
            upsertChildField(newLegWriter.field)
            writersByLeg[Keyword.intern(newLegWriter.field.name)] = newLegWriter
        }
    }

    @Suppress("NAME_SHADOWING")
    override fun legWriter(leg: Keyword, fieldType: FieldType): IVectorWriter {
        val isNew = leg !in writersByLeg

        var w: IVectorWriter = writersByLeg.computeIfAbsent(leg) { leg ->
            val field = Field(leg.sym.name, fieldType, emptyList())
            val typeId = vector.registerNewTypeId(field)

            val child = vector.addVector(typeId, fieldType.createNewSingleVector(field.name, vector.allocator, null))
            writerFor(child, typeId)
        }

        if (isNew) {
            upsertChildField(w.field)
        } else if(fieldType.isNullable && !w.field.isNullable) {
            w = promoteLeg(w, fieldType)
        }

        if (fieldType.type != w.field.type) {
            throw FieldMismatch(w.field.fieldType, fieldType)
        }

        return w
    }

    override fun legWriter(leg: ArrowType) = legWriter(leg.toLeg(), FieldType.notNullable(leg))

    private fun duvRowCopier(src: DenseUnionVector): IRowCopier {
        val copierMapping = src.map { childVec ->
            val childField = childVec.field
            legWriter(Keyword.intern(childField.name), childField.fieldType).maybePromote(childField).rowCopier(childVec)
        }

        return IRowCopier { srcIdx ->
            copierMapping[src.getTypeId(srcIdx).also { check(it >= 0) }.toInt()]
                .copyRow(src.getOffset(srcIdx))
        }
    }

    private fun rowCopier0(src: ValueVector): IRowCopier {
        val srcField = src.field
        return legWriter(srcField.type.toLeg(), srcField.fieldType).rowCopier(src)
    }

    override fun maybePromote(field: Field): IVectorWriter {
        when (field.type) {
            is ArrowType.Union -> {
                for (potentialNewChild in field.children) {
                    val potentialNewLeg = potentialNewChild.type.toLeg()
                    when (val legWriter = writersByLeg[potentialNewLeg]) {
                        null ->  legWriter(potentialNewLeg, potentialNewChild.fieldType).maybePromote(potentialNewChild)
                        else ->
                            if (potentialNewChild.fieldType != legWriter.field.fieldType) {
                                promoteLeg(legWriter, potentialNewChild.fieldType).maybePromote(potentialNewChild)
                            } else {
                                legWriter.maybePromote(potentialNewChild)
                            }
                    }
                }
            }
            else -> {
                val potentialNewLeg = field.type.toLeg()
                when (val legWriter = writersByLeg[potentialNewLeg]) {
                    null ->  legWriter(potentialNewLeg , field.fieldType).maybePromote(field)
                    else ->
                        if (field.type != legWriter.field.type && (field.isNullable || !legWriter.field.isNullable)) {
                            promoteLeg(legWriter, field.fieldType).maybePromote(field)
                        } else {
                            legWriter.maybePromote(field)
                        }
                }
            }
        }
        notify(field)
        return this
    }

    override fun rowCopier(src: ValueVector): IRowCopier {
        assert(isSubType(src.field, field)) { "${src.field} is not a subtype of $field" }
        return if (src is DenseUnionVector) duvRowCopier(src)
        else rowCopier0(src)
    }
}
