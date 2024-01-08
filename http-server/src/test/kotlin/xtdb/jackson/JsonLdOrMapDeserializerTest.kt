package xtdb.jackson

import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.serializersModuleOf
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.time.Instant

val json = Json { serializersModule
//     SerializersModule {
//        contextual(Map::class, JsonLdOrMapDeserializer),
//         contextual(Map::class, JsonLdOrMapDeserializer)
//
//    }
    serializersModuleOf(Map::class, JsonLdOrMapDeserializer as KSerializer<Map<*, *>>)

}



class JsonLdOrMapDeserializerTest {



    @Test
    fun testJsonLdInstantDeserialization() {
        // Arrange
        val expected = Instant.parse("2020-01-01T00:00:00Z")

        // Act
        val actual = json.decodeFromString<Any>(
            """
            { "@type":"xt:instant", "@value":"2020-01-01T00:00:00Z" }
            """)

        // Assert
        Assertions.assertEquals(expected, actual)
    }



    @Test
    fun testJsonLdInstantSerialization() {
        // Arrange
        val expected =
            """
            { "@type":"xt:instant", "@value":"2020-01-01T00:00:00Z" }
            """.trimIndent()

        // Act
        val actual = json.encodeToString(Instant.parse("2020-01-01T00:00:00Z"))

        // Assert
        Assertions.assertEquals(expected, actual)
    }


}