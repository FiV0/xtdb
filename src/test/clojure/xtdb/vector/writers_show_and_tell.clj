(ns xtdb.vector.writers-show-and-tell
  (:require [xtdb.vector.writer :as vw]
            [xtdb.types :as types])
  (:import (org.apache.arrow.vector.complex DenseUnionVector StructVector ListVector)
           (org.apache.arrow.memory RootAllocator)
           (org.apache.arrow.vector.types.pojo FieldType)))

;; Arrow
;; 3 kind of "types"

;; Arrow type
#xt.arrow/type :i64
;; => #xt.arrow/type :i64
#xt.arrow/type :union

;; Field Type
(FieldType/notNullable #xt.arrow/type :i64)

(FieldType/nullable #xt.arrow/type :union)

;; Fields : name + nesting
(types/->field "my-int" #xt.arrow/type :i64 true)
;; => <Field "foo" #xt.arrow/type :i64 null>

(types/->field "my-union" #xt.arrow/type :union false
               (types/->field "my-int" #xt.arrow/type :i64 true))
;; => <Field "my-union" #xt.arrow/type :union not-null <Field "my-int" #xt.arrow/type :i64 null>>

;; Our two use cases:
;; - fixed schema, the schema shouldn't change underneath you
;; - dynamic reading/writing/copying

;; The main "problems" cases arise for composite fields

;; Fixed schema vs dynamic data

(with-open [struct-vec (.createVector (types/col-type->field "my-struct" '[:struct {foo :i64}]) (RootAllocator.))]
  (let [struct-wtr (vw/->writer struct-vec)]

    ;; (.getField struct-wtr)

    (-> struct-wtr
        (.structKeyWriter "foo")
        (.getField))

    (-> struct-wtr
        (.structKeyWriter "foo" (FieldType/notNullable #xt.arrow/type :i64))
        (.getField))

    #_(-> struct-wtr
          (.structKeyWriter "foo" (FieldType/notNullable #xt.arrow/type :utf8))
          (.getField))

    (-> struct-wtr
        (.structKeyWriter "bar")
        (.getField))

    #_(-> struct-wtr
          (.structKeyWriter "bar" (FieldType/notNullable #xt.arrow/type :i64))
          (.getField))

    (-> struct-wtr
        (.structKeyWriter "bar" (FieldType/notNullable #xt.arrow/type :union))
        (.getField))

    #_(let [bar-wrt (.structKeyWriter struct-wtr "bar")
            my-doc-wrt (.legWriter bar-wrt #xt.arrow/type :struct)
            my-int-wrt (.legWriter bar-wrt #xt.arrow/type :i64)]

        (vw/write-value! {:some :doc} my-doc-wrt)
        (vw/write-value! 64 my-int-wrt))))
