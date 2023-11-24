package xtdb.jackson;

import clojure.java.api.Clojure;
import clojure.lang.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import xtdb.IllegalArgumentException;
import xtdb.tx.Ops;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class TxDeserializer extends StdDeserializer<Tx>  {

    public TxDeserializer() {
        super(Tx.class);
    }

    @Override
    public Tx deserialize(com.fasterxml.jackson.core.JsonParser jp, DeserializationContext ctxt) throws IOException, JsonProcessingException {
        ObjectMapper mapper = (ObjectMapper) jp.getCodec();
        ObjectNode node = mapper.readTree(jp);
        ITransientMap t = PersistentHashMap.EMPTY.asTransient();
        List<Ops> ops = null;
        LocalDateTime systemTime = null;
        ZoneId defaultTz = null;

        if (node.has("tx-ops")) {
            ops = mapper.treeToValue(node.get("tx-ops"), mapper.getTypeFactory().constructCollectionType(List.class, Ops.class));
        } else {
           throw IllegalArgumentException.create("Illegal argument: 'tx/missing-tx-ops'", Keyword.intern("tx", "missing-tx-ops"));
        }
        if (node.has("system-time")) {
            systemTime = (LocalDateTime) mapper.readValue(node.get("system-time").traverse(mapper), Object.class);
        }
        if (node.has("default-tz")) {
            defaultTz = (ZoneId)  mapper.readValue(node.get("system-time").traverse(mapper), Object.class);
        }

        return new Tx(ops, systemTime, defaultTz);
    }
}
