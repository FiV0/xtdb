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
            [xtdb.datalog :as xt]
            [xtdb.api :as api])
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

  (require '[xtdb.api :as api])

  ;; uses single-join
  (def query1 "
select
  users.xt$id,
  (
      select count(posts.xt$id)
      from posts
      where posts.user_id = users.xt$id
  ) as post_count
from users
")

  (api/q node [query1])



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





  (def query2 "
SELECT
  p.p_partkey,
  p.p_mfgr,
  ps.ps_supplycost
FROM
  part AS p,
  partsupp AS ps
WHERE
  p.p_partkey = ps.ps_partkey
  AND ps.ps_supplycost = (
    SELECT MIN(ps.ps_supplycost)
    FROM
      partsupp AS ps
    WHERE
      p.p_partkey = ps.ps_partkey
  )")


  (api/q node [query2])

  (def query3 "
SELECT
  o.o_orderpriority,
  COUNT(*) AS order_count
FROM orders AS  o
WHERE EXISTS (
SELECT *
FROM lineitem AS l
WHERE
l.l_orderkey = o.o_orderkey
AND l.l_commitdate < l.l_receiptdate
)
GROUP BY
o.o_orderpriority")


  (api/q node [query3])


  (def query4 "
SELECT SUM(l.l_extendedprice) / 7.0 AS avg_yearly
FROM
  lineitem AS l,
  part AS p
WHERE
  p.p_partkey = l.l_partkey
  AND l.l_quantity < (
    SELECT 0.2 * AVG(l.l_quantity)
    FROM
      lineitem AS l
    WHERE
      l.l_partkey = p.p_partkey
  )")

  (api/q node [query4])

  (api/submit-tx node [[:put :orders {:xt/id 1 :foo :bar :nb-items 10}]
                       [:put :orders {:xt/id 2 :foo :bar}]])


  (api/q node ['{:find [o]
                 :where [(match :orders [{:xt/* o} nb-items])
                         [(<= 10 nb-items)]]}])

  ;; uncleaned
  (clojure.core/fn
    [rel22620 params22621 out_vec24304]
    (clojure.core/let
        [lit50918
         10
         nb_items
         (.polyReader
          (.vectorForName rel22620 "nb-items")
          '[:union #{:absent :i64}])
         out_writer_sym24305
         (xtdb.vector.writer/->writer out_vec24304)
         row-count__50769__auto__
         (.rowCount rel22620)]
      (clojure.core/dotimes
          [idx22619 row-count__50769__auto__]
        (clojure.core/case
            (.read nb_items idx22619)
          0
          (.writeBoolean
           out_writer_sym24305
           (clojure.core/neg? (clojure.core/compare lit50918 nil)))
          1
          (.writeBoolean
           out_writer_sym24305
           (< lit50918 (.readLong nb_items)))))))

  ;;
  (fn [relation out-vec]
    (let [literal 10
          nb_items (.polyReader (.vectorForName relation "nb-items") '[:union #{:absent :i64}])
          out_writer (xtdb.vector.writer/->writer out-vec)
          row-count (.rowCount relation)]
      (dotimes [idx row-count]
        (case (.read nb_items idx)
          0 (.writeBoolean out_writer (neg? (compare literal nil)))
          1 (.writeBoolean out_writer (<= literal (.readLong nb_items)))))))


  ;; uncleaned
  (clojure.core/fn
    [rel22620 params22621 out_vec24304]
    (clojure.core/let
        [lit50820
         10
         nb_items
         (.monoReader (.vectorForName rel22620 "nb-items") ':absent)
         out_writer_sym24305
         (xtdb.vector.writer/->writer out_vec24304)
         row-count__50769__auto__
         (.rowCount rel22620)]
      (clojure.core/dotimes
          [idx22619 row-count__50769__auto__]
        (.writeBoolean
         out_writer_sym24305
         (clojure.core/neg? (clojure.core/compare lit50820 nil))))))

  ;; cleaned
  (eval '(fn [relation params out_vec]
           (let [literal 10
                 nb_items (.monoReader (.vectorForName relation "nb-items") ':absent)
                 out_writer (xtdb.vector.writer/->writer out_vec)
                 row-count (.rowCount relation)]
             (dotimes [idx row-count]
               (.writeBoolean out_writer (neg? (compare literal nil)))))))


  (def expr->fn
    (-> (fn [expr]
          (-> expr
              build-fn
              eval))
        memoize))

  )

(api/q node ['{:find [o]
               :where [(match :orders [{:xt/* o} nb-items])
                       [(<= 10 nb-items)]]}])


{:find [o]
 :where [(match :orders [{:xt/* o} nb-items])
         [(<= 10 nb-items)]]}

(compare 10 nil)


;; before
[:project
 [{xt$id users__108_xt$id} {post_count subquery__25_$column_1$}]
 [:apply
  :single-join
  {users__108_xt$id ?users__108_xt$id}
  [:rename users__108
   [:scan {:table users} [xt$id]]]
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
       [:rename posts__58
        [:scan {:table posts} [xt$id user_id]]]]]]]]]]

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
     [:rename {xt$id x1}
      [:scan {:table users} [xt$id]]]]
    [:rename
     {xt$id x3, user_id x4}
     [:scan {:table posts} [xt$id user_id]]]]]]]


(def expr->fn
  (-> (fn [expr]
        (-> expr
            build-fn
            eval))
      memoize))
