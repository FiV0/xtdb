package xtdb.operator.join

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.types.Types
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.Schema
import org.roaringbitmap.RoaringBitmap
import xtdb.arrow.*
import xtdb.expression.map.IndexHasher
import xtdb.util.openWritableChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.IntConsumer
import java.util.function.IntUnaryOperator
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists

internal const val NULL_ROW_IDX = 0
internal var IN_MEMORY_THRESHOLD = 1_000_000

class BuildSide(
    val al: BufferAllocator,
    val schema: Schema,
    val keyColNames: List<String>,
    val matchedBuildIdxs: RoaringBitmap?,
    private val withNilRow: Boolean
) : AutoCloseable {

    private var toDisk: Boolean = false
    private val diskSchema: Schema =  Schema(schema.fields.plus(HASH_COLUMN_FIELD))
    private val tmpFile: Path =
        Files.createTempDirectory("xtdb-build-side-tmp").let { rootPath ->
            Files.createTempFile(rootPath.createDirectories(), "build-side", ".arrow") }

    private fun internalRelToExternalRel(internalRel: Relation) =
        Relation(al, internalRel.vectors.filter { it.name != HASH_COLUMN_NAME }, internalRel.rowCount)

    private fun internalRelToHashColumn(internalRel: Relation) =
        internalRel.vectorForOrNull(HASH_COLUMN_NAME) as IntVector

    // The reference counting should happen on the internalRel
    private val internalRel = Relation(al, diskSchema)
    private val externalRel = internalRelToExternalRel(internalRel)
    private val hashColumn: IntVector = internalRelToHashColumn(internalRel)
    private val unloader = runCatching { internalRel.startUnload(tmpFile.openWritableChannel()) }
        .onFailure { internalRel.close() }
        .getOrThrow()

    var buildMap: BuildSideMap? = null

    init {
        if (withNilRow) {
            hashColumn.writeInt(0)
            externalRel.endRow()
            internalRel.rowCount = 1
        }
    }

    companion object {
        const val HASH_COLUMN_NAME = "xt/join-hash"
        val HASH_COLUMN_FIELD: Field = Field.notNullable(HASH_COLUMN_NAME, Types.MinorType.INT.type)
    }

    private fun finishBatch() {
        internalRel.rowCount = externalRel.rowCount
        unloader.writePage()
        hashColumn.clear()
        externalRel.clear()
        internalRel.clear()
    }

    @Suppress("NAME_SHADOWING")
    fun append(inRel: RelationReader) {
        val inKeyCols = keyColNames.map { inRel.vectorForOrNull(it) as VectorReader }

        val hasher = IndexHasher.fromCols(inKeyCols)
        val rowCopier = externalRel.rowCopier(inRel)

        repeat(inRel.rowCount) { inIdx ->
            hashColumn.writeInt(hasher.hashCode(inIdx))
            rowCopier.copyRow(inIdx)
        }

        if (externalRel.rowCount > IN_MEMORY_THRESHOLD) {
            toDisk = true
            finishBatch()
        }
    }

    private fun readInRels() {
        if (toDisk) {
            unloader.end()
            internalRel.clear()
            Relation.loader(al, tmpFile).use { loader ->
                Relation(al, diskSchema).use { loadRel ->
                    while (loader.loadNextPage(loadRel)){
                        val rowCopier = internalRel.rowCopier(loadRel)
                        repeat(loadRel.rowCount) { loadIdx ->
                            rowCopier.copyRow(loadIdx)
                        }
                    }
                }
            }
        }
    }

    fun build() {
        buildMap?.close()
        finishBatch()
        readInRels()
        buildMap = BuildSideMap.from(
            al,
            internalRelToHashColumn(internalRel).let { if (withNilRow) it.select(1, it.valueCount.dec()) else it },
            if (withNilRow) 1 else 0)
    }

    val builtRel get() = internalRelToExternalRel(internalRel)

    fun addMatch(idx: Int) = matchedBuildIdxs?.add(idx)

    fun indexOf(hashCode: Int, cmp: IntUnaryOperator, removeOnMatch: Boolean): Int =
        requireNotNull(buildMap).findValue(hashCode, cmp, removeOnMatch)

    fun forEachMatch(hashCode: Int, c: IntConsumer) =
        requireNotNull(buildMap).forEachMatch(hashCode, c)

    override fun close() {
        buildMap?.close()
        internalRel.close()
        unloader.close()
        tmpFile.deleteIfExists()
    }
}
