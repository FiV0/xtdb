(ns xtdb.metadata
  (:require [clojure.tools.logging :as log]
            [cognitect.transit :as transit]
            [integrant.core :as ig]
            [xtdb.bloom :as bloom]
            xtdb.buffer-pool
            xtdb.expression.temporal
            [xtdb.serde :as serde]
            [xtdb.trie :as trie]
            [xtdb.types :as types]
            [xtdb.util :as util])
  (:import (com.cognitect.transit TransitFactory)
           (com.github.benmanes.caffeine.cache Cache Caffeine RemovalListener)
           (java.io ByteArrayInputStream ByteArrayOutputStream)
           java.lang.AutoCloseable
           java.nio.ByteBuffer
           (java.nio.file Path)
           (java.util HashMap HashSet Map NavigableMap TreeMap)
           (java.util.concurrent.atomic AtomicInteger)
           (org.apache.arrow.vector.types.pojo ArrowType Field FieldType)
           (xtdb.arrow Relation)
           xtdb.IBufferPool
           (xtdb.metadata ITableMetadata PageIndexKey)
           (xtdb.trie ArrowHashTrie HashTrie)
           (xtdb.util TemporalBounds TemporalDimension)
           (xtdb.vector IVectorReader)))

(def arrow-read-handlers
  {"xtdb/arrow-type" (transit/read-handler types/->arrow-type)
   "xtdb/field-type" (transit/read-handler (fn [[arrow-type nullable?]]
                                             (if nullable?
                                               (FieldType/nullable arrow-type)
                                               (FieldType/notNullable arrow-type))))
   "xtdb/field" (transit/read-handler (fn [[name field-type children]]
                                        (Field. name field-type children)))})

(def arrow-write-handlers
  {ArrowType (transit/write-handler "xtdb/arrow-type" #(types/<-arrow-type %))
   ;; beware that this currently ignores dictionary encoding and metadata of FieldType's
   FieldType (transit/write-handler "xtdb/field-type"
                                    (fn [^FieldType field-type]
                                      (TransitFactory/taggedValue "array" [(.getType field-type) (.isNullable field-type)])))
   Field (transit/write-handler "xtdb/field"
                                (fn [^Field field]
                                  (TransitFactory/taggedValue "array" [(.getName field) (.getFieldType field) (.getChildren field)])))})
(set! *unchecked-math* :warn-on-boxed)


#_{:clj-kondo/ignore [:unused-binding :clojure-lsp/unused-public-var]}
(definterface IMetadataManager
  (^void finishChunk [^long chunkIdx, newChunkMetadata])
  (^java.util.NavigableMap chunksMetadata [])
  (^xtdb.metadata.ITableMetadata openTableMetadata [^java.nio.file.Path metaFilePath])
  (columnFields [^String tableName])
  (columnField [^String tableName, ^String colName])
  (allColumnFields [])
  (cleanUp []))

#_{:clj-kondo/ignore [:unused-binding :clojure-lsp/unused-public-var]}
(definterface IMetadataPredicate
  (^java.util.function.IntPredicate build [^xtdb.metadata.ITableMetadata tableMetadata]))

(defn- obj-key->chunk-idx [^Path obj-key]
  (some->> (.getFileName obj-key)
           (str)
           (re-matches #"(\p{XDigit}+).transit.json")
           (second)
           (util/<-lex-hex-string)))

(def ^Path chunk-metadata-path (util/->path "chunk-metadata"))

(defn- ->chunk-metadata-obj-key [chunk-idx]
  (.resolve chunk-metadata-path (format "%s.transit.json" (util/->lex-hex-string chunk-idx))))

(defn- write-chunk-metadata ^java.nio.ByteBuffer [chunk-meta]
  (with-open [os (ByteArrayOutputStream.)]
    (let [w (transit/writer os :json {:handlers (merge serde/transit-write-handlers
                                                       arrow-write-handlers)})]
      (transit/write w chunk-meta))
    (ByteBuffer/wrap (.toByteArray os))))

(defn- merge-fields [fields {:keys [tables]}]
  (reduce (fn [fields [table {new-fields :fields}]]
            (update fields table
                    (fn [fields new-fields]
                      (->> (merge-with types/merge-fields fields new-fields)
                           (map (fn [[col-name field]] [col-name (types/field-with-name field col-name)]))
                           (into {})))
                    new-fields))
          fields
          tables))

(defn ->table-metadata-idxs [^IVectorReader metadata-rdr]
  (let [page-idx-cache (HashMap.)
        meta-row-count (.valueCount metadata-rdr)
        data-page-idx-rdr (.structKeyReader metadata-rdr "data-page-idx")
        cols-rdr (.structKeyReader metadata-rdr "columns")
        col-rdr (.listElementReader cols-rdr)
        column-name-rdr (.structKeyReader col-rdr "col-name")
        root-col-rdr (.structKeyReader col-rdr "root-col?")
        col-names (HashSet.)]

    (dotimes [meta-idx meta-row-count]
      (when-not (or (.isNull metadata-rdr meta-idx)
                    (.isNull cols-rdr meta-idx))
        (let [cols-start-idx (.getListStartIndex cols-rdr meta-idx)
              data-page-idx (if-let [data-page-idx (.getObject data-page-idx-rdr meta-idx)]
                              data-page-idx
                              -1)]
          (dotimes [cols-data-idx (.getListCount cols-rdr meta-idx)]
            (let [cols-data-idx (+ cols-start-idx cols-data-idx)
                  col-name (str (.getObject column-name-rdr cols-data-idx))]
              (.add col-names col-name)
              (when (.getBoolean root-col-rdr cols-data-idx)
                (.put page-idx-cache (PageIndexKey. col-name data-page-idx) cols-data-idx)))))))

    {:col-names (into #{} col-names)
     :page-idx-cache page-idx-cache}))


(defrecord TableMetadata [^HashTrie trie
                          ^Relation meta-rel
                          ^IVectorReader metadata-leaf-rdr
                          col-names
                          ^Map page-idx-cache
                          ^AtomicInteger ref-count
                          ^IVectorReader min-rdr
                          ^IVectorReader max-rdr]
  ITableMetadata
  (metadataReader [_] metadata-leaf-rdr)
  (columnNames [_] col-names)
  (rowIndex [_ col-name page-idx] (.getOrDefault page-idx-cache (PageIndexKey. col-name page-idx) -1))

  (iidBloomBitmap [_ page-idx]
    (let [bloom-rdr (-> (.structKeyReader metadata-leaf-rdr "columns")
                        (.listElementReader)
                        (.structKeyReader "bloom"))]

      (when-let [bloom-vec-idx (.get page-idx-cache (PageIndexKey. "_iid" page-idx))]
        (when (.getObject bloom-rdr bloom-vec-idx)
          (bloom/bloom->bitmap bloom-rdr bloom-vec-idx)))))

  (temporalBounds[_ page-idx]
    (let [^long system-from-idx (.get page-idx-cache (PageIndexKey. "_system_from" page-idx))
          ^long valid-from-idx (.get page-idx-cache (PageIndexKey. "_valid_from" page-idx))
          ^long valid-to-idx (.get page-idx-cache (PageIndexKey. "_valid_to" page-idx))]
      (TemporalBounds. (TemporalDimension. (.getLong min-rdr valid-from-idx) (.getLong max-rdr valid-to-idx))
                       (TemporalDimension. (.getLong min-rdr system-from-idx) Long/MAX_VALUE))))

  AutoCloseable
  (close [_]
    (when (zero? (.decrementAndGet ref-count))
      (util/close meta-rel))))

(def ^:private temporal-col-type-leg-name (name (types/arrow-type->leg (types/->arrow-type [:timestamp-tz :micro "UTC"]))))

(defn ->table-metadata ^xtdb.metadata.ITableMetadata [^IBufferPool buffer-pool ^Path file-path]
  (let [footer (.getFooter buffer-pool file-path)]
    (util/with-open [rb (.getRecordBatch buffer-pool file-path 0)]
      (let [alloc (.getAllocator (.getReferenceManager ^ArrowBuf (first (.getBuffers rb))))]
        (util/with-close-on-catch [rel (Relation/fromRecordBatch alloc (.getSchema footer) rb)]
          (let [nodes-vec (.get rel "nodes")
                rdr (.getOldRelReader rel)
                ^IVectorReader metadata-reader (-> (.readerForName rdr "nodes")
                                                   (.legReader "leaf"))
                {:keys [col-names page-idx-cache]} (->table-metadata-idxs metadata-reader)
                temporal-col-types-rdr (some-> (.structKeyReader metadata-reader "columns")
                                               (.listElementReader)
                                               (.structKeyReader "types")
                                               (.structKeyReader temporal-col-type-leg-name))

                min-rdr (some-> temporal-col-types-rdr (.structKeyReader "min"))
                max-rdr (some-> temporal-col-types-rdr (.structKeyReader "max"))]
            (->TableMetadata (ArrowHashTrie. nodes-vec) rel metadata-reader col-names page-idx-cache (AtomicInteger. 1)
                             min-rdr max-rdr)))))))

(defn metadata-manager-cleanup [table-names ^Cache table-metadata-cache ^IBufferPool buffer-pool]
  (doseq [table-name table-names]
    (let [table-path (util/table-name->table-path table-name)
          superseded-meta-files (->> (trie/list-meta-files buffer-pool table-path)
                                     (trie/superseded-trie-files))
          m (.asMap table-metadata-cache)]
      (doseq [meta-file superseded-meta-files]
        (.computeIfPresent m meta-file
                           (fn [_ table-metadata]
                             (let [{:keys [^AtomicInteger ref-count]} table-metadata]
                               (if (= 1 (.get ref-count))
                                 (util/close table-metadata)
                                 table-metadata))))))))

(deftype MetadataManager [^IBufferPool buffer-pool
                          ^Cache table-metadata-cache
                          ^NavigableMap chunks-metadata
                          ^:volatile-mutable ^Map fields]
  IMetadataManager
  (finishChunk [this chunk-idx new-chunk-metadata]
    (.putObject buffer-pool (->chunk-metadata-obj-key chunk-idx) (write-chunk-metadata new-chunk-metadata))
    (set! (.fields this) (merge-fields fields new-chunk-metadata))
    (.put chunks-metadata chunk-idx new-chunk-metadata))

  (openTableMetadata [_ file-path]
    (-> (.asMap table-metadata-cache)
        (.compute file-path (fn [file-path table-metadata]
                              (let [{:keys [^AtomicInteger ref-count] :as tm} (or table-metadata
                                                                                  (->table-metadata buffer-pool file-path))]
                                (.incrementAndGet ref-count)
                                tm)))))

  (chunksMetadata [_] chunks-metadata)
  (columnField [_ table-name col-name]
    (some-> (get fields table-name)
            (get col-name (types/->field col-name #xt.arrow/type :null true))))

  (columnFields [_ table-name] (get fields table-name))
  (allColumnFields [_] fields)

  (cleanUp [_]
    (metadata-manager-cleanup (keys fields) table-metadata-cache buffer-pool))

  AutoCloseable
  (close [_]
    (.clear chunks-metadata)
    (util/close (.asMap table-metadata-cache))))

(defn latest-chunk-metadata [^IMetadataManager metadata-mgr]
  (some-> (.lastEntry (.chunksMetadata metadata-mgr))
          (.getValue)))

(defn- load-chunks-metadata ^java.util.NavigableMap [{:keys [^IBufferPool buffer-pool]}]
  (let [cm (TreeMap.)]
    (doseq [cm-obj-key (.listObjects buffer-pool chunk-metadata-path)]
      (with-open [is (ByteArrayInputStream. (.getByteArray buffer-pool cm-obj-key))]
        (let [rdr (transit/reader is :json {:handlers (merge serde/transit-read-handlers
                                                             arrow-read-handlers)})]
          (.put cm (obj-key->chunk-idx cm-obj-key) (transit/read rdr)))))
    cm))

(comment
  (require '[clojure.java.io :as io])

  (with-open [is (io/input-stream "src/test/resources/xtdb/indexer-test/can-build-live-index/v02/chunk-metadata/00.transit.json")]
    (let [rdr (transit/reader is :json {:handlers (merge serde/transit-read-handlers
                                                         arrow-read-handlers)})]
      (transit/read rdr))))

(defmethod ig/prep-key ::metadata-manager [_ opts]
  (merge {:buffer-pool (ig/ref :xtdb/buffer-pool)}
         opts))

(defmethod ig/init-key ::metadata-manager [_ {:keys [cache-size ^IBufferPool buffer-pool], :or {cache-size 128} :as deps}]
  (let [chunks-metadata (load-chunks-metadata deps)
        table-metadata-cache (-> (Caffeine/newBuilder)
                                 (.maximumSize cache-size)
                                 (.removalListener (reify RemovalListener
                                                     (onRemoval [_ _path table-metadata _reason]
                                                       (util/close table-metadata))))
                                 (.build))]
    (MetadataManager. buffer-pool
                      table-metadata-cache
                      chunks-metadata
                      (->> (vals chunks-metadata) (reduce merge-fields {})))))

(defmethod ig/halt-key! ::metadata-manager [_ mgr]
  (util/try-close mgr))
