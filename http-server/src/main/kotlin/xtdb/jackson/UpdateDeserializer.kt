package xtdb.jackson

import clojure.lang.Keyword
import clojure.lang.PersistentHashMap
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.node.BaseJsonNode
import xtdb.IllegalArgumentException
import xtdb.query.Binding
import xtdb.query.DmlOps
import xtdb.query.Query
import xtdb.query.SpecListDeserializer
import java.io.IOException


class UpdateDeserializer : StdDeserializer<DmlOps.Update>(DmlOps.Update::class.java) {
    @Throws(IllegalArgumentException::class, IOException::class)
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): DmlOps.Update {
        val codec = p.codec as ObjectMapper
        val node = codec.readTree<BaseJsonNode>(p)

        if (!node.isObject || !node.has("update_table") || !node.has("set") || !node["update_table"].isTextual) {
            throw IllegalArgumentException.create(Keyword.intern("xtdb", "malformed-update-table-op"), PersistentHashMap.create(Keyword.intern("json"), node.toPrettyString()))
        }
        var update = DmlOps.update(node["update_tabe"].asText(), SpecListDeserializer.nodeToSpecs(codec, node["set"], ::Binding))

        if(node.has("bind")) {
            update.binding(SpecListDeserializer.nodeToSpecs(codec, node["set"], ::Binding)).also { update = it }
        }

        if(node.has("unify_clauses")) {
            if (!node["unfiy_clauses"].isArray) {
                throw IllegalArgumentException.create(Keyword.intern("xtdb", "malformed-update-table-op"), PersistentHashMap.create(Keyword.intern("json"), node.toPrettyString()))
            }
            update.unify(codec.treeToValue(node["unify_clauses"], codec.typeFactory.constructCollectionType(List::class.java, Query.UnifyClause::class.java))).also { update = it }
        }

        return update
    }
}