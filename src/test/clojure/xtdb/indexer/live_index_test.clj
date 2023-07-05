(ns xtdb.indexer.live-index-test
  (:require [clojure.test :as t]
            [xtdb.api.protocols :as xtp]
            [xtdb.indexer.live-index :as li]
            xtdb.object-store
            [xtdb.test-util :as tu]
            [xtdb.util :as util]
            [xtdb.vector.writer :as vw])
  (:import [java.util Random UUID]
           [org.apache.arrow.memory BufferAllocator]
           [org.apache.arrow.vector ValueVector]
           [org.apache.arrow.vector.ipc ArrowFileReader]
           xtdb.indexer.live_index.ILiveIndex
           xtdb.object_store.ObjectStore
           (xtdb.trie ArrowHashTrie ArrowHashTrie$Node ArrowHashTrie$NodeVisitor WalTrie WalTrie$Node WalTrie$NodeVisitor)
           xtdb.vector.IRowCopier))

(def with-live-index
  (tu/with-system {:xtdb/allocator {}
                   :xtdb.indexer/live-index {}
                   :xtdb.object-store/memory-object-store {}}))

(t/use-fixtures :each with-live-index)

(deftype WalTrieRenderer [^ValueVector iid-vec]
  WalTrie$NodeVisitor
  (visitBranch [this branch]
    (into [] (mapcat #(.accept ^WalTrie$Node % this)) (.children branch)))

  (visitLeaf [_ leaf]
    (mapv #(vec (.getObject iid-vec %)) (.data leaf))))

(deftype ArrowTrieRenderer [^ArrowFileReader leaf-rdr, ^ValueVector iid-vec,
                            ^:unsychronized-mutable ^int current-page-idx]
  ArrowHashTrie$NodeVisitor
  (visitBranch [this branch]
    (mapcat #(.accept ^ArrowHashTrie$Node % this) (.getChildren branch)))

  (visitLeaf [_ leaf]
    ;; would be good if ArrowFileReader accepted a page-idx...
    (.loadRecordBatch leaf-rdr (.get (.getRecordBlocks leaf-rdr) (.getPageIndex leaf)))

    (->> (range 0 (.getValueCount iid-vec))
         (mapv #(vec (.getObject iid-vec %))))))

(t/deftest test-chunk
  (let [{^BufferAllocator allocator :xtdb/allocator
         ^ILiveIndex live-index :xtdb.indexer/live-index
         ^ObjectStore obj-store :xtdb.object-store/memory-object-store} tu/*sys*

        iids (let [rnd (Random. 0)]
               (repeatedly 12000 #(UUID. (.nextLong rnd) (.nextLong rnd))))

        iid-bytes (->> (sort-by #(.getMostSignificantBits ^UUID %) #(Long/compareUnsigned %1 %2) iids)
                       (mapv (comp vec util/uuid->bytes)))]

    (t/testing "commit"
      (util/with-open [live-idx-tx (.startTx live-index (xtp/->TransactionInstant 0 (.toInstant #inst "2000")))
                       live-table-tx (.liveTable live-idx-tx "my-table")]
        (let [wtr (.documentWriter live-table-tx)]
          (doseq [iid iids]
            (let [pos (.getPosition (.writerPosition wtr))]
              (.logPut live-table-tx (util/uuid->bytes iid) 0 0
                       (reify IRowCopier
                         (copyRow [_ _idx]
                           (.startRow wtr)
                           (.endRow wtr)
                           pos))
                       0))))

        (.commit live-idx-tx)

        (let [live-table (.liveTable live-index "my-table")
              log-rel (li/log-rel live-table)
              iid-vec (.getVector (.writerForName log-rel "xt$iid"))

              ^WalTrie trie (li/log-trie live-table)]

          (t/is (= iid-bytes (-> (.compactLogs trie)
                                 (.accept (WalTrieRenderer. iid-vec))))))))

    (t/testing "finish chunk"
      (.finishChunk live-index 0)

      (let [trie-buf @(.getObject obj-store "tables/my-table/wal/trie-c00.arrow")
            leaf-buf @(.getObject obj-store "tables/my-table/wal/leaf-c00.arrow")]
        (with-open [trie-rdr (ArrowFileReader. (util/->seekable-byte-channel trie-buf) allocator)
                    leaf-rdr (ArrowFileReader. (util/->seekable-byte-channel leaf-buf) allocator)]
          (.loadNextBatch trie-rdr)
          (t/is (= iid-bytes
                   (.accept (ArrowHashTrie/from (.getVectorSchemaRoot trie-rdr))
                            (ArrowTrieRenderer. leaf-rdr (.getVector (.getVectorSchemaRoot leaf-rdr) "xt$iid") -1)))))))))
