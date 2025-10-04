(ns xtdb.trie-catalog-simulation
  (:require [xtdb.trie-catalog :as cat]
            [xtdb.trie :as trie]
            [xtdb.compactor :as c]
            [xtdb.test-util :as tu])
  (:import [java.time Instant LocalDate ZoneId DayOfWeek]
           [java.time.temporal TemporalAdjusters ChronoUnit]))

(def empty-trie-catalog {:file-size-target cat/*file-size-target*})

(defn- apply-msgs
  ([trie-keys] (apply-msgs {} trie-keys))
  ([cat trie-keys]
   (-> trie-keys
       (->> (transduce (map (fn [[trie-key size]]
                              (-> (trie/parse-trie-key trie-key)
                                  (assoc :data-file-size (or size -1)))))
                       (completing (partial cat/apply-trie-notification empty-trie-catalog))
                       cat)))))

(defn compaction-jobs [cat]
  (c/compaction-jobs "foo" cat empty-trie-catalog))

(defn rand-l0-file-size []
  (max 1 (rand-int (/ cat/*file-size-target* 10))))

(defn l0-seq
  ([n] (l0-seq "xt/txs" n))
  ([table-name n]
   (for [i (range n)]
     {:recency nil
      :part []
      :data-file-size (rand-l0-file-size)
      :table-name table-name
      :level 0
      :block-idx i
      :trie-key (trie/->l0-trie-key i)})))

(defn apply-tries
  ([tries]
   (->> tries
        (map (juxt :trie-key :data-file-size))
        (apply-msgs)))
  ([cat tries]
   (->> tries
        (map (juxt :trie-key :data-file-size))
        (apply-msgs cat))))

(defn beginning-of-week [^java.time.Instant inst]
  (-> inst
      (.atZone (ZoneId/systemDefault))
      (.toLocalDate)
      (.with (TemporalAdjusters/previousOrSame DayOfWeek/MONDAY))))

(defn subtract-weeks [^LocalDate local-date num-weeks]
  (.minus local-date num-weeks ChronoUnit/WEEKS))


(def ^:dynamic *mock-clock*)

(defn random-l1-recencies []
  (let [r (rand-int 100)
        the-week (beginning-of-week (.instant *mock-clock*))]
    (cond-> [nil]
      (< r 6) (conj the-week)
      ;; (< r 50) (conj the-week)
      ;; (< r 25) (conj (subtract-weeks the-week 1))
      ;; (< r 12) (conj (subtract-weeks the-week 2))
      ;; (< r 6) (conj (subtract-weeks the-week 3))
      )
    ))

(comment
  (binding [*mock-clock* (tu/->mock-clock (tu/->instants :day))]
    [(subtract-weeks (beginning-of-week (.instant *mock-clock*)) 2)])


  (binding [*mock-clock* (tu/->mock-clock (tu/->instants :day))]
    (random-l1-recencies)))

(defn get-random-job [cat]
  (let [{:keys [input-tries out-trie-key] :as job} (rand-nth (compaction-jobs cat))
        {:keys [level block-idx] :as out-trie} (trie/parse-trie-key (str out-trie-key))
        data-file-size (reduce + (map :data-file-size input-tries))]
    (if (= 1 level)
      (let [l1-recencies (random-l1-recencies)
            data-file-size (int (/ data-file-size (count l1-recencies)))]
        (map (fn [recency] (assoc out-trie
                                  :recency recency
                                  :data-file-size data-file-size
                                  :trie-key (trie/->l1-trie-key recency block-idx)))
             l1-recencies))
      [(assoc out-trie :data-file-size data-file-size)])))

(comment

  (def cat-only-l0 (apply-tries (l0-seq 10000)))

  (cat/current-tries cat-only-l0)

  (compaction-jobs cat-only-l0)

  (binding [*mock-clock* (tu/->mock-clock (tu/->instants :day))]
    (get-random-job cat-only-l0)

    (get-random-job (apply-tries cat-only-l0 (get-random-job cat-only-l0)))))

(defn apply-n-steps
  "Tests `system` number (2 by default) of trie catalogs initialized to `initial-cat` running for `n` steps.
   At each step a job (new trie) is applied to a random catalog. The new trie is chosen from one of the other trie catalogs
   to try to simulate systems that don't run in lockstep."
  ([initial-cat n] (apply-n-steps 2 initial-cat n))
  ([systems initial-cat n]
   (binding [*mock-clock* (tu/->mock-clock (tu/->instants :day))]
     (loop [cats (vec (repeat systems initial-cat)) n n]
       (if (zero? n)
         cats
         (let [i (rand-int (count cats)) #_(mod n (count cats))
               j (mod (inc i) (count cats))]
           (recur
            (update cats i apply-tries (get-random-job (nth cats j))) (dec n))))))))

(defn duplicates? [systems]
  (let [trie-keys (map (comp #(map :trie-key %) cat/current-tries) systems)]
    (some #(not (= (count %) (count (set %)))) trie-keys)))

(comment
  (def systems-after-1000-jobs (apply-n-steps cat-only-l0 1000))

  (duplicates? systems-after-1000-jobs)

  (def systems-after-10000-jobs (apply-n-steps cat-only-l0 10000))

  (duplicates? systems-after-10000-jobs)

  (def three-systems-after-10000-jobs (apply-n-steps 3 cat-only-l0 10000))

  (duplicates? three-systems-after-10000-jobs)

  )
