package xtdb.jackson;


import clojure.lang.Keyword;
import clojure.lang.PersistentHashMap;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import xtdb.IllegalArgumentException;
import xtdb.query.DmlOps;
import xtdb.tx.*;

import java.io.IOException;

public class AOpsDeserializer extends StdDeserializer<AOps>  {

    public AOpsDeserializer() {
        super(AOps.class);
    }

    @Override
    public AOps deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        ObjectCodec codec = p.getCodec();
        ObjectNode node = codec.readTree(p);

        if (node.has("put")) {
            return codec.treeToValue(node, Put.class);
        } else if (node.has("delete")) {
            return codec.treeToValue(node, Delete.class);
        } else if (node.has("erase")) {
            return codec.treeToValue(node, Erase.class);
        } else if (node.has("call")) {
            return codec.treeToValue(node, Call.class);
        } else if (node.has("sql")) {
            return codec.treeToValue(node, Sql.class);
        }else if (node.has("insert_into")) {
            return codec.treeToValue(node, DmlOps.Insert.class);
        } else if (node.has("update_table")) {
            return codec.treeToValue(node, DmlOps.Update.class);
        } else if (node.has("delete_from")) {
            return codec.treeToValue(node, DmlOps.Delete.class);
        } else if (node.has("erase_from")) {
            return codec.treeToValue(node, DmlOps.Erase.class);
        } else if (node.has("assert_exists")) {
            return codec.treeToValue(node, DmlOps.AssertExists.class);
        } else if (node.has("assert_not_exists")) {
            return codec.treeToValue(node, DmlOps.AssertNotExists.class);
        } else {
            throw IllegalArgumentException.create(Keyword.intern("xtql", "malformed-tx-op"), PersistentHashMap.create(Keyword.intern("json"), node.toPrettyString()));
        }
    }
}
