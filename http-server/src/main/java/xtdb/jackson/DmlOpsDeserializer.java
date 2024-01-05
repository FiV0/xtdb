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

import java.io.IOException;

public class DmlOpsDeserializer extends StdDeserializer<DmlOps>  {

    public DmlOpsDeserializer() {
        super(DmlOps.class);
    }

    public DmlOps deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        ObjectCodec codec = p.getCodec();
        ObjectNode node = codec.readTree(p);

        if (node.has("insert_into")) {
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
            throw IllegalArgumentException.create(Keyword.intern("xtql", "malformed-tx-op"), PersistentHashMap.create(Keyword.intern("jsona"), node.toPrettyString()));
        }
    }
}
