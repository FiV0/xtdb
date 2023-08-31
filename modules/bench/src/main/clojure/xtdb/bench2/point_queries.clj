(ns xtdb.bench2.point-queries
  (:require [clojure.java.io :as io]
            [cognitect.transit :as t]
            [xtdb.api :as xt]
            [xtdb.test-util :as tu]
            [xtdb.util :as util]))

(defn read-form [rdr]
  (try
    (t/read rdr)
    (catch Throwable _)))

(defn read-transit-file [file]
  (let [rdr (t/reader (io/input-stream file) :json)]
    (loop [txs (transient [])]
      (if-let [data (read-form rdr)]
        (recur (conj! txs data))
        (persistent! txs)))))

(defn ingest-am-replay [node txs]
  (doseq [[i tx] (map-indexed vector txs)]
    (when (= 0 (mod i 50)) (println i "@" (util/micros->instant  (* (System/currentTimeMillis) 1000))))
    (xt/submit-tx node tx)))

(comment
  ;; NEED to load this first via ./bin/download_dataset.sh
  (def transit-tx-file (io/file (io/resource "data/auctionmark/am01-30s-tx.transit.json")))

  (def am-txs (future (->> (read-transit-file transit-tx-file)
                           (mapcat identity)
                           (partition-all 512)
                           doall)))

  (future-done? am-txs)

  (require 'dev)
  (dev/halt)
  (dev/go)
  (util/delete-dir (.toPath dev/dev-node-dir))
  (ingest-am-replay dev/node @am-txs))

;; ra for the above
(def ra-query
  '[:scan
    {:table item :for-valid-time [:at :now], :for-system-time nil}
    [{i_status (= i_status :open)}
     i_u_id
     i_current_price
     i_initial_price
     {xt/id (= xt/id ?i_id)}
     id]])

(defn read-or-calc-open-ids [node]
  (let [file-name "open-ids.edn"]
    (if (.exists (io/file file-name))
      (doall (read-string (slurp file-name)))
      (let [open-ids (->> (xt/q node '{:find [i]
                                       :where [(match :item {:xt/id i :i_status :open})]})
                          (map :i))]
        (spit file-name (pr-str open-ids))
        open-ids))))

(comment
  (dev/halt)
  (dev/go)
  (def open-ids (read-or-calc-open-ids dev/node))

  (def rand-ids (shuffle open-ids))

  (def q  (fn [open-id]
            (tu/query-ra ra-query {:node dev/node
                                   :params {'?i_id open-id}})))


  (def issue-id "i_2005107")

  (time
   (tu/with-allocator
     (fn []
       (q issue-id))))


  (time
   (tu/with-allocator
     (fn []
       (q (first rand-ids)))))

  (time
   (tu/with-allocator
     (fn []
       (q (first (drop 4 the-shuffle))))))


  ;; ra query
  (time
   (tu/with-allocator
     #(doseq [id (take 1000 (shuffle open-ids))]
        (q id))))

  (def the-shuffle (take 5 (shuffle rand-seq)))

  (first (drop 4 the-shuffle))
  ;; => "i_2005806"

  (time
   (tu/with-allocator
     #(doseq [id the-shuffle]
        (q id))))





  )
