(ns xtdb.expression-engine-doc
  (:import clojure.lang.Counted))

;; This namespace tries to give an introduction into our expression engine.
;; For the purpose of this document we are going to compile a very simple language,
;; which consists of null and number literals, if statements and
;; a let statements which contain a single binding.

;; setting up something that resembles our readers/writers
;; for a NullableLongVectorReader/Writer

;; This just simulates a mutable ArrowVector via a simple Clojure vector.

(defprotocol IVectorReader
  (^void isNull [this idx])
  (^long getLong [this idx]))

(defprotocol IVectorWriter
  (^void writeNull [this])
  (^void writeLong [this lng]))

(deftype NullableLongVectorReader [v]
  IVectorReader
  (isNull [_ idx] (nil? (nth v idx)))
  (getLong [_ idx] (if-let [lng (nth v idx)]
                     lng
                     (throw (UnsupportedOperationException.))))

  Counted
  (count [_] (count v))

  Object
  (toString [_]
    (format "NullableLongVectorReader%s" (pr-str v))))

(defn ->vec-rdr [v] (->NullableLongVectorReader v))

(deftype NullableLongVectorWriter [^:unsynchronized-mutable v]
  IVectorWriter
  (writeNull [_] (set! v (conj v nil)) nil)
  (writeLong [_ lng] (set! v (conj v lng)) nil)

  Object
  (toString [_]
    (format "NullableLongVectorWriter%s" (pr-str v))))

(defn ->vec-wrt ^NullableLongVectorWriter [] (->NullableLongVectorWriter []))

;; here I just implemented ValueBox as something implementing IVectorReader/IVectorWriter
;; In our real usage this implements ValueReader, but I didn't want to blow this implementation
;; out of proportion

(deftype ValueBox [^:unsynchronized-mutable value]
  IVectorReader
  (isNull [_ _] (nil? value))
  (getLong [_ _] (if value
                   value
                   (throw (UnsupportedOperationException.))))

  IVectorWriter
  (writeNull [_] (set! value nil))
  (writeLong [_ lng] (set! value lng))

  Object
  (toString [_]
    (format "NullableLongValueBox[%s]" (pr-str value))))

(defn ->value-box [] (->ValueBox nil))

(comment
  (def vec-rdr (->vec-rdr [1 nil 2]))
  (.isNull vec-rdr 0)
  (.isNull vec-rdr 1)

  (def vec-wrt (->vec-wrt))
  (.writeNull vec-wrt)
  (.writeLong vec-wrt 1)
  vec-wrt

  (def value-box (->value-box))
  (.isNull value-box -1)
  (.writeLong value-box 42)
  (.getLong value-box -1))


;; the language
;; we consider nil to be false
(comment
  nil
  1
  (+ 1 2)
  '(if cond if-branch else-branch)
  '(let [binding expr]
     body))

;; lets do a first iteration without vectors

;; first approach - interpreter

(defprotocol Expr
  (invoke [this env]))

(defrecord NullExpr []
  Expr
  (invoke [_ _env] nil))

(defrecord LongExpr [lng]
  Expr
  (invoke [_ _env] lng))

(defrecord VarExpr [var]
  Expr
  (invoke [_ env]
    (if (contains? env var)
      (get env var)
      (throw (UnsupportedOperationException.)))))

(defrecord PlusExpr [args]
  Expr
  (invoke [_ env]
    (let [args (map #(invoke % env) args)]
      (if (some nil? args)
        nil
        (apply + args)))))

(defrecord IfExpr [cond if-branch else-branch]
  Expr
  (invoke [_ env]
    (if (invoke cond env)
      (invoke if-branch env)
      (invoke else-branch env))))

(defrecord LetExpr [binding b-expr body]
  Expr
  (invoke [_ env]
    (invoke body (assoc env binding (invoke b-expr env)))))

(defmulti parse-expr (fn [expr] (cond (nil? expr) :nil
                                      (number? expr) :long
                                      (symbol? expr) :var
                                      (list? expr) (keyword (first expr)))))

(defmethod parse-expr :nil [_] (->NullExpr))
(defmethod parse-expr :long [lng] (->LongExpr lng))
(defmethod parse-expr :var [var] (->VarExpr var))
(defmethod parse-expr :+ [[_ & args]]
  (->PlusExpr (map parse-expr args)))
(defmethod parse-expr :if [[_ cond if-branch else-branch]]
  (->IfExpr (parse-expr cond) (parse-expr if-branch) (parse-expr else-branch)))
(defmethod parse-expr :let [[_ [binding b-expr] body]]
  (->LetExpr binding (parse-expr b-expr) (parse-expr body)))

(comment
  (-> (parse-expr (if 1 (+ 1 1) 3))
      (invoke {}))

  (-> (parse-expr (if (+ 1 2) 3 4))
      (invoke {}))

  (-> (parse-expr '(let [x (if (+ 1 2) 3 4)]
                     (+ 1 2 3)))
      (invoke {})))


;; second approach direct compiler

(def idx-sym (gensym 'idx))

(defmulti codegen-direct (fn [expr] (cond (nil? expr) :nil
                                          (number? expr) :long
                                          (symbol? expr) :var
                                          (list? expr) (keyword (first expr)))))

(defmethod codegen-direct :nil [_] nil)
(defmethod codegen-direct :long [lng] lng)
(defmethod codegen-direct :var [var]
  `(when-not (.isNull ~var ~idx-sym)
     (.getLong ~var ~idx-sym)))

(defmethod codegen-direct :+ [[_ x-expr y-expr]]
  `(if-let [x-res# ~(codegen-direct x-expr)]
     (if-let [y-res# ~(codegen-direct y-expr)]
       (Math/addExact x-res# y-res#)
       nil)
     nil))

(defmethod codegen-direct :if [[_ cond if-branch else-branch]]
  `(if ~(codegen-direct cond)
     ~(codegen-direct if-branch)
     ~(codegen-direct else-branch)))

(defmethod codegen-direct :let [[_ [binding b-expr] body]]
  `(let [~binding (->value-box)]
     (if-let [b-expr-res# ~(codegen-direct b-expr)]
       (.writeLong ~binding b-expr-res#))
     ~(codegen-direct body)))

(comment
  (codegen-direct '(if 1 (+ 1 1) 3))
  (codegen-direct '(if (+ 1 2) 3 4))
  (codegen-direct '(let [x (if (+ 1 2) 3 4)]
                     (+ 1 x)))

  )

(defn compile-expr [expr col-names]
  (-> `(fn ~(vec col-names)
         (let [res-vec# (->vec-wrt)]
           (dotimes [~idx-sym (count ~(first col-names))]
             (if-let [res# ~(codegen-direct expr)]
               (.writeLong res-vec# res#)
               (.writeNull res-vec#)))
           res-vec#))
      #_(doto clojure.pprint/pprint)
      eval))

(comment
  ((compile-expr '(+ x 1) ['x]) (->vec-rdr [1 nil 2]))

  ((compile-expr '(let [x (if (+ 1 y) 10 1)]
                    (+ 1 x))
                 ['y])
   (->vec-rdr [1 nil 2])))

;; third approach - CPS style compiler

(defmulti codegen-expr (fn [expr _cont]
                         (cond (nil? expr) :nil
                               (number? expr) :long
                               (symbol? expr) :var
                               (list? expr) (keyword (first expr)))))

(defmethod codegen-expr :nil [_ cont] (cont :nil nil))
(defmethod codegen-expr :long [lng cont] (cont :long lng))
(defmethod codegen-expr :var [var cont]
  `(if (.isNull ~var ~idx-sym)
     (:nil nil)
     ~(cont :long `(.getLong ~var ~idx-sym))))

(defmethod codegen-expr :+ [[_ x-expr y-expr] cont]
  (codegen-expr x-expr
                (fn continue-x [x-type x-code]
                  (case x-type
                    :long (codegen-expr y-expr
                                        (fn continue-y [y-type y-code]
                                          (case y-type
                                            :long (cont :long `(Math/addExact ~x-code ~y-code))
                                            :nil (cont :nil nil))))
                    :nil (cont :nil nil)))))

(defmethod codegen-expr :if [[_ cond if-branch else-branch] cont]
  `(if ~(codegen-expr cond (fn [_ x] x))
     ~(codegen-expr if-branch cont)
     ~(codegen-expr else-branch cont)))

(defmethod codegen-expr :let [[_ [binding b-expr] body] cont]
  (codegen-expr b-expr
                (fn [b-type b-code]
                  (codegen-expr body (fn [body-type body-code]
                                       (cont body-type
                                             `(let [~binding (->value-box)]
                                                (case( ~b-type)
                                                  :nil (.writeNull ~binding)
                                                  :long (.writeLong ~binding ~b-code))
                                                ~body-code)))))))


(comment
  (codegen-expr '(if 1 (+ 1 1) 3) (fn [type code] code))
  (codegen-expr '(if (+ 1 2) 3 4) (fn [type code] code))
  (codegen-expr '(let [x (if (+ 1 y) 3 4)]
                   (+ 1 x))
                (fn [type code] code)) )

(defn compile-expr2 [expr col-names]
  (let [res-vec-sym (gensym 'res-vec)]
    (-> `(fn ~(vec col-names)
           (let [~res-vec-sym (->vec-wrt)]
             (dotimes [~idx-sym (count ~(first col-names))]
               ~(codegen-expr expr
                              (fn [out-type out-code]
                                (case out-type
                                  :nil `(.writeNull ~res-vec-sym)
                                  :long `(.writeLong ~res-vec-sym ~out-code)))))
             ~res-vec-sym))
        (doto clojure.pprint/pprint)
        eval)))

(comment
  ((compile-expr2 '(+ x 1) ['x]) (->vec-rdr [1 nil 2]))

  ((compile-expr2 '(let [x (if (+ 1 y) 10 1)]
                     (+ 1 x))
                  ['y])
   (->vec-rdr [1 nil 2]))

  )
