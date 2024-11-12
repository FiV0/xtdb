(ns xtdb.client-test
  (:require [clojure.test :as t :refer [deftest]]
            [xtdb.api :as xt]
            [xtdb.test-util :as tu])
  (:import [clojure.lang ExceptionInfo]))

(t/use-fixtures :each (tu/with-opts {:http-server {:authn-records [{:user "xtdb" :method :password :address "127.0.0.1"}
                                                                   {:user "ada" :method :trust :address "127.0.0.1"}]}})
  tu/with-mock-clock tu/with-http-client-node)

(deftest authentication
  (t/is (thrown-with-msg? ExceptionInfo #"no authentication record found for user: all"
                          (xt/status tu/*node*)))

  (t/is (= {:latest-completed-tx nil, :latest-submitted-tx nil}
           (xt/status tu/*node* {:auth-opts {:user "xtdb" :password "xtdb"}})))

  (t/is (= {:latest-completed-tx nil, :latest-submitted-tx nil}
           (xt/status tu/*node* {:auth-opts {:user "ada"}}))
        "password is optional")

  (t/is (thrown-with-msg? ExceptionInfo #"no authentication record found for user: all"
                          (xt/q tu/*node* "SELECT 1 AS x")))

  (t/is (= [{:x 1}]
           (xt/q tu/*node* "SELECT 1 AS x" {:auth-opts {:user "xtdb" :password "xtdb"}})))

  (t/is (thrown-with-msg? ExceptionInfo #"no authentication record found for user: all"
                          (xt/submit-tx tu/*node* [[:put-docs :docs {:xt/id 1}]])))

  (t/is (= #xt/tx-key {:tx-id 0, :system-time #time/instant "2020-01-01T00:00:00Z"}
           (xt/submit-tx tu/*node* [[:put-docs :docs {:xt/id 1}]] {:auth-opts {:user "xtdb" :password "xtdb"}}))))
