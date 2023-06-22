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
  :temporal-log-indexer (:log-indexer)
  :content-log-indexer (~ :live-table)

  (with-open [al (org.apache.arrow.memory.RootAllocator.)]
    (let [wrt (vw/->rel-writer al)
          list-wrt (.writerForName wrt "tx-ops" [:list
                                                 [:union {'foo :i64
                                                          'bar :f64}]])
          list-el-wrt (.listElementWriter list-wrt)]
      list-el-wrt))

  )

(comment
  (defn print-rel-wrt [^xtdb.vector.IRelationWriter rel-wrt]
    (.syncRowCount rel-wrt)
    (let [vsr (let [^Iterable vecs (for [^IVectorWriter w (vals rel-wrt)]
                                     (.getVector w))]
                (VectorSchemaRoot. vecs))]
      (println (.contentToTSVString vsr))))

  (with-open [al (org.apache.arrow.memory.RootAllocator.)]
    (let [wrt (vw/->rel-writer al)
          list-wrt (.writerForName wrt "tx-ops" [:list
                                                 [:union {'foo :i64
                                                          'bar :f64}]])
          list-el-wrt (.listElementWriter list-wrt)
          foo-wrt (.writerForTypeId list-el-wrt (byte 0))
          bar-wrt (.writerForTypeId list-el-wrt (byte 1))]
      (.startList list-wrt)
      (.writeLong foo-wrt 1)
      (.writeDouble bar-wrt (double 2.0))
      (.endList list-wrt)
      (print-rel-wrt wrt)
      (.close wrt))))


(defn long->byte-hash [^long l]
  (byte-array 16 (.getBytes (Long/toHexString l))))

(defmethod ig/init-key :xtdb.indexer/temporal-log-indexer [_ {:keys [^BufferAllocator allocator, ^ObjectStore object-store]}]
  (let [log-writer (vw/->rel-writer allocator)
        transient-log-writer (vw/->rel-writer allocator)

        tx-id-wtr (.writerForName transient-log-writer "tx-id" :i64)
        system-time-wtr (.writerForName transient-log-writer "system-time" [:timestamp-tz :micro "UTC"])

        tx-ops-wtr (.writerForName transient-log-writer "tx-ops" [:list
                                                                  [:union
                                                                   {'put [:struct {'iid [:fixed-size-binary 16]
                                                                                   'row-id :i64
                                                                                   'valid-from nullable-inst-type
                                                                                   'valid-to nullable-inst-type}]
                                                                    'delete [:struct {'iid [:fixed-size-binary 16]
                                                                                      'valid-from nullable-inst-type
                                                                                      'valid-to nullable-inst-type}]}]])

        tx-ops-el-wtr (.listElementWriter tx-ops-wtr)

        ;; log-root (VectorSchemaRoot/create temporal-log-schema allocator)
        ;; transient-log-root (VectorSchemaRoot/create temporal-log-schema allocator)

        ;; tx-id-wtr (vw/->writer (.getVector transient-log-root "tx-id"))
        ;; system-time-wtr (vw/->writer (.getVector transient-log-root "system-time"))
        ;; tx-ops-wtr (vw/->writer (.getVector transient-log-root "tx-ops"))
        ;; tx-ops-el-wtr (.listElementWriter tx-ops-wtr)

        put-wtr (.writerForTypeId tx-ops-el-wtr (byte 0))
        put-iid-wtr (.structKeyWriter put-wtr "iid")
        put-row-id-wtr (.structKeyWriter put-wtr "row-id")
        put-vf-wtr (.structKeyWriter put-wtr "valid-from")
        put-vt-wtr (.structKeyWriter put-wtr "valid-to")

        delete-wtr (.writerForTypeId tx-ops-el-wtr (byte 1))
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
            (.endStruct put-wtr)
            )

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
            (vw/append-rel log-writer (vw/rel-wtr->rdr transient-log-writer))

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


(def test-schema
  (Schema. [#_(types/->field "$data" (ArrowType$Union. UnionMode/Dense (int-array (range 2))) false
                             (types/col-type->field "put" :i64)
                             (types/col-type->field "foo" :f64))




            #_(types/col-type->field "tx-id" :i64)
            (types/->field "tx-ops" types/list-type false
                           (types/->field "$data" (ArrowType$Union. UnionMode/Dense (int-array (range 1))) false
                                          (types/col-type->field "put" [:struct {#_#_'iid [:fixed-size-binary 16]
                                                                                 'row-id :i64
                                                                                 #_#_'valid-from nullable-inst-type
                                                                                 #_#_'valid-to nullable-inst-type}])
                                          #_(types/col-type->field "delete" [:struct {'iid [:fixed-size-binary 16]
                                                                                      #_#_'valid-from nullable-inst-type
                                                                                      #_#_'valid-to nullable-inst-type}])))]))

(comment


  (with-open [al (RootAllocator.)
              vsr1 (VectorSchemaRoot/create test-schema #_(Schema. [(types/col-type->field "tx-id" :i64)]) al)
              vsr2 (VectorSchemaRoot/create test-schema #_(Schema. [(types/col-type->field "tx-id" :i64)]) al)]
    (let [#_#_tx-id-wrt1 (vw/->writer (.getVector vsr1 "tx-id"))
          #_#_tx-id-wrt2 (vw/->writer (.getVector vsr2 "tx-id"))

          ;; data-wtr (vw/->writer (.getVector vsr1 "$data"))
          ;; put-wtr (.writerForTypeId data-wtr (byte 0))
          ;; foo-wtr (.writerForTypeId data-wtr (byte 1))

          ;; system-time-wtr (vw/->writer (.getVector vsr1 "system-time"))
          tx-ops-wtr (vw/->writer (.getVector vsr1 "tx-ops"))
          tx-ops-el-wtr (.listElementWriter tx-ops-wtr)
          put-wtr (.writerForTypeId tx-ops-el-wtr (byte 0))
          ;; put-iid-wtr (.structKeyWriter put-wtr "iid")
          put-row-id-wtr (.structKeyWriter put-wtr "row-id")
          ;; put-vf-wtr (.structKeyWriter put-wtr "valid-from")
          ;; put-vt-wtr (.structKeyWriter put-wtr "valid-to")

          tx-ops-wtr2 (vw/->writer (.getVector vsr2 "tx-ops"))
          tx-ops-el-wtr2 (.listElementWriter tx-ops-wtr2)
          put-wtr2 (.writerForTypeId tx-ops-el-wtr2 (byte 0))
          ;; put-iid-wtr (.structKeyWriter put-wtr "iid")
          put-row-id-wtr2 (.structKeyWriter put-wtr2 "row-id")


          ]
      ;; (.writeLong tx-id-wtr 0)
      ;; (.writeLong system-time-wtr 0)
      (.startList tx-ops-wtr)
      (.startStruct put-wtr)
      ;; (.writeBytes put-iid-wtr (ByteBuffer/wrap (long->byte-hash 111)))
      (.writeLong put-row-id-wtr 111)
      ;; (.writeLong put-vf-wtr 0)
      ;; (.writeLong put-vt-wtr Long/MAX_VALUE)
      (.endStruct put-wtr)
      (.endList tx-ops-wtr)
      (.setRowCount vsr1 1)
      (println (.contentToTSVString vsr1))
      ;; (.getOffsetBuffer (.getVector vsr2 "tx-ops"))
      ;; (.getInt (.getOffsetBuffer (.getVector vsr2 "tx-ops")) 0)


      ;; (.writeLong tx-id-wrt1 1)
      ;; (println (.contentToTSVString vsr1))
      ;; (.writeLong tx-id-wrt2 2)
      (.startList tx-ops-wtr2)
      (.startStruct put-wtr2)
      ;; (.writeBytes put-iid-wtr (ByteBuffer/wrap (long->byte-hash 111)))
      (.writeLong put-row-id-wtr2 111)
      ;; (.writeLong put-vf-wtr 0)
      ;; (.writeLong put-vt-wtr Long/MAX_VALUE)
      (.endStruct put-wtr2)
      (.endList tx-ops-wtr2)
      (.setRowCount vsr2 1)
      (VectorSchemaRootAppender/append vsr2 (into-array VectorSchemaRoot [vsr1]))
      (println (.contentToTSVString vsr2))

      )))

(def test-schema
  (Schema. [#_(types/->field "$data" (ArrowType$Union. UnionMode/Dense (int-array (range 2))) false
                             (types/col-type->field "put" :i64)
                             (types/col-type->field "foo" :f64))




            #_(types/col-type->field "tx-id" :i64)
            (types/->field "tx-ops" types/list-type false
                           (types/col-type->field "foo" :i64)
                           #_(types/->field "$data" (ArrowType$Union. UnionMode/Dense (int-array (range 1))) false
                                            (types/col-type->field "put" [:struct {#_#_'iid [:fixed-size-binary 16]
                                                                                   'row-id :i64
                                                                                   #_#_'valid-from nullable-inst-type
                                                                                   #_#_'valid-to nullable-inst-type}])
                                            #_(types/col-type->field "delete" [:struct {'iid [:fixed-size-binary 16]
                                                                                        #_#_'valid-from nullable-inst-type
                                                                                        #_#_'valid-to nullable-inst-type}])))]))

(comment


  (with-open [al (RootAllocator.)
              vsr1 (VectorSchemaRoot/create test-schema #_(Schema. [(types/col-type->field "tx-id" :i64)]) al)
              vsr2 (VectorSchemaRoot/create test-schema #_(Schema. [(types/col-type->field "tx-id" :i64)]) al)]
    (let [#_#_tx-id-wrt1 (vw/->writer (.getVector vsr1 "tx-id"))
          #_#_tx-id-wrt2 (vw/->writer (.getVector vsr2 "tx-id"))

          ;; data-wtr (vw/->writer (.getVector vsr1 "$data"))
          ;; put-wtr (.writerForTypeId data-wtr (byte 0))
          ;; foo-wtr (.writerForTypeId data-wtr (byte 1))

          ;; system-time-wtr (vw/->writer (.getVector vsr1 "system-time"))
          tx-ops-wtr (vw/->writer (.getVector vsr1 "tx-ops"))
          tx-ops-el-wtr (.listElementWriter tx-ops-wtr)

          tx-ops-wtr2 (vw/->writer (.getVector vsr2 "tx-ops"))
          tx-ops-el-wtr2 (.listElementWriter tx-ops-wtr2)


          ;; put-wtr (.writerForTypeId tx-ops-el-wtr (byte 0))
          ;; ;; put-iid-wtr (.structKeyWriter put-wtr "iid")
          ;; put-row-id-wtr (.structKeyWriter put-wtr "row-id")
          ;; ;; put-vf-wtr (.structKeyWriter put-wtr "valid-from")
          ;; ;; put-vt-wtr (.structKeyWriter put-wtr "valid-to")

          ;; tx-ops-wtr2 (vw/->writer (.getVector vsr2 "tx-ops"))
          ;; tx-ops-el-wtr2 (.listElementWriter tx-ops-wtr2)
          ;; put-wtr2 (.writerForTypeId tx-ops-el-wtr2 (byte 0))
          ;; ;; put-iid-wtr (.structKeyWriter put-wtr "iid")
          ;; put-row-id-wtr2 (.structKeyWriter put-wtr2 "row-id")


          ]
      ;; (bean tx-ops-el-wtr)
      ;; (vw/write-value! tx-ops-el-wtr 0)
      (.startList tx-ops-wtr)
      (.writeLong tx-ops-el-wtr 0)
      (.endList tx-ops-wtr)
      (.setRowCount vsr1 1)
      (println (.contentToTSVString vsr1))
      ;; (.getOffsetBuffer (.getVector vsr2 "tx-ops"))
      ;; (.getInt (.getOffsetBuffer (.getVector vsr2 "tx-ops")) 0)

      (.startList tx-ops-wtr2)
      (.writeLong tx-ops-el-wtr2 1)
      (.endList tx-ops-wtr2)
      (.setRowCount vsr2 1)

      (VectorSchemaRootAppender/append vsr2 (into-array VectorSchemaRoot [vsr1]))
      (println (.contentToTSVString vsr2))



      )))

;; 2 issues
;; - empty list doesn't correctly VectorAppender
;; - duv's are not correctly copied with VectorAppender
