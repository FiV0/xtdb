(ns xtdb.buffer-pool-test
  (:require [clojure.test :as t :refer [deftest]]
            [juxt.clojars-mirrors.integrant.core :as ig]
            [xtdb.object-store-test :as ost]
            [xtdb.util :as util])
  (:import (xtdb.buffer_pool BufferPool)))

(defn- with-buffer-pool [opts f]
  (let [sys (-> (merge opts {:xtdb/allocator {}
                             :xtdb.buffer-pool/buffer-pool {}
                             :xtdb.object-store/memory-object-store {}})
                ig/prep
                ig/init)]
    (try
      (f (:xtdb.buffer-pool/buffer-pool sys) (:xtdb.object-store/memory-object-store sys))
      (finally
        (ig/halt! sys)))))

(defn- random-char []
  (char (+ (int \a) (rand-int 26))))

(defn- random-str [n]
  (apply str (repeatedly n random-char)))

(defn- random-key-value-mapping [n]
  (zipmap (map str (range n))
          (repeatedly n #(random-str 10000))))

(deftest buffer-pool-test
  (with-buffer-pool {}
    (fn [^BufferPool buffer-pool object-store]
      (let [key-value-map (random-key-value-mapping 100)
            the-keys (keys key-value-map)]
        (doseq [[k v] key-value-map]
          (ost/put-object object-store k v))
        (doseq [k (repeatedly 10 #(rand-nth the-keys))]
          (let [b @(.getBuffer buffer-pool k)]
            (t/is (some? b))
            (util/try-close b)))))))

(deftest concurrent-buffer-pool-test
  (with-buffer-pool {}
    (fn [^BufferPool buffer-pool object-store]
      (let [key-value-map (random-key-value-mapping 100)
            the-keys (keys key-value-map)
            s (repeatedly 1000 (fn [] (rand-nth the-keys)))]
        (doseq [[k v] key-value-map]
          (ost/put-object object-store k v))
        (->> (repeatedly 10 #(future
                               (doseq [k s]
                                 (let [b @(.getBuffer buffer-pool k)]
                                   (t/is (some? b))
                                   (util/try-close b)))))
             (mapv deref))))))
