(ns xtdb.indexer.temporal-log-indexer
  (:require
   [juxt.clojars-mirrors.integrant.core :as ig]
   [xtdb.blocks :as blocks]
   [xtdb.types :as types]
   [xtdb.util :as util]
   [xtdb.vector :as vec]
   [xtdb.vector.writer :as vw])
  (:import
   (java.io Closeable)
   (java.nio ByteBuffer)
   (java.util ArrayList)
   (java.util.concurrent.atomic AtomicInteger)
   (java.util.function Consumer)
   (org.apache.arrow.memory BufferAllocator)
   (org.apache.arrow.vector.util VectorSchemaRootAppender)
   (org.apache.arrow.vector VectorLoader VectorSchemaRoot VectorUnloader)
   org.apache.arrow.vector.types.UnionMode
   (org.apache.arrow.vector.types.pojo ArrowType$Union Schema)
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

(.getName (types/col-type->field log-ops-col-type))



(def ^:private nullable-inst-type [:union #{:null [:timestamp-tz :micro "UTC"]}])

(def ^:private put-op-col-type [:struct {'iid [:fixed-size-binary 16]
                                         'row-id :i64
                                         'valid-from nullable-inst-type
                                         'valid-to nullable-inst-type}])
(def ^:private delete-op-col-type [:struct {'iid [:fixed-size-binary 16]
                                            'valid-from nullable-inst-type
                                            'valid-to nullable-inst-type}])

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

(comment
  :temporal-log-indexer (:log-indexer)
  :content-log-indexer (~ :live-table)

  (with-open [al (org.apache.arrow.memory.RootAllocator.)
              vsr (VectorSchemaRoot/create temporal-log-schema al)]
    (map #(.getName %) (.getFields (.getSchema vsr)))
    ;; (.getFields (.getSchema vsr))
    (.getVector vsr "tx-id")
    )

  (defn copy-vsr [^VectorSchemaRoot src-vsr ^VectorSchemaRoot des-vsr]
    (let [loader (VectorLoader. src-vsr)
          unloader (VectorUnloader. )]
      (try
        )
      (.clear src-vsr)
      des-vrs)))

(defmethod ig/init-key :xtdb.indexer/temporal-log-indexer [_ {:keys [^BufferAllocator allocator, ^ObjectStore object-store]}]
  (let [log-root (VectorSchemaRoot/create temporal-log-schema allocator)
        transient-log-root (VectorSchemaRoot/create temporal-log-schema allocator)

        tx-id-wtr (vw/->writer (.getVector transient-log-root "tx-id"))
        system-time-wtr (vw/->writer (.getVector transient-log-root "system-time"))
        tx-ops-wtr (vw/->writer (.getVector transient-log-root "tx-ops"))
        tx-ops-el-wtr (.listElementWriter tx-ops-wtr)

        put-wtr (.writerForTypeId tx-ops-el-wtr (byte 0))
        put-iid-wtr (.structKeyWriter put-wtr "iid")
        put-row-id-wtr (.structKeyWriter put-wtr "row-id")
        put-vf-wtr (.structKeyWriter put-wtr "valid-from")
        put-vt-wtr (.structKeyWriter put-wtr "valid-to")

        delete-wtr (.writerForTypeId tx-ops-el-wtr (byte 1))
        delete-iid-wtr (.structKeyWriter delete-wtr "iid")
        delete-row-id-wtr (.structKeyWriter delete-wtr "row-id")
        delete-vf-wtr (.structKeyWriter delete-wtr "valid-from")
        delete-vt-wtr (.structKeyWriter delete-wtr "valid-to")

        page-row-counts (ArrayList.)
        !page-row-count (AtomicInteger.)]

    (reify ILogIndexer
      (startTx [_ tx-key]
        (.writeLong tx-id-wtr (.tx-id tx-key))
        (vw/write-value! (.system-time tx-key) system-time-wtr)

        (.startList tx-ops-el-wtr)
        (reify ILogOpIndexer
          (logPut [_ iid row-id app-time-start app-time-end]
            (.startStruct put-wtr)
            (.writeBytes put-iid-wtr (ByteBuffer/wrap iid))
            (.writeLong put-row-id-wtr row-id)
            (.writeLong put-vf-wtr app-time-start)
            (.writeNull put-vt-wtr app-time-end)
            (.endStruct put-wtr))

          (logDelete [_ iid app-time-start app-time-end]
            (.startStruct delete-wtr)
            (.writeNull delete-row-id-wtr nil)
            (.writeBytes delete-iid-wtr (ByteBuffer/wrap iid))
            (.writeLong delete-vf-wtr app-time-start)
            (.writeLong delete-vt-wtr  app-time-end)
            (.endStruct delete-wtr))

          (logEvict [_ _iid]
            (throw (UnsupportedOperationException.)))

          (commit [_]
            (.endList tx-ops-el-wtr)
            (VectorSchemaRootAppender/append log-root [transient-log-root])
            (.clear transient-log-root)
            (.getAndIncrement !page-row-count))

          (abort [_]
            ;; (.clear ops-wtr)
            ;; (.writeNull ops-wtr nil)
            ;; (.endRow transient-log-writer)
            ;; (vw/append-rel log-writer (vw/rel-wtr->rdr transient-log-writer))

            ;; (.clear transient-log-writer)
            ;; (.getAndIncrement !page-row-count)
            )))

      (finishPage [_]
        (.add page-row-counts (.getAndSet !page-row-count 0)))

      (finishChunk [_ chunk-idx]
        (let [log-bytes (with-open [write-root (VectorSchemaRoot/create (.getSchema log-root) allocator)]
                          (let [loader (VectorLoader. write-root)]
                            (with-open [^ICursor slices (blocks/->slices log-root page-row-counts)]
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
        (.clear log-root)
        (.clear page-row-counts))

      Closeable
      (close [_]
        (.close transient-log-root)
        (.close log-root)))))
