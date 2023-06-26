(ns xtdb.indexer.temporal-log-indexer
  (:require
   [juxt.clojars-mirrors.integrant.core :as ig]
   [xtdb.blocks :as blocks]
   [xtdb.types :as types]
   [xtdb.util :as util]
   [xtdb.vector :as vec]
   [xtdb.vector.writer :as vw]
   [clojure.tools.logging :as log])
  (:import
   (java.io Closeable)
   (java.nio ByteBuffer)
   (java.util ArrayList)
   (java.util.concurrent.atomic AtomicInteger)
   (java.util.function Consumer)
   (org.apache.arrow.memory BufferAllocator)
   (org.apache.arrow.vector VectorLoader VectorSchemaRoot VectorUnloader)
   org.apache.arrow.vector.types.UnionMode
   (org.apache.arrow.memory RootAllocator)
   (org.apache.arrow.vector.types.pojo ArrowType$Union Schema)
   (org.apache.arrow.vector.util VectorSchemaRootAppender)
   (xtdb ICursor)
   (xtdb.indexer.log_indexer ILogOpIndexer ILogIndexer)
   (xtdb.object_store ObjectStore)
   (xtdb.vector IVectorWriter)))

;; #_{:clj-kondo/ignore [:unused-binding :clojure-lsp/unused-public-var]}
;; (definterface ILogOpIndexer
;;   (^void logPut [^long iid, ^long rowId, ^long app-timeStart, ^long app-timeEnd])
;;   (^void logDelete [^long iid, ^long app-timeStart, ^long app-timeEnd])
;;   (^void logEvict [^long iid])
;;   (^void commit [])
;;   (^void abort []))

;; #_{:clj-kondo/ignore [:unused-binding :clojure-lsp/unused-public-var]}
;; (definterface ILogIndexer
;;   (^xtdb.indexer.log_indexer.ILogOpIndexer startTx [^xtdb.api.protocols.TransactionInstant txKey])
;;   (^void finishPage [])
;;   (^java.util.concurrent.CompletableFuture finishChunk [^long chunkIdx])
;;   (^void nextChunk [])
;;   (^void close []))

(defn- ->log-obj-key [chunk-idx]
  (format "chunk-%s/temporal-log.arrow" (util/->lex-hex-string chunk-idx)))

(defmethod ig/prep-key :xtdb.indexer/temporal-log-indexer [_ opts]
  (merge {:allocator (ig/ref :xtdb/allocator)
          :object-store (ig/ref :xtdb/object-store)}
         opts))


(def ^:private log-ops-col-type
  '[:union #{:null
             [:list
              [:union
               #{[:struct {'iid [:fixed-size-binary 16]
                           'row-id :i64
                           'valid-from nullable-inst-type
                           'valid-to nullable-inst-type}]
                 [:struct {'iid [:fixed-size-binary 16]
                           'valid-from nullable-inst-type
                           'valid-to nullable-inst-type}]}]]}])


(def ^:private nullable-inst-type [:union #{:null [:timestamp-tz :micro "UTC"]}])

(def temporal-log-schema
  (Schema. [(types/col-type->field "tx-id" :i64)
            (types/col-type->field "system-time" types/temporal-col-type)
            (types/->field "tx-ops" types/list-type false
                           (types/->field "$data" (ArrowType$Union. UnionMode/Dense (int-array (range 2))) false
                                          (types/col-type->field "put" [:struct {'iid [:fixed-size-binary 16]
                                                                                 'row-id :i64
                                                                                 'valid-from nullable-inst-type
                                                                                 'valid-to nullable-inst-type}])
                                          (types/col-type->field "delete" [:struct {'iid [:fixed-size-binary 16]
                                                                                    'valid-from nullable-inst-type
                                                                                    'valid-to nullable-inst-type}])))]))

(def ^:private put-col-type [:struct {'iid [:fixed-size-binary 16]
                                      'row-id :i64
                                      'valid-from nullable-inst-type
                                      'valid-to nullable-inst-type}])


(comment

  (with-open [al (org.apache.arrow.memory.RootAllocator.)]
    (let [wrt (vw/->rel-writer al)
          list-wrt (.writerForName wrt "tx-ops" [:list
                                                 [:union {'foo :i64
                                                          'bar :f64}]])
          list-el-wrt (.listElementWriter list-wrt)]
      list-el-wrt))

  )

(defn rel-wrt->tsv-string [^xtdb.vector.IRelationWriter rel-wrt]
  (.syncRowCount rel-wrt)
  (let [vsr (let [^Iterable vecs (for [^IVectorWriter w (vals rel-wrt)]
                                   (.getVector w))]
              (VectorSchemaRoot. vecs))]
    (.contentToTSVString vsr)))

(comment

  (with-open [al (org.apache.arrow.memory.RootAllocator.)
              wrt (vw/->rel-writer al)
              wrt2 (vw/->rel-writer al) ]
    (let [u-wrt (.writerForName wrt "toto" [:union {'foo [:struct {'foo :i64}]
                                                    'bar [:struct {'bar :f64}]}])
          foo-wrt (.writerForTypeId u-wrt (byte 0))
          foo-field (.structKeyWriter foo-wrt "foo")
          bar-wrt (.writerForTypeId u-wrt (byte 1))
          bar-field (.structKeyWriter foo-wrt "bar")]

      ;; (-> u-wrt (.getVector) (.getField))
      (.startStruct foo-wrt)
      (.writeLong foo-field 1)
      (.endStruct foo-wrt)
      ;; (.startStruct bar-wrt)
      ;; (.writeLong bar-field 1)
      ;; (.endStruct bar-wrt)

      (.syncRowCount wrt)


      (println (rel-wrt->tsv-string wrt))

      (-> (bean u-wrt) :vector (.getField) #_(.getChildren) #_#_first (.getChildren))
      (vw/append-rel wrt2 (vw/rel-wtr->rdr wrt))

      (println (rel-wrt->tsv-string wrt2))

      (-> (seq wrt) first val (.getVector) (.getField))
      ;; (-> (seq wrt2) first val (.getVector) (.getField))

      ))

  (types/col-type->field [:union {'foo [:struct {'foo :i64}]
                                  'bar [:struct {'bar :f64}]}])

  (types/field->col-type
   (types/col-type->field [:union {'foo [:struct {'foo :i64}]
                                   'bar [:struct {'bar :f64}]}]))
  ;; => [:struct {foo [:union #{:absent :i64}], bar [:union #{:f64 :absent}]}]

  (types/field->col-type
   (types/col-type->field [:struct '{foo [:union #{:absent :i64}], bar [:union #{:f64 :absent}]}]))
  ;; => [:struct {foo [:union #{:absent :i64}], bar [:union #{:f64 :absent}]}]

  )

(defn long->byte-hash [^long l]
  (byte-array 16 (.getBytes (Long/toHexString l))))

(comment
  (require 'sc.api)

  (sc.api/letsc [3 -2]

                (-> (val (last (seq log-writer)))
                    (.getVector)
                    (.getField))

                (-> (val (last (seq transient-log-writer)))
                    (.getVector)
                    (.getField))



                #_(-> (bean tx-ops-wtr) :vector (.getField) (.getChildren) seq #_first #_(.getChildren))

                #_(-> (.getFieldVectors log-root) last
                      (.getField)))


  )

(def ^org.apache.arrow.vector.types.pojo.Field put-field
  (types/col-type->field "put" [:struct {'iid [:fixed-size-binary 16]
                                         'row-id :i64
                                         'valid-from nullable-inst-type
                                         'valid-to nullable-inst-type}]))

(def ^org.apache.arrow.vector.types.pojo.Field delete-field
  (types/col-type->field "delete" [:struct {'iid [:fixed-size-binary 16]
                                            'valid-from nullable-inst-type
                                            'valid-to nullable-inst-type}]))

(comment
  (with-open [al (org.apache.arrow.memory.RootAllocator.)]
    (let [log-writer (vw/->rel-writer allocator)
          transient-log-writer (vw/->rel-writer allocator)

          tx-ops-wtr (.writerForName transient-log-writer "foo" [:union
                                                                 {'put [:struct {'iid [:fixed-size-binary 16]
                                                                                 'row-id :i64
                                                                                 'valid-from nullable-inst-type
                                                                                 'valid-to nullable-inst-type}]
                                                                  'delete [:struct {'iid [:fixed-size-binary 16]
                                                                                    'valid-from nullable-inst-type
                                                                                    'valid-to nullable-inst-type}]}])

          #_#__ (println (-> (bean tx-ops-wtr) :vector (.getField) (.getChildren) seq #_first #_(.getChildren)))

          tx-ops-el-wtr (.listElementWriter tx-ops-wtr)

          put-wtr
          ;; (.writerForField tx-ops-el-wtr put-field)
          (.writerForTypeId tx-ops-el-wtr (byte 0))
          put-iid-wtr (.structKeyWriter put-wtr "iid")
          put-row-id-wtr (.structKeyWriter put-wtr "row-id")
          put-vf-wtr (.structKeyWriter put-wtr "valid-from")
          put-vt-wtr (.structKeyWriter put-wtr "valid-to")

          delete-wtr
          ;; (.writerForField tx-ops-el-wtr delete-field)
          (.writerForTypeId tx-ops-el-wtr (byte 1))
          delete-iid-wtr (.structKeyWriter delete-wtr "iid")
          delete-vf-wtr (.structKeyWriter delete-wtr "valid-from")
          delete-vt-wtr (.structKeyWriter delete-wtr "valid-to")

          block-row-counts (ArrayList.)
          !block-row-count (AtomicInteger.)]

      )
    ))


(defmethod ig/init-key :xtdb.indexer/temporal-log-indexer [_ {:keys [^BufferAllocator allocator, ^ObjectStore object-store]}]
  (let [log-writer (vw/->rel-writer allocator)
        transient-log-writer (vw/->rel-writer allocator)

        tx-id-wtr (.writerForName transient-log-writer "tx-id" :i64)
        system-time-wtr (.writerForName transient-log-writer "system-time" [:timestamp-tz :micro "UTC"])

        tx-ops-wtr (.writerForName transient-log-writer "tx-ops" [:list [:union
                                                                         {'put [:struct {'iid [:fixed-size-binary 16]
                                                                                         'row-id :i64
                                                                                         'valid-from nullable-inst-type
                                                                                         'valid-to nullable-inst-type}]
                                                                          'delete [:struct {'iid [:fixed-size-binary 16]
                                                                                            'valid-from nullable-inst-type
                                                                                            'valid-to nullable-inst-type}]}]])

        #_#__ (println (-> (bean tx-ops-wtr) :vector (.getField) (.getChildren) seq #_first #_(.getChildren)))

        tx-ops-el-wtr (.listElementWriter tx-ops-wtr)

        put-wtr
        ;; (.writerForField tx-ops-el-wtr put-field)
        (.writerForTypeId tx-ops-el-wtr (byte 0))
        put-iid-wtr (.structKeyWriter put-wtr "iid")
        put-row-id-wtr (.structKeyWriter put-wtr "row-id")
        put-vf-wtr (.structKeyWriter put-wtr "valid-from")
        put-vt-wtr (.structKeyWriter put-wtr "valid-to")

        delete-wtr
        ;; (.writerForField tx-ops-el-wtr delete-field)
        (.writerForTypeId tx-ops-el-wtr (byte 1))
        delete-iid-wtr (.structKeyWriter delete-wtr "iid")
        delete-vf-wtr (.structKeyWriter delete-wtr "valid-from")
        delete-vt-wtr (.structKeyWriter delete-wtr "valid-to")

        block-row-counts (ArrayList.)
        !block-row-count (AtomicInteger.)]

    (reify ILogIndexer
      (startTx [_ tx-key]
        (.writeLong tx-id-wtr (.tx-id tx-key))
        (vw/write-value! (.system-time tx-key) system-time-wtr)

        (.startList tx-ops-wtr)
        (reify ILogOpIndexer
          (logPut [_ iid row-id app-time-start app-time-end]
            (.startStruct put-wtr)
            (.writeBytes put-iid-wtr (ByteBuffer/wrap (long->byte-hash iid)))
            (.writeLong put-row-id-wtr row-id)
            (.writeLong put-vf-wtr app-time-start)
            (.writeLong put-vt-wtr app-time-end)
            (.endStruct put-wtr))

          (logDelete [_ iid app-time-start app-time-end]
            (.startStruct delete-wtr)
            (.writeBytes delete-iid-wtr (ByteBuffer/wrap (long->byte-hash iid)))
            (.writeLong delete-vf-wtr app-time-start)
            (.writeLong delete-vt-wtr app-time-end)
            (.endStruct delete-wtr))

          (logEvict [_ _iid]
            (throw (UnsupportedOperationException.)))

          (commit [_]
            (.endList tx-ops-wtr)
            (.endRow transient-log-writer)
            (println "---- transient")
            (println (rel-wrt->tsv-string transient-log-writer))
            (vw/append-rel log-writer (vw/rel-wtr->rdr transient-log-writer))
            (println "---- static")
            (println (rel-wrt->tsv-string log-writer))

            (.clear transient-log-writer)
            (.getAndIncrement !block-row-count))

          (abort [_]
            ;; (.clear ops-wtr)
            ;; (.writeNull ops-wtr nil)
            ;; (.endRow transient-log-writer)
            ;; (vw/append-rel log-writer (vw/rel-wtr->rdr transient-log-writer))

            ;; (.clear transient-log-writer)
            ;; (.getAndIncrement !page-row-count)
            )))

      (finishBlock [_]
        (.add block-row-counts (.getAndSet !block-row-count 0)))

      (finishChunk [_ chunk-idx]
        (.syncRowCount log-writer)

        (let [log-root (let [^Iterable vecs (for [^IVectorWriter w (vals log-writer)]
                                              (.getVector w))]
                         (VectorSchemaRoot. vecs))

              log-bytes (with-open [write-root (VectorSchemaRoot/create (.getSchema log-root) allocator)]
                          (let [loader (VectorLoader. write-root)]
                            (with-open [^ICursor slices (blocks/->slices log-root block-row-counts)]
                              (util/build-arrow-ipc-byte-buffer write-root :file
                                                                (fn [write-batch!]
                                                                  (.forEachRemaining slices
                                                                                     (reify Consumer
                                                                                       (accept [_ sliced-root]
                                                                                         (with-open [arb (.getRecordBatch (VectorUnloader. sliced-root))]
                                                                                           (.load loader arb)
                                                                                           (write-batch!))))))))))]

          (.putObject object-store (->log-obj-key chunk-idx) log-bytes)))

      (nextChunk [_]
        (.clear log-writer)
        (.clear block-row-counts))

      Closeable
      (close [_]
        (.close transient-log-writer)
        (.close log-writer)))))

(comment

  (def test-schema
    (Schema. [(types/->field "tx-ops" types/list-type false (types/col-type->field "foo" :i64))]))

  (def test-schema
    (Schema. [(types/->field "tx-ops" types/list-type false (types/col-type->field "foo" :i64))]))

  (with-open [al (RootAllocator.)
              vsr1 (VectorSchemaRoot/create test-schema al)
              vsr2 (VectorSchemaRoot/create test-schema al)]
    (let [tx-ops-wtr (vw/->writer (.getVector vsr1 "tx-ops"))
          tx-ops-el-wtr (.listElementWriter tx-ops-wtr)]
      (.startList tx-ops-wtr)
      (.writeLong tx-ops-el-wtr 0)
      (.endList tx-ops-wtr)
      (.setRowCount vsr1 1)
      (println (.contentToTSVString vsr1))

      ;; uncomment to make it pass
      ;; (.setRowCount vsr2 1)

      (VectorSchemaRootAppender/append vsr2 (into-array VectorSchemaRoot [vsr1]))
      (println (.contentToTSVString vsr2))))

  (with-open [al (RootAllocator.)
              vsr1 (VectorSchemaRoot/create test-schema al)
              vsr2 (VectorSchemaRoot/create test-schema al)]
    (let [tx-op-vec (.getVector vsr1 "tx-ops")
          int-vector (.getDataVector tx-op-vec)]
      (.getVector vsr1 "tx-ops")
      ;; (.startNewValue tx-op-vec 0)
      ;; (.setSafe int-vector 0 1)
      ;; (.endValue tx-op-vec 0 (- 1 (.getElementStartIndex tx-op-vec 0)))
      ;; (.setRowCount vsr1 1)
      ;; (println (.contentToTSVString vsr1))
      )))
