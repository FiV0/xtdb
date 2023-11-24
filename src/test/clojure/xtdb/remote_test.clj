(ns xtdb.remote-test
  (:require [clojure.test :as t :refer [deftest]]
            [cognitect.transit :as transit]
            [juxt.clojars-mirrors.hato.v0v8v2.hato.client :as http]
            [xtdb.api :as xt]
            [xtdb.test-util :as tu :refer [*node*]]
            [xtdb.transit :as xt.transit])
  (:import (java.io ByteArrayInputStream EOFException)))

;; ONLY put stuff here where remote DIFFERS to in-memory

(t/use-fixtures :each tu/with-mock-clock tu/with-http-client-node)

(deftest normalisation-option
  (t/is (thrown-with-msg? IllegalArgumentException
                          #"Illegal argument: "
                          (xt/q *node* '(from :docs [first-name last-name])
                                {:key-fn identity}))
        "remote can not serialize arbitrary fns"))


(defn- http-url [endpoint] (str "http://localhost:" tu/*http-port* "/" endpoint))

(defn- decode-transit* [^String s]
  (let [rdr (transit/reader (ByteArrayInputStream. (.getBytes s)) :json {:handlers xt.transit/tj-read-handlers})]
    (loop [res []]
      (let [value-read (try
                         (transit/read rdr)
                         (catch RuntimeException e
                           (if (instance? EOFException (.getCause e))
                             ::finished
                             (throw e))))]
        (if (= ::finished value-read)
          res
          (recur (conj res value-read)))))))

(defn- decode-transit [^String s] (first (decode-transit* s)))

(deftest request-test
  (xt/submit-tx *node* [[:put :docs {:xt/id 1}]])
  (Thread/sleep 100)
  (t/is (= {:latest-completed-tx
            #xt/tx-key {:tx-id 0, :system-time #time/instant "2020-01-01T00:00:00Z"},
            :latest-submitted-tx
            #xt/tx-key {:tx-id 0, :system-time #time/instant "2020-01-01T00:00:00Z"}}
           (-> (http/request {:accept :transit+json
                              :as :string
                              :request-method :get
                              :url (http-url "status")})
               :body
               decode-transit))))
