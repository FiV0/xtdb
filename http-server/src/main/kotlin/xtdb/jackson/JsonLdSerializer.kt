package xtdb.jackson

import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.LocalDate

class JsonLdSerializer() : KSerializer<Any> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("JsonLd") {
        element<String>("@type")
        element<Any>("@value")
    }

    private val typeToEncoder : Map<Class<out Any>, Pair<String, (Any) -> JsonElement>> = mapOf(
        Instant::class.java to Pair("xt:instant", {instant: Instant -> JsonPrimitive(instant.toString())}),
        LocalDate::class.java to Pair("xt:date", {localDate: LocalDate -> JsonPrimitive(localDate.toString())})
    ) as Map<Class<out Any>, Pair<String ,(Any) -> JsonElement>>

    private val tagToParser : Map<String, (Any) -> Any> = mapOf(
        "xt:instant" to { str: String -> Instant.parse(str)},
        "xt:date" to { str: String ->  LocalDate.parse(str)}
    ) as Map<String, (Any) -> Any>

    override fun serialize(encoder: Encoder, value: Any) {
        require(encoder is JsonEncoder)
        val (tag, encodeFn) = typeToEncoder[value.javaClass] as Pair<String, (Any) -> JsonElement>
        if (encodeFn != null) {
            encoder.encodeJsonElement(buildJsonObject {
                put("@type", tag)
                put("@value", encodeFn(value))
            })
        } else {
            encoder.encodeJsonElement(encoder.json.encodeToJsonElement(value))
        }
    }

    override fun deserialize(decoder: Decoder): Any {
        require(decoder is JsonDecoder)
        val jsonObject = decoder.decodeJsonElement()
//        val jsonObject = decoder.decodeSerializableValue(JsonObject.serializer())
//        val map = jsonObject.mapValues { it.value.jsonPrimitive.content }

        if (jsonObject is JsonObject) {
            val type = jsonObject["@type"]
            val value = jsonObject["@value"]
            val parser : ((Any) -> Any)? = tagToParser.get<Any?, (Any) -> Any>(type)
            if (parser != null) {
                return parser(value!!)
            } else {
               return decoder.json.decodeFromJsonElement(jsonObject)
            }
        } else {
            return decoder.json.decodeFromJsonElement(jsonObject)
        }
    }
}
