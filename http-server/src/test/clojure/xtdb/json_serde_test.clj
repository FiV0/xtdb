(ns xtdb.json-serde-test
  (:require [clojure.test :as t :refer [deftest]]
            [xtdb.api :as xt]
            [xtdb.error :as err]
            xtdb.serde)
  (:import (java.time Instant)
           (java.util List)
           (xtdb JsonSerde)
           (xtdb.api TransactionKey)
           (xtdb.api.query Query$From Query$ParamRelation Query$OrderBy Expr$SetExpr Query$Join Query$LeftJoin
                           Query$Without Query$WithCols Query$Aggregate Basis Binding Expr Expr Query
                           Query$OrderDirection Query$OrderNulls Query$Pipeline Query$QueryTail Query$Unify
                           Query$Return Expr$Call Query$Where Query$With Query$UnnestVar Query$UnnestCol
                           QueryOptions QueryRequest TemporalFilter)
           (xtdb.api.tx TxOp TxOptions TxRequest)))

(defn- encode [v]
  (JsonSerde/encode v))

(defn- roundtrip-json-ld [v]
  (-> v JsonSerde/encode JsonSerde/decode))

(deftest test-json-ld-roundtripping
  (let [v {"keyword" :foo/bar
           "set-key" #{:foo :baz}
           "instant" #time/instant "2023-12-06T09:31:27.570827956Z"
           "date" #time/date "2020-01-01"
           "date-time" #time/date-time "2020-01-01T12:34:56.789"
           "zoned-date-time" #time/zoned-date-time "2020-01-01T12:34:56.789Z"
           "time-zone" #time/zone "America/Los_Angeles"
           "duration" #time/duration "PT3H1M35.23S"}]
    (t/is (= v
             (roundtrip-json-ld v))
          "testing json ld values"))

  (let [ex (err/illegal-arg :divison-by-zero {:foo "bar"})
        roundtripped-ex (roundtrip-json-ld ex)]

    (t/testing "testing exception encoding/decoding"
      (t/is (= (ex-message ex)
               (ex-message roundtripped-ex)))

      (t/is (= (ex-data ex)
               (ex-data roundtripped-ex))))))


(defn roundtrip-tx-op [v]
  (-> v (JsonSerde/encode TxOp) (JsonSerde/decode TxOp)))

(defn decode-tx-op [^String s]
  (JsonSerde/decode s TxOp))

(deftest deserialize-tx-op-test
  (t/testing "put"
    (let [v #xt.tx/put {:table-name :docs,
                        :doc {"foo" :bar, "xt/id" "my-id"},
                        :valid-from nil,
                        :valid-to nil}]

      (t/is (= v (roundtrip-tx-op v))))

    (let [v #xt.tx/put {:table-name :docs,
                        :doc {"foo" :bar, "xt/id" "my-id"},
                        :valid-from #time/instant "2020-01-01T00:00:00Z",
                        :valid-to #time/instant "2021-01-01T00:00:00Z"}]

      (t/is (= v (roundtrip-tx-op v))))

    (t/is (thrown-with-msg? xtdb.IllegalArgumentException #"Error decoding JSON!"
                            (-> {"put" "docs"
                                 "doc" "blob"
                                 "validFrom" #inst "2020"
                                 "validTo" #inst "2021"}
                                encode
                                decode-tx-op))))

  (t/testing "delete"
    (let [v #xt.tx/delete {:table-name :docs,
                           :xt/id "my-id",
                           :valid-from nil,
                           :valid-to nil}]
      (t/is (= v (roundtrip-tx-op v))))

    (let [v #xt.tx/delete {:table-name :docs,
                           :xt/id :keyword-id,
                           :valid-from nil,
                           :valid-to nil}]

      (t/is (= v (roundtrip-tx-op v))))

    (let [v #xt.tx/delete {:table-name :docs,
                           :xt/id "my-id",
                           :valid-from #time/instant "2020-01-01T00:00:00Z",
                           :valid-to #time/instant "2021-01-01T00:00:00Z"}]
      (t/is (= v (roundtrip-tx-op v))))


    (t/is (thrown-with-msg? xtdb.IllegalArgumentException #"Error decoding JSON!"
                            (-> {"delete" "docs"
                                 "id" "my-id"
                                 "valid_from" ["not-a-date"]}
                                encode
                                decode-tx-op))))
  (t/testing "erase"
    (let [v #xt.tx/erase {:table-name :docs,
                          :xt/id "my-id"}]
      (t/is (= v (roundtrip-tx-op v))))

    (let [v #xt.tx/erase {:table-name :docs,
                          :xt/id :keyword-id}]
      (t/is (= v (roundtrip-tx-op v))))

    (t/is (thrown-with-msg? xtdb.IllegalArgumentException #"Error decoding JSON!"
                            (-> {"erase" "docs"
                                 "xt/id" "my-id"}
                                encode
                                decode-tx-op))))

  (t/testing "call"
    (let [v #xt.tx/call {:fn-id :my-fn
                         :args ["args"]}]
      (t/is (= v (roundtrip-tx-op v))))

    (let [v #xt.tx/call {:fn-id "my-fn"
                         :args ["args"]}]
      (t/is (= v (roundtrip-tx-op v))))

    (t/is (thrown-with-msg? xtdb.IllegalArgumentException #"Error decoding JSON!"
                            (-> {"call" "my-fn"
                                 "args" {"not" "a-list"}}
                                encode
                                decode-tx-op))))

  (t/testing "sql"
    (let [v #xt.tx/sql {:sql "INSERT INTO docs (xt$id, foo) VALUES (1, \"bar\")"}]
      (t/is (= v (roundtrip-tx-op v))))

    (let [v #xt.tx/sql {:sql "INSERT INTO docs (xt$id, foo) VALUES (?, ?)", :arg-rows [[1 "bar"] [2 "toto"]]}]
      (t/is (= v (roundtrip-tx-op v))))

    (t/is (thrown-with-msg? xtdb.IllegalArgumentException #"Error decoding JSON!"
                            (-> {"sql" "INSERT INTO docs (xt$id, foo) VALUES (?, ?)"
                                 "arg_rows" [1 "bar"]}
                                encode
                                decode-tx-op))))

  ;; TODO keyword args handling when
  (t/testing "xtdml"
    (let [v (xt/insert-into :foo '(from :bar [xt/id]))]
      (t/is (= v (roundtrip-tx-op v))))

    (let [v (-> (xt/insert-into :foo '(from :bar [{:xt/id $id}]))
                (xt/with-op-arg-rows [{"id" 1}]))]
      (t/is (= v (roundtrip-tx-op v))))

    (let [v (-> (xt/update-table :users '{:bind [{:xt/id $uid} version], :set {:version (inc version)}})
                (xt/with-op-args {"uid" "james"}, {"uid" "dave"}))]
      (t/is (= v (roundtrip-tx-op v))))

    (let [v (xt/delete-from :users '[{:xt/id $uid} version] '(from :users [version]))]
      (t/is (= v (roundtrip-tx-op v))))

    (let [v (xt/erase-from :users '[{:xt/id $uid} version] '(from :users [version]))]
      (t/is (= v (roundtrip-tx-op v))))

    (let [v (xt/assert-exists '(from :users [xt/id]))]
      (t/is (= v (roundtrip-tx-op v))))

    (let [v (xt/assert-not-exists '(from :users [xt/id]))]
      (t/is (= v (roundtrip-tx-op v))))))


(defn- roundtrip-tx [v]
  (-> v (JsonSerde/encode TxRequest) (JsonSerde/decode TxRequest)))

(defn- decode-tx [^String s]
  (JsonSerde/decode s TxRequest))

(deftest deserialize-tx-test
  (let [v (TxRequest. [#xt.tx/put {:table-name :docs,
                                   :doc {"xt/id" "my-id"},
                                   :valid-from nil,
                                   :valid-to nil}],
                      (TxOptions.))]
    (t/is (= v (roundtrip-tx v))))

  (let [v (TxRequest. [#xt.tx/put {:table-name :docs,
                                   :doc {"xt/id" "my-id"},
                                   :valid-from nil,
                                   :valid-to nil}],
                      (TxOptions. #time/instant "2020-01-01T12:34:56.789Z"
                                  #time/zone "America/Los_Angeles"
                                  false))]

    (t/is (= v (roundtrip-tx v))
          "transaction options"))

  (t/is (thrown-with-msg? xtdb.IllegalArgumentException #"Error decoding JSON!"
                          (-> {"tx_ops" {"put" "docs"
                                         "doc" {"xt/id" "my-id"}}}
                              encode
                              decode-tx))
        "put not wrapped throws"))

(defn- roundtrip-expr [v]
  (-> v (JsonSerde/encode Expr) (JsonSerde/decode Expr)))

(deftest deserialize-expr-test
  (t/is (= Expr/NULL (roundtrip-expr Expr/NULL))
        "null")
  (t/is (= (Expr/val "foo") (roundtrip-expr (Expr/val "foo")))
        "string")
  (t/is (= (Expr/lVar "foo") (roundtrip-expr (Expr/lVar "foo") ))
        "logic-var")
  (t/is (= (Expr/param "foo") (roundtrip-expr (Expr/param "foo")))
        "param")
  (t/is (= Expr/FALSE (roundtrip-expr Expr/FALSE)))
  (t/is (= (Expr/val (long 1)) (roundtrip-expr (Expr/val (long 1)))))
  (t/is (= (Expr/val (double 1.2)) (roundtrip-expr (Expr/val (double 1.2)))))

  (let [v (Expr/exists (Query$From. "docs" [(Binding. "xt/id" (Expr/lVar "xt/id"))])
                       [])]
    (t/is (= v (roundtrip-expr v))
          "exists"))

  (let [v (Expr/q (Query$From. "docs" [(Binding. "xt/id" (Expr/lVar "xt/id"))])
                  [])]

    (t/is (= v (roundtrip-expr v))
          "subquery"))

  (let [v (Expr/pull (Query$From. "docs" [(Binding. "xt/id" (Expr/lVar "xt/id"))])
                     [])]

    (t/is (= v (roundtrip-expr v))
          "pull"))

  (let [v (Expr/pullMany (Query$From. "docs" [(Binding. "xt/id" (Expr/lVar "xt/id"))])
                         [])]
    (t/is (= v (roundtrip-expr v))
          "pull-many"))

  (let [v (Expr$Call. "+" [(Expr/val "foo") (Expr/val "bar")])]
    (t/is (= v (roundtrip-expr v))
          "call"))

  (let [v (Expr/list ^List (list (Expr/val 1) (Expr/val 2)))]
    (t/is (= v (roundtrip-expr v))
          "list"))

  (let [v (Expr/map {"foo" (Expr/val 1)})]
    (t/is (= v (roundtrip-expr v))
          "maps"))

  (let [v (Expr$SetExpr. [(Expr/val 1) (Expr/val :foo)])]
    (t/is (= v (roundtrip-expr v))
          "sets")))

(defn- roundtrip-temporal-filter [v]
  (-> v (JsonSerde/encode TemporalFilter) (JsonSerde/decode TemporalFilter)))

(defn- decode-temporal-filter [^String s]
  (JsonSerde/decode s TemporalFilter))

(deftest deserialize-temporal-filter-test
  (t/is (= TemporalFilter/ALL_TIME (roundtrip-temporal-filter TemporalFilter/ALL_TIME)) "all-time")

  (t/is (thrown-with-msg? IllegalArgumentException #"Illegal argument: 'xtql/malformed-temporal-filter'"
                          (-> "all_times" encode decode-temporal-filter))
        "all-time (wrong format)")

  (let [v (TemporalFilter/at (Expr/val #time/instant "2020-01-01T00:00:00Z"))]
    (t/is (= v (roundtrip-temporal-filter v)) "at"))

  (let [v (TemporalFilter/from (Expr/val #time/instant "2020-01-01T00:00:00Z"))]
    (t/is (= v (roundtrip-temporal-filter v)) "from"))

  (let [v (TemporalFilter/to (Expr/val #time/instant "2020-01-01T00:00:00Z"))]
    (t/is (= v (roundtrip-temporal-filter v)) "to"))

  (let [v (TemporalFilter/in (Expr/val #time/instant "2020-01-01T00:00:00Z") (Expr/val #time/instant "2021-01-01T00:00:00Z"))]
    (t/is (= v (roundtrip-temporal-filter v)) "in"))

  (t/is (thrown-with-msg? IllegalArgumentException #"Illegal argument: 'xtql/malformed-temporal-filter'"
                          (-> {"in" [#inst "2020"]} encode decode-temporal-filter))
        "in with wrong arguments"))

(defn- roundtrip-query [v]
  (-> v (JsonSerde/encode Query) (JsonSerde/decode Query)))

(defn- decode-query [^String v]
  (JsonSerde/decode v Query))

(try
  (-> {"from" "docs" "bind" "xt/id"} encode decode-query )
  (catch Throwable t
    t))

(deftest deserialize-query-test
  (let [v (-> (Query/from "docs")
              (.bind (Binding. "xt/id" (Expr/lVar "xt/id")))
              (.bind (Binding. "a" (Expr/lVar "b")))
              (.build))]
    (t/is (= v (roundtrip-query v))))

  (let [v (-> (doto (Query/from "docs")
                (.setBindings [(Binding. "xt/id" (Expr/lVar "xt/id"))])
                (.forValidTime (TemporalFilter/at (Expr/val #time/instant "2020-01-01T00:00:00Z")))
                (.forSystemTime TemporalFilter/ALL_TIME))
              (.build))]
    (t/is (= v (roundtrip-query v)) "from with temporal bounds"))

  (t/is (thrown-with-msg? xtdb.IllegalArgumentException #"Error decoding JSON!"
                          (-> {"from" "docs" "bind" "xt/id"} encode decode-query))
        "bind not an array")

  (let [v (Query$Pipeline. (Query$From. "docs" [(Binding. "xt/id" (Expr/lVar "xt/id"))])
                           [(Query/limit 10)])]
    (t/is (= v (roundtrip-query v)) "pipeline"))

  (let [v (Query$Unify. [(Query$From. "docs" [(Binding. "xt/id" (Expr/lVar "xt/id"))])
                         (Query$From. "docs" [(Binding. "xt/id" (Expr/lVar "xt/id"))])])]
    (t/is (= v (roundtrip-query v)) "unify"))

  (t/testing "rel"
    (let [v (Query/relation ^List (list {"foo" (Expr/val :bar)}) ^List (list (Binding. "foo" (Expr/lVar "foo"))))]
      (t/is (= v (roundtrip-query v))))

    (let [v (Query/relation (Expr/param "bar") ^List (list (Binding. "foo" (Expr/lVar "foo"))))]
      (t/is (= v (roundtrip-query v))))))

(defn- roundtrip-query-tail [v]
  (-> v (JsonSerde/encode Query$QueryTail) (JsonSerde/decode Query$QueryTail)))

(defn- decode-query-tail [^String v]
  (JsonSerde/decode v Query$QueryTail))

(deftest deserialize-query-tail-test
  (t/testing "where"
    (let [v (Query$Where. [(Expr$Call. ">=" [(Expr/val 1) (Expr/val 2)])
                           (Expr$Call. "<" [(Expr/val 1) (Expr/val 2)])])]
      (t/is (= v (roundtrip-query-tail v))))

    (t/is (thrown-with-msg? xtdb.IllegalArgumentException #"Error decoding JSON!"
                            (-> {"where" "not-a-list"} encode decode-query-tail))
          "should fail when not a list"))

  (t/testing "limit"
    (t/is (= (Query/limit 100) (roundtrip-query-tail (Query/limit 100))))

    (t/is (thrown-with-msg? xtdb.IllegalArgumentException #"Error decoding JSON!"
                            (-> {"limit" "not-a-limit"} encode decode-query-tail))))
  (t/testing "offset"
    (t/is (= (Query/offset 100)
             (roundtrip-query-tail (Query/offset 100))))

    (t/is (thrown-with-msg? xtdb.IllegalArgumentException #"Error decoding JSON!"
                            (-> {"offset" "not-an-offset"} encode decode-query-tail))))

  (t/testing "orderBy"
    (t/is (= (Query$OrderBy. [(Query/orderSpec (Expr/lVar "someField") nil nil)])
             (roundtrip-query-tail (Query$OrderBy. [(Query/orderSpec (Expr/lVar "someField") nil nil)]))))

    (let [v (Query$OrderBy. [(Query/orderSpec (Expr/lVar "someField") Query$OrderDirection/ASC Query$OrderNulls/FIRST)])]
      (t/is (= v (roundtrip-query-tail v))))

    (t/is (thrown-with-msg? xtdb.IllegalArgumentException #"Error decoding JSON!"
                            (-> {"orderBy" [{"val" {"lvar" "someField"}, "dir" "invalid-direction"}]}
                                encode
                                decode-query-tail) ))

    (t/is (thrown-with-msg? xtdb.IllegalArgumentException #"Error decoding JSON!"
                            (-> {"orderBy" [{"val" {"lvar" "someField"}, "nulls" "invalid-nulls"}]}
                                encode
                                decode-query-tail))))

  (t/testing "return"
    (let [v (Query$Return. [(Binding. "a" (Expr/lVar "a"))
                            (Binding. "b" (Expr/lVar "b"))])]
      (t/is (= v (roundtrip-query-tail v))))

    (let [v (Query$Return. [(Binding. "a" (Expr/lVar "a"))
                            (Binding. "b" (Expr/lVar "c"))])]
      (t/is (= v (roundtrip-query-tail v))))

    (t/is (thrown-with-msg? xtdb.IllegalArgumentException #"Error decoding JSON!"
                            (-> {"return" "a"} encode decode-query-tail))))

  (t/testing "unnest"
    (t/is (= (Query/unnestCol (Binding. "a" (Expr/lVar "b")))
             (roundtrip-query-tail (Query/unnestCol (Binding. "a" (Expr/lVar "b"))))))

    (t/is (thrown-with-msg? xtdb.IllegalArgumentException #"Illegal argument: 'xtql/malformed-binding'"
                            (-> {"unnest" {"a" {"xt:lvar" "b"} "c" {"xt:lvar" "d"}}}
                                encode
                                decode-query-tail))
          "should fail with >1 binding"))

  (t/testing "with"
    (let [v (Query$WithCols. [(Binding. "a" (Expr/lVar "a")) (Binding. "b" (Expr/lVar "b"))])]
      (t/is (= v (roundtrip-query-tail v))))

    (let [v (Query$WithCols. [(Binding. "a" (Expr/lVar "b")) (Binding. "c" (Expr/lVar "d"))])]
      (t/is (= v (roundtrip-query-tail v))))

    (t/is (thrown-with-msg? xtdb.IllegalArgumentException #"Error decoding JSON!"
                            (-> {"with" "a"} encode decode-query-tail))
          "should fail when not a list"))


  (t/testing "without"
    (t/is (= (Query$Without. ["a" "b"]) (roundtrip-query-tail (Query$Without. ["a" "b"]))))

    (t/is (thrown-with-msg? xtdb.IllegalArgumentException #"Error decoding JSON!"
                            (-> {"without" "a"} encode decode-query-tail))
          "should fail when not a list"))


  (t/testing "aggregate"
    (let [v (Query$Aggregate. [(Binding. "bar" (Expr/lVar "bar"))
                               (Binding. "baz" (Expr$Call. "sum" [(Expr/val 1)]))])]
      (t/is (= v (roundtrip-query-tail v))))

    (t/is (thrown-with-msg? xtdb.IllegalArgumentException #"Error decoding JSON!"
                            (-> {"aggregate" "a"} encode decode-query-tail))
          "should fail when not a list")))

(defn- roundtrip-unify [v]
  (-> v (JsonSerde/encode Query$Unify) (JsonSerde/decode Query$Unify)))

(defn- decode-unify [^String v]
  (JsonSerde/decode v Query$Unify))

(deftest deserialize-unify-test
  (let [parsed-q (Query$From. "docs" [(Binding. "xt/id" (Expr/lVar "xt/id"))])
        simply-unify (Query$Unify. [parsed-q])
        complex-unify (Query$Unify. [parsed-q
                                     (Query$Where. [(Expr$Call. ">=" [(Expr/val 1) (Expr/val 2)])])
                                     (Query$UnnestVar. (Binding. "a" (Expr/lVar "b")))
                                     (Query$With. [(Binding. "a" (Expr/lVar "a"))
                                                   (Binding. "b" (Expr/lVar "b"))])
                                     (Query$Join. parsed-q
                                                  [(Binding. "id" (Expr/lVar "id"))]
                                                  [(Binding. "id" (Expr/lVar "id"))])
                                     (Query$LeftJoin. parsed-q
                                                      [(Binding. "id" (Expr/lVar "id"))]
                                                      [(Binding. "id" (Expr/lVar "id"))])
                                     (Query$ParamRelation. (Expr/param "bar") ^List (list (Binding. "foo" (Expr/lVar "foo"))))])]

    (t/is (= simply-unify (roundtrip-unify simply-unify)))

    (t/is (= complex-unify (roundtrip-unify complex-unify))))

  (t/is (thrown-with-msg? xtdb.IllegalArgumentException #"Error decoding JSON!"
                          (-> {"unify" "foo"} encode decode-unify))
        "unify value not an array"))

(defn- roundtrip-query-request [v]
  (-> v (JsonSerde/encode QueryRequest) (JsonSerde/decode QueryRequest)))

(defn- decode-query-request [^String v]
  (JsonSerde/decode v QueryRequest))

(deftest deserialize-query-map-test
  (let [tx-key (TransactionKey. 1 #time/instant "2023-12-06T09:31:27.570827956Z")
        v (QueryRequest. (Query$From. "docs" [(Binding. "xt/id" (Expr/lVar "xt/id"))])
                         (-> (QueryOptions/queryOpts)
                             (.args {"id" :foo})
                             (.basis (Basis. tx-key Instant/EPOCH))
                             (.afterTx tx-key)
                             (.txTimeout #time/duration "PT3H")
                             (.defaultTz #time/zone "America/Los_Angeles")
                             (.defaultAllValidTime true)
                             (.explain true)
                             (.keyFn #xt/key-fn :clojure-kw)
                             (.build)))]
    (t/is (= v (roundtrip-query-request v))))

  (t/is (thrown-with-msg? xtdb.IllegalArgumentException #"Error decoding JSON!"
                          (-> {"explain" true} encode decode-query-request))
        "query map without query"))
