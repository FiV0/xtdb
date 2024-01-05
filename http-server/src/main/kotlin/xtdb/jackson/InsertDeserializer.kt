package xtdb.jackson

import clojure.lang.Keyword
import clojure.lang.PersistentHashMap
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.node.BaseJsonNode
import xtdb.IllegalArgumentException
import xtdb.query.DmlOps
import xtdb.query.Query
import java.io.IOException


class InsertDeserializer: StdDeserializer<DmlOps.Insert>(DmlOps.Insert::class.java) {
    @Throws(IllegalArgumentException::class, IOException::class)
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): DmlOps.Insert{
        val codec = p.codec
        val node = codec.readTree<BaseJsonNode>(p)

        if (!node.isObject || !node.has("insert_into") || !node.has("query") || !node["insert_into"].isTextual) {
            throw IllegalArgumentException.create(Keyword.intern("xtdb", "malformed-insert-into-op"), PersistentHashMap.create(Keyword.intern("json"), node.toPrettyString()))
        }
        return DmlOps.insert(node["insert_into"].asText(), codec.treeToValue(node["query"], Query::class.java))
    }
}