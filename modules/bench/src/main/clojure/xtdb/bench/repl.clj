(ns xtdb.bench.repl
  "A namespace for running benchmarks at the repl."
  (:require [clojure.java.io :as io]
            [xtdb.bench.xtdb2 :as bxt]
            [xtdb.util :as util]
            [xtdb.datasets.tpch :as tpch]
            [xtdb.test-util :as tu])
  (:import (java.time InstantSource)))

(comment
  ;; benchmark-opts
  ;; ts-devices
  {:size #{:small :med :big}}

  ;; TPC-H
  {:scale-factor 0.05}

  ;; Auctionmark
  {:duration "PT2M"
   :load-phase true
   :scale-factor 0.1
   :threads 1
   :sync true})


(defn run-bench
  "type - the type of benchmark to run
   opts - the benchmark options
   node-dir (optional) - the directory to run the benchmark in"
  [{:keys [type node-dir node-opts opts]}]
  (util/with-tmp-dirs #{node-tmp-dir}
    (bxt/run-benchmark
     {:node-opts (merge {:node-dir (or node-dir node-tmp-dir)
                         :instant-src (InstantSource/system)
                         :metrics? true
                         :log-catchup? false}
                        node-opts)
      :benchmark-type type
      :benchmark-opts opts})))

(comment
  ;; running benchmarks
  (run-bench {:type :ts-devices :opts {:size :small}})

  (def node-dir (.toPath (io/file "dev/tpc-h-1.0")))

  (with-open [node (tu/->local-node {:node-dir node-dir})]
    (tpch/submit-docs! node 1.0)
    (tu/then-await-tx node))

  (time (run-bench {:type :tpch
                    :opts {:scale-factor 1.0 :load-phase false}
                    :node-dir node-dir}))

  (def node-dir (.toPath (io/file "dev/auctionmark")))
  (util/delete-dir node-dir)
  (def node (tu/->local-node {:node-dir node-dir :log-catchup? false}))
  (.close node)

  (future (run-bench {:type :auctionmark
                      :opts {:duration "PT5M"
                             :load-phase false
                             :scale-factor 0.1
                             :threads 8
                             :sync true}
                      :node-dir node-dir}))

  (do
    (def memory-cache (-> bxt/bench-node :system :xtdb/buffer-pool :memory-cache))

    (defn path-slice->map [path-slice]
      {:path (.getPath path-slice)
       :offset (.getOffset path-slice)
       :length (.getLength path-slice)})

    (defn entry->k+ref-count [entry]
      [(path-slice->map  (key entry)) (-> entry val deref (.getRefCount) (.get))])

    (defn  get-files+ref-count []
      (->> (.getCache memory-cache) (.asMap)
           (map entry->k+ref-count)
           (sort-by (comp :path first)))))

  (def snap (get-files+ref-count))

  (require '[xtdb.trie :as trie]
           '[clojure.set :as set])

  (defn snap-overlap [snap1 snap2]
    (let [snap1-paths (map first snap1)
          snap2-paths (map first snap2)
          {meta1-files true data1-files false} (group-by (comp trie/meta-file? :path) snap1-paths)
          {meta2-files true data2-files false} (group-by (comp trie/meta-file? :path) snap2-paths)]
      {:meta-new (count (set/difference (set meta2-files) (set meta1-files)))
       :data-new (count (set/difference (set data2-files) (set data1-files)))
       :meta-old (count (set/intersection (set meta1-files) (set meta2-files)))
       :data-old (count (set/intersection (set data1-files) (set data2-files)))}))

  (defn snap-overlap-seq [size]
    (loop [old (get-files+ref-count) res [] n (dec size)]
      (Thread/sleep 500)
      (let [new (get-files+ref-count)
            res (conj res (snap-overlap old new))]
        (if (pos? n)
          (recur new res (dec n))
          res))))

  (snap-overlap-seq 5)

  (count (.getUniqueKeys memory-cache))

  (def snap1 (get-files+ref-count))
  (def snap2 (get-files+ref-count))

  (snap-overlap snap1 snap2)



  (let [snap (get-files+ref-count)
        paths (map first snap)
        {meta-files true other-files false} (group-by (comp trie/meta-file? :path) paths)
        meta-sizes (map (comp :length) meta-files)
        data-sizes (map (comp :length) other-files)
        parsed-meta-files (map (comp trie/parse-trie-file-path :path) meta-files)
        parsed-data-files (map (comp trie/parse-trie-file-path :path first) other-files)
        file->length (into {} (map (fn [{:keys [path length]}] [path length])) paths)
        file->ref-count (into {} (map (fn [[{:keys [path]} ref-count]] [path ref-count])) snap)]

    {:levels-count (->> parsed-meta-files
                        (map (comp :level))
                        (frequencies))
     :levels-size-kb (->> parsed-meta-files
                          (group-by :level)
                          (map (fn [[k v]] [k (quot (->> (map (comp file->length :file-path) v) (reduce +))
                                                    (* (count v) 1000))]))
                          (into {}))
     #_#_:level-2 (->> (get (->> parsed-meta-files (group-by :level)) 2)
                       (map (fn [v]  ((comp #(quot % (* 1000 1000)) file->length :file-path) v)))

                       )
     :meta-pinned-vs-unpinned (->> meta-files
                                   (map #(if (pos? (file->ref-count (:path %))) :pinned :unpinned))
                                   frequencies)

     :meta-file-count (count meta-files)
     :meta-total-mb (quot (reduce + meta-sizes) (* 1000 1000))
     :meta-average-mb (if (empty? meta-sizes) 0 (quot (reduce + meta-sizes) (* (count meta-sizes) 1000 1000)))

     :data-file-count (count other-files)
     :data-total-mb (quot (reduce + data-sizes) (* 1000 1000))
     :data-average-mb (if (empty? data-sizes) 0 (quot (reduce + data-sizes) (* (count meta-sizes) 1000 1000)))

     :data-pinned-vs-unpinned (->> other-files
                                   (map #(if (pos? (file->ref-count (:path %))) :pinned :unpinned))
                                   frequencies)

     })



  ;; readings
  (def node-dir (.toPath (io/file "dev/readings")))
  (util/delete-dir node-dir)
  (run-bench {:type :readings
              :opts {:load-phase true
                     ;; ~2 years
                     :size (* 2 (+ (* 366 24 12) 1000))
                     :devices 10}
              :node-dir node-dir})

  (run-bench {:type :overwritten-readings
              :opts {:load-phase true
                     ;; ~1 years
                     :size (* 1 (+ (* 366 24 12) 1000))
                     :devices 1}
              :node-dir node-dir})


  ;; test benchmark
  (run-bench {:type :test-bm
              :opts {:duration "PT1H"}}))
