package xtdb.jackson;

import clojure.lang.Keyword;
import clojure.lang.PersistentHashMap;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.node.BaseJsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import xtdb.IllegalArgumentException;
import xtdb.tx.Erase;

import java.io.IOException;

public class EraseDeserializer extends StdDeserializer<Erase> {

    public EraseDeserializer() {
        super(Erase.class);
    }

    @Override
    public Erase deserialize(JsonParser p, DeserializationContext ctxt) throws IllegalArgumentException, IOException {
        ObjectMapper mapper = (ObjectMapper) p.getCodec();
        BaseJsonNode node = mapper.readTree(p);

        try {
            ObjectNode objectNode = (ObjectNode) node;
            Erase op = new Erase(Keyword.intern(objectNode.get("erase").asText()), mapper.convertValue(objectNode.get("id"), Object.class));
            return op;
        } catch (Exception e) {
            throw IllegalArgumentException.create(Keyword.intern("xtdb", "malformed-erase"), PersistentHashMap.create(Keyword.intern("json"), node.toPrettyString()), e);
        }  
    }
}