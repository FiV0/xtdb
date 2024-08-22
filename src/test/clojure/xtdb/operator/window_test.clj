(ns xtdb.operator.window-test
  (:require [clojure.test :as t :refer [deftest]]
            [xtdb.api :as xt]
            [xtdb.operator.window :as window]
            [xtdb.test-util :as tu]
            [xtdb.types :as types]
            [xtdb.util :as util]
            [xtdb.vector.reader :as vr]
            [xtdb.node :as xtn]))

(t/use-fixtures :each tu/with-allocator)

(deftest test-window-operator
  (letfn [(run-test [window-spec projection-specs blocks]
            (let [window-name (gensym "window")]
              (-> (tu/query-ra [:window {:windows {window-name window-spec}
                                         :projections (mapv (fn [[col-name projection]]
                                                              {col-name {:window-name window-name
                                                                         :window-agg projection}}) projection-specs) }
                                [::tu/blocks '{a :i64, b :i64} blocks]]
                               {:with-col-types? true})
                  (update :res set))))]

    (t/is (= nil
             (run-test '{:partition-cols [a]
                         :order-specs [[b]]}
                       '{col-name (row-number)}
                       [[{:a 1 :b 20}
                         {:a 1 :b 10}
                         {:a 2 :b 30}
                         {:a 2 :b 40}]
                        [{:a 1 :b 50}
                         {:a 1 :b 60}
                         {:a 2 :b 70}
                         {:a 3 :b 80}
                         {:a 3 :b 90}]]))))
  )
