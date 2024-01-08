package xtdb.jackson

import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

object JsonLdOrMapDeserializer: JsonContentPolymorphicSerializer<Any>(Any::class) {
    override fun selectDeserializer(element: JsonElement) = when {
        "@type" in element.jsonObject -> JsonLdSerializer()
        else -> JsonObject.serializer()
    }
}
