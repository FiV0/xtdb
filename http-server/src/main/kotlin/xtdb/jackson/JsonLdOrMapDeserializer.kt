package xtdb.jackson

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import java.time.Instant

object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("JsonLd") {
        element<String>("@type")
        element<String>("@value")
    }

    override fun serialize(encoder: Encoder, value: Instant) {
        require(encoder is JsonEncoder)
        encoder.encodeJsonElement(buildJsonObject {
            put("@type", "xt:instant")
            put("@value", value.toString())
        })
    }

    override fun deserialize(decoder: Decoder): Instant {
        require(decoder is JsonDecoder)
        val jsonObject = decoder.decodeJsonElement()

        if (jsonObject is JsonObject) {
            val type = jsonObject["@type"]
            return Instant.parse(jsonObject["@value"]!!.jsonPrimitive.toString())
        } else {
            TODO()
        }
    }


}
object JsonLdOrMapDeserializer: JsonContentPolymorphicSerializer<Any>(Any::class) {
    override fun selectDeserializer(element: JsonElement) = when {
        "@type" in element.jsonObject -> InstantSerializer
        else -> JsonObject.serializer()
    }
}
