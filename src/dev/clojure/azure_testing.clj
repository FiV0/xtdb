(ns azure-testing
  (:require [clojure.java.io :as io]
            [integrant.core :as i]
            [integrant.repl :as ir]
            [xtdb.api :as xt]
            [xtdb.node :as xtdb]
            [xtdb.util :as util]))

(def dev-node-dir
  (io/file "dev/azure-node"))

(def node nil)
(def storage-account "xtdb")
(def container "xtdb-azure-test")
(def fully-qualified-namespace "xtdbeventhublogtest.servicebus.windows.net")
(def eventhub "xtdb-azure-module-test")

(defmethod i/init-key ::xtdb [_ {:keys [node-opts]}]
  (alter-var-root #'node (constantly (xtdb/start-node node-opts)))
  node)

(defmethod i/halt-key! ::xtdb [_ node]
  (util/try-close node)
  (alter-var-root #'node (constantly nil)))

(def azure-object-store
  {::xtdb {:node-opts {:log [:local {:path (io/file dev-node-dir "log")}]
                       :storage [:remote {:object-store [:azure {:storage-account storage-account
                                                                 :container container}]
                                          :local-disk-cache (io/file dev-node-dir "objects")}]}}})

(def azure-log
  {::xtdb {:node-opts {:log [:azure-event-hub {:fully-qualified-namespace fully-qualified-namespace
                                               :event-hub-name eventhub
                                               :max-wait-time "PT1S"}]}}})

(ir/set-prep! (fn [] azure-log))

(comment
  (ir/go)
  (xt/status node)
  (def submit (xt/submit-tx node [[:put-docs :posts {:xt/id 1234
                                                :user-id 5678
                                                :text "hello world!"}]]))
  
  (def a
    (.appendRecord (:xtdb.azure/event-hub-log (:system node))
                   (java.nio.ByteBuffer/wrap (.getBytes "Hello3"))))

  
  (.readRecords (:xtdb.azure/event-hub-log (:system node)) 0 100)
  
  (xt/q node '(from :posts [text]))
  (node)
  (.putObject (:xtdb.azure/blob-object-store (:system node)) "bar" (java.nio.ByteBuffer/wrap (.getBytes "helloworld")))
  (ir/halt)
  (ir/reset)
  )

(System/getenv "AZURE_TENANT_ID")
(System/getenv "AZURE_CLIENT_ID")
(System/getenv "AZURE_CLIENT_SECRET")
