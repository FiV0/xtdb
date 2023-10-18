(ns xtdb.vector.writer-test
  (:require [clojure.test :as t]
            [xtdb.test-util :as tu]
            [xtdb.vector.writer :as vw]
            [xtdb.types :as types])
  (:import [org.apache.arrow.vector.complex DenseUnionVector]
           (org.apache.arrow.vector.types Types$MinorType)))

(t/use-fixtures :each tu/with-allocator)

(t/deftest adding-legs-to-dense-union
  (with-open [duv (DenseUnionVector/empty "my-duv" tu/*allocator*)]
    (t/is (= (types/->field "my-duv" types/dense-union-type false
                            (types/col-type->field 'i64 :i64))

             (-> (vw/->writer duv)
                 (doto (.legWriter (.getType (types/col-type->field :i64))))
                 (.getField)))))

  (with-open [duv (DenseUnionVector/empty "my-duv" tu/*allocator*)]
    (let [duv-wtr (vw/->writer duv)
          my-list-wtr (.legWriter duv-wtr types/list-type)
          my-set-wtr (.legWriter duv-wtr types/set-type)]

      (t/is (= (types/->field "my-duv" types/dense-union-type false
                              (types/->field "list" types/list-type false
                                             (types/->field "$data$" types/dense-union-type false))

                              (types/->field "set" types/set-type false
                                             (types/->field "$data$" types/dense-union-type false)))

               (.getField duv-wtr))

            "writerForField pessimistically adds lists/sets as unions")

      (doto (.listElementWriter my-list-wtr)
        (.legWriter (.getType (types/col-type->field :i64))))

      (doto (.listElementWriter my-set-wtr)
        (.legWriter (.getType (types/col-type->field :f64))))

      (t/is (= (types/->field "my-duv" types/dense-union-type false
                              (types/->field "list" types/list-type false
                                             (types/->field "$data$" types/dense-union-type false
                                                            (types/col-type->field :i64)))

                              (types/->field "set" types/set-type false
                                             (types/->field "$data$" types/dense-union-type false
                                                            (types/col-type->field :f64))))
               (.getField duv-wtr)))

      (doto (.listElementWriter my-list-wtr)
        (.legWriter (.getType (types/col-type->field :f64))))

      (t/is (= (types/->field "my-duv" types/dense-union-type false
                              (types/->field "list" types/list-type false
                                             (types/->field "$data$" types/dense-union-type false
                                                            (types/col-type->field :i64)
                                                            (types/col-type->field :f64)))

                              (types/->field "set" types/set-type false
                                             (types/->field "$data$" types/dense-union-type false
                                                            (types/col-type->field :f64))))
               (.getField duv-wtr)))))

  (with-open [duv (DenseUnionVector/empty "my-duv" tu/*allocator*)]
    (let [duv-wtr (vw/->writer duv)
          my-struct-wtr (.legWriter duv-wtr types/struct-type)]
      (t/is (= (types/->field "my-duv" types/dense-union-type false
                              (types/->field "struct" types/struct-type false))

               (.getField duv-wtr)))

      (let [a-wtr (.structKeyWriter my-struct-wtr "a")]
        (t/is (= (types/->field "my-duv" types/dense-union-type false
                                (types/->field "struct" types/struct-type false
                                               (types/->field "a" types/dense-union-type false)))

                 (.getField duv-wtr))

              "writerForField pessimistically adds struct keys as unions")

        (.legWriter a-wtr (.getType Types$MinorType/BIGINT))

        (t/is (= (types/->field "my-duv" types/dense-union-type false
                                (types/->field "struct" types/struct-type false
                                               (types/->field "a" types/dense-union-type false
                                                              (types/->field "i64" (.getType Types$MinorType/BIGINT) false))))

                 (.getField duv-wtr)))

        (-> (.structKeyWriter my-struct-wtr "b") (.legWriter (.getType Types$MinorType/FLOAT8)))
        (-> a-wtr (.legWriter (.getType Types$MinorType/VARCHAR)))

        (t/is (= (types/->field "my-duv" types/dense-union-type false
                                (types/->field "struct" types/struct-type false
                                               (types/->field "a" types/dense-union-type false
                                                              (types/col-type->field :i64)
                                                              (types/col-type->field :utf8))
                                               (types/->field "b" types/dense-union-type false
                                                              (types/col-type->field :f64))))

                 (.getField duv-wtr)))))))
