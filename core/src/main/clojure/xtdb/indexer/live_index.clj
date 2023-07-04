(ns xtdb.indexer.live-index
  (:require [juxt.clojars-mirrors.integrant.core :as ig]
            xtdb.buffer-pool
            xtdb.object-store
            [xtdb.types :as types]
            [xtdb.util :as util]
            [xtdb.vector.indirect :as iv]
            [xtdb.vector.writer :as vw])
  (:import (java.lang AutoCloseable)
           (java.util ArrayList Arrays HashMap Map)
           (java.util.concurrent CompletableFuture)
           (java.util.concurrent.atomic AtomicInteger)
           (java.util.function BiConsumer BiFunction Function IntConsumer)
           (java.util.stream IntStream)
           (org.apache.arrow.memory BufferAllocator)
           (org.apache.arrow.vector BaseFixedWidthVector FixedSizeBinaryVector TimeStampMicroTZVector VectorSchemaRoot)
           (org.apache.arrow.vector.types.pojo ArrowType$Union Schema)
           org.apache.arrow.vector.types.UnionMode
           (xtdb.object_store ObjectStore)
           (xtdb.trie MemoryHashTrie MemoryHashTrie$Visitor TrieKeys)
           (xtdb.vector IIndirectRelation IIndirectVector IRelationWriter)))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(definterface ILiveTableWatermark
  (^xtdb.vector.IIndirectRelation leaf [])
  (tries []))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(definterface ILiveTableTx
  (^xtdb.indexer.live_index.ILiveTableWatermark openWatermark [^boolean retain])
  (^xtdb.vector.IRelationWriter leafWriter [])
  (^void addRow [idx])
  (^void commit [])
  (^void close []))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(definterface ILiveTable
  (^xtdb.indexer.live_index.ILiveTableTx startTx [])
  (^xtdb.indexer.live_index.ILiveTableWatermark openWatermark [^boolean retain])
  (^java.util.concurrent.CompletableFuture #_<?> finishChunk [^long chunkIdx])
  (^void close []))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(definterface ILiveIndexWatermark
  (^xtdb.indexer.live_index.ILiveTableWatermark liveTable [^String tableName]))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(definterface ILiveIndexTx
  (^xtdb.indexer.live_index.ILiveTableTx liveTable [^String tableName])
  (^xtdb.indexer.live_index.ILiveIndexWatermark openWatermark [])
  (^void commit [])
  (^void close []))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(definterface ILiveIndex
  (^xtdb.indexer.live_index.ILiveTable liveTable [^String tableName])
  (^xtdb.indexer.live_index.ILiveIndexTx startTx [])
  (^xtdb.indexer.live_index.ILiveIndexWatermark openWatermark [])
  (^void finishChunk [^long chunkIdx])
  (^void close []))

(defprotocol TestLiveTable
  (^xtdb.vector.IRelationWriter leaf-writer [test-live-table])
  (^java.util.Map tries [test-live-table]))

(def ^org.apache.arrow.vector.types.pojo.Schema trie-schema
  (Schema. [(types/->field "nodes" (ArrowType$Union. UnionMode/Dense (int-array (range 3))) false
                           (types/col-type->field "nil" :null)
                           (types/col-type->field "branch" [:list [:union #{:null :i32}]])
                           ;; TODO metadata
                           (types/col-type->field "leaf" '[:struct {page-idx :i32}]))]))

(defn- write-trie!
  ^java.util.concurrent.CompletableFuture [^BufferAllocator allocator, ^ObjectStore obj-store,
                                           ^String table-name, ^String trie-name, ^String chunk-idx,
                                           ^MemoryHashTrie trie, ^IIndirectRelation leaf]

  (util/with-close-on-catch [leaf-vsr (VectorSchemaRoot/create (Schema. (for [^IIndirectVector rdr leaf]
                                                                          (.getField (.getVector rdr))))
                                                               allocator)
                             trie-vsr (VectorSchemaRoot/create trie-schema allocator)]
    (let [leaf-rel-wtr (vw/root->writer leaf-vsr)
          trie-rel-wtr (vw/root->writer trie-vsr)

          node-wtr (.writerForName trie-rel-wtr "nodes")
          node-wp (.writerPosition node-wtr)

          branch-wtr (.writerForTypeId node-wtr (byte 1))
          branch-el-wtr (.listElementWriter branch-wtr)

          leaf-wtr (.writerForTypeId node-wtr (byte 2))
          page-idx-wtr (.structKeyWriter leaf-wtr "page-idx")
          !page-idx (AtomicInteger. 0)
          copier (vw/->rel-copier leaf-rel-wtr leaf)]

      (-> (.putObject obj-store
                      (format "tables/%s/%s/leaf-c%s.arrow" table-name trie-name chunk-idx)
                      (util/build-arrow-ipc-byte-buffer leaf-vsr :file
                        (fn [write-batch!]
                          (.accept trie
                                   (reify MemoryHashTrie$Visitor
                                     (visitBranch [visitor branch]
                                       (let [!page-idxs (IntStream/builder)]
                                         (doseq [^MemoryHashTrie child (.children branch)]
                                           (.add !page-idxs (if child
                                                              (do
                                                                (.accept child visitor)
                                                                (dec (.getPosition node-wp)))
                                                              -1)))
                                         (.startList branch-wtr)
                                         (.forEach (.build !page-idxs)
                                                   (reify IntConsumer
                                                     (accept [_ idx]
                                                       (if (= idx -1)
                                                         (.writeNull branch-el-wtr nil)
                                                         (.writeInt branch-el-wtr idx)))))
                                         (.endList branch-wtr)
                                         (.endRow trie-rel-wtr)))

                                     (visitLeaf [_ leaf]
                                       (-> (Arrays/stream (.data leaf))
                                           (.forEach (reify IntConsumer
                                                       (accept [_ idx]
                                                         (.copyRow copier idx)))))

                                       (.syncRowCount leaf-rel-wtr)
                                       (write-batch!)
                                       (.clear leaf-rel-wtr)
                                       (.clear leaf-vsr)

                                       (.startStruct leaf-wtr)
                                       (.writeInt page-idx-wtr (.getAndIncrement !page-idx))
                                       (.endStruct leaf-wtr)
                                       (.endRow trie-rel-wtr)))))))
          (util/then-compose
            (fn [_]
              (.syncRowCount trie-rel-wtr)
              (.putObject obj-store
                          (format "tables/%s/%s/trie-c%s.arrow" table-name trie-name chunk-idx)
                          (util/root->arrow-ipc-byte-buffer trie-vsr :file))))

          (.whenComplete (reify BiConsumer
                           (accept [_ _ _]
                             (util/try-close trie-vsr)
                             (util/try-close leaf-vsr))))))))

(def ^:private ^org.apache.arrow.vector.types.pojo.Schema leaf-schema
  (Schema. [(types/col-type->field "xt$iid" [:fixed-size-binary 16])
            (types/col-type->field "xt$valid_from" types/nullable-temporal-type)
            (types/col-type->field "xt$valid_to" types/nullable-temporal-type)
            (types/col-type->field "xt$system_from" types/nullable-temporal-type)
            (types/col-type->field "xt$system_to" types/nullable-temporal-type)
            (types/col-type->field "xt$doc" [:struct {}])]))

(defn- open-leaf-root ^xtdb.vector.IRelationWriter [^BufferAllocator allocator]
  (vw/root->writer (VectorSchemaRoot/create leaf-schema allocator)))

(defn- open-wm-leaf ^xtdb.vector.IIndirectRelation [^IRelationWriter rel, retain?]
  (let [out-cols (ArrayList.)]
    (try
      (doseq [^IIndirectVector v (vw/rel-wtr->rdr rel)]
        (.add out-cols (iv/->direct-vec (cond-> (.getVector v)
                                          retain? (util/slice-vec)))))

      (iv/->indirect-rel out-cols)

      (catch Throwable t
        (when retain? (util/close out-cols))
        (throw t)))))

(deftype LiveTable [^BufferAllocator allocator, ^ObjectStore obj-store, ^String table-name
                    ^IRelationWriter leaf, ^:unsynchronized-mutable ^Map tries
                    ^FixedSizeBinaryVector iid-vec
                    ^TimeStampMicroTZVector valid-to-vec, ^TimeStampMicroTZVector system-to-vec]
  ILiveTable
  (startTx [this-table]
    (let [transient-tries (HashMap. tries)
          t1-trie-keys (TrieKeys. (into-array BaseFixedWidthVector [iid-vec]))
          t2-trie-keys (TrieKeys. (into-array BaseFixedWidthVector [iid-vec valid-to-vec]))]
      (reify ILiveTableTx
        (leafWriter [_] leaf)

        (addRow [_ idx]
          (let [trie-idx (+ (if (.isNull system-to-vec idx) 0 2)
                            (if (.isNull valid-to-vec idx) 0 1)
                            1)]
            ;; TODO later tries could be partitioned by various times
            (.compute transient-tries (format "t%d-diff" trie-idx)
                      (reify BiFunction
                        (apply [_ _trie-name {:keys [trie trie-keys]}]
                          (let [^MemoryHashTrie trie (or trie (MemoryHashTrie/emptyTrie))

                                trie-keys (or trie-keys (case trie-idx 1 t1-trie-keys, 2 t2-trie-keys))]

                            {:trie (.add trie trie-keys idx)
                             :trie-keys trie-keys}))))))

        (openWatermark [_ retain?]
          (locking this-table
            (let [wm-leaf (open-wm-leaf leaf retain?)
                  wm-tries (into {} transient-tries)]
              (reify ILiveTableWatermark
                (leaf [_] wm-leaf)
                (tries [_] wm-tries)

                AutoCloseable
                (close [_]
                  (when retain? (util/close wm-leaf)))))))

        (commit [_]
          (locking this-table
            (set! (.-tries this-table) transient-tries)))

        AutoCloseable
        (close [_]))))

  (finishChunk [_ chunk-idx]
    (let [chunk-idx-str (util/->lex-hex-string chunk-idx)]
      (CompletableFuture/allOf
       (->> (for [[trie-name {:keys [^MemoryHashTrie trie trie-keys]}] tries]
              (write-trie! allocator obj-store table-name trie-name chunk-idx-str (-> trie (.compactLogs trie-keys)) (vw/rel-wtr->rdr leaf)))
            (into-array CompletableFuture)))))

  (openWatermark [this retain?]
    (locking this
      (let [wm-leaf (open-wm-leaf leaf retain?)
            wm-tries (into {} tries)]
        (reify ILiveTableWatermark
          (leaf [_] wm-leaf)
          (tries [_] wm-tries)

          AutoCloseable
          (close [_]
            (when retain? (util/close wm-leaf)))))))

  TestLiveTable
  (leaf-writer [_] leaf)
  (tries [_] tries)

  AutoCloseable
  (close [this]
    (locking this
      (util/close leaf))))

(defrecord LiveIndex [^BufferAllocator allocator, ^ObjectStore object-store, ^Map tables]
  ILiveIndex
  (liveTable [_ table-name]
    (.computeIfAbsent tables table-name
                      (reify Function
                        (apply [_ table-name]
                          (util/with-close-on-catch [rel (open-leaf-root allocator)]
                            (LiveTable. allocator object-store table-name rel (HashMap.)
                                        (.getVector (.writerForName rel "xt$iid"))
                                        (.getVector (.writerForName rel "xt$valid_to"))
                                        (.getVector (.writerForName rel "xt$system_to"))))))))

  (startTx [live-idx]
    (let [table-txs (HashMap.)]
      (reify ILiveIndexTx
        (liveTable [_ table-name]
          (.computeIfAbsent table-txs table-name
                            (reify Function
                              (apply [_ table-name]
                                (-> (.liveTable live-idx table-name)
                                    (.startTx))))))

        (commit [_]
          (doseq [^ILiveTableTx table-tx (.values table-txs)]
            (.commit table-tx)))

        (openWatermark [_]
          (util/with-close-on-catch [wms (HashMap.)]
            (doseq [[table-name ^ILiveTableTx live-table] table-txs]
              (.put wms table-name (.openWatermark live-table false)))

            (doseq [[table-name ^ILiveTable live-table] tables]
              (.computeIfAbsent wms table-name
                                (util/->jfn (fn [_] (.openWatermark live-table false)))))

            (reify ILiveIndexWatermark
              (liveTable [_ table-name] (.get wms table-name))

              AutoCloseable
              (close [_] (util/close wms)))))

        AutoCloseable
        (close [_]
          (util/close table-txs)))))

  (openWatermark [_]
    (util/with-close-on-catch [wms (HashMap.)]
      (doseq [[table-name ^ILiveTable live-table] tables]
        (.put wms table-name (.openWatermark live-table true)))

      (reify ILiveIndexWatermark
        (liveTable [_ table-name] (.get wms table-name))

        AutoCloseable
        (close [_] (util/close wms)))))

  (finishChunk [_ chunk-idx]
    @(CompletableFuture/allOf (->> (for [^ILiveTable table (.values tables)]
                                     (.finishChunk table chunk-idx))

                                   (into-array CompletableFuture)))

    (util/close tables)
    (.clear tables))

  AutoCloseable
  (close [_]
    (util/close tables)))

(defmethod ig/prep-key :xtdb.indexer/live-index [_ opts]
  (merge {:allocator (ig/ref :xtdb/allocator)
          :object-store (ig/ref :xtdb/object-store)}
         opts))

(defmethod ig/init-key :xtdb.indexer/live-index [_ {:keys [allocator object-store]}]
  (LiveIndex. allocator object-store (HashMap.)))

(defmethod ig/halt-key! :xtdb.indexer/live-index [_ live-idx]
  (util/close live-idx))
