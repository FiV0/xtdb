;; THIRD-PARTY SOFTWARE NOTICE
;;
;; This file is derivative of the `metosin/jsonista` library, which is licensed under the EPL (version 2.0),
;; and hence this file is also licensed under the terms of that license.
;;
;; Originally accessed at https://github.com/metosin/jsonista/blob/88267219e0c1ed6397162e7737d21447e97f32d6/src/clj/jsonista/tagged.clj
;; The EPL 2.0 license is available at https://opensource.org/license/epl-2-0/

(ns xtdb.jackson
  (:require [xtdb.error :as err]
            [jsonista.core :as json]
            [jsonista.tagged :as jt])
  (:import (clojure.lang Keyword)
           (com.fasterxml.jackson.core JsonGenerator)
           (com.fasterxml.jackson.databind.module SimpleModule)
           (java.time Instant Duration LocalDate LocalDateTime ZonedDateTime)
           (java.util Date Map Set)
           (java.time Instant Duration LocalDate LocalDateTime ZonedDateTime ZoneId)
           (jsonista.jackson FunctionalSerializer)
           (xtdb.jackson JsonLdValueOrPersistentHashMapDeserializer)))
           (xtdb.jackson JsonLdValueOrPersistentHashMapDeserializer TxDeserializer
                         FunctionalDeserializer TxOpDeserializer)
           (xtdb.tx Ops)))

(defn serializer ^FunctionalSerializer [^String tag encoder]
  (FunctionalSerializer.
   (fn [value ^JsonGenerator gen]
     (.writeStartObject gen)
     (.writeStringField gen "@type" tag)
     (.writeFieldName gen "@value")
     (encoder value gen)
     (.writeEndObject gen))))

(defn encode-throwable [^Throwable t ^JsonGenerator gen]
  (.writeStartObject gen)
  (.writeStringField gen "xtdb.error/message" (.getMessage t))
  (.writeStringField gen "xtdb.error/class" (.getName (.getClass t)))
  (.writeFieldName gen "xtdb.error/data")
  (.writeObject gen (ex-data t))
  (.writeEndObject gen))

;; TODO this only works in connection with keyword decoding
(defn decode-throwable [{:xtdb.error/keys [message class data] :as _err}]
  (case class
    "xtdb.IllegalArgumentException" (err/illegal-arg (:xtdb.error/error-key data) data)
    "xtdb.RuntimeException" (err/runtime-err (:xtdb.error/error-key data) data)
    (ex-info message data)))

(defn json-ld-module
  "See jsonista.tagged/module but for Json-Ld reading/writing."
  ^SimpleModule
  [{:keys [handlers]}]
  (let [decoders (->> (for [[_ {:keys [tag decode]}] handlers] [tag decode]) (into {}))]
    (reduce-kv
     (fn [^SimpleModule module t {:keys [tag encode] :or {encode jt/encode-str}}]
       (.addSerializer module t (serializer tag encode)))
     (doto (SimpleModule. "JSON-LD")
       (.addDeserializer Map (JsonLdValueOrPersistentHashMapDeserializer. decoders)))
     handlers)))

(def handlers {Keyword {:tag "xt:keyword"
                        :encode jt/encode-keyword
                        :decode keyword}
               Set {:tag "xt:set"
                    :encode jt/encode-collection
                    :decode set}
               Date {:tag "xt:instant"
                     :encode (fn [^Date date ^JsonGenerator gen]
                               (.writeString gen (str (.toInstant date))))
                     :decode #(Instant/parse %)}
               LocalDate {:tag "xt:date"
                          :decode #(LocalDate/parse %)}
               Duration {:tag "xt:duration"
                         :decode #(Duration/parse %)}
               LocalDateTime {:tag "xt:timestamp"
                              :decode #(LocalDateTime/parse %)}
               ZonedDateTime {:tag "xt:timestamptz"
                              :decode #(ZonedDateTime/parse %)}
               ZoneId {:tag "xt:timezone"
                       :decode #(ZoneId/of %)}
               Instant {:tag "xt:instant"
                        :decode #(Instant/parse %)}
               Throwable {:tag "xt:error"
                          :encode encode-throwable
                          :decode decode-throwable}})

(def json-ld-mapper
  (doto (json/object-mapper
         {:encode-key-fn true
          :decode-key-fn true
          :modules [(json-ld-module {:handlers handlers})]})
    (-> (.getFactory) (.disable com.fasterxml.jackson.core.JsonGenerator$Feature/AUTO_CLOSE_TARGET))))


(def ^ObjectMapper tx-op-mapper
  (doto (json/object-mapper
         {:encode-key-fn true
          :decode-key-fn true
          :modules [(doto (json-ld-module {:handlers handlers})
                      (.addDeserializer Ops (TxOpDeserializer.)))]})
    (-> (.getFactory) (.disable com.fasterxml.jackson.core.JsonGenerator$Feature/AUTO_CLOSE_TARGET))))

(comment
  (.readValue tx-op-mapper (json/write-value-as-string {"put" "docs" "doc" {"xt/id" 1 "foo" :bar}} json-ld-mapper) Ops))


;; (defn tx-deserializer ^FunctionalDeserializer []
;;   (FunctionalDeserializer.
;;    (fn [^JsonParser jp ^DeserializationContext ctxt]
;;      (let [^ObjectMapper mapper (.getCodec jp)
;;            ^ObjectNode node (.readTree tx-op-mapper jp)
;;            ^JsonDeserializer deserializer (.findRootValueDeserializer ctxt (.constructCollectionType (.getTypeFactory tx-op-mapper) List Ops))]
;;        (loop [res (transient {}) entries (iterator-seq (.fields node))]
;;          (if-let [entry (first entries)]
;;            (let [k (key entry)
;;                  ^JsonNode v (val entry)
;;                  ^JsonParser v (.traverse v #_mapper tx-op-mapper)
;;                  v (if (= "tx-ops" k)
;;                      (do
;;                        (prn "special2")
;;                        ;; (.nextToken v)
;;                        ;; (.deserialize deserializer v ctxt)

;;                        (.readValue #_mapper tx-op-mapper v (.constructCollectionType (.getTypeFactory #_mapper tx-op-mapper) ^Class List ^Class Ops)))
;;                      (do
;;                        (prn "normal")
;;                        (.readValue #_mapper tx-op-mapper v #_(.traverse v tx-op-mapper) ^Class Object)))
;;                  k (keyword k)]
;;              (recur (assoc! res k v) (rest entries)))
;;            (persistent! res)))))))

;; (defn tx-deserializer ^FunctionalDeserializer []
;;   (FunctionalDeserializer.
;;    (fn [^JsonParser jp ^DeserializationContext ctxt]
;;      (let [^JsonDeserializer tx-ops-deser (.findRootValueDeserializer ctxt (.constructCollectionType (.getTypeFactory tx-op-mapper) List Ops))
;;            ^JsonDeserializer value-deser  (.findNonContextualValueDeserializer ctxt (.constructType ctxt Object))  ]
;;        (loop [res (transient {})]
;;          (let [token (.nextToken jp)]
;;            (if (= JsonToken/END_OBJECT token)
;;              (persistent! res)
;;              (let [k (keyword (.getCurrentName jp))
;;                    _ (.nextToken jp)
;;                    v (if (= :tx-ops k)
;;                        (do
;;                          (prn "special2")
;;                          (.deserialize tx-ops-deser jp ctxt)

;;                          #_(.readValue tx-op-mapper (.traverse v tx-op-mapper)
;;                                        (.constructCollectionType (.getTypeFactory tx-op-mapper) List Ops)))
;;                        (do
;;                          (prn "normal")
;;                          (.deserialize value-deser jp ctxt)

;;                          #_(.readValue tx-op-mapper v #_(.traverse v tx-op-mapper) ^Class Object)))]
;;                (recur (assoc! res k v))))))))))


;; (def tx-mapper
;;   (doto (json/object-mapper
;;          {:encode-key-fn true
;;           :decode-key-fn true
;;           :modules [(doto (SimpleModule. "foo")
;;                       (.addDeserializer Map (tx-deserializer)))
;;                     (doto (json-ld-module {:handlers handlers})
;;                       (.addDeserializer Ops (TxOpDeserializer.)))
;;                     ]})
;;     (-> (.getFactory) (.disable com.fasterxml.jackson.core.JsonGenerator$Feature/AUTO_CLOSE_TARGET))))

;; (def tx {:tx-ops
;;          [{"put" "docs"
;;            "doc" {"xt/id" 1}}
;;           {"put" "docs"
;;            "doc" {"xt/id" 2}}]
;;          :foo "bar"})

;; (-> (json/write-value-as-string tx)
;;     (json/read-value tx-mapper))
