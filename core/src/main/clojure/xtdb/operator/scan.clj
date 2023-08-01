(ns xtdb.operator.scan
  (:require [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [juxt.clojars-mirrors.integrant.core :as ig]
            [xtdb.bloom :as bloom]
            [xtdb.buffer-pool :as bp]
            [xtdb.coalesce :as coalesce]
            [xtdb.expression :as expr]
            [xtdb.expression.metadata :as expr.meta]
            [xtdb.expression.walk :as expr.walk]
            xtdb.indexer.live-index
            [xtdb.logical-plan :as lp]
            [xtdb.metadata :as meta]
            [xtdb.temporal :as temporal]
            [xtdb.types :as types]
            [xtdb.util :as util]
            [xtdb.vector :as vec]
            [xtdb.vector.reader :as vr]
            [xtdb.vector.writer :as vw]
            xtdb.watermark)
  (:import (clojure.lang IPersistentSet MapEntry)
           (java.util HashMap Iterator LinkedList List Map Queue Set)
           (java.util.function BiFunction Consumer)
           java.util.stream.IntStream
           org.apache.arrow.memory.BufferAllocator
           [org.apache.arrow.memory.util ArrowBufPointer]
           (org.apache.arrow.vector BigIntVector NullVector VarBinaryVector)
           (org.apache.arrow.vector.complex ListVector StructVector)
           (org.roaringbitmap IntConsumer RoaringBitmap)
           org.roaringbitmap.buffer.MutableRoaringBitmap
           (org.roaringbitmap.longlong Roaring64Bitmap)
           xtdb.api.protocols.TransactionInstant
           xtdb.buffer_pool.IBufferPool
           xtdb.ICursor
           xtdb.indexer.live_index.ILiveTableWatermark
           (xtdb.metadata IMetadataManager ITableMetadata)
           xtdb.operator.IRelationSelector
           (xtdb.trie LiveHashTrie$Leaf)
           (xtdb.vector IVectorReader IVectorWriter RelationReader)
           (xtdb.watermark IWatermark IWatermarkSource Watermark)))

(s/def ::table symbol?)

;; TODO be good to just specify a single expression here and have the interpreter split it
;; into metadata + col-preds - the former can accept more than just `(and ~@col-preds)
(defmethod lp/ra-expr :scan [_]
  (s/cat :op #{:scan}
         :scan-opts (s/keys :req-un [::table]
                            :opt-un [::lp/for-valid-time ::lp/for-system-time ::lp/default-all-valid-time?])
         :columns (s/coll-of (s/or :column ::lp/column
                                   :select ::lp/column-expression))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(definterface IScanEmitter
  (tableColNames [^xtdb.watermark.IWatermark wm, ^String table-name])
  (allTableColNames [^xtdb.watermark.IWatermark wm])
  (scanColTypes [^xtdb.watermark.IWatermark wm, scan-cols])
  (emitScan [scan-expr scan-col-types param-types]))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn ->scan-cols [{:keys [columns], {:keys [table]} :scan-opts}]
  (for [[col-tag col-arg] columns]
    [table (case col-tag
             :column col-arg
             :select (key (first col-arg)))]))

(def ^:dynamic *column->pushdown-bloom* {})

(defn- filter-pushdown-bloom-block-idxs [^IMetadataManager metadata-manager chunk-idx ^String table-name ^String col-name ^RoaringBitmap block-idxs]
  (if-let [^MutableRoaringBitmap pushdown-bloom (get *column->pushdown-bloom* (symbol col-name))]
    ;; would prefer this `^long` to be on the param but can only have 4 params in a primitive hinted function in Clojure
    @(meta/with-metadata metadata-manager ^long chunk-idx table-name
       (util/->jfn
         (fn [^ITableMetadata table-metadata]
           (let [metadata-root (.metadataRoot table-metadata)
                 ^VarBinaryVector bloom-vec (-> ^ListVector (.getVector metadata-root "columns")
                                                ^StructVector (.getDataVector)
                                                (.getChild "bloom"))]
             (when (MutableRoaringBitmap/intersects pushdown-bloom
                                                    (bloom/bloom->bitmap bloom-vec (.rowIndex table-metadata col-name -1)))
               (let [filtered-block-idxs (RoaringBitmap.)]
                 (.forEach block-idxs
                           (reify IntConsumer
                             (accept [_ block-idx]
                               (when-let [bloom-vec-idx (.rowIndex table-metadata col-name block-idx)]
                                 (when (and (not (.isNull bloom-vec bloom-vec-idx))
                                            (MutableRoaringBitmap/intersects pushdown-bloom
                                                                             (bloom/bloom->bitmap bloom-vec bloom-vec-idx)))
                                   (.add filtered-block-idxs block-idx))))))

                 (when-not (.isEmpty filtered-block-idxs)
                   filtered-block-idxs)))))))
    block-idxs))

(deftype ContentChunkCursor [^BufferAllocator allocator
                             ^IMetadataManager metadata-mgr
                             ^IBufferPool buffer-pool
                             table-name content-col-names
                             ^Queue matching-chunks
                             ^ICursor current-cursor]
  ICursor #_<RR>
  (tryAdvance [this c]
    (loop []
      (or (when current-cursor
            (or (.tryAdvance current-cursor
                             (reify Consumer
                               (accept [_ in-root]
                                 (.accept c (-> (vr/<-root in-root)
                                                (vr/with-absent-cols allocator content-col-names))))))
                (do
                  (util/try-close current-cursor)
                  (set! (.-current-cursor this) nil)
                  false)))

          (if-let [{:keys [chunk-idx block-idxs col-names]} (.poll matching-chunks)]
            (if-let [block-idxs (reduce (fn [block-idxs col-name]
                                          (or (->> block-idxs
                                                   (filter-pushdown-bloom-block-idxs metadata-mgr chunk-idx table-name col-name))
                                              (reduced nil)))
                                        block-idxs
                                        content-col-names)]

              (do
                (set! (.current-cursor this)
                      (->> (for [col-name (set/intersection col-names content-col-names)]
                             (-> (.getBuffer buffer-pool (meta/->chunk-obj-key chunk-idx table-name col-name))
                                 (util/then-apply
                                   (fn [buf]
                                     (MapEntry/create col-name
                                                      (util/->chunks buf {:block-idxs block-idxs, :close-buffer? true}))))))
                           (remove nil?)
                           vec
                           (into {} (map deref))
                           (util/rethrowing-cause)
                           (util/combine-col-cursors)))

                (recur))

              (recur))

            false))))

  (close [_]
    (some-> current-cursor util/try-close)))

(defn- ->content-chunks ^xtdb.ICursor [^BufferAllocator allocator
                                       ^IMetadataManager metadata-mgr
                                       ^IBufferPool buffer-pool
                                       table-name content-col-names
                                       metadata-pred]
  (ContentChunkCursor. allocator metadata-mgr buffer-pool table-name content-col-names
                       (LinkedList. (or (meta/matching-chunks metadata-mgr table-name metadata-pred) []))
                       nil))

(defn- roaring-and
  (^org.roaringbitmap.RoaringBitmap [] (RoaringBitmap.))
  (^org.roaringbitmap.RoaringBitmap [^RoaringBitmap x] x)
  (^org.roaringbitmap.RoaringBitmap [^RoaringBitmap x ^RoaringBitmap y]
   (doto x
     (.and y))))

(defn- ->atemporal-row-id-bitmap [^BufferAllocator allocator, ^Map col-preds, ^RelationReader in-rel, params]
  (let [row-id-rdr (.readerForName in-rel "_row_id")
        res (Roaring64Bitmap.)]

    (if-let [content-col-preds (seq (remove (comp temporal/temporal-column? util/str->normal-form-str str key) col-preds))]
      (let [^RoaringBitmap
            idx-bitmap (->> (for [^IRelationSelector col-pred (vals content-col-preds)]
                              (RoaringBitmap/bitmapOf (.select col-pred allocator in-rel params)))
                            (reduce roaring-and))]
        (.forEach idx-bitmap
                  (reify org.roaringbitmap.IntConsumer
                    (accept [_ idx]
                      (.addLong res (.getLong row-id-rdr idx))))))

      (dotimes [idx (.valueCount row-id-rdr)]
        (.addLong res (.getLong row-id-rdr idx))))

    res))

(defn- adjust-temporal-min-range-to-row-id-range ^longs [^longs temporal-min-range ^Roaring64Bitmap row-id-bitmap]
  (let [temporal-min-range (or (temporal/->copy-range temporal-min-range) (temporal/->min-range))]
    (if (.isEmpty row-id-bitmap)
      temporal-min-range
      (let [min-row-id (.select row-id-bitmap 0)]
        (doto temporal-min-range
          (aset temporal/row-id-idx
                (max min-row-id (aget temporal-min-range temporal/row-id-idx))))))))

(defn- adjust-temporal-max-range-to-row-id-range ^longs [^longs temporal-max-range ^Roaring64Bitmap row-id-bitmap]
  (let [temporal-max-range (or (temporal/->copy-range temporal-max-range) (temporal/->max-range))]
    (if (.isEmpty row-id-bitmap)
      temporal-max-range
      (let [max-row-id (.select row-id-bitmap (dec (.getLongCardinality row-id-bitmap)))]
        (doto temporal-max-range
          (aset temporal/row-id-idx
                (min max-row-id (aget temporal-max-range temporal/row-id-idx))))))))

(defn- select-current-row-ids ^xtdb.vector.RelationReader [^RelationReader content-rel, ^Roaring64Bitmap atemporal-row-id-bitmap, ^IPersistentSet current-row-ids]
  (let [sel (IntStream/builder)
        row-id-rdr (.readerForName content-rel "_row_id")]
    (dotimes [idx (.rowCount content-rel)]
      (let [row-id (.getLong row-id-rdr idx)]
        (when (and (.contains atemporal-row-id-bitmap row-id)
                   (.contains current-row-ids row-id))
          (.add sel idx))))

    (.select content-rel (.toArray (.build sel)))))

(defn- ->temporal-rel ^xtdb.vector.RelationReader [^IWatermark watermark, ^BufferAllocator allocator, ^List col-names ^longs temporal-min-range ^longs temporal-max-range atemporal-row-id-bitmap]
  (let [temporal-min-range (adjust-temporal-min-range-to-row-id-range temporal-min-range atemporal-row-id-bitmap)
        temporal-max-range (adjust-temporal-max-range-to-row-id-range temporal-max-range atemporal-row-id-bitmap)]
    (.createTemporalRelation (.temporalRootsSource watermark)
                             allocator
                             (->> (conj col-names "_row_id")
                                  (into [] (comp (distinct) (filter temporal/temporal-column?))))
                             temporal-min-range
                             temporal-max-range
                             atemporal-row-id-bitmap)))

(defn- apply-temporal-preds ^xtdb.vector.RelationReader [^RelationReader temporal-rel, ^BufferAllocator allocator, ^Map col-preds, params]
  (->> (for [^IVectorReader col temporal-rel
             :let [col-pred (get col-preds (.getName col))]
             :when col-pred]
         col-pred)
       (reduce (fn [^RelationReader temporal-rel, ^IRelationSelector col-pred]
                 (.select temporal-rel (.select col-pred allocator temporal-rel params)))
               temporal-rel)))

(defn- ->row-id->repeat-count ^java.util.Map [^IVectorReader row-id-col]
  (let [res (HashMap.)]
    (dotimes [idx (.valueCount row-id-col)]
      (let [row-id (.getLong row-id-col idx)]
        (.compute res row-id (reify BiFunction
                               (apply [_ _k v]
                                 (if v
                                   (inc (long v))
                                   1))))))
    res))

(defn align-vectors ^xtdb.vector.RelationReader [^RelationReader content-rel, ^RelationReader temporal-rel]
  ;; assumption: temporal-rel is sorted by row-id
  (let [temporal-row-id-col (.readerForName temporal-rel "_row_id")
        content-row-id-rdr (.readerForName content-rel "_row_id")
        row-id->repeat-count (->row-id->repeat-count temporal-row-id-col)
        sel (IntStream/builder)]
    (assert temporal-row-id-col)

    (dotimes [idx (.valueCount content-row-id-rdr)]
      (let [row-id (.getLong content-row-id-rdr idx)]
        (when-let [ns (.get row-id->repeat-count row-id)]
          (dotimes [_ ns]
            (.add sel idx)))))

    (vr/rel-reader (concat temporal-rel
                           (.select content-rel (.toArray (.build sel)))))))

(defn- remove-col ^xtdb.vector.RelationReader [^RelationReader rel, ^String col-name]
  (vr/rel-reader (remove #(= col-name (.getName ^IVectorReader %)) rel)
                 (.rowCount rel)))

(defn- unnormalize-column-names ^xtdb.vector.RelationReader [^RelationReader rel col-names]
  (vr/rel-reader
   (map (fn [col-name]
          (-> (.readerForName ^RelationReader rel (util/str->normal-form-str col-name))
              (.withName col-name)))
        col-names)))


(deftype ScanCursor [^BufferAllocator allocator
                     ^IMetadataManager metadata-manager
                     ^IWatermark watermark
                     ^Set content-col-names
                     ^Set temporal-col-names
                     ^Map col-preds
                     ^longs temporal-min-range
                     ^longs temporal-max-range
                     ^IPersistentSet current-row-ids
                     ^ICursor #_<RR> blocks
                     params]
  ICursor
  (tryAdvance [_ c]
    (let [keep-row-id-col? (contains? temporal-col-names "_row_id")
          !advanced? (volatile! false)
          normalized-temporal-col-names (into #{} (map util/str->normal-form-str) temporal-col-names)]

      (while (and (not @!advanced?)
                  (.tryAdvance blocks
                               (reify Consumer
                                 (accept [_ content-rel]
                                   (let [content-rel (unnormalize-column-names content-rel content-col-names)
                                         atemporal-row-id-bitmap (->atemporal-row-id-bitmap allocator col-preds content-rel params)]
                                     (letfn [(accept-rel [^RelationReader read-rel]
                                               (when (and read-rel (pos? (.rowCount read-rel)))
                                                 (let [read-rel (cond-> read-rel
                                                                  (not keep-row-id-col?) (remove-col "_row_id"))]
                                                   (.accept c read-rel)
                                                   (vreset! !advanced? true))))]
                                       (if current-row-ids
                                         (accept-rel (-> content-rel
                                                         (select-current-row-ids atemporal-row-id-bitmap current-row-ids)))

                                         (let [temporal-rel (->temporal-rel watermark allocator normalized-temporal-col-names
                                                                            temporal-min-range temporal-max-range atemporal-row-id-bitmap)]
                                           (try
                                             (let [temporal-rel (-> temporal-rel
                                                                    (unnormalize-column-names (conj temporal-col-names "_row_id"))
                                                                    (apply-temporal-preds allocator col-preds params))]
                                               (accept-rel (align-vectors content-rel temporal-rel)))
                                             (finally
                                               (util/try-close temporal-rel))))))))))))
      (boolean @!advanced?)))

  (close [_]
    (util/try-close blocks)))

(defn ->temporal-min-max-range [^RelationReader params, {^TransactionInstant basis-tx :tx}, {:keys [for-valid-time for-system-time]}, selects]
  (let [min-range (temporal/->min-range)
        max-range (temporal/->max-range)]
    (letfn [(apply-bound [f col-name ^long time-μs]
              (let [range-idx (temporal/->temporal-column-idx (util/str->normal-form-str (str col-name)))]
                (case f
                  :< (aset max-range range-idx
                           (min (dec time-μs) (aget max-range range-idx)))
                  :<= (aset max-range range-idx
                            (min time-μs (aget max-range range-idx)))
                  :> (aset min-range range-idx
                           (max (inc time-μs) (aget min-range range-idx)))
                  :>= (aset min-range range-idx
                            (max time-μs (aget min-range range-idx)))
                  nil)))

            (->time-μs [[tag arg]]
              (case tag
                :literal (-> arg
                             (util/sql-temporal->micros (.getZone expr/*clock*)))
                :param (-> (-> (.readerForName params (name arg))
                               (.getObject 0))
                           (util/sql-temporal->micros (.getZone expr/*clock*)))
                :now (-> (.instant expr/*clock*)
                         (util/instant->micros))))]

      (when-let [system-time (some-> basis-tx (.system-time) util/instant->micros)]
        (apply-bound :<= "xt$system_from" system-time)

        (when-not for-system-time
          (apply-bound :> "xt$system_to" system-time)))

      (letfn [(apply-constraint [constraint start-col end-col]
                (when-let [[tag & args] constraint]
                  (case tag
                    :at (let [[at] args
                              at-μs (->time-μs at)]
                          (apply-bound :<= start-col at-μs)
                          (apply-bound :> end-col at-μs))

                    ;; overlaps [time-from time-to]
                    :in (let [[from to] args]
                          (apply-bound :> end-col (->time-μs (or from [:now])))
                          (when to
                            (apply-bound :< start-col (->time-μs to))))

                    :between (let [[from to] args]
                               (apply-bound :> end-col (->time-μs (or from [:now])))
                               (when to
                                 (apply-bound :<= start-col (->time-μs to))))

                    :all-time nil)))]

        (apply-constraint for-valid-time "xt$valid_from" "xt$valid_to")
        (apply-constraint for-system-time "xt$system_from" "xt$system_to"))

      (let [col-types (into {} (map (juxt first #(get temporal/temporal-col-types (util/str->normal-form-str (str (first %)))))) selects)
            param-types (expr/->param-types params)]
        (doseq [[col-name select-form] selects
                :when (temporal/temporal-column? (util/str->normal-form-str (str col-name)))]
          (->> (-> (expr/form->expr select-form {:param-types param-types, :col-types col-types})
                   (expr/prepare-expr)
                   (expr.meta/meta-expr {:col-types col-types}))
               (expr.walk/prewalk-expr
                (fn [{:keys [op] :as expr}]
                  (case op
                    :call (when (not= :or (:f expr))
                            expr)

                    :metadata-vp-call
                    (let [{:keys [f param-expr]} expr]
                      (when-let [v (if-let [[_ literal] (find param-expr :literal)]
                                     (when literal (->time-μs [:literal literal]))
                                     (->time-μs [:param (get param-expr :param)]))]
                        (apply-bound f col-name v)))

                    expr)))))
        [min-range max-range]))

    [min-range max-range]))

(defn- ->range ^longs []
  (let [res (long-array 8)]
    (doseq [i (range 0 8 2)]
      (aset res i Long/MIN_VALUE)
      (aset res (inc i) Long/MAX_VALUE))
    res))

(def ^:private column->idx {"xt$valid_from" 0
                            "xt$valid_to" 1
                            "xt$system_from" 2
                            "xt$system_to" 3})

(defn- ->temporal-column-idx ^long [col-name]
  (long (get column->idx (name col-name))))

(def ^:const ^int valid-from-lower-idx 0)
(def ^:const ^int valid-from-upper-idx 1)
(def ^:const ^int valid-to-lower-idx 2)
(def ^:const ^int valid-to-upper-idx 3)
(def ^:const ^int system-from-lower-idx 4)
(def ^:const ^int system-from-upper-idx 5)
(def ^:const ^int system-to-lower-idx 6)
(def ^:const ^int system-to-upper-idx 7)


(defn- ->temporal-range [^RelationReader params, {^TransactionInstant basis-tx :tx}, {:keys [for-valid-time for-system-time]}, selects]
  (let [range (->range)]
    (letfn [(apply-bound [f col-name ^long time-μs]
              (let [range-idx-lower (* (->temporal-column-idx (util/str->normal-form-str (str col-name))) 2)
                    range-idx-upper (inc range-idx-lower)]
                (case f
                  :< (aset range range-idx-upper
                           (min (dec time-μs) (aget range range-idx-upper)))
                  :<= (aset range range-idx-upper
                            (min time-μs (aget range range-idx-upper)))
                  :> (aset range range-idx-lower
                           (max (inc time-μs) (aget range range-idx-lower)))
                  :>= (aset range range-idx-lower
                            (max time-μs (aget range range-idx-lower)))
                  nil)))

            (->time-μs [[tag arg]]
              (case tag
                :literal (-> arg
                             (util/sql-temporal->micros (.getZone expr/*clock*)))
                :param (-> (-> (.readerForName params (name arg))
                               (.getObject 0))
                           (util/sql-temporal->micros (.getZone expr/*clock*)))
                :now (-> (.instant expr/*clock*)
                         (util/instant->micros))))]

      (when-let [system-time (some-> basis-tx (.system-time) util/instant->micros)]
        (apply-bound :<= "xt$system_from" system-time)

        (when-not for-system-time
          (apply-bound :> "xt$system_to" system-time)))

      (letfn [(apply-constraint [constraint start-col end-col]
                (when-let [[tag & args] constraint]
                  (case tag
                    :at (let [[at] args
                              at-μs (->time-μs at)]
                          (apply-bound :<= start-col at-μs)
                          (apply-bound :> end-col at-μs))

                    ;; overlaps [time-from time-to]
                    :in (let [[from to] args]
                          (apply-bound :> end-col (->time-μs (or from [:now])))
                          (when to
                            (apply-bound :< start-col (->time-μs to))))

                    :between (let [[from to] args]
                               (apply-bound :> end-col (->time-μs (or from [:now])))
                               (when to
                                 (apply-bound :<= start-col (->time-μs to))))

                    :all-time nil)))]

        (apply-constraint for-valid-time "xt$valid_from" "xt$valid_to")
        (apply-constraint for-system-time "xt$system_from" "xt$system_to"))

      (let [col-types (into {} (map (juxt first #(get temporal/temporal-col-types (util/str->normal-form-str (str (first %)))))) selects)
            param-types (expr/->param-types params)]
        (doseq [[col-name select-form] selects
                :when (temporal/temporal-column? (util/str->normal-form-str (str col-name)))]
          (->> (-> (expr/form->expr select-form {:param-types param-types, :col-types col-types})
                   (expr/prepare-expr)
                   (expr.meta/meta-expr {:col-types col-types}))
               (expr.walk/prewalk-expr
                (fn [{:keys [op] :as expr}]
                  (case op
                    :call (when (not= :or (:f expr))
                            expr)

                    :metadata-vp-call
                    (let [{:keys [f param-expr]} expr]
                      (when-let [v (if-let [[_ literal] (find param-expr :literal)]
                                     (when literal (->time-μs [:literal literal]))
                                     (->time-μs [:param (get param-expr :param)]))]
                        (apply-bound f col-name v)))

                    expr)))))))
    (when (nil? (aget range 0))
      (aset range 0 Long/MIN_VALUE))
    (when (nil? (aget range 1))
      (aset range 1 Long/MAX_VALUE))
    (when (nil? (aget range 2))
      (aset range 2 Long/MIN_VALUE))
    (when (nil? (aget range 3))
      (aset range 3 Long/MAX_VALUE))
    range))

(defn- scan-op-at-now [scan-op]
  (= :now (first (second scan-op))))

(defn- at-now? [{:keys [for-valid-time for-system-time]}]
  (and (or (nil? for-valid-time)
           (scan-op-at-now for-valid-time))
       (or (nil? for-system-time)
           (scan-op-at-now for-system-time))))

(defn- scan-op-point? [scan-op]
  (= :at (first scan-op)))

(defn- at-point-point? [{:keys [for-valid-time for-system-time]}]
  (and (or (nil? for-valid-time)
           (scan-op-point? for-valid-time))
       (or (nil? for-system-time)
           (scan-op-point? for-system-time))))

(defn- range-point-query? [^IWatermark watermark basis {:keys [for-system-time] :as _scan-opts}]
  (and
   (.txBasis watermark)
   (= (:tx basis)
      (.txBasis watermark))
   (or (nil? for-system-time)
       (scan-op-point? for-system-time))
   (>= (util/instant->micros (:current-time basis))
       (util/instant->micros (:system-time (:tx basis))))))

(defn use-current-row-id-cache? [^IWatermark watermark scan-opts basis temporal-col-names]
  (and
   (.txBasis watermark)
   (= (:tx basis)
      (.txBasis watermark))
   (at-now? scan-opts)
   (>= (util/instant->micros (:current-time basis))
       (util/instant->micros (:system-time (:tx basis))))
   (empty? (remove #(= % "xt$id") temporal-col-names))))

(defn get-current-row-ids [^IWatermark watermark basis]
  (.getCurrentRowIds
   ^xtdb.temporal.ITemporalRelationSource
   (.temporalRootsSource watermark)
   (util/instant->micros (:current-time basis))))

(defn tables-with-cols [basis ^IWatermarkSource wm-src ^IScanEmitter scan-emitter]
  (let [{:keys [tx, after-tx]} basis
        wm-tx (or tx after-tx)]
    (with-open [^Watermark wm (.openWatermark wm-src wm-tx)]
      (.allTableColNames scan-emitter wm))))

(defn- print-temporal-data [^longs temporal-data]
  (prn "$$$$$$$$$$$$$")
  (dotimes [i (alength temporal-data)]
    (prn (util/micros->instant (aget temporal-data i))))
  (prn "$$$$$$$$$$$$$"))

(defn temporal-range->temporal-timestamp [^longs temporal-range]
  (let [res (long-array 2)]
    (aset res 0 (aget temporal-range valid-to-lower-idx))
    (aset res 1 (aget temporal-range system-to-lower-idx))
    res))

(defn point-point-selection [^RelationReader leaf-rel ^longs temporal-timestamps
                             ^IVectorWriter valid-from-wrt, ^IVectorWriter valid-to-wrt]
  (let [leaf-row-count (.rowCount leaf-rel)
        iid-rdr (.readerForName leaf-rel "xt$iid")
        sys-from-rdr (.readerForName leaf-rel "xt$system_from")
        op-rdr (.readerForName leaf-rel "op")
        put-rdr (.legReader op-rdr :put)
        put-valid-from-rdr (.structKeyReader put-rdr "xt$valid_from")
        put-valid-to-rdr (.structKeyReader put-rdr "xt$valid_to")

        delete-rdr (.legReader op-rdr :delete)
        delete-valid-from-rdr (.structKeyReader delete-rdr "xt$valid_from")
        delete-valid-to-rdr (.structKeyReader delete-rdr "xt$valid_to")

        valid-time (aget temporal-timestamps 0)
        system-time (aget temporal-timestamps 1)

        current-iid-ptr (ArrowBufPointer.)
        cmp-ptr (ArrowBufPointer.)
        !selection (IntStream/builder)

        current-bounds (long-array 2)]

    (letfn [(reset-current-bounds []
              (aset current-bounds 0 Long/MIN_VALUE)
              (aset current-bounds 1 Long/MAX_VALUE))

            (next-iid [idx]
              (reset-current-bounds)
              (loop [idx idx]
                (if (= idx leaf-row-count)
                  idx
                  (if (= current-iid-ptr (.getPointer iid-rdr idx cmp-ptr))
                    (recur (inc idx))
                    idx))))

            (move-index [idx ^long valid-from ^long valid-to]
              (let [new-idx (inc idx)]
                ;; this checks if went over an iid boundary but did not find anything
                (if-not (and (< new-idx leaf-row-count) ;; TODO avoid double check
                             (= (.getPointer iid-rdr idx cmp-ptr)
                                (.getPointer iid-rdr new-idx current-iid-ptr)))
                  (reset-current-bounds)

                  (if (< valid-to valid-time)
                    (when (< (aget current-bounds 0) valid-to)
                      (aset current-bounds 0 valid-to))
                    (when (< valid-from (aget current-bounds 1))
                      (aset current-bounds 1 valid-from))))

                new-idx))]

      (reset-current-bounds)

      (loop [idx 0]
        (when-not (= idx leaf-row-count)
          (if (<= (.getLong sys-from-rdr idx) system-time)
            (case (.getLeg op-rdr idx)
              :put
              (let [valid-from (.getLong put-valid-from-rdr idx)
                    valid-to (.getLong put-valid-to-rdr idx)]
                (if (and (<= valid-from valid-time)
                         (< valid-time valid-to))
                  (do
                    (.getPointer iid-rdr idx current-iid-ptr)
                    (.add !selection idx)
                    (when valid-from-wrt
                      (.writeLong valid-from-wrt (Long/max valid-from (aget current-bounds 0))))
                    (when valid-to-wrt
                      (.writeLong valid-to-wrt (Long/min valid-to (aget current-bounds 1))))
                    (recur (long (next-iid idx))))

                  (recur (long (move-index idx valid-from valid-to)))))

              :delete
              (let [valid-from (.getLong delete-valid-from-rdr idx)
                    valid-to (.getLong delete-valid-to-rdr idx)]
                (if (and (<= valid-from valid-time)
                         (< valid-time valid-to))
                  (do
                    (.getPointer iid-rdr idx current-iid-ptr)
                    (recur (long (next-iid idx))))
                  (recur (long (move-index idx valid-from valid-to)))))

              :evict
              (do
                (.getPointer iid-rdr idx current-iid-ptr)
                (recur (long (next-iid idx)))))

            (recur (long (inc idx)))))))

    (.toArray (.build !selection))))

(deftype PointPointCursor [^BufferAllocator allocator, ^RelationReader live-rel, ^Iterator leaves,
                           col-names, ^Map col-preds, ^longs temporal-timestamps, params]
  ICursor
  (tryAdvance [_ c]
    (if (.hasNext leaves)
      (let [^LiveHashTrie$Leaf leaf (.next leaves)
            leaf-rel (.select live-rel (.data leaf))
            legacy-iid-rdr (.readerForName leaf-rel "xt$legacy_iid")
            sys-from-rdr (.readerForName leaf-rel "xt$system_from")
            op-rdr (.readerForName leaf-rel "op")
            put-rdr (.legReader op-rdr :put)
            doc-rdr (.structKeyReader put-rdr "xt$doc")

            ;; HACK we really should only do this once...
            normalized-col-names (into #{} (map util/str->normal-form-str) col-names)]

        (util/with-open [valid-from-wtr (when (contains? normalized-col-names "xt$valid_from")
                                          (-> (types/col-type->field "xt$valid_from" types/temporal-col-type)
                                              (.createVector allocator)
                                              (vw/->writer)))
                         valid-to-wtr (when (contains? normalized-col-names "xt$valid_to")
                                        (-> (types/col-type->field "xt$valid_to" types/temporal-col-type)
                                            (.createVector allocator)
                                            (vw/->writer)))]
          (let [^ints selection (point-point-selection leaf-rel temporal-timestamps
                                                       valid-from-wtr valid-to-wtr)
                out-rel (vr/rel-reader
                         (for [col-name col-names
                               :let [normalized-name (util/str->normal-form-str col-name)
                                     rdr (case normalized-name
                                           "_iid" (.select legacy-iid-rdr selection)
                                           "xt$system_from"  (.select sys-from-rdr selection)
                                           "xt$system_to" (vr/vec->reader (doto (NullVector. "xt$system_to")
                                                                            (.setValueCount (alength selection))))
                                           "xt$valid_from" (vw/vec-wtr->rdr valid-from-wtr)
                                           "xt$valid_to" (vw/vec-wtr->rdr valid-to-wtr)
                                           (some-> (.structKeyReader doc-rdr normalized-name)
                                                   (.select selection)))]
                               :when rdr]
                           (.withName rdr col-name))
                         (alength selection))]
            (.accept c (-> out-rel
                           (vr/with-absent-cols allocator col-names)

                           (as-> rel (reduce (fn [^RelationReader rel, ^IRelationSelector col-pred]
                                               (.select rel (.select col-pred allocator rel params)))
                                             rel
                                             (vals col-preds)))))))
        true)
      false))

  (close [_]
    ;; TODO convince ourselves there's nothing to close here
    ))

(deftype Interval [^long start, ^long end, ^int id, ^byte op])

;; interval is represented as [start end id op]
(defn- intersect [^Interval i1 ^Interval i2]
  ;; TODO strict checks ?
  (and (<= (.start i1) (.end i2)) (<= (.start i2) (.end i1))))

;; TODO zero length intervals
(defn- split
  "i1 comes before i2 in system time"
  [^Interval i1 ^Interval i2]
  (let [start1 (.start i1)
        start2 (.start i2)
        end1 (.end i1)
        end2 (.end i2)
        id (.id i1)
        op (.op i1)]
    (cond-> []
      (< start1 start2)
      (conj (->Interval start1 start2 id op))
      (< end2 end1)
      (conj (->Interval end2 end1 id op)))))

(defn range-point-selection [^RelationReader leaf-rel, ^longs temporal-ranges
                             ^IVectorWriter valid-from-wrt, ^IVectorWriter valid-to-wrt]
  (let [leaf-row-count (.rowCount leaf-rel)
        iid-rdr (.readerForName leaf-rel "xt$iid")
        sys-from-rdr (.readerForName leaf-rel "xt$system_from")
        op-rdr (.readerForName leaf-rel "op")
        put-rdr (.legReader op-rdr :put)
        put-valid-from-rdr (.structKeyReader put-rdr "xt$valid_from")
        put-valid-to-rdr (.structKeyReader put-rdr "xt$valid_to")

        delete-rdr (.legReader op-rdr :delete)
        delete-valid-from-rdr (.structKeyReader delete-rdr "xt$valid_from")
        delete-valid-to-rdr (.structKeyReader delete-rdr "xt$valid_to")

        valid-from-lower (aget temporal-ranges valid-from-lower-idx)
        valid-from-upper (aget temporal-ranges valid-from-upper-idx)
        valid-to-lower (aget temporal-ranges valid-to-lower-idx)
        valid-to-upper (aget temporal-ranges valid-to-upper-idx)
        system-time (aget temporal-ranges system-to-lower-idx)

        current-iid-ptr (ArrowBufPointer.)
        cmp-ptr (ArrowBufPointer.)
        !selection (IntStream/builder)
        ;; TODO the ranges could be kept in sorted order which could speed up some checks
        !ranges (LinkedList.)
        !new-ranges (LinkedList.)]
    (letfn [(copy-ranges []
              (doseq [^Interval i !ranges]
                (let [valid-from (.start i)
                      valid-to (.end i)]
                  (when (and (zero? (.op i))
                             (<= valid-from-lower valid-from)
                             (<= valid-from valid-from-upper)
                             (<= valid-to-lower valid-to)
                             (<= valid-to valid-to-upper))
                    (.add !selection (.id i))
                    (when valid-from-wrt
                      (.writeLong valid-from-wrt (.start i)))
                    (when valid-to-wrt
                      (.writeLong valid-to-wrt (.end i))))))
              (.clear !ranges))
            (next-idx [idx]
              (let [new-idx (inc idx)]
                (when (and (< new-idx leaf-row-count)
                           (not= (.getPointer iid-rdr idx current-iid-ptr)
                                 (.getPointer iid-rdr new-idx cmp-ptr)))
                  (copy-ranges))
                new-idx))
            (next-iid [idx]
              (.clear !ranges)
              (loop [idx idx]
                (if (= idx leaf-row-count)
                  idx
                  (if (= current-iid-ptr (.getPointer iid-rdr idx cmp-ptr))
                    (recur (inc idx))
                    idx))))]
      (loop [idx 0]
        (when (< idx leaf-row-count)
          (if (<= (.getLong sys-from-rdr idx) system-time)
            (case (.getLeg op-rdr idx)
              :put
              (let [i1 (->Interval (.getLong put-valid-from-rdr idx) (.getLong put-valid-to-rdr idx) idx 0)
                    itr (.listIterator !ranges)]
                (.add !new-ranges i1)
                (while (.hasNext itr)
                  (let [i2 (.next itr)
                        inner-itr (.listIterator !new-ranges)]
                    (while (.hasNext inner-itr)
                      (let [i1 (.next inner-itr)]
                        (when (intersect i1 i2)
                          (.remove inner-itr)
                          (run! #(.add inner-itr %) (split i1 i2)))))))
                (doseq [i !new-ranges]
                  (.add !ranges i))
                (.clear !new-ranges)
                (recur (long (next-idx idx))))

              :delete
              (let [i1 (->Interval (.getLong delete-valid-from-rdr idx) (.getLong delete-valid-to-rdr idx) idx 1)
                    itr (.listIterator !ranges)]
                (.add !new-ranges i1)
                (while (.hasNext itr)
                  (let [i2 (.next itr)
                        inner-itr (.listIterator !new-ranges)]
                    (while (.hasNext inner-itr)
                      (let [i1 (.next inner-itr)]
                        (when (intersect i1 i2)
                          (.remove inner-itr)
                          (run! #(.add inner-itr %) (split i1 i2)))))))
                (doseq [i !new-ranges]
                  (.add !ranges i))
                (.clear !new-ranges)
                (recur (long (next-idx idx))))
              :evict
              (recur (long (next-iid idx))))
            (recur (long (next-idx idx))))))
      (copy-ranges))
    (.toArray (.build !selection))))

(deftype RangePointCursor [^BufferAllocator allocator, ^RelationReader live-rel, ^Iterator leaves,
                           col-names, ^Map col-preds, ^longs temporal-timestamps, params]
  ICursor
  (tryAdvance [_ c]
    (if (.hasNext leaves)
      (let [^LiveHashTrie$Leaf leaf (.next leaves)
            leaf-rel (.select live-rel (.data leaf))
            legacy-iid-rdr (.readerForName leaf-rel "xt$legacy_iid")
            sys-from-rdr (.readerForName leaf-rel "xt$system_from")
            op-rdr (.readerForName leaf-rel "op")
            put-rdr (.legReader op-rdr :put)
            doc-rdr (.structKeyReader put-rdr "xt$doc")

            ;; HACK we really should only do this once...
            normalized-col-names (into #{} (map util/str->normal-form-str) col-names)]

        (util/with-open [valid-from-wtr (when (contains? normalized-col-names "xt$valid_from")
                                          (-> (types/col-type->field "xt$valid_from" types/temporal-col-type)
                                              (.createVector allocator)
                                              (vw/->writer)))
                         valid-to-wtr (when (contains? normalized-col-names "xt$valid_to")
                                        (-> (types/col-type->field "xt$valid_to" types/temporal-col-type)
                                            (.createVector allocator)
                                            (vw/->writer)))]
          (let [^ints selection (range-point-selection leaf-rel temporal-timestamps
                                                       valid-from-wtr valid-to-wtr)
                out-rel (vr/rel-reader
                         (for [col-name col-names
                               :let [normalized-name (util/str->normal-form-str col-name)
                                     rdr (case normalized-name
                                           "_iid" (.select legacy-iid-rdr selection)
                                           "xt$system_from"  (.select sys-from-rdr selection)
                                           "xt$system_to" (vr/vec->reader (doto (NullVector. "xt$system_to")
                                                                            (.setValueCount (alength selection))))
                                           "xt$valid_from" (vw/vec-wtr->rdr valid-from-wtr)
                                           "xt$valid_to" (vw/vec-wtr->rdr valid-to-wtr)
                                           (some-> (.structKeyReader doc-rdr normalized-name)
                                                   (.select selection)))]
                               :when rdr]
                           (.withName rdr col-name))
                         (alength selection))]
            (.accept c (-> out-rel
                           (vr/with-absent-cols allocator col-names)

                           (as-> rel (reduce (fn [^RelationReader rel, ^IRelationSelector col-pred]
                                               (.select rel (.select col-pred allocator rel params)))
                                             rel
                                             (vals col-preds)))))))
        true)
      false))

  (close [_]
    ;; TODO convince ourselves there's nothing to close here
    ))


(deftype Rectangle [^long valid-from, ^long valid-to,
                    ^long sys-from, ^long sys-to
                    ^int id, ^byte op])

(defn- rectangle-intersect [^Rectangle r1 ^Rectangle r2]
  (not (or (> (.valid-from r2) (.valid-to r1))
           (> (.valid-from r1) (.valid-to r2))
           (> (.sys-from r2) (.sys-to r1))
           (> (.sys-from r1) (.sys-to r2)))))

(defn- rectangle-split
  "r1 comes before r2 in system time"
  [^Rectangle r1 ^Rectangle r2]
  (let [valid-from1 (.valid-from r1)
        valid-from2 (.valid-from r2)
        valid-to1 (.valid-to r1)
        valid-to2 (.valid-to r2)
        sys-from1 (.sys-from r1)
        sys-from2 (.sys-from r2)
        sys-to1 (.sys-to r1)
        sys-to2 (.sys-to r2)
        id (.id r1)
        op (.op r1)]
    (cond-> []
      (< sys-from1 sys-from2)
      (conj (->Rectangle valid-from1 valid-to1 sys-from1 sys-from2 id op))
      (< sys-to2 sys-to1)
      (conj (->Rectangle valid-from1 valid-to1 sys-to2 sys-to1 id op))
      (< valid-from1 valid-from2)
      (conj (->Rectangle valid-from1 valid-from2 (max sys-from1 sys-from2) (min sys-to1 sys-to2) id op))
      (< valid-to2 valid-to1)
      (conj (->Rectangle valid-to2 valid-to1 (max sys-from1 sys-from2) (min sys-to1 sys-to2) id op)))))

(defn range-range-selection [^RelationReader leaf-rel,^longs temporal-ranges
                             ^IVectorWriter valid-from-wrt, ^IVectorWriter valid-to-wrt
                             ^IVectorWriter sys-from-wrt, ^IVectorWriter sys-to-wrt]
  (let [leaf-row-count (.rowCount leaf-rel)
        iid-rdr (.readerForName leaf-rel "xt$iid")
        sys-from-rdr (.readerForName leaf-rel "xt$system_from")
        op-rdr (.readerForName leaf-rel "op")
        put-rdr (.legReader op-rdr :put)
        put-valid-from-rdr (.structKeyReader put-rdr "xt$valid_from")
        put-valid-to-rdr (.structKeyReader put-rdr "xt$valid_to")

        delete-rdr (.legReader op-rdr :delete)
        delete-valid-from-rdr (.structKeyReader delete-rdr "xt$valid_from")
        delete-valid-to-rdr (.structKeyReader delete-rdr "xt$valid_to")

        valid-from-lower (aget temporal-ranges valid-from-lower-idx)
        valid-from-upper (aget temporal-ranges valid-from-upper-idx)
        valid-to-lower (aget temporal-ranges valid-to-lower-idx)
        valid-to-upper (aget temporal-ranges valid-to-upper-idx)
        sys-from-lower (aget temporal-ranges system-from-lower-idx)
        sys-from-upper (aget temporal-ranges system-from-upper-idx)
        sys-to-lower (aget temporal-ranges system-to-lower-idx)
        sys-to-upper (aget temporal-ranges system-to-upper-idx)

        current-iid-ptr (ArrowBufPointer.)
        cmp-ptr (ArrowBufPointer.)
        !selection (IntStream/builder)
        ;; TODO the ranges could be kept in sorted order which could speed up some checks
        !ranges (LinkedList.)
        !new-ranges (LinkedList.)]
    (letfn [(copy-ranges []
              (doseq [^Rectangle r !ranges]
                (let [valid-from (.valid-from r)
                      valid-to (.valid-to r)
                      sys-from (.sys-from r)
                      sys-to (.sys-to r)]
                  (when (and (zero? (.op r))
                             (<= valid-from-lower valid-from)
                             (<= valid-from valid-from-upper)
                             (<= valid-to-lower valid-to)
                             (<= valid-to valid-to-upper)
                             (<= sys-from-lower sys-from)
                             (<= sys-from sys-from-upper)
                             (<= sys-to-lower sys-to)
                             (<= sys-to sys-to-upper))
                    (.add !selection (.id r))
                    (when valid-from-wrt
                      (.writeLong valid-from-wrt (.valid-from r)))
                    (when valid-to-wrt
                      (.writeLong valid-to-wrt (.valid-to r)))
                    (when sys-from-wrt
                      (.writeLong sys-from-wrt (.sys-from r)))
                    (when sys-to-wrt
                      (.writeLong sys-to-wrt (.sys-to r))))))
              (.clear !ranges))
            (next-idx [idx]
              (let [new-idx (inc idx)]
                (when (and (< new-idx leaf-row-count)
                           (not= (.getPointer iid-rdr idx current-iid-ptr)
                                 (.getPointer iid-rdr new-idx cmp-ptr)))
                  (copy-ranges))
                new-idx))
            (next-iid [idx]
              (.clear !ranges)
              (loop [idx idx]
                (if (= idx leaf-row-count)
                  idx
                  (if (= current-iid-ptr (.getPointer iid-rdr idx cmp-ptr))
                    (recur (inc idx))
                    idx))))]
      (loop [idx 0]
        (when (< idx leaf-row-count)
          (let [system-from (.getLong sys-from-rdr idx)]
            ;; TODO potentially more fancy check here for skipping
            ;; FIXME why does strict not work in this case
            (if (and (<= sys-from-lower system-from) (<= system-from sys-from-upper))
              (case (.getLeg op-rdr idx)
                :put
                (let [r1 (->Rectangle (.getLong put-valid-from-rdr idx) (.getLong put-valid-to-rdr idx)
                                      (.getLong sys-from-rdr idx) util/end-of-time-μs
                                      idx 0)
                      itr (.listIterator !ranges)]
                  (.add !new-ranges r1)
                  (while (.hasNext itr)
                    (let [r2 (.next itr)
                          inner-itr (.listIterator !new-ranges)]
                      (while (.hasNext inner-itr)
                        (let [r1 (.next inner-itr)]
                          (when (rectangle-intersect r1 r2)
                            (.remove inner-itr)
                            (run! #(.add inner-itr %) (rectangle-split r1 r2)))))))
                  (doseq [i !new-ranges]
                    (.add !ranges i))
                  (.clear !new-ranges)
                  (recur (long (next-idx idx))))

                :delete
                (let [r1 (->Rectangle (.getLong delete-valid-from-rdr idx) (.getLong delete-valid-to-rdr idx)
                                      (.getLong sys-from-rdr idx) util/end-of-time-μs
                                      idx 1)
                      itr (.listIterator !ranges)]
                  (.add !new-ranges r1)
                  (while (.hasNext itr)
                    (let [r2 (.next itr)
                          inner-itr (.listIterator !new-ranges)]
                      (while (.hasNext inner-itr)
                        (let [r1 (.next inner-itr)]
                          (when (rectangle-intersect r1 r2)
                            (.remove inner-itr)
                            (run! #(.add inner-itr %) (rectangle-split r1 r2)))))))
                  (doseq [i !new-ranges]
                    (.add !ranges i))
                  (.clear !new-ranges)
                  (recur (long (next-idx idx))))
                :evict
                (recur (long (next-iid idx))))
              (recur (long (next-idx idx)))))))
      (copy-ranges))
    (.toArray (.build !selection))))

(deftype RangeRangeCursor [^BufferAllocator allocator, ^RelationReader live-rel, ^Iterator leaves,
                           col-names, ^Map col-preds, ^longs temporal-timestamps, params]
  ICursor
  (tryAdvance [_ c]
    (if (.hasNext leaves)
      (let [^LiveHashTrie$Leaf leaf (.next leaves)
            leaf-rel (.select live-rel (.data leaf))
            legacy-iid-rdr (.readerForName leaf-rel "xt$legacy_iid")
            op-rdr (.readerForName leaf-rel "op")
            put-rdr (.legReader op-rdr :put)
            doc-rdr (.structKeyReader put-rdr "xt$doc")

            ;; HACK we really should only do this once...
            normalized-col-names (into #{} (map util/str->normal-form-str) col-names)]

        (util/with-open [valid-from-wtr (when (contains? normalized-col-names "xt$valid_from")
                                          (-> (types/col-type->field "xt$valid_from" types/temporal-col-type)
                                              (.createVector allocator)
                                              (vw/->writer)))
                         valid-to-wtr (when (contains? normalized-col-names "xt$valid_to")
                                        (-> (types/col-type->field "xt$valid_to" types/temporal-col-type)
                                            (.createVector allocator)
                                            (vw/->writer)))
                         sys-from-wtr (when (contains? normalized-col-names "xt$system_from")
                                        (-> (types/col-type->field "xt$system_from" types/temporal-col-type)
                                            (.createVector allocator)
                                            (vw/->writer)))
                         sys-to-wtr (when (contains? normalized-col-names "xt$system_to")
                                      (-> (types/col-type->field "xt$system_to" types/temporal-col-type)
                                          (.createVector allocator)
                                          (vw/->writer)))]
          (let [^ints selection (range-range-selection leaf-rel temporal-timestamps
                                                       valid-from-wtr valid-to-wtr
                                                       sys-from-wtr sys-to-wtr)
                out-rel (vr/rel-reader
                         (for [col-name col-names
                               :let [normalized-name (util/str->normal-form-str col-name)
                                     rdr (case normalized-name
                                           "_iid" (.select legacy-iid-rdr selection)
                                           "xt$system_from" (vw/vec-wtr->rdr sys-from-wtr)
                                           "xt$system_to" (vw/vec-wtr->rdr sys-to-wtr)
                                           "xt$valid_from" (vw/vec-wtr->rdr valid-from-wtr)
                                           "xt$valid_to" (vw/vec-wtr->rdr valid-to-wtr)
                                           (some-> (.structKeyReader doc-rdr normalized-name)
                                                   (.select selection)))]
                               :when rdr]
                           (.withName rdr col-name))
                         (alength selection))]
            (.accept c (-> out-rel
                           (vr/with-absent-cols allocator col-names)

                           (as-> rel (reduce (fn [^RelationReader rel, ^IRelationSelector col-pred]
                                               (.select rel (.select col-pred allocator rel params)))
                                             rel
                                             (vals col-preds)))))))
        true)
      false))

  (close [_]
    ;; TODO convince ourselves there's nothing to close here
    ))

(defn ->4r-cursor [^BufferAllocator allocator, ^IBufferPool buffer-pool, ^IWatermark wm
                   table-name, col-names, ^longs temporal-range
                   ^Map col-preds, params, scan-opts, basis]
  (let [^ILiveTableWatermark  live-table-wm (some-> (.liveIndex wm) (.liveTable table-name))]

    (cond
      (at-point-point? scan-opts)
      (->PointPointCursor allocator
                          (some-> live-table-wm .liveRelation) (.iterator ^Iterable (vec (some-> live-table-wm .liveTrie .leaves)))
                          col-names col-preds
                          (temporal-range->temporal-timestamp temporal-range)
                          params)

      (range-point-query? wm basis scan-opts)
      (->RangePointCursor allocator
                          (some-> live-table-wm .liveRelation) (.iterator ^Iterable (vec (some-> live-table-wm .liveTrie .leaves)))
                          col-names col-preds
                          temporal-range
                          params)

      :else
      (->RangeRangeCursor allocator
                          (some-> live-table-wm .liveRelation) (.iterator ^Iterable (vec (some-> live-table-wm .liveTrie .leaves)))
                          col-names col-preds
                          temporal-range
                          params))))

(defn no-finished-chunks? [^IMetadataManager metadata-mgr]
  (nil? (seq (.chunksMetadata metadata-mgr))))

(defmethod ig/prep-key ::scan-emitter [_ opts]
  (merge opts
         {:metadata-mgr (ig/ref ::meta/metadata-manager)
          :buffer-pool (ig/ref ::bp/buffer-pool)}))

(defmethod ig/init-key ::scan-emitter [_ {:keys [^IMetadataManager metadata-mgr, ^IBufferPool buffer-pool]}]
  (reify IScanEmitter
    (tableColNames [_ wm table-name]
      (let [normalized-table (util/str->normal-form-str table-name)]
        (into #{} cat [(keys (.columnTypes metadata-mgr normalized-table))
                       (some-> (.liveChunk wm)
                               (.liveTable normalized-table)
                               (.columnTypes)
                               keys)])))

    (allTableColNames [_ wm]
      (merge-with
       set/union
       (update-vals
        (.allColumnTypes metadata-mgr)
        (comp set keys))
       (update-vals
        (some-> (.liveChunk wm)
                (.allColumnTypes))
        (comp set keys))))

    (scanColTypes [_ wm scan-cols]

      (letfn [(->col-type [[table col-name]]
                (let [normalized-table (util/str->normal-form-str (str table))
                      normalized-col-name (util/str->normal-form-str (str col-name))]
                  (if (temporal/temporal-column? (util/str->normal-form-str (str col-name)))
                    [:timestamp-tz :micro "UTC"]
                    (types/merge-col-types (.columnType metadata-mgr normalized-table normalized-col-name)
                                           (some-> (.liveChunk wm)
                                                   (.liveTable normalized-table)
                                                   (.columnTypes)
                                                   (get normalized-col-name))))))]
        (->> scan-cols
             (into {} (map (juxt identity ->col-type))))))

    (emitScan [_ {:keys [columns], {:keys [table for-valid-time] :as scan-opts} :scan-opts} scan-col-types param-types]
      (let [col-names (->> columns
                           (into [] (comp (map (fn [[col-type arg]]
                                                 (case col-type
                                                   :column arg
                                                   :select (key (first arg)))))

                                          (distinct))))

            {content-col-names false, temporal-col-names true}
            (->> col-names (group-by (comp temporal/temporal-column? util/str->normal-form-str str)))

            content-col-names (-> (set (map str content-col-names)) (conj "_row_id"))
            normalized-content-col-names (set (map (comp util/str->normal-form-str) content-col-names))
            temporal-col-names (into #{} (map (comp str)) temporal-col-names)
            normalized-table-name (util/str->normal-form-str (str table))

            col-types (->> col-names
                           (into {} (map (juxt identity
                                               (fn [col-name]
                                                 (get scan-col-types [table col-name]))))))

            selects (->> (for [[tag arg] columns
                               :when (= tag :select)]
                           (first arg))
                         (into {}))

            col-preds (->> (for [[col-name select-form] selects]
                             ;; for temporal preds, we may not need to re-apply these if they can be represented as a temporal range.
                             (MapEntry/create (str col-name)
                                              (expr/->expression-relation-selector select-form {:col-types col-types, :param-types param-types})))
                           (into {}))

            metadata-args (vec (for [[col-name select] selects
                                     :when (not (temporal/temporal-column? (util/str->normal-form-str (str col-name))))]
                                 select))

            row-count (->> (meta/with-all-metadata metadata-mgr normalized-table-name
                             (util/->jbifn
                               (fn [_chunk-idx ^ITableMetadata table-metadata]
                                 (let [id-col-idx (.rowIndex table-metadata "xt$id" -1)
                                       ^BigIntVector count-vec (-> (.metadataRoot table-metadata)
                                                                   ^ListVector (.getVector "columns")
                                                                   ^StructVector (.getDataVector)
                                                                   (.getChild "count"))]
                                   (.get count-vec id-col-idx)))))
                           (reduce +))]

        {:col-types col-types
         :stats {:row-count row-count}
         :->cursor (fn [{:keys [allocator, ^IWatermark watermark, basis, params default-all-valid-time?]}]
                     (let [metadata-pred (expr.meta/->metadata-selector (cons 'and metadata-args) col-types params)
                           scan-opts (cond-> scan-opts
                                       (nil? for-valid-time)
                                       (assoc :for-valid-time (if default-all-valid-time? [:all-time] [:at [:now :now]])))
                           [temporal-min-range temporal-max-range] (->temporal-min-max-range params basis scan-opts selects)]
                       (if (no-finished-chunks? metadata-mgr)
                         (->4r-cursor allocator buffer-pool
                                      watermark
                                      normalized-table-name
                                      (set/union content-col-names temporal-col-names)
                                      (->temporal-range params basis scan-opts selects)
                                      col-preds
                                      params
                                      scan-opts
                                      basis)

                         (let [current-row-ids (when (use-current-row-id-cache? watermark scan-opts basis temporal-col-names)
                                                 (get-current-row-ids watermark basis))]
                           (-> (ScanCursor. allocator metadata-mgr watermark
                                            content-col-names temporal-col-names col-preds
                                            temporal-min-range temporal-max-range current-row-ids
                                            (util/->concat-cursor (->content-chunks allocator metadata-mgr buffer-pool
                                                                                    normalized-table-name normalized-content-col-names
                                                                                    metadata-pred)
                                                                  (some-> (.liveChunk watermark)
                                                                          (.liveTable normalized-table-name)
                                                                          (.liveBlocks normalized-content-col-names metadata-pred)))
                                            params)
                               (coalesce/->coalescing-cursor allocator))))))}))))

(defmethod lp/emit-expr :scan [scan-expr {:keys [^IScanEmitter scan-emitter scan-col-types, param-types]}]
  (.emitScan scan-emitter scan-expr scan-col-types param-types))
