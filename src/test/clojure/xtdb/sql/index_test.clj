(ns xtdb.sql.index-test
  (:require [clojure.test :as t :refer [deftest]]
            [xtdb.api :as xt]
            [xtdb.test-util :as tu]))

(t/use-fixtures :each tu/with-mock-clock tu/with-node)

(deftest create-index-test
  (xt/submit-tx tu/*node* [[:put-docs :people
                            {:xt/id 1 :name "alan" :email "alan@xtdb.com"}
                            {:xt/id 2 :name "ada" :email "ada@juxt.pro"}]])
  (xt/submit-tx tu/*node* [[:sql "CREATE INDEX email_index ON people (email)"]]))
