(ns grid
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [sc.api]
            [xtdb.node :as node]
            [xtdb.api :as d]
            [nextjournal.clerk :as clerk])
  (:import (org.apache.arrow.vector.complex FixedSizeListVector)))

(comment
  (def noodle-dir "dev/temporal-noodle")
  (def auctionmark-dir "dev/auctionmark-node")

  (defn ->config [dir]
    {:xtdb.log/local-directory-log {:root-path (io/file dir "log")}
     :xtdb.buffer-pool/buffer-pool {:cache-path (io/file dir "buffers")}
     :xtdb.object-store/file-system-object-store {:root-path (io/file dir "objects")}})

  (def node (node/start-node (->config noodle-dir)))
  (def auction-node (node/start-node (->config auctionmark-dir)))

  (d/q auction-node '[{:find [e] :where [(match :user {:xt/id e})]}])

  (def grid (-> node :system :xtdb.temporal/temporal-manager tmp/get-kd-tree (.static-kd-tree))))

(def grid (.static-kd-tree (sc.api/letsc [102 -1] kd-tree)))
(def auction-grid (.static-kd-tree (sc.api/letsc [421 -1] kd-tree)))

(defn single-dimension-idxs [k idx axis-shift]
  (let [axis-size (dec (bit-shift-left 1 axis-shift))]
    (->> (range (bit-shift-left 1 (* k axis-shift)))
         (reduce (fn [res i]
                   (update res (bit-and axis-size (bit-shift-right i (* idx axis-shift))) (fnil conj []) i))
                 {}))))

(defn two-dimension-idxs [k [idx1 idx2] axis-shift]
  (for [[i idxs1] (single-dimension-idxs k idx1 axis-shift)
        [j idxs2] (single-dimension-idxs k idx2 axis-shift)]
    [[i j] (seq (set/intersection (set idxs1) (set idxs2)))]))

(comment
  (single-dimension-idxs 5 0 1)
  (single-dimension-idxs 5 3 1)
  (two-dimension-idxs 5 [0 3] 1))

(defn single-dimension-bins [^xtdb.temporal.grid.Grid grid k]
  (let [cells (.cells grid)
        axis-shift (.axis-shift grid)]
    (for [i (range k)]
      [i (->> (single-dimension-idxs k i axis-shift)
              (map (fn [[i idxs]] [i (->> (map #(some-> ^FixedSizeListVector (nth cells %) (.getValueCount)) idxs)
                                          (remove nil?)
                                          (reduce +))]))
              (sort-by first))])))

(defn two-dimensions-bins [^xtdb.temporal.grid.Grid grid k]
  (let [cells (.cells grid)
        axis-shift (.axis-shift grid)]
    (for [i (range k)
          j (range (inc i) k)]
      [[i j] (->> (two-dimension-idxs k [i j] axis-shift)
                  (map (fn [[i idxs]] [i (->> (map #(some-> ^FixedSizeListVector (nth cells %) (.getValueCount)) idxs)
                                              (remove nil?)
                                              (reduce +))]))
                  (sort-by first))])))

(defn grid->stats [^xtdb.temporal.grid.Grid grid]
  (let [k (dec (.k grid))
        cells (.cells grid)
        axis-shift (.axis-shift grid)]
    {:single-dimension (single-dimension-bins grid k)
     :two-dimensions (two-dimensions-bins grid k)}))

(def dimension->name (zipmap (range) '("system-time-end-idx" "id-idx" "system-time-start-idx"
                                       "row-id-idx" "app-time-start-idx" "app-time-end-idx")))


(defn display-grid-stats-1-dimension [grid]
  (let [grid-stats (grid->stats grid)
        single-dimension (:single-dimension grid-stats)]
    (for [[dimension data-points] single-dimension]
      (clerk/vl
       {:$schema "https://vega.github.io/schema/vega-lite/v5.json"
        :title (get dimension->name dimension)
        :data {:values (map (fn [[b p]] (hash-map :bucket b :points p)) data-points)}
        :description "A simple bar chart with embedded data."
        :encoding {:x {:axis {:labelAngle 0} :field "bucket" :type "nominal"}
                   :y {:field "points" :type "quantitative"}}
        :mark "bar"}))))

(defn display-grid-stats-2-dimensions [grid]
  (let [grid-stats (grid->stats grid)
        two-dimensions (:two-dimensions grid-stats)]
    (for [[[dim-1 dim-2] data-points] two-dimensions]
      (clerk/vl
       {:$schema "https://vega.github.io/schema/vega-lite/v5.json",
        :title "heatmap",
        :data {:values (map (fn [[[a b] p]] (hash-map :a a :b b :points p)) data-points)},
        :mark "rect",
        :encoding {:y {:field "a" :type "nominal" :title (get dimension->name dim-1)},
                   :x {:field "b" :type "ordinal" :title (get dimension->name dim-2)},
                   :color {:aggregate "mean", :field "points"}},
        :config {:axis {:grid true, :tickBand "extent"}}}))))

(display-grid-stats-1-dimension grid)

(display-grid-stats-2-dimensions grid)

(defn number-empty-cells [^xtdb.temporal.grid.Grid grid]
  (let [cells (.cells grid)]
    (->> (map #(some-> % (.getValueCount)) cells)
         (filter nil?)
         count)))

;; total cells noodle example
(count (.cells grid))

;; number of empty cells noodle example
(number-empty-cells grid)


(display-grid-stats-1-dimension auction-grid)

(display-grid-stats-2-dimensions auction-grid)

;; total cells auctionmark
(count (.cells auction-grid))

;; number of empty cells auctionmark
(number-empty-cells auction-grid)
