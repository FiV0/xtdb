(ns test-harness.test-utils
  (:require [juxt.clojars-mirrors.integrant.core :as ig]
            [test-harness.core :as core]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def system (atom :unset))

(defn with-system [f]
  (reset! system (ig/init core/system))
  (f)
  (ig/halt! @system)
  (reset! system :unset))

(defn- get-root-path
  "Returns a path to the root of the project (with a .git directory)"
  []
  (loop [path (.getCanonicalFile (io/file "."))]
    (cond
      (= "/" (str path))
      path

      (some #(and (.isDirectory %)
                  (str/ends-with? (str %) ".git"))
            (.listFiles path))
      path

      :else (recur (.getParentFile path)))))

(def root-path (delay (get-root-path)))
