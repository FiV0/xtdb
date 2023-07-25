(ns xtdb.trie
  (:require [xtdb.buffer-pool]
            [xtdb.object-store]
            [xtdb.types :as types]
            [xtdb.util :as util]
            [xtdb.vector.writer :as vw])
  (:import (java.nio ByteBuffer)
           (java.util Arrays)
           (java.util.concurrent.atomic AtomicInteger)
           (java.util.function IntConsumer)
           (java.util.stream IntStream)
           (org.apache.arrow.memory BufferAllocator)
           (org.apache.arrow.vector VectorSchemaRoot)
           (org.apache.arrow.vector.types.pojo ArrowType$Union Schema)
           org.apache.arrow.vector.types.UnionMode
           (xtdb.object_store ObjectStore)
           (xtdb.trie HashTrie$Node LiveHashTrie LiveHashTrie$Leaf)
           (xtdb.vector IVectorReader RelationReader)))

(def ^org.apache.arrow.vector.types.pojo.Schema trie-schema
  (Schema. [(types/->field "nodes" (ArrowType$Union. UnionMode/Dense (int-array (range 3))) false
                           (types/col-type->field "nil" :null)
                           (types/col-type->field "branch" [:list [:union #{:null :i32}]])
                           ;; TODO metadata
                           (types/col-type->field "leaf" '[:struct {page-idx :i32}]))]))

(def put-field
  (types/col-type->field "put" [:struct {'xt$valid_from types/temporal-col-type
                                         'xt$valid_to types/temporal-col-type
                                         'xt$doc [:union #{:null [:struct {}]}]}]))

(def delete-field
  (types/col-type->field "delete" [:struct {'xt$valid_from types/temporal-col-type
                                            'xt$valid_to types/temporal-col-type}]))

(def evict-field
  (types/col-type->field "evict" :null))

(def ^:private ^org.apache.arrow.vector.types.pojo.Schema leaf-schema
  (Schema. [(types/col-type->field "xt$iid" [:fixed-size-binary 16])
            (types/col-type->field "xt$system_from" types/temporal-col-type)
            (types/->field "op" (ArrowType$Union. UnionMode/Dense (int-array (range 3))) false
                           put-field delete-field evict-field)]))

(defn open-leaf-root ^xtdb.vector.IRelationWriter [^BufferAllocator allocator]
  (util/with-close-on-catch [root (VectorSchemaRoot/create leaf-schema allocator)]
    (vw/root->writer root)))

(defn live-trie->bufs [^BufferAllocator allocator, ^LiveHashTrie trie, ^RelationReader leaf-rel]
  (when (pos? (.rowCount leaf-rel))
    (util/with-open [leaf-vsr (VectorSchemaRoot/create (Schema. (for [^IVectorReader rdr leaf-rel]
                                                                  (.getField rdr)))
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
            copier (vw/->rel-copier leaf-rel-wtr leaf-rel)

            leaf-buf (util/build-arrow-ipc-byte-buffer leaf-vsr :file
                       (fn [write-batch!]
                         (letfn [(write-node! [^HashTrie$Node node]
                                   (if-let [children (.children node)]
                                     (let [!page-idxs (IntStream/builder)]
                                       (doseq [child children]
                                         (.add !page-idxs (if child
                                                            (do
                                                              (write-node! child)
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
                                       (.endRow trie-rel-wtr))

                                     (let [^LiveHashTrie$Leaf leaf node]
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
                                       (.endRow trie-rel-wtr))))]

                           (write-node! (.rootNode trie)))))]

        (.syncRowCount trie-rel-wtr)

        {:leaf-buf leaf-buf
         :trie-buf (util/root->arrow-ipc-byte-buffer trie-vsr :file)}))))

(defn write-trie-bufs! [^ObjectStore obj-store, ^String dir, ^String chunk-idx
                        {:keys [^ByteBuffer leaf-buf ^ByteBuffer trie-buf]}]
  (-> (.putObject obj-store (format "%s/leaf-c%s.arrow" dir chunk-idx) leaf-buf)
      (util/then-compose
        (fn [_]
          (.putObject obj-store
                      (format "%s/trie-c%s.arrow" dir chunk-idx)
                      trie-buf
                      #_(util/root->arrow-ipc-byte-buffer trie-buf :file))))))
