package xtdb.operator.join

import org.apache.arrow.memory.BufferAllocator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import xtdb.api.query.IKeyFn.KeyFn.KEBAB_CASE_STRING
import xtdb.arrow.Relation
import xtdb.test.AllocatorResolver
import xtdb.types.Type
import xtdb.types.Type.Companion.I32
import xtdb.types.Type.Companion.maybe
import xtdb.types.Type.Companion.ofType
import xtdb.types.schema

@ExtendWith(AllocatorResolver::class)
class ShuffleTest {

    @Test
    fun testShuffle(al: BufferAllocator) {
        val schema = schema(
            "id" ofType maybe(I32),
            "name" ofType maybe(Type.UTF8),
            "value" ofType maybe(I32)
        )

        val rowCount = 30_000L
        val blockCount = 100
        val approxPartCount = rowCount / blockCount
        val rows = mutableListOf<Map<Any, *>>()

        for (i in 1.. rowCount) {
            rows.add(mapOf("id" to i, "name" to "name$i", "value" to i * 100))
        }

        Relation.openFromRows(al, rows).use { inRel ->
            Shuffle.open(al, inRel, listOf("id"), rowCount, blockCount).use { shuffle ->
                shuffle.shuffle()
                shuffle.end()

                val outRows = mutableListOf<Map<Any, *>>()
                val upperBound = (2.0 * approxPartCount).toInt()

                repeat(shuffle.partCount) { partIdx ->
                    Relation(al, schema).use { outDataRel ->
                        shuffle.appendDataPart(outDataRel, partIdx)

                        assertTrue(outDataRel.rowCount in 1..upperBound) {
                            "part $partIdx row count ${outDataRel.rowCount} not within expected range of (1, $upperBound)"
                        }

                        outRows.addAll(outDataRel.toMaps(KEBAB_CASE_STRING))
                    }
                }

                assertEquals(rows.size, outRows.size)
                assertTrue(rows.all { it in outRows })
            }
        }
    }
}