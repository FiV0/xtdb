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
                         :metrics? true}
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
  (future (run-bench {:type :auctionmark
                      :opts {:duration "PT3H"
                             :load-phase false
                             :scale-factor 0.1
                             :threads 8
                             :sync true}
                      :node-dir node-dir}))

  (def memory-cache (-> bxt/bench-node :system :xtdb/buffer-pool :memory-cache))

  (defn path-slice->map [path-slice]
    {:path (str (.getPath path-slice))
     :offset (.getOffset path-slice)
     :length (.getLength path-slice)})

  (defn entry->k+ref-count [entry]
    [(path-slice->map  (key entry)) (-> entry val deref (.getRefCount) (.get))])

  (defn  get-files+ref-count []
    (->> (.getCache memory-cache) (.asMap)
         (map entry->k+ref-count)
         (sort-by (comp :path first))))

  (def snap (get-files+ref-count))

  (require '[xtdb.trie :as trie])

  (let [{meta-files true other-files false} (group-by (comp trie/meta-file? :path first) (get-files+ref-count))
        meta-sizes (map (comp :length first) meta-files)
        data-sizes (map (comp :length first) other-files)
        #_#_parsed-meta-files (map (comp trie/parse-trie-file-path :path first) meta-files)
        #_#_parsed-data-files (map (comp trie/parse-trie-file-path :path first) other-files)]

    {:meta-file-count (count meta-files)
     :meta-mb-total (quot (reduce + meta-sizes) (* 1000 1000))
     :meta-average-mb (if (empty? meta-sizes) 0 (quot (reduce + meta-sizes) (* (count meta-sizes) 1000 1000)))

     :data-file-count (count other-files)
     :data-mb-total (quot (reduce + data-sizes) (* 1000 1000))
     :data-average-mb (if (empty? data-sizes) 0 (quot (reduce + data-sizes) (* (count meta-sizes) 1000 1000)))}
    #_parsed-meta-files)



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
