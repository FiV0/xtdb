package xtdb.operator.join

import org.apache.arrow.memory.BufferAllocator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.assertNull
import org.junit.jupiter.api.extension.ExtendWith
import xtdb.api.query.IKeyFn.KeyFn.SNAKE_CASE_STRING
import xtdb.arrow.Relation
import xtdb.expression.map.IndexHasher.Companion.hasher
import xtdb.test.AllocatorResolver
import xtdb.types.Type
import xtdb.types.Type.Companion.I32
import xtdb.types.Type.Companion.maybe
import xtdb.types.Type.Companion.ofType
import xtdb.types.schema

@ExtendWith(AllocatorResolver::class)
class BuildSideTest {

    private fun BuildSide.getMatches(hash: Int): List<Int> {
        val matches = mutableListOf<Int>()
        forEachMatch(hash) { matches.add(it) }
        return matches
    }

    @Test
    fun testBuildSideWithDiskSpill(al: BufferAllocator) {
        val schema = schema(
            "id" ofType maybe(I32),
            "name" ofType maybe(Type.UTF8),
            "value" ofType maybe(I32)
        )

        val john = mapOf("id" to 1, "name" to "John", "value" to 100)
        val jane = mapOf("id" to 2, "name" to "Jane", "value" to 200)
        val bob = mapOf("id" to 3, "name" to "Bob", "value" to 300)

        val rows = listOf(john, jane, bob)

        val partOneRows = listOf(bob, bob, bob)
        val partTwoRows = listOf(john, jane, john, jane, john, jane)

        Relation.openFromRows(al, rows).use { rel ->
            // without nil row
            BuildSide(
                al, schema, listOf("id"),
                trackUnmatchedIdxs = false,
                withNilRow = false,
                inMemoryThreshold = 5
            ).use { buildSide ->
                buildSide.append(rel)
                buildSide.append(rel)
                buildSide.append(rel)

                buildSide.end()

                assertNotNull(buildSide.spill)

                assertEquals(4, buildSide.partCount)
                buildSide.loadPart(0)
                buildSide.builtRel.let { rel ->
                    assertEquals(3, rel.rowCount)
                    assertEquals(partOneRows, rel.toMaps(SNAKE_CASE_STRING))

                    val hasher = rel.hasher(listOf("id"))
                    val bobHash = hasher.hashCode(0)
                    assertEquals(listOf(0, 1, 2), buildSide.getMatches(bobHash).sorted())
                }

                buildSide.loadPart(1)
                buildSide.builtRel.let { rel ->
                    assertEquals(6, rel.rowCount)
                    assertEquals(partTwoRows, rel.toMaps(SNAKE_CASE_STRING))

                    val hasher = rel.hasher(listOf("id"))
                    val johnHash = hasher.hashCode(0)
                    assertEquals(listOf(0, 2, 4), buildSide.getMatches(johnHash).sorted())
                }

                buildSide.loadPart(2)
                assertEquals(0, buildSide.builtRel.rowCount)

                buildSide.loadPart(3)
                assertEquals(0, buildSide.builtRel.rowCount)
            }

            // with nil row
            BuildSide(
                al, schema, listOf("id"),
                trackUnmatchedIdxs = false,
                withNilRow = true,
                inMemoryThreshold = 5
            ).use { buildSide ->
                buildSide.append(rel)
                buildSide.append(rel)
                buildSide.append(rel)

                buildSide.end()

                assertNotNull(buildSide.spill)

                assertEquals(4, buildSide.partCount)

                buildSide.loadPart(0)
                buildSide.builtRel.let { rel ->
                    assertEquals(4, rel.rowCount)

                    assertEquals(partOneRows + emptyMap(), rel.toMaps(SNAKE_CASE_STRING))
                }

                buildSide.loadPart(1)
                buildSide.builtRel.let { rel ->
                    assertEquals(7, rel.rowCount)

                    assertEquals(partTwoRows + emptyMap(), rel.toMaps(SNAKE_CASE_STRING))
                }

                buildSide.loadPart(2)
                assertEquals(1, buildSide.builtRel.rowCount)

                buildSide.loadPart(3)
                assertEquals(1, buildSide.builtRel.rowCount)
            }
        }
    }

    @Test
    fun testBuildSideWithoutDiskSpill(al: BufferAllocator) {
        val schema = schema(
            "id" ofType maybe(I32),
            "name" ofType maybe(Type.UTF8),
            "value" ofType maybe(I32)
        )

        val rows = listOf(
            mapOf("id" to 1, "name" to "John", "value" to 100),
            mapOf("id" to 2, "name" to "Jane", "value" to 200),
            mapOf("id" to 3, "name" to "Bob", "value" to 300)
        )

        Relation(al, schema).use { rel ->
            rel.writeRows(*rows.toTypedArray())

            BuildSide(
                al, schema, listOf("id"),
                trackUnmatchedIdxs = false,
                withNilRow = false,
                inMemoryThreshold = 5
            ).use { buildSide ->
                buildSide.append(rel)

                buildSide.end()

                assertNull(buildSide.spill)

                val builtRelation = buildSide.builtRel

                assertEquals(3, builtRelation.rowCount)

                assertEquals(rows, builtRelation.toMaps(SNAKE_CASE_STRING))
            }
        }
    }
}
