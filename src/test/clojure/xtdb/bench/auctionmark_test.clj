(ns xtdb.bench.auctionmark-test
  (:require [clojure.test :as t]
            [xtdb.bench :as b]
            [xtdb.bench.auctionmark :as am]
            [xtdb.bench.xtdb2 :as bxt2]
            [xtdb.api :as xt]
            [xtdb.test-util :as tu :refer [*node*]])
  (:import (java.time Clock)
           (java.util Random)
           (java.util.concurrent ConcurrentHashMap)))

(t/use-fixtures :each tu/with-node)

(defn- ->worker [node]
  (let [clock (Clock/systemUTC)
        domain-state (ConcurrentHashMap.)
        custom-state (ConcurrentHashMap.)
        root-random (Random. 112)
        worker (b/->Worker node root-random domain-state custom-state clock (random-uuid) (System/getProperty "user.name"))]
    worker))

(t/deftest generate-user-test
  (let [worker (->worker *node*)]
    (bxt2/generate worker :user am/generate-user 1)

    (t/is (= {:count-id 1} (first (xt/q *node* '(-> (from :user [])
                                                    (aggregate {:count-id (row-count)}))))))
    (t/is (= "u_0" (b/sample-flat worker am/user-id)))))

(t/deftest generate-categories-test
  (let [worker (->worker *node*)]
    (am/load-categories-tsv worker)
    (bxt2/generate worker :category am/generate-category 1)

    (t/is (= {:count-id 1} (first (xt/q *node* '(-> (from :category [])
                                                    (aggregate {:count-id (row-count)}))))))
    (t/is (= "c_0" (b/sample-flat worker am/category-id)))))

(t/deftest generate-region-test
  (let [worker (->worker *node*)]
    (bxt2/generate worker :region am/generate-region 1)

    (t/is (= {:count-id 1} (first (xt/q *node* '(-> (from :region [])
                                                    (aggregate {:count-id (row-count)}))))))
    (t/is (= "r_0" (b/sample-flat worker am/region-id)))))

(t/deftest generate-global-attribute-group-test
  (let [worker (->worker *node*)]
    (am/load-categories-tsv worker)
    (bxt2/generate worker :category am/generate-category 1)
    (bxt2/generate worker :gag am/generate-global-attribute-group 1)

    (t/is (= {:count-id 1} (first (xt/q *node* '(-> (from :gag [])
                                                    (aggregate {:count-id (row-count)}))))))
    (t/is (= "gag_0" (b/sample-flat worker am/gag-id)))))

(t/deftest generate-global-attribute-value-test
  (let [worker (->worker *node*)]
    (am/load-categories-tsv worker)
    (bxt2/generate worker :category am/generate-category 1)
    (bxt2/generate worker :gag am/generate-global-attribute-group 1)
    (bxt2/generate worker :gav am/generate-global-attribute-value 1)

    (t/is (= {:count-id 1} (first (xt/q *node* '(-> (from :gav [])
                                                    (aggregate {:count-id (row-count)}))))))
    (t/is (= "gav_0" (b/sample-flat worker am/gav-id)))))

(t/deftest generate-user-attributes-test
  (let [worker (->worker *node*)]
    (bxt2/generate worker :user am/generate-user 1)
    (bxt2/generate worker :user-attribute am/generate-user-attributes 1)
    (t/is (= {:count-id 1} (first (xt/q *node* '(-> (from :user-attribute [])
                                                    (aggregate {:count-id (row-count)}))))))
    (t/is (= "ua_0" (b/sample-flat worker am/user-attribute-id)))))

(t/deftest generate-item-test
  (with-redefs [am/sample-status (constantly :open)]
    (let [worker (->worker *node*)]
      (bxt2/generate worker :user am/generate-user 1)
      (am/load-categories-tsv worker)
      (bxt2/generate worker :category am/generate-category 1)
      (bxt2/generate worker :item am/generate-item 1)

      (t/is (= {:count-id 1} (first (xt/q *node* '(-> (from :item [])
                                                      (aggregate {:count-id (row-count)}))))))
      (t/is (= "i_0" (:i_id (am/random-item worker :status :open)))))))

(t/deftest proc-get-item-test
  (with-redefs [am/sample-status (constantly :open)]
    (let [worker (->worker *node*)]
      (bxt2/generate worker :user am/generate-user 1)
      (am/load-categories-tsv worker)
      (bxt2/generate worker :category am/generate-category 1)
      (bxt2/generate worker :item am/generate-item 1)
      ;; to wait for indexing
      (Thread/sleep 10)
      (t/is (= "i_0" (-> (am/proc-get-item worker) first :i_id))))))

(t/deftest proc-new-user-test
  (with-redefs [am/sample-status (constantly :open)]
    (let [worker (->worker *node*)]
      (am/load-categories-tsv worker)
      (bxt2/generate worker :category am/generate-category 1)
      (bxt2/generate worker :item am/generate-item 1)
      (am/proc-new-user worker)

      (t/is (= {:count-id 1} (first (xt/q *node* '(-> (from :user [])
                                                      (aggregate {:count-id (row-count)}))))))
      (t/is (= "u_0" (b/sample-flat worker am/user-id))))))

(t/deftest proc-new-bid-test
  (with-redefs [am/sample-status (constantly :open)]
    (let [worker (->worker *node*)]
      (t/testing "new bid"
        (bxt2/install-tx-fns worker {:new-bid am/tx-fn-new-bid})
        (bxt2/generate worker :user am/generate-user 1)
        (am/load-categories-tsv worker)
        (bxt2/generate worker :category am/generate-category 1)
        (bxt2/generate worker :item am/generate-item 1)

        (am/proc-new-bid worker)

        ;; item has a new bid
        ;; (t/is (= nil (am/generate-new-bid-params worker)))
        (t/is (= {:i_num_bids 1}
                 (first (xt/q *node* '(from :item [i_num_bids])
                              {:key-fn :snake-case-keyword}))))
        ;; there exists a bid
        (t/is (= {:ib_i_id "i_0", :ib_id "ib_0"}
                 (first (xt/q *node* '(from :item-bid [ib_id ib_i_id])
                              {:key-fn :snake-case-keyword}))))
        ;; new max bid
        (t/is (= {:imb "ib_0-i_0", :imb_i_id "i_0"}
                 (first (xt/q *node*
                              '(from :item-max-bid [{:xt/id imb}, imb_i_id])
                              {:key-fn :snake-case-keyword})))))

      (t/testing "new bid but does not exceed max"
        (with-redefs [am/random-price (constantly Double/MIN_VALUE)]
          (bxt2/generate worker :user am/generate-user 1)
          (am/proc-new-bid worker)

          ;; new bid
          (t/is (= 2 (-> (xt/q *node* '(from :item [i_num_bids])
                               {:key-fn :snake-case-keyword})
                         first :i_num_bids)))
          ;; winning bid remains the same
          (t/is (= {:imb "ib_0-i_0", :imb_i_id "i_0"}
                   (first (xt/q *node* '(from :item-max-bid [{:xt/id imb} imb_i_id])
                                {:key-fn :snake-case-keyword})))))))))

(t/deftest proc-new-item-test
  (with-redefs [am/sample-status (constantly :open)]
    (let [worker (->worker *node*)]
      (t/testing "new item"
        (bxt2/install-tx-fns worker {:new-bid am/tx-fn-new-bid})
        (bxt2/generate worker :user am/generate-user 1)
        (am/load-categories-tsv worker)
        (bxt2/generate worker :category am/generate-category 10)
        (bxt2/generate worker :gag am/generate-global-attribute-group 10)
        (bxt2/generate worker :gav am/generate-global-attribute-value 100)
        (am/proc-new-item worker)

        ;; new item
        (let [{:keys [i_id i_u_id]} (first (xt/q *node* '(from :item [i_id i_u_id])
                                                 {:key-fn :snake-case-keyword}))]
          (t/is (= "i_0" i_id))
          (t/is (= "u_0" i_u_id)))
        (t/is (< (- (:u_balance (first (xt/q *node* '(from :user [u_balance])
                                             {:key-fn :snake-case-keyword})))
                    (double -1.0))
                 0.0001))))))
