(ns xtdb.types
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [xtdb.api]
            [xtdb.rewrite :refer [zmatch]]
            [xtdb.util :as util])
  (:import (clojure.lang MapEntry)
           (java.io Writer)
           (org.apache.arrow.vector.types DateUnit FloatingPointPrecision IntervalUnit TimeUnit Types$MinorType UnionMode)
           (org.apache.arrow.vector.types.pojo ArrowType ArrowType$Binary ArrowType$Bool ArrowType$Date ArrowType$Decimal ArrowType$Duration ArrowType$FixedSizeBinary ArrowType$FixedSizeList ArrowType$FloatingPoint ArrowType$Int ArrowType$Interval ArrowType$List ArrowType$Map ArrowType$Null ArrowType$Struct ArrowType$Time ArrowType$Time ArrowType$Timestamp ArrowType$Union ArrowType$Utf8 Field FieldType)
           (xtdb Types)
           (xtdb.vector.extensions IntervalMDMType KeywordType RegClassType RegProcType SetType TransitType TsTzRangeType UriType UuidType)))

(set! *unchecked-math* :warn-on-boxed)

(defn col-type-head [col-type]
  (if (vector? col-type)
    (first col-type)
    col-type))

;;;; fields

(defn arrow-type->leg ^String [^ArrowType arrow-type]
  (Types/toLeg arrow-type))

(defprotocol FromArrowType
  (<-arrow-type [arrow-type]))

(defn- date-unit->kw [unit]
  (util/case-enum unit
    DateUnit/DAY :day
    DateUnit/MILLISECOND :milli))

(defn- kw->date-unit [kw]
  (case kw
    :day DateUnit/DAY
    :milli DateUnit/MILLISECOND))

(defn- time-unit->kw [unit]
  (util/case-enum unit
    TimeUnit/SECOND :second
    TimeUnit/MILLISECOND :milli
    TimeUnit/MICROSECOND :micro
    TimeUnit/NANOSECOND :nano))

(defn- kw->time-unit [kw]
  (case kw
    :second TimeUnit/SECOND
    :milli TimeUnit/MILLISECOND
    :micro TimeUnit/MICROSECOND
    :nano TimeUnit/NANOSECOND))

(defn- interval-unit->kw [unit]
  (util/case-enum unit
    IntervalUnit/DAY_TIME :day-time
    IntervalUnit/MONTH_DAY_NANO :month-day-nano
    IntervalUnit/YEAR_MONTH :year-month))

(defn- kw->interval-unit [kw]
  (case kw
    :day-time IntervalUnit/DAY_TIME
    :month-day-nano IntervalUnit/MONTH_DAY_NANO
    :year-month IntervalUnit/YEAR_MONTH))

(extend-protocol FromArrowType
  ArrowType$Null (<-arrow-type [_] :null)
  ArrowType$Bool (<-arrow-type [_] :bool)

  ArrowType$FloatingPoint
  (<-arrow-type [arrow-type]
    (util/case-enum (.getPrecision arrow-type)
      FloatingPointPrecision/SINGLE :f32
      FloatingPointPrecision/DOUBLE :f64))

  ArrowType$Int
  (<-arrow-type [arrow-type]
    (if (.getIsSigned arrow-type)
      (case (.getBitWidth arrow-type)
        8 :i8, 16 :i16, 32 :i32, 64 :i64)

      (throw (UnsupportedOperationException. "unsigned ints"))))

  ArrowType$Utf8 (<-arrow-type [_] :utf8)
  ArrowType$Binary (<-arrow-type [_] :varbinary)
  ArrowType$FixedSizeBinary (<-arrow-type [arrow-type] [:fixed-size-binary (.getByteWidth arrow-type)])

  ArrowType$Decimal
  (<-arrow-type [arrow-type]
    [:decimal (.getPrecision arrow-type) (.getScale arrow-type) (.getBitWidth arrow-type)])

  ArrowType$FixedSizeList (<-arrow-type [arrow-type] [:fixed-size-list (.getListSize arrow-type)])

  ArrowType$Timestamp
  (<-arrow-type [arrow-type]
    (let [time-unit (time-unit->kw (.getUnit arrow-type))]
      (if-let [tz (.getTimezone arrow-type)]
        [:timestamp-tz time-unit tz]
        [:timestamp-local time-unit])))

  ArrowType$Date (<-arrow-type [arrow-type] [:date (date-unit->kw (.getUnit arrow-type))])
  ArrowType$Time (<-arrow-type [arrow-type] [:time-local (time-unit->kw (.getUnit arrow-type))])
  ArrowType$Duration (<-arrow-type [arrow-type] [:duration (time-unit->kw (.getUnit arrow-type))])
  ArrowType$Interval (<-arrow-type [arrow-type] [:interval (interval-unit->kw (.getUnit arrow-type))])
  IntervalMDMType (<-arrow-type [_arrow-type] [:interval :month-day-micro])

  ArrowType$Struct (<-arrow-type [_] :struct)
  ArrowType$List (<-arrow-type [_] :list)
  SetType (<-arrow-type [_] :set)
  ArrowType$Map (<-arrow-type [arrow-type] [:map {:sorted? (.getKeysSorted arrow-type)}])

  ArrowType$Union
  (<-arrow-type [^ArrowType$Union arrow-type]
    (if (= UnionMode/Dense (.getMode arrow-type)) :union :sparse-union))

  TsTzRangeType (<-arrow-type [_] :tstz-range)
  KeywordType (<-arrow-type [_] :keyword)
  RegClassType (<-arrow-type [_] :regclass)
  RegProcType (<-arrow-type [_] :regproc)
  UuidType (<-arrow-type [_] :uuid)
  UriType (<-arrow-type [_] :uri)
  TransitType (<-arrow-type [_] :transit))

(defn ->arrow-type ^org.apache.arrow.vector.types.pojo.ArrowType [col-type]
  (case col-type
    :null ArrowType$Null/INSTANCE
    :bool ArrowType$Bool/INSTANCE

    :i8 (.getType Types$MinorType/TINYINT)
    :i16 (.getType Types$MinorType/SMALLINT)
    :i32 (.getType Types$MinorType/INT)
    :i64 (.getType Types$MinorType/BIGINT)
    :f32 (.getType Types$MinorType/FLOAT4)
    :f64 (.getType Types$MinorType/FLOAT8)

    :utf8 (.getType Types$MinorType/VARCHAR)
    :varbinary (.getType Types$MinorType/VARBINARY)

    :tstz-range TsTzRangeType/INSTANCE
    :keyword KeywordType/INSTANCE
    :regclass RegClassType/INSTANCE
    :regproc RegProcType/INSTANCE
    :uuid UuidType/INSTANCE
    :uri UriType/INSTANCE
    :transit TransitType/INSTANCE

    :struct ArrowType$Struct/INSTANCE
    :list ArrowType$List/INSTANCE
    :set SetType/INSTANCE
    :union (.getType Types$MinorType/DENSEUNION)
    :sparse-union (.getType Types$MinorType/UNION)

    (case (col-type-head col-type)
      :struct ArrowType$Struct/INSTANCE
      :list ArrowType$List/INSTANCE
      :set SetType/INSTANCE
      :map (let [[_ {:keys [sorted?]}] col-type]
             (ArrowType$Map. (boolean sorted?)))
      :union (.getType Types$MinorType/DENSEUNION)
      :sparse-union (.getType Types$MinorType/UNION)

      :date (let [[_ date-unit] col-type]
              (ArrowType$Date. (kw->date-unit date-unit)))

      :decimal (let [[_ precision scale bitwidth] col-type]
                 (ArrowType$Decimal/createDecimal precision scale (int bitwidth)))

      :timestamp-tz (let [[_ time-unit tz] col-type]
                      (ArrowType$Timestamp. (kw->time-unit time-unit) tz))

      :timestamp-local (let [[_ time-unit] col-type]
                         (ArrowType$Timestamp. (kw->time-unit time-unit) nil))

      :time-local (let [[_ time-unit] col-type]
                    (ArrowType$Time. (kw->time-unit time-unit)
                                     (case time-unit (:second :milli) 32, (:micro :nano) 64)))

      :duration (let [[_ time-unit] col-type]
                  (ArrowType$Duration. (kw->time-unit time-unit)))

      :interval (let [[_ interval-unit] col-type]
                  (if (= :month-day-micro interval-unit)
                    IntervalMDMType/INSTANCE
                    (ArrowType$Interval. (kw->interval-unit interval-unit))))

      :fixed-size-list (let [[_ list-size] col-type]
                         (ArrowType$FixedSizeList. list-size))

      :fixed-size-binary (let [[_ byte-width] col-type]
                           (ArrowType$FixedSizeBinary. byte-width)))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]} ; xt.arrow/field-type reader macro
(defn ->field-type ^org.apache.arrow.vector.types.pojo.FieldType [[arrow-type nullable? dictionary metadata]]
  (FieldType. nullable? arrow-type dictionary metadata))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]} ; xt.arrow/field reader macro
(defn ->field* ^org.apache.arrow.vector.types.pojo.Field [[field-name field-type & children]]
  (Field. field-name field-type children))

(defmethod print-dup ArrowType [arrow-type, ^Writer w]
  (.write w "#xt.arrow/type ")
  (.write w (pr-str (<-arrow-type arrow-type))))

(defmethod print-method ArrowType [arrow-type w]
  (print-dup arrow-type w))

(defmethod print-dup FieldType [^FieldType field-type, ^Writer w]
  (.write w "#xt.arrow/field-type [")
  (print-method (.getType field-type) w)
  (.write w (if (.isNullable field-type)
              " true" " false"))
  (when-let [dictionary (.getDictionary field-type)]
    (.write w " ")
    (.write w (pr-str dictionary)))
  (when-let [metadata (.getDictionary field-type)]
    (.write w " ")
    (print-method metadata w))
  (.write w "]"))

(defmethod print-method FieldType [field-type w]
  (print-dup field-type w))

(defmethod print-dup Field [^Field field, ^Writer w]
  (.write w "#xt.arrow/field [")
  (.write w (pr-str (.getName field)))
  (.write w " ")
  (print-method (.getFieldType field) w)
  (when-let [children (seq (.getChildren field))]
    (doseq [^Field child-field children]
      (.write w " ")
      (print-method child-field w)))
  (.write w "]"))

(defmethod print-method Field [field w]
  (print-dup field w))


(def temporal-col-type [:timestamp-tz :micro "UTC"])
(def nullable-temporal-type [:union #{:null temporal-col-type}])
(def temporal-arrow-type (->arrow-type [:timestamp-tz :micro "UTC"]))
(def nullable-temporal-field-type (FieldType/nullable temporal-arrow-type))

(def temporal-col-types
  {"_iid" [:fixed-size-binary 16],
   "_system_from" temporal-col-type, "_system_to" nullable-temporal-type
   "_valid_from" temporal-col-type, "_valid_to" nullable-temporal-type})

(defn temporal-column? [col-name]
  (contains? temporal-col-types (str col-name)))

(defn ->field ^org.apache.arrow.vector.types.pojo.Field [^String field-name ^ArrowType arrow-type nullable & children]
  (Field. field-name (FieldType. nullable arrow-type nil nil) children))

(defn field-with-name ^org.apache.arrow.vector.types.pojo.Field [^Field field, name]
  (Field. name (.getFieldType field) (.getChildren field)))

(defn ->field-default-name ^org.apache.arrow.vector.types.pojo.Field [^ArrowType arrow-type nullable children]
  (apply ->field (name (arrow-type->leg arrow-type)) arrow-type nullable children))

(defn ->canonical-field ^org.apache.arrow.vector.types.pojo.Field [^Field field]
  (->field-default-name (.getType field) (.isNullable field) (.getChildren field)))

(defn ->nullable-field ^org.apache.arrow.vector.types.pojo.Field [^Field field]
  (if (.isNullable field) field (apply ->field (.getName field) (.getType field) true (.getChildren field))))

;;;; col-types

(defn union? [col-type]
  (= :union (col-type-head col-type)))

(def col-type-hierarchy
  (-> (make-hierarchy)
      (derive :null :any)
      (derive :bool :any)

      (derive :f32 :float) (derive :f64 :float)
      (derive :i8 :int) (derive :i16 :int) (derive :i32 :int) (derive :i64 :int)
      (derive :u8 :uint) (derive :u16 :uint) (derive :u32 :uint) (derive :u64 :uint)

      (derive :uint :num) (derive :int :num) (derive :float :num) (derive :decimal :num)
      (derive :num :any)

      (derive :date-time :any)

      (derive :timestamp-tz :date-time) (derive :timestamp-local :date-time) (derive :date :date-time)
      (derive :time-local :any) (derive :interval :any) (derive :duration :any)
      (derive :fixed-size-binary :varbinary) (derive :varbinary :any) (derive :utf8 :any)

      (derive :tstz-range :any)

      (derive :keyword :any) (derive :uri :any) (derive :uuid :any) (derive :transit :any)

      (derive :regclass :regoid) (derive :regproc :regoid) (derive :regoid :any)

      (derive :list :any) (derive :struct :any) (derive :set :any) (derive :map :any)))

(defn flatten-union-types [col-type]
  (if (= :union (col-type-head col-type))
    (second col-type)
    #{col-type}))

(defn flatten-union-field [^Field field]
  (if (instance? ArrowType$Union (.getType field))
    (.getChildren field)
    [field]))

(defn merge-col-types [& col-types]
  (letfn [(merge-col-type* [acc col-type]
            (zmatch col-type
              [:union inner-types] (reduce merge-col-type* acc inner-types)
              [:list inner-type] (update acc :list merge-col-type* inner-type)
              [:set inner-type] (update acc :set merge-col-type* inner-type)
              [:fixed-size-list el-count inner-type] (update acc [:fixed-size-list el-count] merge-col-type* inner-type)
              [:struct struct-col-types] (update acc :struct
                                                 (fn [acc]
                                                   (let [default-col-type (if acc {:null nil} nil)]
                                                     (as-> acc acc
                                                           (reduce-kv (fn [acc col-name col-type]
                                                                        (update acc col-name (fnil merge-col-type* default-col-type) col-type))
                                                                      acc
                                                                      struct-col-types)
                                                           (reduce (fn [acc null-k]
                                                                     (update acc null-k merge-col-type* :null))
                                                                   acc
                                                                   (set/difference (set (keys acc))
                                                                                   (set (keys struct-col-types))))))))
              (assoc acc col-type nil)))

          (kv->col-type [[head opts]]
            (case (if (vector? head) (first head) head)
              :list [:list (map->col-type opts)]
              :set [:set (map->col-type opts)]
              :fixed-size-list [:fixed-size-list (second head) (map->col-type opts)]
              :struct [:struct (->> (for [[col-name col-type-map] opts]
                                      (MapEntry/create col-name (map->col-type col-type-map)))
                                    (into {}))]
              head))

          (map->col-type [col-type-map]
            (case (count col-type-map)
              0 :null
              1 (kv->col-type (first col-type-map))
              [:union (into #{} (map kv->col-type) col-type-map)]))]

    (-> (transduce (comp (remove nil?) (distinct)) (completing merge-col-type*) {} col-types)
        (map->col-type))))

(def ^Field null-field (->field "null" ArrowType$Null/INSTANCE true))

;; beware that anywhere this is used, naming of the fields (apart from struct subfields) should not matter
(defn merge-fields* [& fields]
  (letfn [(merge-field* [acc ^Field field]
            (let [arrow-type (.getType field)]
              (if (instance? ArrowType$Union arrow-type)
                (reduce merge-field* acc (.getChildren field))

                (-> acc
                    (update arrow-type
                            (fn [{:keys [nullable?] :as type-opts}]
                              (into {:nullable? (or nullable? (.isNullable field))}
                                    (condp = (class arrow-type)
                                      ArrowType$List {:el (merge-field* (:el type-opts) (first (.getChildren field)))}
                                      SetType {:el (merge-field* (:el type-opts) (first (.getChildren field)))}
                                      ArrowType$FixedSizeList {:el (merge-field* (:el type-opts) (first (.getChildren field)))}
                                      ArrowType$Struct {:fields (let [default-field-mapping (if type-opts {#xt.arrow/type :null {:nullable? true}} nil)
                                                                      children (.getChildren field)]
                                                                  (as-> (:fields type-opts) fields-acc
                                                                    (reduce (fn [field-acc ^Field field]
                                                                              (update field-acc (.getName field) (fnil merge-field* default-field-mapping) field))
                                                                            fields-acc
                                                                            children)
                                                                    (reduce (fn [field-acc null-k]
                                                                              (update field-acc null-k merge-field* null-field))
                                                                            fields-acc
                                                                            (set/difference (set (keys fields-acc))
                                                                                            (set (map #(.getName ^Field %) children))))))}
                                      {}))))))))

          (kv->field [[arrow-type {:keys [nullable?] :as type-opts}]]
            (condp instance? arrow-type
              ArrowType$List (->field-default-name arrow-type nullable? [(-> (map->field (:el type-opts))
                                                                             (field-with-name "$data$"))])
              SetType (->field-default-name arrow-type nullable? [(-> (map->field (:el type-opts))
                                                                      (field-with-name "$data$"))])
              ArrowType$FixedSizeList (->field-default-name arrow-type nullable? [(-> (map->field (:el type-opts))
                                                                                      (field-with-name "$data$"))])
              ArrowType$Struct (->field-default-name arrow-type nullable?
                                                     (map (fn [[name opts]]
                                                            (let [^Field field (map->field opts)]
                                                              (apply ->field name (.getType field) (.isNullable field)
                                                                     (cond->> (.getChildren field)
                                                                       (not= ArrowType$Struct (class (.getType field)))
                                                                       (map ->canonical-field)))))
                                                          (:fields type-opts)))

              ArrowType$Null (->field-default-name #xt.arrow/type :null true nil)

              TsTzRangeType (->field-default-name #xt.arrow/type :tstz-range nullable?
                                                  [(->field "$data" temporal-arrow-type false)])

              (->field-default-name arrow-type nullable? nil)))

          (map->field [arrow-type-map]
            (let [without-null (dissoc arrow-type-map #xt.arrow/type :null)
                  nullable? (contains? arrow-type-map #xt.arrow/type :null)]
              (case (count without-null)
                0 null-field
                1 (kv->field (let [[arrow-type type-opts] (first without-null)]
                               [arrow-type (cond-> type-opts
                                             nullable? (assoc :nullable? true))]))

                (->field-default-name #xt.arrow/type :union false (map kv->field arrow-type-map)))))]

    (-> (transduce (comp (remove nil?) (distinct)) (completing merge-field*) {} fields)
        (map->field)
        (cond-> (some identity fields) (field-with-name (.getName ^Field (some identity fields)))))))

(def merge-fields (util/lru-memoize merge-fields*))

;;; time units

(defn ts-units-per-second ^long [time-unit]
  (case time-unit
    :second 1
    :milli #=(long 1e3)
    :micro #=(long 1e6)
    :nano #=(long 1e9)))

(defn smallest-ts-unit
  ([x-unit y-unit]
   (if (> (ts-units-per-second x-unit) (ts-units-per-second y-unit))
     x-unit
     y-unit))

  ([x-unit y-unit & more]
   (reduce smallest-ts-unit (smallest-ts-unit x-unit y-unit) more)))

;;; multis

;; HACK not ideal that end-users would need to extend all of these
;; to add their own extension types

(defmulti ^String col-type->field-name col-type-head, :default ::default, :hierarchy #'col-type-hierarchy)
(defmethod col-type->field-name ::default [col-type] (name (col-type-head col-type)))

#_{:clj-kondo/ignore [:unused-binding]}
(defmulti col-type->field*
  (fn [col-name nullable? col-type]
    (col-type-head col-type))
  :default ::default, :hierarchy #'col-type-hierarchy)

(alter-meta! #'col-type->field* assoc :tag Field)

(defmethod col-type->field* ::default [col-name nullable? col-type]
  (let [^Types$MinorType
        minor-type (case (col-type-head col-type)
                     :null Types$MinorType/NULL, :bool Types$MinorType/BIT
                     :f32 Types$MinorType/FLOAT4, :f64 Types$MinorType/FLOAT8
                     :i8 Types$MinorType/TINYINT, :i16 Types$MinorType/SMALLINT, :i32 Types$MinorType/INT, :i64 Types$MinorType/BIGINT
                     :utf8 Types$MinorType/VARCHAR, :varbinary Types$MinorType/VARBINARY)]
    (->field col-name (.getType minor-type) (or nullable? (= col-type :null)))))

(defmethod col-type->field* :decimal [col-name nullable? [_ precision scale bit-width]]
  (->field col-name (ArrowType$Decimal/createDecimal precision scale (int bit-width)) nullable?))

(defmethod col-type->field-name :decimal [[type-head precision scale bit-width :as v]]
  (str (name type-head) "-" precision "-" scale "-" bit-width))

(defmethod col-type->field* :tstz-range [col-name nullable? _col-type]
  (->field col-name TsTzRangeType/INSTANCE nullable?
           (->field "$data" temporal-arrow-type false)))

(defmethod col-type->field* :keyword [col-name nullable? _col-type]
  (->field col-name KeywordType/INSTANCE nullable?))

(defmethod col-type->field* :regclass [col-name nullable? _col-type]
  (->field col-name RegClassType/INSTANCE nullable?))

(defmethod col-type->field* :regproc [col-name nullable? _col-type]
  (->field col-name RegProcType/INSTANCE nullable?))

(defmethod col-type->field* :uuid [col-name nullable? _col-type]
  (->field col-name UuidType/INSTANCE nullable?))

(defmethod col-type->field* :uri [col-name nullable? _col-type]
  (->field col-name UriType/INSTANCE nullable?))

(defmethod col-type->field* :transit [col-name nullable? _col-type]
  (->field col-name TransitType/INSTANCE nullable?))

(defn col-type->field
  (^org.apache.arrow.vector.types.pojo.Field [col-type] (col-type->field (col-type->field-name col-type) col-type))
  (^org.apache.arrow.vector.types.pojo.Field [col-name col-type] (col-type->field* (str col-name) false col-type)))

(defn col-type->leg [col-type]
  (let [head (col-type-head col-type)]
    (case head
      (:struct :list :set :map) (str (symbol head))
      :union (let [without-null (-> (flatten-union-types col-type)
                                    (disj :null))]
               (if (= 1 (count without-null))
                 (recur (first without-null))
                 (name :union)))
      (col-type->field-name col-type))))

#_{:clj-kondo/ignore [:unused-binding]}
(defmulti arrow-type->col-type
  (fn [arrow-type & child-fields]
    (class arrow-type)))

(defmethod arrow-type->col-type ArrowType$Null [_] :null)
(defmethod arrow-type->col-type ArrowType$Bool [_] :bool)
(defmethod arrow-type->col-type ArrowType$Utf8 [_] :utf8)
(defmethod arrow-type->col-type ArrowType$Binary [_] :varbinary)
(defmethod arrow-type->col-type ArrowType$Decimal  [^ArrowType$Decimal arrow-type]
  [:decimal (.getPrecision arrow-type) (.getScale arrow-type) (.getBitWidth arrow-type)])

(defn col-type->nullable-col-type [col-type]
  (zmatch col-type
    [:union inner-types] [:union (conj inner-types :null)]
    :null :null
    [:union #{:null col-type}]))

(defn field->col-type [^Field field]
  (let [inner-type (apply arrow-type->col-type (.getType field) (.getChildren field))]
    (cond-> inner-type
      (.isNullable field) col-type->nullable-col-type)))

;;; fixed size binary

(defmethod arrow-type->col-type ArrowType$FixedSizeBinary [^ArrowType$FixedSizeBinary fsb-type]
  [:fixed-size-binary (.getByteWidth fsb-type)])

(defmethod col-type->field* :fixed-size-binary [col-name nullable? [_ byte-width]]
  (->field col-name (ArrowType$FixedSizeBinary. byte-width) nullable?))

;;; list


(defmethod col-type->field* :list [col-name nullable? [_ inner-col-type]]
  (->field col-name ArrowType$List/INSTANCE nullable?
           (col-type->field "$data$" inner-col-type)))

(defmethod arrow-type->col-type ArrowType$List [_ data-field]
  [:list (field->col-type data-field)])

(defmethod col-type->field* :fixed-size-list [col-name nullable? [_ list-size inner-col-type]]
  (->field col-name (ArrowType$FixedSizeList. list-size) nullable?
           (col-type->field inner-col-type)))

(defmethod arrow-type->col-type ArrowType$FixedSizeList [^ArrowType$FixedSizeList list-type, data-field]
  [:fixed-size-list (.getListSize list-type) (field->col-type data-field)])

(defmethod col-type->field* :set [col-name nullable? [_ inner-col-type]]
  (->field col-name SetType/INSTANCE nullable?
           (col-type->field "$data$" inner-col-type)))

(defmethod arrow-type->col-type SetType [_ data-field]
  [:set (field->col-type data-field)])

(defmethod col-type->field* :map [col-name nullable? [_ {:keys [sorted?]} inner-col-type]]
  (->field col-name (ArrowType$Map. (boolean sorted?)) nullable?
           (col-type->field inner-col-type)))

(defmethod arrow-type->col-type ArrowType$Map [^ArrowType$Map arrow-field data-field]
  [:map {:sorted? (.getKeysSorted arrow-field)} (field->col-type data-field)])

(defn unnest-field [^Field field]
  (condp = (class (.getType field))
    ArrowType$List (first (.getChildren field))
    SetType (first (.getChildren field))
    ArrowType$FixedSizeList (first (.getChildren field))
    (col-type->field :null)))

;;; struct

(defmethod col-type->field* :struct [col-name nullable? [_ inner-col-types]]
  (apply ->field col-name ArrowType$Struct/INSTANCE nullable?
         (for [[col-name col-type] inner-col-types]
           (col-type->field col-name col-type))))

(defmethod arrow-type->col-type ArrowType$Struct [_ & child-fields]
  [:struct (->> (for [^Field child-field child-fields]
                  [(symbol (.getName child-field)) (field->col-type child-field)])
                (into {}))])

;;; union

(defmethod col-type->field* :union [col-name nullable? col-type]
  (let [col-types (cond-> (flatten-union-types col-type)
                    nullable? (conj :null))
        nullable? (contains? col-types :null)
        without-null (disj col-types :null)]
    (case (count without-null)
      0 (col-type->field* col-name true :null)
      1 (col-type->field* col-name nullable? (first without-null))

      (apply ->field col-name (.getType Types$MinorType/DENSEUNION) false
             (map col-type->field col-types)))))

(defmethod arrow-type->col-type ArrowType$Union [_ & child-fields]
  (->> child-fields
       (into #{} (map field->col-type))
       (apply merge-col-types)))


;;; number

(defmethod arrow-type->col-type ArrowType$Int [^ArrowType$Int arrow-type]
  (if (.getIsSigned arrow-type)
    (case (.getBitWidth arrow-type)
      8 :i8, 16 :i16, 32 :i32, 64 :i64)
    (case (.getBitWidth arrow-type)
      8 :u8, 16 :u16, 32 :u32, 64 :u64)))

(defmethod arrow-type->col-type ArrowType$FloatingPoint [^ArrowType$FloatingPoint arrow-type]
  (util/case-enum (.getPrecision arrow-type)
    FloatingPointPrecision/HALF :f16
    FloatingPointPrecision/SINGLE :f32
    FloatingPointPrecision/DOUBLE :f64))

;;; timestamp

(defmethod col-type->field-name :timestamp-tz [[type-head time-unit tz]]
  (str (name type-head) "-" (name time-unit) "-" (-> (str/lower-case tz) (str/replace #"[/:]" "_"))))

(defmethod col-type->field* :timestamp-tz [col-name nullable? [_type-head time-unit tz]]
  (assert (string? tz) )
  (->field col-name (ArrowType$Timestamp. (kw->time-unit time-unit) tz) nullable?))

(defmethod col-type->field-name :timestamp-local [[type-head time-unit]]
  (str (name type-head) "-" (name time-unit)))

(defmethod col-type->field* :timestamp-local [col-name nullable? [_type-head time-unit]]
  (->field col-name (ArrowType$Timestamp. (kw->time-unit time-unit) nil) nullable?))

(defmethod arrow-type->col-type ArrowType$Timestamp [^ArrowType$Timestamp arrow-type]
  (let [time-unit (time-unit->kw (.getUnit arrow-type))]
    (if-let [tz (.getTimezone arrow-type)]
      [:timestamp-tz time-unit tz]
      [:timestamp-local time-unit])))

;;; date

(defmethod col-type->field-name :date [[type-head date-unit]]
  (str (name type-head) "-" (name date-unit)))

(defmethod col-type->field* :date [col-name nullable? [_type-head date-unit]]
  (->field col-name
           (ArrowType$Date. (kw->date-unit date-unit))
           nullable?))

(defmethod arrow-type->col-type ArrowType$Date [^ArrowType$Date arrow-type]
  [:date (date-unit->kw (.getUnit arrow-type))])

;;; time

(defmethod col-type->field-name :time-local [[type-head time-unit]]
  (str (name type-head) "-" (name time-unit)))

(defmethod col-type->field* :time-local [col-name nullable? [_type-head time-unit]]
  (->field col-name
           (ArrowType$Time. (kw->time-unit time-unit)
                            (case time-unit (:second :milli) 32, (:micro :nano) 64))
           nullable?))

(defmethod arrow-type->col-type ArrowType$Time [^ArrowType$Time arrow-type]
  [:time-local (time-unit->kw (.getUnit arrow-type))])

;;; duration

(defmethod col-type->field-name :duration [[type-head time-unit]]
  (str (name type-head) "-" (name time-unit)))

(defmethod col-type->field* :duration [col-name nullable? [_type-head time-unit]]
  (->field col-name (ArrowType$Duration. (kw->time-unit time-unit)) nullable?))

(defmethod arrow-type->col-type ArrowType$Duration [^ArrowType$Duration arrow-type]
  [:duration (time-unit->kw (.getUnit arrow-type))])

;;; interval

(defmethod col-type->field-name :interval [[type-head interval-unit]]
  (str (name type-head) "-" (name interval-unit)))

(defmethod col-type->field* :interval [col-name nullable? [_type-head interval-unit]]
  (->field
   col-name
   (if (= :month-day-micro interval-unit)
     IntervalMDMType/INSTANCE
     (ArrowType$Interval. (kw->interval-unit interval-unit))) nullable?))

(defmethod arrow-type->col-type ArrowType$Interval [^ArrowType$Interval arrow-type]
  [:interval (interval-unit->kw (.getUnit arrow-type))])

;;; extension types

(defmethod arrow-type->col-type TsTzRangeType [_ _] :tstz-range)
(defmethod arrow-type->col-type KeywordType [_] :keyword)
(defmethod arrow-type->col-type RegClassType [_] :regclass)
(defmethod arrow-type->col-type RegProcType [_] :regproc)
(defmethod arrow-type->col-type UriType [_] :uri)
(defmethod arrow-type->col-type UuidType [_] :uuid)
(defmethod arrow-type->col-type TransitType [_] :transit)
(defmethod arrow-type->col-type IntervalMDMType [_] [:interval :month-day-micro])

;;; LUB

(defmulti least-upper-bound2
  (fn [x-type y-type] [(col-type-head x-type) (col-type-head y-type)])
  :hierarchy #'col-type-hierarchy)

;; HACK
;; the decimal widening to other types currently only works without
;; casting elsewhere because we are using Clojure's polymorphic functions
;; or the Math/* functions cast things to the correct type.
(def widening-hierarchy
  (-> col-type-hierarchy
      (derive :i8 :i16) (derive :i16 :i32) (derive :i32 :i64)
      (derive :decimal :float) (derive :decimal :f64) (derive :decimal :f32)
      (derive :f32 :f64)
      (derive :int :f64) (derive :int :f32)
      (derive :int :decimal) (derive :uint :decimal)))

(defmethod least-upper-bound2 [:num :num] [x-type y-type]
  (let [x-head-type (col-type-head x-type)
        y-head-type (col-type-head y-type)]
    (cond
      (isa? widening-hierarchy x-head-type y-head-type) y-type
      (isa? widening-hierarchy y-head-type x-head-type) x-type
      :else :any)))

(defmethod least-upper-bound2 [:duration :duration] [[_ x-unit] [_ y-unit]]
  [:duration (smallest-ts-unit x-unit y-unit)])

(defmethod least-upper-bound2 [:date :date] [_ _] [:date :day])

(defmethod least-upper-bound2 [:timestamp-local :timestamp-local] [[_ x-unit] [_ y-unit]]
  [:timestamp-local (smallest-ts-unit x-unit y-unit)])

(defmethod least-upper-bound2 [:time-local :time-local] [[_ x-unit] [_ y-unit]]
  [:time-local (smallest-ts-unit x-unit y-unit)])

(defmethod least-upper-bound2 [:timestamp-tz :timestamp-tz] [[_ x-unit x-tz] [_ y-unit y-tz]]
  [:timestamp-tz (smallest-ts-unit x-unit y-unit) (if (= x-tz y-tz) x-tz "Z")])

(defmethod least-upper-bound2 :default [_ _] :any)

(defn least-upper-bound [col-types]
  (reduce (fn
            ([] :null)
            ([lub col-type]
             (if (= lub col-type)
               lub
               (least-upper-bound2 lub col-type))))
          col-types))

(defn with-nullable-fields [fields]
  (->> fields
       (into {} (map (juxt key (comp #(merge-fields % null-field) val))))))

(defn remove-nulls
  [typ]
  (zmatch typ
    [:union inner-types] (let [res (->> (disj inner-types :null)
                                        (into #{} (map remove-nulls)))]
                           (if (<= (count res) 1)
                             (first res)
                             [:union res]))
    [:list inner-type] [:list (remove-nulls inner-type)]
    [:set inner-type] [:set (remove-nulls inner-type)]
    [:struct field-map] [:struct (zipmap (keys field-map)
                                         (map remove-nulls (vals field-map)))]
    typ))
