(ns dev
  (:require [clj-async-profiler.core :as clj-async-profiler]
            [clojure.java.browse :as browse]
            [clojure.java.io :as io]
            [xtdb.datasets.tpch :as tpch]
            [xtdb.ingester :as ingest]
            [xtdb.node :as node]
            [xtdb.test-util :as tu]
            [xtdb.util :as util]
            [integrant.core :as i]
            [integrant.repl :as ir]
            [xtdb.datalog :as xt])
  (:import java.time.Duration))

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
  (go)
  (halt)
  (reset)

  (do
    (xt/submit-tx node [[:put :users {:xt/id 1 :name "Shannon"}]
                        [:put :users {:xt/id 2 :name "Turing"}]])

    (xt/submit-tx node [[:put :posts {:xt/id 1 :user-id 1 :text "Entropy is good!"}]
                        [:put :posts {:xt/id 2 :user-id 1 :text "The Turing test is wrong!"}]
                        [:put :posts {:xt/id 3 :user-id 2 :text "foo"}]])
    )

  (require '[xtdb.sql :as sql])

  ;; uses single-join
  (def query "
select
  users.xt$id,
  (
      select count(posts.xt$id)
      from posts
      where posts.user_id = users.xt$id
  ) as post_count
from users")

  ;; before
  [:project
   [{xt$id users__108_xt$id} {post_count subquery__25_$column_1$}]
   [:apply
    :single-join
    {users__108_xt$id ?users__108_xt$id}
    [:rename users__108 [:scan {:table users} [xt$id]]]
    [:rename
     subquery__25
     [:project
      [{$column_1$ $agg_out__27_34$}]
      [:group-by
       [{$agg_out__27_34$ (count $agg_in__27_34$)}]
       [:map
        [{$agg_in__27_34$ posts__58_xt$id}]
        [:select
         (= posts__58_user_id ?users__108_xt$id)
         [:rename posts__58 [:scan {:table posts} [xt$id user_id]]]]]]]]]]
  ;; after
  [:rename
   {x1 xt$id, x6 post_count}
   [:project
    [x1 x6]
    [:group-by
     [x1 $row_number$ {x6 (count x3)}]
     [:left-outer-join
      [{x1 x4}]
      [:map
       [{$row_number$ (row-number)}]
       [:rename {xt$id x1} [:scan {:table users} [xt$id]]]]
      [:rename
       {xt$id x3, user_id x4}
       [:scan {:table posts} [xt$id user_id]]]]]]]

  (sql/q node [query])



  )
