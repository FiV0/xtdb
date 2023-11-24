package xtdb.jackson;

import clojure.lang.Keyword;
import clojure.lang.PersistentHashMap;
import clojure.lang.PersistentVector;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleDeserializers;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.junit.jupiter.api.Test;
import xtdb.tx.Ops;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TxDeserializerTest {

    private final ObjectMapper objectMapper;

    public TxDeserializerTest() {
        ObjectMapper objectMapper = new ObjectMapper();
        SimpleModule module = new SimpleModule("JsonLd-test");

        HashMap<Class<?>, JsonDeserializer<?>> deserializerMapping = new HashMap<>();
        deserializerMapping.put(Map.class, new TxDeserializer());
        deserializerMapping.put(Ops.class, new TxOpDeserializer());
        SimpleDeserializers deserializers = new SimpleDeserializers(deserializerMapping);
        module.setDeserializers(deserializers);
        objectMapper.registerModule(module);
        this.objectMapper = objectMapper;
    }

    @Test
    public void shouldDeserializeKeyword() throws IOException {
        // given
        String json =
                    """
                    {"tx-ops":[{"put":"docs","doc":{}}],"foo":"bar"} 
                """;

//        """
//        {"tx-ops":[{"put":"docs","doc":{"xt/id":1}},{"put":"docs","doc":{"xt/id":2}}],"foo":"bar"}
//    """;


        // when
        Object actual = objectMapper.readValue(json, Object.class);

        // then
        assertEquals(PersistentHashMap.create("tx-ops", PersistentVector.create(Ops.put(Keyword.intern("docs"), PersistentHashMap.EMPTY)),
                                              "foo","bar"),
                actual);
    }



}