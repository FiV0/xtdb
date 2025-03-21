package xtdb.vector

import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.types.pojo.FieldType
import xtdb.arrow.Relation

class RootWriter(private val root: VectorSchemaRoot) : IRelationWriter {
    private val writers: MutableMap<String, IVectorWriter> =
        root.fieldVectors.associateTo(mutableMapOf()) { it.name to writerFor(it) }

    override var rowCount = 0

    override fun iterator() = writers.entries.iterator()

    override fun startRow() = Unit

    override fun endRow() {
        val pos = ++rowCount
        writers.values.forEach { it.populateWithAbsents(pos) }
    }

    internal data class MissingColException(private val colNames: Set<String>, private val colName: String) :
        NullPointerException("Dynamic column creation unsupported in RootWriter")

    override fun colWriter(colName: String): IVectorWriter =
        writers[colName] ?: throw MissingColException(writers.keys, colName)

    // dynamic column creation unsupported in RootWriters
    override fun colWriter(colName: String, fieldType: FieldType) = colWriter(colName)

    override fun syncRowCount() {
        root.syncSchema()
        root.rowCount = rowCount

        writers.values.forEach { it.syncValueCount() }
    }

    override fun openAsRelation() = Relation.fromRoot(root)

    override fun close() {
        writers.values.forEach { it.close() }
    }
}
