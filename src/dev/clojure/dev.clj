(ns dev
  (:require
   [clj-async-profiler.core :as clj-async-profiler]
   [clojure.java.browse :as browse]
   [clojure.java.io :as io]
   [integrant.core :as i]
   [integrant.repl :as ir]
   [xtdb.api :as xt]
   [xtdb.datasets.tpch :as tpch]
   [xtdb.ingester :as ingest]
   [xtdb.test-util :as tu]
   [xtdb.util :as util]
   [xtdb.node :as node])
  (:import
   (java.time Duration)))

(def dev-node-dir
  (io/file "dev/dev-node"))

(def node)

(defmethod i/init-key ::xtdb [_ {:keys [node-opts]}]
  (alter-var-root #'node (constantly (node/start-node node-opts)))
  node)

(defmethod i/halt-key! ::xtdb [_ node]
  (util/try-close node)
  (alter-var-root #'node (constantly nil)))

(def standalone-config
  {::xtdb {:node-opts {:xtdb.log/local-directory-log {:root-path (io/file dev-node-dir "log")}
                       :xtdb.buffer-pool/buffer-pool {:cache-path (io/file dev-node-dir "buffers")}
                       :xtdb.object-store/file-system-object-store {:root-path (io/file dev-node-dir "objects")}
                       :xtdb/server {}
                       :xtdb/pgwire {:port 5433}
                       :xtdb.flight-sql/server {:port 52358}}}})

(ir/set-prep! (fn [] standalone-config))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def go ir/go)

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def halt ir/halt)

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(def reset ir/reset)

(def profiler-port 5001)

(defonce profiler-server
  (delay
    (let [port profiler-port
          url (str "http://localhost:" port)]
      (println "Starting serving profiles on" url)
      (clj-async-profiler/serve-files port))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defmacro profile
  "Profiles the given code body with clj-async-profiler, see (browse-profiler) to look at the resulting flamegraph.
  e.g (profile (reduce + (my-function)))
  Options are the same as clj-async-profiler/profile."
  [options? & body]
  `(clj-async-profiler/profile ~options? ~@body))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn start-profiler
  "Start clj-async-profiler see also: (stop-profiler) (browse-profiler)
  Options are the same as clj-async-profiler/start."
  ([] (clj-async-profiler/start))
  ([options] (clj-async-profiler/start options)))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn stop-profiler
  "Stops clj-async-profiler, see (browse-profiler) to go look at the profiles in a nice little UI."
  []
  (let [file (clj-async-profiler/stop)]
    (println "Saved flamegraph to" (str file))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn browse-profiler
  "Opens the clj-async-profiler page in your browser, you can go look at your flamegraphs and start/stop the profiler
  from here."
  []
  @profiler-server
  (browse/browse-url (str "http://localhost:" profiler-port)))

(comment
  #_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
  (def !submit-tpch
    (future
      (let [last-tx (time
                     (tpch/submit-docs! node 0.05))]
        (time (tu/then-await-tx last-tx node (Duration/ofHours 1)))
        (time (tu/finish-chunk! node)))))

  (do
    (newline)
    (doseq [!q [#'tpch/tpch-q1-pricing-summary-report
                #'tpch/tpch-q5-local-supplier-volume
                #'tpch/tpch-q9-product-type-profit-measure]]
      (prn !q)
      (let [db (ingest/snapshot (tu/component node :xtdb/ingester))]
        (time (tu/query-ra @!q db))))))


(comment

  (defn node-dir->config [^File node-dir]
    (let [^Path path (.toPath node-dir)]
      {:xtdb.log/local-directory-log {:root-path (.resolve path "log")}
       :xtdb.tx-producer/tx-producer {}
       :xtdb.buffer-pool/buffer-pool {:cache-path (.resolve path "buffers")}
       :xtdb.object-store/file-system-object-store {:root-path (.resolve path "objects")}}))

  (defn random-point-queries [node q s cnt id]
    (println id " started")
    (loop [i 0]
      (when (< i cnt)
        (xt/q node [q (rand-nth s)])
        (recur (inc i))))
    (println id " finished"))

  (let [i_ids (->> (xt/q node '{:find [i_id] :where [(match :item {:xt/id i_id :i_status :open})]})
                   (map :i_id))
        q '{:find [i_id i_u_id i_initial_price i_current_price]
            :in [i_id]
            :where [(match :item {:xt/id i_id})
                    [i_id :i_status :open]
                    [i_id :i_u_id i_u_id]
                    [i_id :i_initial_price i_initial_price]
                    [i_id :i_current_price i_current_price]]}]
    (->> (range 2)
         (map #(future (random-point-queries node q i_ids 300 %)))
         (map deref)
         doall))


  (with-open [node (node/start-node (node-dir->config node-dir))]
    (let [i_ids (->> (xt/q node '{:find [i_id] :where [(match :item {:xt/id i_id :i_status :open})]})
                     (map :i_id))
          q '{:find [i_id i_u_id i_initial_price i_current_price]
              :in [i_id]
              :where [(match :item {:xt/id i_id})
                      [i_id :i_status :open]
                      [i_id :i_u_id i_u_id]
                      [i_id :i_initial_price i_initial_price]
                      [i_id :i_current_price i_current_price]]}]
      (->> (range 2)
           (map #(future (random-point-queries node q i_ids 300 %)))
           (map deref)
           doall)))


  (->> (repeatedly 1e7 #(rand-int 1e6))
       (partition-all 1e3)
       (map-indexed (fn [block-idx nums]
                      (into {} (map (juxt identity (constantly block-idx))) nums)))
       (apply merge)
       (vals)
       (frequencies)
       (count)
       )



  )
