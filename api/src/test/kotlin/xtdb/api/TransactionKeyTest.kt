package xtdb.api

import kotlinx.serialization.UseSerializers
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

class TransactionKeyTest {

    @Test
    fun testTransactionKeySerialization() {
        // Arrange
        val expected = """ {"txId":1,"systemTime":"1970-01-01T00:00:00Z"} """.trimIndent()

        // Act
        val actual = Json.encodeToString(TransactionKey(1L, Instant.ofEpochMilli(0)))

        // Assert
        Assertions.assertEquals(expected, actual)
    }


    @Test
    fun testTransactionKeyDeserialization() {
        // Arrange
        val expected = TransactionKey(1L, Instant.ofEpochMilli(0))

        // Act
        val actual = Json.decodeFromString<TransactionKey> (""" {"txId":1,"systemTime":"1970-01-01T00:00:00Z"} """)

        // Assert
        Assertions.assertEquals(expected, actual)
    }


}