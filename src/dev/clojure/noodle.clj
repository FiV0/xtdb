(ns noodle
  (:require [clojure.java.io :as io]
            [xtdb.node :as node]
            [xtdb.test-util :as tu]
            [xtdb.api :as d]
            [xtdb.util :as util]
            [xtdb.temporal :as tmp]
            [xtdb.temporal.grid :as grid]))

#_(doseq [s '[io node tu d util tmp grid]]
    (ns-unalias *ns* s))

(comment
  (def no-history-dir "dev/temporal-noodle-no-history")
  (def history-dir "dev/temporal-noodle-history")
  ;; (util/delete-dir (.toPath (io/file data-dir)))

  (defn ->config [dir]
    {:xtdb.log/local-directory-log {:root-path (io/file dir "log")}
     :xtdb.buffer-pool/buffer-pool {:cache-path (io/file dir "buffers")}
     :xtdb.object-store/file-system-object-store {:root-path (io/file dir "objects")}})

  (def no-history-n (node/start-node (->config no-history-dir)))
  (def history-n (node/start-node (->config history-dir)))

  (defn caught-up? [n]
    (let [{:keys [latest-completed-tx latest-submitted-tx]} (d/status n)]
      (= latest-completed-tx latest-submitted-tx)))

  (defn catch-up [n]
    (while (not (caught-up? n))
      (Thread/sleep 100)))

  (def integer-count (+ 102400 2000) #_(- (* 1 1e5) 100))
  (def tx-batch-size 64)

  ;; without updates to docs
  (time
   (do
     (doseq [tx (->> (shuffle (range integer-count))
                     (map (fn [i] [:put :ints {:xt/id i :n i}]))
                     (partition-all tx-batch-size))]
       (d/submit-tx n tx))
     (catch-up history-n)))
  ;; "Elapsed time: 26111.239338 msecs" 1e5 inserts
  ;; "Elapsed time: 49860.27167 msecs" (* 2 1e5) inserts
  (do
    (reset! tmp/range-queries 0)
    (reset! grid/scan-counter 0)
    (d/q no-history-n '{:find [n] :where [($ :ints {:xt/id 1 :n n})]})
    [@tmp/range-queries @grid/scan-counter])


  (defn point-query-estimates [n s]
    (for [i s]
      (do
        (reset! tmp/range-queries 0)
        (reset! grid/scan-counter 0)
        (d/q n {:find ['n] :where [(list '$ :ints {:xt/id i :n 'n})]})
        [@tmp/range-queries @grid/scan-counter])))

  (point-query-estimates no-history-n (range 0 10))

  (def updates-per-doc 100)

  (time
   (do
     (doseq [tx (->> (shuffle (range integer-count))
                     (map-indexed (fn [i n] {:xt/id (mod i (long (quot integer-count updates-per-doc))) :n n}))
                     (partition-all tx-batch-size)
                     (mapcat (fn [b]
                               (let [g (group-by :xt/id b)]
                                 (loop [g g
                                        acc []]
                                   (if (empty? g)
                                     acc
                                     (let [vvecs (vals g)
                                           tx (mapv (comp (fn [d] [:put :ints d]) peek) vvecs)]
                                       (recur (into {} (keep (fn [[k v]] (when (seq (pop v)) [k (pop v)]))) g)
                                              (conj acc tx)))))
                                 ))))]
       (d/submit-tx n tx))
     (catch-up n)))
  ;; "Elapsed time: 23715.407992 msecs" 1e5 inserts
  ;; "Elapsed time: 122185.781407 msecs" (* 2 1e5) inserts

  (point-query-estimates history-n (range 0 10))


  (d/status n)

  (System/setProperty "xtdb.current.rowid.cache.enabled" "true")
  (System/setProperty "xtdb.current.rowid.cache.enabled" "false")

  ;; no content selectivity
  (time (q '{:find [n] :where [($ :ints {:n n})]}))

  (time (count (q '{:find [n] :where [($ :ints {:n n} {:for-valid-time :all-time})]})))

  ;; find 1 row by eid
  (time (q '{:find [n] :where [($ :ints {:n n, :xt/id 1})]}))

  ;; find 1 row by eid, find row at past valid point
  (time (q '{:find [n] :where [($ :ints {:n n,
                                         :xt/id 1}
                                  {:for-valid-time [:at #inst "2021-01-01"]})]}))

  ;; find 1 row by eid, explicit now query (in case it matters)
  (time (q '{:find [n] :where [($ :ints {:n n,
                                         :xt/id 1}
                                  {:for-valid-time [:at :now]})]}))

  ;; find 1 row by eid, all time
  (time (count (q '{:find [n] :where [($ :ints {:n n, :xt/id 1} {:for-valid-time :all-time})]})))
  (time (q '{:find [n valid-time] :where [($ :ints {:n n, :xt/id 1 :xt/valid-time valid-time}
                                             {:for-valid-time :all-time})]}))

  (time (q '{:find [valid-time] :where [($ :ints {:n 42036,:xt/id 1 :xt/valid-time valid-time}
                                           {:for-valid-time :all-time})]}))





  ;; selective range query (random distribution across time)
  (time (count (q '{:find [n] :where [($ :ints {:n n}) [(< n 100)]]})))

  (time (count (q '{:find [n] :where [($ :ints {:n n} {:for-valid-time [:at #inst "2022-01-01"]})
                                      [(< n 100)]]})))

  (time (count (q '{:find [n] :where [($ :ints {:n n} {:for-valid-time :all-time})
                                      [(< n 100)]]})))

  (d/status n)

  (def data-dir "tmp/temporal-noodle")
  (do
    #_(.close n)
    (util/delete-dir (.toPath (io/file data-dir)))
    (def n (tu/->local-node {:rows-per-chunk 10
                             :rows-per-block 10
                             :node-dir (.toPath (io/file data-dir))})))

  (def mem-n (node/start-node {:xtdb/live-chunk {:rows-per-chunk 10 :rows-per-block 10}
                               :xtdb.tx-producer/tx-producer {:instant-src (tu/->mock-clock)}
                               :xtdb.log/memory-log {:instant-src (tu/->mock-clock)}}))
  (.close mem-n)


  (doseq [i (range 10)]
    (d/submit-tx mem-n [[:put :ints {:xt/id 0 :n i}]]))

  (d/q mem-n '{:find [n valid-time] :where [($ :ints {:n n :xt/id 0 :xt/valid-time valid-time}
                                               #_{:for-valid-time :all-time}
                                               {:for-valid-time [:in #inst "2020-01-01" #inst "2020-01-06"]})]})

  ;; delete
  (def del {"sys-time-end-idx" 253402300799999999,
            "id-idx" 0,
            "sys-time-start-idx" 1578182400000000,
            "row-id-idx" 8,
            "app-time-start-idx" 1578182400000000,
            "app-time-end-idx" 253402300799999999})
  ;; insert 1
  (def ins1 {"sys-time-end-idx" 1578268800000000,
             "id-idx" 0,
             "sys-time-start-idx" 1578182400000000,
             "row-id-idx" 8,
             "app-time-start-idx" 1578182400000000,
             "app-time-end-idx" 253402300799999999})
  ;; insert 2
  (def ins2 {"sys-time-end-idx" 253402300799999999,
             "id-idx" 0,
             "sys-time-start-idx" 1578268800000000,
             "row-id-idx" 8,
             "app-time-start-idx" 1578182400000000,
             "app-time-end-idx" 1578268800000000})

  ;; returned
  (def ret1 {"sys-time-end-idx" 253402300799999999,
             "id-idx" 0,
             "sys-time-start-idx" 1578182400000000,
             "row-id-idx" 8,
             "app-time-start-idx" 1578182400000000,
             "app-time-end-idx" 253402300799999999})

  (def ret2 {"sys-time-end-idx" 253402300799999999,
             "id-idx" 0,
             "sys-time-start-idx" 1578268800000000,
             "row-id-idx" 8,
             "app-time-start-idx" 1578182400000000,
             "app-time-end-idx" 1578268800000000})

  (require '[lambdaisland.deep-diff2 :as ddiff])

  (= del ret1)
  (ddiff/pretty-print (ddiff/diff del ins1))

  (= {"sys-time-end-idx" 253402300799999999,
      "sys-time-start-idx" 1578182400000000,
      "app-time-start-idx" 1578182400000000,
      "app-time-end-idx" 253402300799999999}
     {"sys-time-end-idx" 253402300799999999,
      "sys-time-start-idx" 1578268800000000,
      "app-time-start-idx" 1578182400000000,
      "app-time-end-idx" 1578268800000000})

  (count (d/q mem-n '{:find [n valid-time] :where [($ :ints {:n n :xt/id 0 :xt/valid-time valid-time}
                                                      {:for-valid-time [:in #inst "2020-01-01" #inst "2020-01-06"]})]}))
  ;; => 24

  (count (d/q mem-n '{:find [n valid-time] :where [($ :ints {:n n :xt/id 0 :xt/valid-time valid-time}
                                                      {:for-valid-time :all-time})]}))
  ;; => 290

  (def data-dir "tmp/temporal-noodle")

  (util/delete-dir (.toPath (io/file data-dir)))

  (def n (tu/->local-node {:node-dir (.toPath (io/file data-dir))}))

  (.close n)

  (time
   (doseq [ints (partition-all 512 (range 100000))]
     (d/submit-tx n (map (fn [i] [:put :ints {:xt/id (mod i 100000) :n i}]) ints))))

  (defn q [d] (d/q @#'n d))

  (time (count (q '{:find [n] :where [($ :ints {:n n, :xt/id 1})]})))


  ;; get content (ie. row-ids)
  ;; temporal filters over row-ids

  ;; min coordinate
  {"id-idx" -9223372036854775808,
   "row-id-idx" -9223372036854775808,

   "sys-time-start-idx" -9223372036854775808,
   "sys-time-end-idx" 1594684800000001,

   "app-time-start-idx" -9223372036854775808,
   "app-time-end-idx" 1683282094753558}

  ;; max coordinate
  {"id-idx" 9223372036854775807,
   "row-id-idx" 9223372036854775807,

   "sys-time-start-idx" 1594684800000000,
   "sys-time-end-idx" 9223372036854775807,

   "app-time-start-idx" 1683282094753557,
   "app-time-end-idx" 9223372036854775807}

  ;; eid puts
  ["2020" "+inf"]                       ;v1
  ["2021" "2023"]                       ;v2

  ;; representation
  ["2020" "-2021"] ;; v1
  ["2021" "2023"]  ;; v2
  ["2023" "+inf"]  ;; v1


  )
