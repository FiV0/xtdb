(ns testing-http
  (:require [clojure.edn :as edn]
            [xtdb.api :as xt]
            [xtdb.client :as client]))

(defn normalize-entity [e]
  (-> (dissoc e :xt/id)
      (update-keys #(keyword (name %)))
      (assoc :xt/id (:xt/id e))))

(defn read-entities []
  (->> (edn/read-string (slurp "entities.edn"))
       (map normalize-entity)))

(defn entity->table [entity]
  (-> entity :xt/id namespace keyword))

(comment
  (entity->table (first entities)))

(defn ingest [node entities]
  (->> (map #(vector :put (entity->table %) %) entities)
       (partition-all 1024)
       (mapv #(xt/submit-tx node (vec %)))))

;; (def node (client/start-client "http://localhost:3000"))
(def node dev/node)

(comment
  (def entities (read-entities))
  (first entities)

  (filter #(= :artist/id-1 (:xt/id %)) entities)
  (filter #(contains? % :artist-id) entities)

  (ingest node entities)

  (xt/q node '(from :track [name composer])))


(xt/q node
      '(-> (unify (from :track [album {:name $name}])
                  (from :album [{:xt/id album} artist])
                  (from :artist [{:xt/id artist :name artist-name}]))
           (return :artist-name))
      {:args {:name "For Those About To Rock (We Salute You)"}})

;; SQL
(xt/q node
      ["SELECT ar.name AS artist_name FROM track AS t, album AS a, artist AS ar
        WHERE t.name = ? AND t.album = a.xt$id AND a.artist = ar.xt$id"
       "For Those About To Rock (We Salute You)"])


;; One can use our EE to transform some data.

(xt/q node
      '(-> (unify (from :track [album {:name "For Those About To Rock (We Salute You)"}])
                  (from :album [{:xt/id album} artist])
                  (from :artist [{:xt/id artist :name artist-name}])
                  (with {not-nil? (nil? artist-name)}))
           (return :not-nil?)))

(xt/q node
      ["SELECT ar.name FROM track AS t, album AS a, artist AS ar
        WHERE t.name = ? AND t.album = a.xt$id AND a.artist = ar.xt$id AND ar.name IS NOT NULL"
       "For Those About To Rock (We Salute You)"])
