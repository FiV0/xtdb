package xtdb.operator.join

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.ipc.ArrowFileReader
import org.apache.arrow.vector.types.Types
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.Schema
import org.roaringbitmap.RoaringBitmap
import xtdb.arrow.*
import xtdb.expression.map.IndexHasher
import xtdb.util.openReadableChannel
import xtdb.util.openWritableChannel
import xtdb.util.useAll
import xtdb.vector.OldRelationWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.IntConsumer
import java.util.function.IntUnaryOperator
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

internal const val NULL_ROW_IDX = 0

class BuildSide(
    val al: BufferAllocator,
    val schema: Schema,
    val keyColNames: List<String>,
    val matchedBuildIdxs: RoaringBitmap?,
    private val withNilRow: Boolean,
    private val inMemoryThreshold: Int = 100_000
) : AutoCloseable {

    internal var toDisk: Boolean = false
    private val diskSchema: Schema =  Schema(schema.fields.plus(HASH_COLUMN_FIELD))
    private val tmpDir = Files.createTempDirectory("xtdb-build-side-tmp")
    private val tmpFile: Path = Files.createTempFile(tmpDir, "build-side", ".arrow")

    private fun internalRelToExternalRel(internalRel: OldRelationWriter) =
        OldRelationWriter(al, internalRel.vectors.filter { it.name != HASH_COLUMN_NAME }).also { it.rowCount = internalRel.rowCount }

    private fun internalRelToHashColumn(internalRel: OldRelationWriter) = internalRel[HASH_COLUMN_NAME]

    // The reference counting should happen on the internalRel
    private val internalRel = OldRelationWriter(al, diskSchema)
    private val externalRel = internalRelToExternalRel(internalRel)
    private val hashColumn: VectorWriter = internalRelToHashColumn(internalRel)
    private val unloadChannel = tmpFile.openWritableChannel()
    private val unloader = ArrowUnloader.open(unloadChannel, diskSchema, ArrowUnloader.Mode.FILE)

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
        if (toDisk) {
            internalRel.asReader.openDirectSlice(al).use { rel ->
                rel.openArrowRecordBatch().use { recordBatch ->
                    unloader.writeBatch(recordBatch)
                }
            }
            hashColumn.clear()
            externalRel.clear()
            internalRel.clear()
        }
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

        if (externalRel.rowCount > inMemoryThreshold) {
            toDisk = true
            finishBatch()
        }
    }

    private fun readInRels() {
        if (toDisk) {
            internalRel.clear()
            ArrowFileReader(tmpFile.openReadableChannel(), al).use { rdr ->
                while (rdr.loadNextBatch()) {
                    val root = rdr.vectorSchemaRoot
                    RelationReader.from(root).use { inRel ->
                        internalRel.append(inRel)
                    }
                }
            }
        }
    }

    fun build() {
        buildMap?.close()
        finishBatch()
        unloader.end()
        if (!toDisk) {
            buildMap = BuildSideMap.from(
                al,
                internalRelToHashColumn(internalRel).asReader.let { if (withNilRow) it.select(1, it.valueCount.dec()) else it },
                if (withNilRow) 1 else 0)
        }
    }

    var nbPartitions: Int = -1

    private fun partitionFile(id: Int) = Files.createTempFile(tmpDir, "build-side-partition-$id", ".arrow")

    fun partition(nbParts: Int) {
        require(nbParts > 1)
        require(nbParts and (nbParts - 1) == 0) { "nbPartitions must be a power of 2" }
        val partitionMask = (1 shl nbParts) - 1
        (0 until nbParts).map { partitionId ->
            partitionFile(partitionId).openWritableChannel() }.useAll{ partitionChannels ->
            partitionChannels.map { Relation(al, diskSchema) }.useAll { rels ->
                rels.mapIndexed { partitionId, rel -> rel.startUnload(partitionChannels[partitionId])}.useAll { unloaders ->
                    Relation.loader(al, tmpFile.openReadableChannel()).use { ldr ->
                        Relation(al, diskSchema).use { inRel ->
                            while (ldr.loadNextPage(inRel)) {
                                val hashCol = inRel.vectorFor(HASH_COLUMN_NAME)
                                val rowCopiers = rels.map { it.rowCopier(inRel) }
                                repeat(inRel.rowCount) { inIdx ->
                                    rowCopiers[hashCol.getInt(inIdx) and partitionMask].copyRow(inIdx)
                                }
                                unloaders.forEach { it.writePage() }
                            }
                        }

                    }
//                    ArrowFileReader(tmpFile.openReadableChannel(), al).use { rdr ->
//                        while (rdr.loadNextBatch()) {
//                            val root = rdr.vectorSchemaRoot
//                            RelationReader.from(root).use { inRel ->
//                                val hashCol = inRel.vectorFor(HASH_COLUMN_NAME)
//                                val rowCopiers = rels.map { it.rowCopier(inRel) }
//                                repeat(inRel.rowCount) { inIdx ->
//                                    val partitionId = hashCol.getInt(inIdx) and partitionMask
//                                    rowCopiers[partitionId].copyRow(inIdx)
//                                }
//                            }
//                            unloaders.forEach { it.writePage() }
//                        }
//                    }
                    unloaders.forEach { it.end() }
                }
            }
        }
        nbPartitions = nbParts
    }

    private fun loadPartition(partitionIdx : Int) {
        internalRel.clear()
        partitionFile(partitionIdx).openReadableChannel().use{ ch ->
            ArrowFileReader(ch, al).use { rdr ->
                while (rdr.loadNextBatch()) {
                    val root = rdr.vectorSchemaRoot
                    RelationReader.from(root).use { inRel ->
                        internalRel.append(inRel)
                    }
                }
            }
        }
    }

    internal fun forEachPartition(action: Runnable){
        (0 until nbPartitions).forEach { partitionIdx ->
            loadPartition(partitionIdx)
            buildMap?.close()
            buildMap = BuildSideMap.from(
                al,
                internalRelToHashColumn(internalRel).asReader.let { if (withNilRow) it.select(1, it.valueCount.dec()) else it },
                if (withNilRow) 1 else 0)
            action.run()
        }
    }

    internal val builtRel get() = internalRelToExternalRel(internalRel).asReader

    val rowCount get() = internalRel.rowCount

    fun select(idxs: IntArray): RelationReader =
        if (!toDisk) builtRel.select(idxs)
        else {
            TODO()
        }

    fun addMatch(idx: Int) = matchedBuildIdxs?.add(idx)

    fun indexOf(hashCode: Int, cmp: IntUnaryOperator, removeOnMatch: Boolean): Int =
        if (!toDisk) requireNotNull(buildMap).findValue(hashCode, cmp, removeOnMatch)
        else {
            var maxIndex = -1
            forEachPartition { maxIndex = maxOf(maxIndex, requireNotNull(buildMap).findValue(hashCode, cmp, removeOnMatch)) }
            maxIndex
        }

    fun forEachMatch(hashCode: Int, c: IntConsumer) =
        if (!toDisk) requireNotNull(buildMap).forEachMatch(hashCode, c)
        else forEachPartition {  requireNotNull(buildMap).forEachMatch(hashCode, c) }

    @OptIn(ExperimentalPathApi::class)
    override fun close() {
        buildMap?.close()
        internalRel.close()
        unloader.close()
        unloadChannel.close()
        tmpDir.deleteRecursively()
    }
}
