//THIRD-PARTY SOFTWARE NOTICE
//
//This file is derivative of the `metosin/jsonista` library, which is licensed under the EPL (version 2.0),
//and hence this file is also licensed under the terms of that license.
//
//Originally accessed at https://github.com/metosin/jsonista/blob/88267219e0c1ed6397162e7737d21447e97f32d6/src/clj/jsonista/tagged.clj
//The EPL 2.0 license is available at https://opensource.org/license/epl-2-0/

package xtdb.jackson;

import clojure.lang.IFn;
import clojure.lang.Keyword;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;
import java.util.Map;

public class FunctionalDeserializer<T> extends StdDeserializer<T> {
    private final IFn decoder;

    public FunctionalDeserializer (IFn decoder) {
        super(Object.class);
        this.decoder = decoder;
    }

    @Override
    public T deserialize(com.fasterxml.jackson.core.JsonParser jp, DeserializationContext ctxt) throws IOException, JsonProcessingException {
        return (T) decoder.invoke(jp, ctxt);
    }
}




