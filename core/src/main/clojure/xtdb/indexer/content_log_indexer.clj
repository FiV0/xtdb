(ns xtdb.indexer.content-log-indexer
  (:require
   [juxt.clojars-mirrors.integrant.core :as ig]
   [xtdb.blocks :as blocks]
   [xtdb.types :as types]
   [xtdb.util :as util]
   [xtdb.vector.writer :as vw])
  (:import
   (java.io Closeable)
   (java.util ArrayList)
   (java.util.concurrent.atomic AtomicInteger)
   (java.util.function Consumer)
   (org.apache.arrow.memory BufferAllocator)
   (org.apache.arrow.vector VectorLoader VectorSchemaRoot VectorUnloader)
   (org.apache.arrow.vector.types.pojo Schema)
   (xtdb ICursor)
   (xtdb.object_store ObjectStore)))

#_{:clj-kondo/ignore [:unused-binding :clojure-lsp/unused-public-var]}
(definterface IContentLogTx
  (^xtdb.vector.IRelationWriter writer [^String table-name])
  (^void endRow [])
  (^void commit [])
  (^void abort []))

#_{:clj-kondo/ignore [:unused-binding :clojure-lsp/unused-public-var]}
(definterface IContentLog
  (^xtdb.indexer.content_log_indexer.IContentLogTx startTx [])

  (^void finishBlock [])
  (^java.util.concurrent.CompletableFuture finishChunk [^long chunkIdx])
  (^void nextChunk [])
  (^void close []))

(defn- ->log-obj-key [chunk-idx]
  (format "chunk-%s/content-log.arrow" (util/->lex-hex-string chunk-idx)))

(defmethod ig/prep-key :xtdb.indexer/content-log-indexer [_ opts]
  (merge {:allocator (ig/ref :xtdb/allocator)
          :object-store (ig/ref :xtdb/object-store)}
         opts))

(def content-log-schema
  (Schema. [(types/->field "documents" types/dense-union-type false)]))

(defmethod ig/init-key :xtdb.indexer/content-log-indexer [_ {:keys [^BufferAllocator allocator, ^ObjectStore object-store]}]
  (let [content-root (VectorSchemaRoot/create content-log-schema allocator)
        content-wrt (vw/root->writer content-root)
        transient-content-root (VectorSchemaRoot/create content-log-schema allocator)
        transient-content-wrt (vw/root->writer transient-content-root)

        document-wtr (.writerForName transient-content-wrt "documents")

        block-row-counts (ArrayList.)
        !block-row-count (AtomicInteger.)
        transient-writer-pos (.writerPosition transient-content-wrt)]

    (reify IContentLog
      (startTx [_]
        (reify IContentLogTx
          (writer [_ table-name]
            (->> (types/->field table-name types/struct-type false)
                 (.writerForField document-wtr)
                 (vw/struct-writer->rel-writer)))

          (endRow [_]
            (.endRow transient-content-wrt))

          (commit [_]
            (.syncRowCount transient-content-wrt)
            (when (pos? (.getPosition transient-writer-pos))
              (.syncSchema transient-content-root)
              (vw/append-rel content-wrt (vw/rel-wtr->rdr transient-content-wrt))

              (.addAndGet !block-row-count (.getPosition transient-writer-pos))
              (.clear transient-content-wrt)))

          (abort [_]
            (.clear transient-content-wrt))))

      (finishBlock [_]
        (let [current-row-count (.getAndSet !block-row-count 0)]
          (when (pos? current-row-count)
            (.add block-row-counts current-row-count))))

      (finishChunk [_ chunk-idx]
        (.syncRowCount content-wrt)
        (.syncSchema content-root)

        (let [content-bytes (with-open [write-root (VectorSchemaRoot/create (.getSchema content-root) allocator)]
                              (let [loader (VectorLoader. write-root)]
                                (with-open [^ICursor slices (blocks/->slices content-root block-row-counts)]
                                  (util/build-arrow-ipc-byte-buffer write-root :file
                                                                    (fn [write-batch!]
                                                                      (.forEachRemaining slices
                                                                                         (reify Consumer
                                                                                           (accept [_ sliced-root]
                                                                                             (with-open [arb (.getRecordBatch (VectorUnloader. sliced-root))]
                                                                                               (.load loader arb)
                                                                                               (write-batch!))))))))))]

          (.putObject object-store (->log-obj-key chunk-idx) content-bytes)))

      (nextChunk [_]
        (.clear content-wrt)
        (.clear block-row-counts))

      Closeable
      (close [_]
        (.close transient-content-root)
        (.close content-root)))))
