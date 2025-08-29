package xtdb.operator.join

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.types.Types
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.Schema
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.roaringbitmap.RoaringBitmap
import xtdb.arrow.IntVector
import xtdb.arrow.Relation
import xtdb.expression.map.IndexHasher
import xtdb.test.AllocatorResolver
import kotlin.properties.Delegates

@ExtendWith(AllocatorResolver::class)
class BuildSideTest {

    companion object {
        private val originalThreshold: Int = IN_MEMORY_THRESHOLD

        @JvmStatic
        @BeforeAll
        fun setUp() {
            IN_MEMORY_THRESHOLD = 5
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            IN_MEMORY_THRESHOLD = originalThreshold
        }
    }

    private fun BuildSide.getMatches(hash: Int): List<Int> {
        val matches = mutableListOf<Int>()
        forEachMatch(hash) { matches.add(it) }
        return matches
    }

    @Test
    fun testBuildSideWithDiskSpill(al: BufferAllocator) {

        val schema = Schema(listOf(
            Field.nullable ("id", Types.MinorType.INT.type),
            Field.nullable("name", Types.MinorType.VARCHAR.type),
            Field.nullable("value", Types.MinorType.INT.type)
        ))

        val relation = Relation.openFromRows(al, listOf(
            mapOf("id" to 1, "name" to "John", "value" to 100),
            mapOf("id" to 2, "name" to "Jane", "value" to 200),
            mapOf("id" to 3, "name" to "Bob", "value" to 300)
        ))

        BuildSide(al, schema, listOf("id"), RoaringBitmap(), false).use { buildSide ->
            buildSide.append(relation)
            buildSide.append(relation)
            buildSide.append(relation)

            buildSide.build()

            val builtRelation = buildSide.builtRel

            assertEquals(9  , builtRelation.rowCount)

            val idVector = builtRelation.vectorFor("id") as IntVector
            val nameVector = builtRelation.vectorFor("name")
            val valueVector = builtRelation.vectorFor("value") as IntVector

            val expectedIds = listOf(1, 2, 3, 1, 2, 3, 1, 2, 3)
            assertEquals(expectedIds, idVector.toList())

            val expectedNames = listOf("John", "Jane", "Bob", "John", "Jane", "Bob", "John", "Jane", "Bob")
            assertEquals(expectedNames, nameVector.toList())

            val expectedValues = listOf(100, 200, 300, 100, 200, 300, 100, 200, 300)
            assertEquals(expectedValues, valueVector.toList())

            val hasher = IndexHasher.fromCols(listOf(idVector))
            val val2Hash = hasher.hashCode(1) // hash for id=2
            val expectedMatches = listOf(1, 4, 7)
            assertEquals(expectedMatches, buildSide.getMatches(val2Hash).sorted())
        }

        BuildSide(al, schema, listOf("id"), RoaringBitmap(), true).use { buildSide ->
            buildSide.append(relation)
            buildSide.append(relation)
            buildSide.append(relation)

            buildSide.build()

            val builtRelation = buildSide.builtRel

            assertEquals(10  , builtRelation.rowCount)

            val idVector = builtRelation.vectorFor("id") as IntVector
            val nameVector = builtRelation.vectorFor("name")
            val valueVector = builtRelation.vectorFor("value") as IntVector

            val expectedIds = listOf(null, 1, 2, 3, 1, 2, 3, 1, 2, 3)
            assertEquals(expectedIds, idVector.toList())

            val expectedNames = listOf(null, "John", "Jane", "Bob", "John", "Jane", "Bob", "John", "Jane", "Bob")
            assertEquals(expectedNames, nameVector.toList())

            val expectedValues = listOf(null, 100, 200, 300, 100, 200, 300, 100, 200, 300)
            assertEquals(expectedValues, valueVector.toList())

            val hasher = IndexHasher.fromCols(listOf(idVector))
            val val2Hash = hasher.hashCode(1) // hash for id=2
            val expectedMatches = listOf(2, 5, 8)
            assertEquals(expectedMatches, buildSide.getMatches(val2Hash).sorted())
        }

        relation.close()

    }
}