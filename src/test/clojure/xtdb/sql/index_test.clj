(ns xtdb.sql.index-test
  (:require [clojure.test :as t :refer [deftest]]
            [xtdb.api :as xt]
            [xtdb.test-util :as tu]
            [xtdb.util :as util])
  (:import (xtdb IBufferPool)))

(t/use-fixtures :each tu/with-mock-clock tu/with-node)

(deftest create-index-test
  (xt/submit-tx tu/*node* [[:sql "CREATE INDEX email_index ON people (email)"]])

  (xt/submit-tx tu/*node* [[:put-docs :people
                            {:xt/id 1 :name "alan" :email "alan@xtdb.com"}
                            {:xt/id 2 :name "ada" :email "ada@juxt.pro"}]])

  (tu/finish-chunk! tu/*node*)

  (let [^IBufferPool bp (util/component tu/*node* :xtdb/buffer-pool)
        objs (mapv str (.listAllObjects bp))]
    (prn objs)
    (t/is (= 1 (count (filter #(re-matches #"tables/people/data/index-email.+?\.arrow" %) objs))))
    (t/is (= 1 (count (filter #(re-matches #"tables/people/meta/index-email.+?\.arrow" %) objs))))))
