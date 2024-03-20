(ns xtdb.indices
  (:require [xtdb.time :as time]
            [xtdb.trie :as trie])
  (:import (org.apache.arrow.vector.types.pojo FieldType)
           xtdb.api.TransactionKey
           (xtdb.indexer.live_index  ILiveIndexTx)))

(def ^:private ^:const ^String index-table "xt$indices")

(defn- add-index-row! [^ILiveIndexTx live-idx-tx, ^TransactionKey tx-key, ^String index-name,
                       ^String table-name, ^String column-name, index-action]
  (let [tx-id (.getTxId tx-key)
        system-time-µs (time/instant->micros (.getSystemTime tx-key))

        live-table (.liveTable live-idx-tx table-name)
        doc-writer (.docWriter live-table)]

    (.logPut live-table (trie/->iid tx-id) system-time-µs Long/MAX_VALUE
             (fn write-doc! []
               (.startStruct doc-writer)
               (doto (.structKeyWriter doc-writer "xt$id" (FieldType/notNullable #xt.arrow/type :utf8))
                 (.writeObject index-name))

               (doto (.structKeyWriter doc-writer "table_name" (FieldType/notNullable #xt.arrow/type :utf8))
                 (.writeObject table-name))

               (doto (.structKeyWriter doc-writer "column_name" (FieldType/notNullable  #xt.arrow/type :utf8))
                 (.writeObject column-name))

               (assert (#{:create :ready :delete} index-action))
               (doto (.structKeyWriter doc-writer "xt$index_state" (FieldType/notNullable  #xt.arrow/type :utf8))
                 (.writeObject index-action))

               (.endStruct doc-writer)))))
