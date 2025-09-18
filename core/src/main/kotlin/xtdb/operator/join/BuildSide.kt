package xtdb.operator.join

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.types.pojo.Schema
import org.roaringbitmap.RoaringBitmap
import xtdb.arrow.IntVector
import xtdb.arrow.Relation
import xtdb.arrow.RelationReader
import xtdb.arrow.VectorReader
import xtdb.expression.map.IndexHasher
import xtdb.vector.OldRelationWriter
import java.util.function.IntConsumer
import java.util.function.IntUnaryOperator

internal const val NULL_ROW_IDX = 0

class BuildSide(
    val al: BufferAllocator,
    val schema: Schema,
    val keyColNames: List<String>,
    val matchedBuildIdxs: RoaringBitmap?,
    private val withNilRow: Boolean
) : AutoCloseable {
    private val relWriter = Relation(al, schema)

    private val hashColumn: IntVector = IntVector(al, "xt/join-hash", false)

    private var _builtRel: RelationReader? = null
    val builtRel get() = _builtRel!!
    var buildMap: BuildSideMap? = null

    init {
        if (withNilRow) {
            relWriter.endRow()
        }
    }

    @Suppress("NAME_SHADOWING")
    fun append(inRel: RelationReader) {
        inRel.openDirectSlice(al).use { inRel ->
            val inKeyCols = keyColNames.map { inRel[it] }

            val hasher = IndexHasher.fromCols(inKeyCols)
            val rowCopier = inRel.rowCopier(relWriter)

            repeat(inRel.rowCount) { inIdx ->
                hashColumn.writeInt(hasher.hashCode(inIdx))
                rowCopier.copyRow(inIdx)
            }
        }
    }

    fun build() {
        buildMap?.close()
        buildMap = BuildSideMap.from(al, hashColumn, if (withNilRow) 1 else 0)

        _builtRel?.close()
        _builtRel = RelationReader.from(relWriter.openAsRoot(al))
    }

    fun addMatch(idx: Int) = matchedBuildIdxs?.add(idx)

    fun indexOf(hashCode: Int, cmp: IntUnaryOperator, removeOnMatch: Boolean): Int =
        requireNotNull(buildMap).findValue(hashCode, cmp, removeOnMatch)

    fun forEachMatch(hashCode: Int, c: IntConsumer) =
        requireNotNull(buildMap).forEachMatch(hashCode, c)

    override fun close() {
        buildMap?.close()
        _builtRel?.close()
        relWriter.close()
        hashColumn.close()
    }
}