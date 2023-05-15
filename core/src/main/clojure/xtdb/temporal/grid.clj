(ns xtdb.temporal.grid
  (:require [clojure.data.json :as json]
            [xtdb.util :as util]
            [xtdb.temporal.kd-tree :as kd]
            [xtdb.temporal.histogram :as hist]
            [clojure.set :as set])
  (:import [xtdb.temporal.kd_tree IKdTreePointAccess KdTreeVectorPointAccess]
           [xtdb.temporal.histogram IHistogram IMultiDimensionalHistogram]
           xtdb.BitUtil
           [org.apache.arrow.memory ArrowBuf BufferAllocator]
           org.apache.arrow.vector.complex.FixedSizeListVector
           [org.apache.arrow.vector VectorLoader VectorSchemaRoot]
           org.apache.arrow.vector.types.pojo.Schema
           org.apache.arrow.vector.ipc.ArrowFileWriter
           java.util.concurrent.atomic.AtomicInteger
           [java.util ArrayList Arrays List]
           [java.util.function Consumer Function IntToLongFunction LongConsumer LongFunction LongPredicate LongUnaryOperator UnaryOperator]
           [java.util.stream LongStream Stream]
           [java.io BufferedInputStream BufferedOutputStream Closeable DataInputStream DataOutputStream]
           [java.nio.channels Channels FileChannel]
           java.nio.file.Path))

;; "Learning Multi-dimensional Indexes"
;; https://arxiv.org/pdf/1912.01668.pdf

(set! *unchecked-math* :warn-on-boxed)

(defn- cartesian-product-idxs ^java.util.stream.Stream [counts]
  (let [total-count (reduce * counts)
        end-idxs (int-array (map dec counts))
        len (alength end-idxs)]
    (.limit (Stream/iterate (int-array len)
                            (reify UnaryOperator
                              (apply [_ as]
                                (let [^ints as as]
                                  (loop [n 0]
                                    (let [x (aget as n)]
                                      (if (= x (aget end-idxs n))
                                        (when (not= n len)
                                          (aset as n 0)
                                          (recur (inc n)))
                                        (aset as n (inc x)))))
                                  as))))
            total-count)))

(defn- three-way-partition ^longs [^IKdTreePointAccess access ^long low ^long hi ^long axis]
  (let [pivot (.getCoordinate access (quot (+ low hi) 2) axis)]
    (loop [i (int low)
           j (int low)
           k (inc (int hi))]
      (if (< j k)
        (let [diff (Long/compare (.getCoordinate access j axis) pivot)]
          (cond
            (neg? diff)
            (do (.swapPoint access i j)
                (recur (inc i) (inc j) k))

            (pos? diff)
            (let [k (dec k)]
              (.swapPoint access j k)
              (recur i j k))

            :else
            (recur i (inc j) k)))
        (doto (long-array 2)
          (aset 0 i)
          (aset 1 (dec k)))))))

(defn- quick-sort [^IKdTreePointAccess access ^long low ^long hi ^long axis]
  (when (< low hi)
    (let [^longs left-right (three-way-partition access low hi axis)
          left (dec (aget left-right 0))
          right (inc (aget left-right 1))]
      (if (< (- left low) (- hi right))
        (do (quick-sort access low left axis)
            (recur access right hi axis))
        (do (quick-sort access right hi axis)
            (recur access low left axis))))))

(defn- binary-search-leftmost ^long [^IntToLongFunction access-fn ^long n ^long idx ^long x]
  (loop [l 0
         r n
         m (max (min idx (dec n)) 0)]
    (if (< l r)
      (if (< (.applyAsLong access-fn m) x)
        (let [l (inc m)]
          (recur l r (BitUtil/unsignedBitShiftRight (+ l r) 1)))
        (let [r m]
          (recur l r (BitUtil/unsignedBitShiftRight (+ l r) 1))))
      l)))

(defn- binary-search-rightmost ^long [^IntToLongFunction access-fn ^long n ^long idx ^long x]
  (loop [l 0
         r n
         m (max (min idx (dec n)) 0)]
    (if (< l r)
      (if (> (.applyAsLong access-fn m) x)
        (let [r m]
          (recur l r (BitUtil/unsignedBitShiftRight (+ l r) 1)))
        (let [l (inc m)]
          (recur l r (BitUtil/unsignedBitShiftRight (+ l r) 1))))
      (dec r))))

;; NOTE: slopes and linear scan cannot beat binary search
;; currently. Remove?

(def ^:private ^:const linear-scan-limit 16)

(defn- linear-search-leftmost ^long [^IntToLongFunction access-fn ^long n ^long idx ^long x]
  (let [m (max (min idx (dec n)) 0)]
    (if (< (.applyAsLong access-fn m) x)
      (loop [m m
             c 0]
        (cond
          (= linear-scan-limit c)
          (binary-search-leftmost access-fn n m x)

          (and (< m n)
               (< (.applyAsLong access-fn m) x))
          (recur (inc m) (inc c))

          :else
          m))
      (loop [m m
             c 0]
        (cond
          (= linear-scan-limit c)
          (binary-search-leftmost access-fn n m x)

          (and (>= m 0)
               (>= (.applyAsLong access-fn m) x))
          (recur (dec m) (inc c))

          :else
          (inc m))))))

(defn- linear-search-rightmost ^long [^IntToLongFunction access-fn ^long n ^long idx ^long x]
  (let [m (max (min idx (dec n)) 0)]
    (if (> (.applyAsLong access-fn m) x)
      (loop [m m
             c 0]
        (cond
          (= linear-scan-limit c)
          (binary-search-rightmost access-fn n m x)

          (and (>= m 0)
               (> (.applyAsLong access-fn m) x))
          (recur (dec m) (inc c))

          :else
          m))
      (loop [m m
             c 0]
        (cond
          (= linear-scan-limit c)
          (binary-search-rightmost access-fn n m x)

          (and (< m n)
               (<= (.applyAsLong access-fn m) x))
          (recur (inc m) (inc c))

          :else
          (dec m))))))

(defn- ->cell-idx ^long [^objects scales ^longs point ^long k-minus-one ^long axis-shift]
  (loop [n 0
         idx 0]
    (if (= n k-minus-one)
      idx
      (let [axis-idx (Arrays/binarySearch ^longs (aget scales n) (aget point n))
            ^long axis-idx (if (neg? axis-idx)
                             (dec (- axis-idx))
                             axis-idx)]
        (recur (inc n) (bit-or (bit-shift-left idx axis-shift) axis-idx))))))

(declare ->grid-point-access)

(def dimensions '("system-time-end-idx" "id-idx" "system-time-start-idx" "row-id-idx" "app-time-start-idx" "app-time-end-idx"))
(defn ->coordinates [x]
  (zipmap dimensions x))

(require '[clojure.set :as set])

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

(defn single-dimension-bins [grid k]
  (let [cells (.cells grid)
        axis-shift (.axis-shift grid)]
    (for [i (range k)]
      [i (->> (single-dimension-idxs k i axis-shift)
              (map (fn [[i idxs]] [i (->> (map #(some-> (nth cells %) (.getValueCount)) idxs)
                                          (remove nil?)
                                          (reduce +))]))
              (sort-by first))])))

(defn two-dimensions-bins [grid k]
  (let [cells (.cells grid)
        axis-shift (.axis-shift grid)]
    (for [i (range k)
          j (range i k)]
      [[i j] (->> (two-dimension-idxs k [i j] axis-shift)
                  (map (fn [[i idxs]] [i (->> (map #(some-> (nth cells %) (.getValueCount)) idxs)
                                              (remove nil?)
                                              (reduce +))]))
                  (sort-by first))])))

(defn grid->stats [grid]
  (let [k (dec (.k grid))
        cells (.cells grid)
        axis-shift (.axis-shift grid)]
    {:single-dimension (single-dimension-bins grid k)
     :two-dimension (two-dimensions-bins grid k)}))

(comment
  (sc.api/letsc [106 -2]
                ;; (type (.static-kd-tree kd-tree))
                (def kd-tree (.static-kd-tree kd-tree)))

  (.k kd-tree)

  (grid->stats kd-tree))



(comment


  (.value-count kd-tree)
  (.ref-cnt kd-tree)

  (let [cells (.cells kd-tree)]
    ;; (count cells)
    (map #(some-> % (.getValueCount)) cells))

  (let [mins (.mins kd-tree)]
    (->coordinates (map identity mins)))

  (let [maxs (.maxs kd-tree)]
    (->coordinates (map identity maxs)))

  (let [scales (.scales kd-tree)]
    (->coordinates (map seq scales)))

  (.axis-shift kd-tree)
  (.cell-shift kd-tree)


  (let [mins (.mins kd-tree)
        maxs (.maxs kd-tree)
        scales (.scales kd-tree)]
    {:mins (->coordinates (map identity mins))
     :maxs (->coordinates (map identity maxs))
     :scales (->coordinates (map seq scales))
     :axis-shift (.axis-shift kd-tree)
     :cell-shift (.cell-shift kd-tree)})

  (.axis-shift kd-tree)
  (.cell-shift kd-tree)


  )

(deftype Grid [^ArrowBuf arrow-buf
               ^objects scales
               ^longs mins
               ^longs maxs
               ^objects cells
               ^doubles k-minus-one-slope+base
               ^int k
               ^int axis-shift
               ^int cell-shift
               ^long size
               ^long value-count
               ^AtomicInteger ref-cnt
               ^boolean deletes?]
  kd/KdTree
  (kd-tree-insert [this allocator point]
    (throw (UnsupportedOperationException.)))
  (kd-tree-delete [this allocator point]
    (throw (UnsupportedOperationException.)))
  (kd-tree-range-search [this min-range max-range]
    (let [min-range (kd/->longs min-range)
          max-range (kd/->longs max-range)
          k-minus-one (dec k)
          axis-mask (kd/range-bitmask min-range max-range)
          axis-idxs+masks (object-array
                           (for [^long n (range k-minus-one)
                                 :let [min-r (aget min-range n)
                                       max-r (aget max-range n)
                                       min-v (aget mins n)
                                       max-v (aget maxs n)]
                                 :when (BitUtil/bitNot (or (< max-v min-r) (> min-v max-r)))
                                 :let [partial-match-axis? (BitUtil/bitNot (BitUtil/isBitSet axis-mask n))
                                       ^longs axis-scale (aget scales n)
                                       min-axis-idx (if partial-match-axis?
                                                      (int 0)
                                                      (Arrays/binarySearch axis-scale min-r))
                                       min-axis-idx (if (neg? min-axis-idx)
                                                      (dec (- min-axis-idx))
                                                      min-axis-idx)
                                       max-axis-idx (if partial-match-axis?
                                                      (alength axis-scale)
                                                      (Arrays/binarySearch axis-scale max-r))
                                       max-axis-idx (if (neg? max-axis-idx)
                                                      (dec (- max-axis-idx))
                                                      max-axis-idx)]
                                 :let [mask (bit-shift-left (bit-and axis-mask (bit-shift-left 1 n)) Integer/SIZE)
                                       axis-idxs+masks (-> (LongStream/range min-axis-idx (unchecked-inc-int max-axis-idx))
                                                           (.toArray))
                                       last-idx (dec (alength axis-idxs+masks))]]
                             (do (aset axis-idxs+masks 0 (bit-or (aget axis-idxs+masks 0) mask))
                                 (aset axis-idxs+masks last-idx (bit-or (aget axis-idxs+masks last-idx) mask))
                                 axis-idxs+masks)))
          partial-match-last-axis? (BitUtil/bitNot (BitUtil/isBitSet axis-mask k-minus-one))
          min-r (aget min-range k-minus-one)
          max-r (aget max-range k-minus-one)
          acc (LongStream/builder)]
      (when (= k-minus-one (count axis-idxs+masks))
        (.forEach (cartesian-product-idxs (map count axis-idxs+masks))
                  (reify Consumer
                    (accept [_ idxs]
                      (let [^ints idxs idxs]
                        (loop [m (int 0)
                               cell-idx 0
                               cell-axis-mask 0]
                          (if (< m k-minus-one)
                            (let [axis-idx+mask (aget ^longs (aget axis-idxs+masks m) (aget idxs m))]
                              (recur (unchecked-inc-int m)
                                     (bit-or (bit-shift-left cell-idx axis-shift) (BitUtil/bitMask axis-idx+mask -1))
                                     (bit-or cell-axis-mask (BitUtil/unsignedBitShiftRight axis-idx+mask Integer/SIZE))))
                            (when-let [^FixedSizeListVector cell (aget cells cell-idx)]
                              (let [access (KdTreeVectorPointAccess. cell k)
                                    access-fn (reify IntToLongFunction
                                                (applyAsLong [_ idx]
                                                  (.getCoordinate access idx k-minus-one)))
                                    n (.getValueCount cell)
                                    slope-idx (bit-shift-left cell-idx 1)
                                    slope (aget k-minus-one-slope+base slope-idx)
                                    base (aget k-minus-one-slope+base (inc slope-idx))
                                    start-point-idx (bit-shift-left cell-idx cell-shift)
                                    start-idx (if partial-match-last-axis?
                                                0
                                                (binary-search-leftmost access-fn n (+ (* slope min-r) base) min-r))
                                    end-idx (if partial-match-last-axis?
                                              (dec n)
                                              (binary-search-rightmost access-fn n (+ (* slope max-r) base) max-r))]
                                (if deletes?
                                  (if (zero? cell-axis-mask)
                                    (loop [idx start-idx]
                                      (when (<= idx end-idx)
                                        (when (BitUtil/bitNot (.isDeleted access idx))
                                          (.add acc (+ start-point-idx idx)))
                                        (recur (inc idx))))
                                    (loop [idx start-idx]
                                      (when (<= idx end-idx)
                                        (when (and (BitUtil/bitNot (.isDeleted access idx))
                                                   (.isInRange access idx min-range max-range cell-axis-mask))
                                          (.add acc (+ start-point-idx idx)))
                                        (recur (inc idx)))))
                                  (if (zero? cell-axis-mask)
                                    (loop [idx start-idx]
                                      (when (<= idx end-idx)
                                        (.add acc (+ start-point-idx idx))
                                        (recur (inc idx))))
                                    (loop [idx start-idx]
                                      (when (<= idx end-idx)
                                        (when (.isInRange access idx min-range max-range cell-axis-mask)
                                          (.add acc (+ start-point-idx idx)))
                                        (recur (inc idx)))))))))))))))

      (.build acc)))
  (kd-tree-points [this deletes?]
    (.flatMap (LongStream/range 0 (alength cells))
              (reify LongFunction
                (apply [_ cell-idx]
                  (if-let [^FixedSizeListVector cell (aget cells cell-idx)]
                    (let [start-point-idx (bit-shift-left cell-idx cell-shift)]
                      (cond-> (LongStream/range 0 (.getValueCount cell))
                        (BitUtil/bitNot deletes?) (.filter (reify LongPredicate
                                                             (test [_ x]
                                                               (BitUtil/bitNot (.isNull cell x)))))
                        true (.map (reify LongUnaryOperator
                                     (applyAsLong [_ x]
                                       (+ start-point-idx x))))))
                    (LongStream/empty))))))
  (kd-tree-height [_] 1)
  (kd-tree-retain [this _]
    (when (zero? (.getAndIncrement ref-cnt))
      (.set ref-cnt 0)
      (throw (IllegalStateException. "grid closed")))
    this)
  (with-point-vec [this _pv] this)
  (kd-tree-point-access [this]
    (->grid-point-access this))
  (kd-tree-size [_] size)
  (kd-tree-value-count [_] value-count)
  (kd-tree-dimensions [_] k)

  Closeable
  (close [_]
    (when (zero? (.decrementAndGet ref-cnt))
      (doseq [cell cells]
        (util/try-close cell))
      (util/try-close arrow-buf))))

(deftype GridPointAccess [^objects cells ^int cell-shift ^int cell-mask ^int k]
  IKdTreePointAccess
  (getPoint [this idx]
    (.getPoint (KdTreeVectorPointAccess. (aget cells (BitUtil/unsignedBitShiftRight idx cell-shift)) k)
               (bit-and idx cell-mask)))
  (getArrayPoint [this idx]
    (.getArrayPoint (KdTreeVectorPointAccess. (aget cells (BitUtil/unsignedBitShiftRight idx cell-shift)) k)
                    (bit-and idx cell-mask)))
  (getCoordinate [this idx axis]
    (.getCoordinate (KdTreeVectorPointAccess. (aget cells (BitUtil/unsignedBitShiftRight idx cell-shift)) k)
                    (bit-and idx cell-mask)
                    axis))
  (setCoordinate [_ idx axis value]
    (throw (UnsupportedOperationException.)))
  (swapPoint [_ from-idx to-idx]
    (throw (UnsupportedOperationException.)))
  (isDeleted [_ idx]
    (.isDeleted (KdTreeVectorPointAccess. (aget cells (BitUtil/unsignedBitShiftRight idx cell-shift)) k)
                (bit-and idx cell-mask)))
  (isInRange [this idx min-range max-range mask]
    (.isInRange (KdTreeVectorPointAccess. (aget cells (BitUtil/unsignedBitShiftRight idx cell-shift)) k)
                (bit-and idx cell-mask) min-range max-range mask)))

(defn- ->grid-point-access ^xtdb.temporal.kd_tree.IKdTreePointAccess [^Grid grid]
  (let [cell-shift (.cell-shift grid)
        cell-mask (dec (bit-shift-left 1 cell-shift))]
    (GridPointAccess. (.cells grid) cell-shift cell-mask (.k grid))))

(defn- ->grid-meta-json->grid
  ^xtdb.temporal.grid.Grid [arrow-buf
                             cells
                             {:keys [scales
                                     mins
                                     maxs
                                     k-minus-one-slope+base
                                     k
                                     axis-shift
                                     cell-shift
                                     size
                                     value-count
                                     deletes?]}]
  (Grid. arrow-buf
         (object-array (map long-array scales))
         (long-array mins)
         (long-array maxs)
         cells
         (double-array k-minus-one-slope+base)
         k
         axis-shift
         cell-shift
         size
         value-count
         (AtomicInteger. 1)
         deletes?))

(def ^:private ^:const point-vec-idx 0)

(comment
  (sc.api/letsc [1018 -2]
                grid-meta))

(defn ->arrow-buf-grid ^xtdb.temporal.grid.Grid [^ArrowBuf arrow-buf]
  (let [footer (util/read-arrow-footer arrow-buf)
        schema (.getSchema footer)
        grid-meta (json/read-str (get (.getCustomMetadata schema) "grid-meta") :key-fn keyword)
        ;; _ (sc.api/spy)
        allocator (.getAllocator (.getReferenceManager arrow-buf))
        cells (object-array
               (for [block (.getRecordBatches footer)]
                 (with-open [arrow-record-batch (util/->arrow-record-batch-view block arrow-buf)
                             root (VectorSchemaRoot/create schema allocator)]
                   (.load (VectorLoader. root) arrow-record-batch)
                   (when (pos? (.getRowCount root))
                     (.getTo (doto (.getTransferPair (.getVector root point-vec-idx) allocator)
                               (.transfer)))))))]
    (->grid-meta-json->grid arrow-buf cells grid-meta)))

(defn ->mmap-grid ^xtdb.temporal.grid.Grid [^BufferAllocator allocator ^Path path]
  (let [nio-buffer (util/->mmap-path path)
        arrow-buf (util/->arrow-buf-view allocator nio-buffer)]
    (->arrow-buf-grid arrow-buf)))

(defn ->disk-grid
  (^xtdb.temporal.grid.Grid [^BufferAllocator allocator ^Path path points {:keys [^long max-histogram-bins
                                                                                  ^long cell-size
                                                                                  ^long k
                                                                                  deletes?]
                                                                           :or {max-histogram-bins 128
                                                                                cell-size (* 8 1024)
                                                                                deletes? false}}]
   (assert (number? k))
   (util/mkdirs (.getParent path))
   (let [^long total (kd/kd-tree-size points)
         _ (assert (= 1 (Long/bitCount cell-size)))
         number-of-cells (Math/ceil (/ total cell-size))
         k-minus-one (dec k)
         cells-per-dimension (BitUtil/ceilPowerOfTwo (Math/ceil (Math/pow number-of-cells (/ 1 k-minus-one))))
         number-of-cells (long (Math/ceil (Math/pow cells-per-dimension k-minus-one)))
         axis-shift (Long/bitCount (dec cells-per-dimension))
         histogram-bins (min max-histogram-bins (* k-minus-one cells-per-dimension))
         ^IMultiDimensionalHistogram histogram (hist/->multidimensional-histogram histogram-bins k-minus-one)
         update-histograms-fn (fn [^longs p]
                                (let [p (double-array k-minus-one p)]
                                  (.update histogram p)))
         cell-outs (object-array number-of-cells)
         cell-paths (object-array number-of-cells)
         ^IKdTreePointAccess access (kd/kd-tree-point-access points)]
     (.forEach ^LongStream (kd/kd-tree-points points deletes?)
               (reify LongConsumer
                 (accept [_ x]
                   (update-histograms-fn (.getArrayPoint access x)))))
     (try
       (let [histograms (for [n (range k-minus-one)]
                          (.projectAxis histogram n))
             scales (object-array (for [^IHistogram h histograms
                                        :let [u (.uniform h cells-per-dimension)]]
                                    (long-array (distinct u))))
             mins (long-array (for [^IHistogram h histograms]
                                (Math/floor (.getMin h))))
             maxs (long-array (for [^IHistogram h histograms]
                                (Math/ceil (.getMax h))))
             k-minus-one-mins (long-array number-of-cells Long/MAX_VALUE)
             k-minus-one-maxs (long-array number-of-cells Long/MIN_VALUE)
             k-minus-one-slope+base (double-array (* 2 number-of-cells))
             write-point-fn (fn [^longs p deleted?]
                              (let [cell-idx (->cell-idx scales p k-minus-one axis-shift)
                                    ^DataOutputStream out (or (aget cell-outs cell-idx)
                                                              (let [f (str "." (.getFileName path) (format "_cell_%016x.raw" cell-idx))
                                                                    cell-path (.resolveSibling path f)]
                                                                (aset cell-paths cell-idx cell-path)
                                                                (let [file-ch (util/->file-channel cell-path util/write-new-file-opts)]
                                                                  (try
                                                                    (doto (DataOutputStream. (BufferedOutputStream. (Channels/newOutputStream file-ch)))
                                                                      (->> (aset cell-outs cell-idx)))
                                                                    (catch Exception e
                                                                      (util/try-close file-ch)
                                                                      (throw e))))))]
                                (dotimes [n k]
                                  (let [x (aget p n)]
                                    (when (= n k-minus-one)
                                      (aset k-minus-one-mins cell-idx (min x (aget k-minus-one-mins cell-idx)))
                                      (aset k-minus-one-maxs cell-idx (max x (aget k-minus-one-maxs cell-idx))))
                                    (.writeLong out x)))
                                (.writeByte out (if deleted?
                                                  1
                                                  0))))]
         (.forEach ^LongStream (kd/kd-tree-points points deletes?)
                   (reify LongConsumer
                     (accept [_ x]
                       (write-point-fn (.getArrayPoint access x) (.isDeleted access x)))))
         (dotimes [n number-of-cells]
           (util/try-close (aget cell-outs n))
           (when-let [^Path cell-path (aget cell-paths n)]
             (let [min-r (double (aget k-minus-one-mins n))
                   max-r (double (aget k-minus-one-maxs n))
                   value-count (quot (util/path-size cell-path) (inc (* k Long/BYTES)))
                   diff (- max-r min-r)
                   slope (if (zero? diff)
                           0.0
                           (double (/ value-count diff)))
                   base (- (* slope min-r))
                   slope-idx (* 2 n)]
               (aset k-minus-one-slope+base slope-idx slope)
               (aset k-minus-one-slope+base (inc slope-idx) base))))
         (let [max-cell-size (reduce max (for [^Path cell-path cell-paths
                                               :when cell-path]
                                           (quot (util/path-size cell-path) (inc (* k Long/BYTES)))))
               cell-shift (Long/bitCount (dec (BitUtil/ceilPowerOfTwo max-cell-size)))
               grid-meta {:scales scales
                          :mins mins
                          :maxs maxs
                          :k-minus-one-slope+base k-minus-one-slope+base
                          :k k
                          :axis-shift axis-shift
                          :cell-shift cell-shift
                          :size total
                          :value-count (bit-shift-left (inc number-of-cells) cell-shift)
                          :deletes? deletes?}
               schema (Schema. [(kd/->point-field k)] {"grid-meta" (json/write-str grid-meta)})
               buf (long-array k)]
           (with-open [root (VectorSchemaRoot/create schema allocator)
                       ch (util/->file-channel path util/write-new-file-opts)
                       out (ArrowFileWriter. root nil ch)]
             (let [^FixedSizeListVector point-vec (.getVector root point-vec-idx)
                   out-access (KdTreeVectorPointAccess. point-vec k)]
               (dotimes [n number-of-cells]
                 (.clear root)
                 (when-let [^Path cell-path (aget cell-paths n)]
                   (with-open [in (DataInputStream. (BufferedInputStream. (Channels/newInputStream (util/->file-channel cell-path))))]
                     (let [value-count (quot (util/path-size cell-path) (inc (* k Long/BYTES)))]
                       (dotimes [_ value-count]
                         (dotimes [m k]
                           (aset buf m (.readLong in)))
                         (let [deleted? (= (.readByte in) 1)
                               idx (kd/write-point point-vec out-access buf)]
                           (when deleted?
                             (.setNull point-vec idx))))
                       (.setRowCount root value-count)))
                   (util/delete-file cell-path)
                   (quick-sort out-access 0 (dec (.getRowCount root)) k-minus-one))
                 (.writeBatch out))))))
       path
       (finally
         (doseq [cell cell-outs]
           (util/try-close cell))
         (doseq [cell-path cell-paths
                 :when cell-path]
           (util/delete-file cell-path)))))))
