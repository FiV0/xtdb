(ns xtdb.min-count-sketch
  (:require [xtdb.memory :as mem]
            [bigml.sketchy.murmur :as murmur])
  (:import [org.agrona MutableDirectBuffer]
           [org.agrona.concurrent AtomicBuffer]))

(def min-count-sketch-size-fields-size (+ Short/BYTES Byte/BYTES))
(def ^:private offset min-count-sketch-size-fields-size)

(defn size ^long
  ([^AtomicBuffer sketch]
   (let [w (.getShortVolatile sketch 0)
         d (.getByte sketch Short/BYTES)]
     (size (long w) (long d))))
  ([w d] (+ offset (* w d Long/BYTES))))

(defn create
  ([{:keys [w d] :or {w 15 d 3}}] (create (mem/allocate-buffer (size w d)) {:w w :d d}))
  ([buf {:keys [w d]}]
   (let [s (size w d)]
     (doto ^MutableDirectBuffer buf
       (.putShort 0 (short w))
       (.putByte Short/BYTES (byte d))
       (.setMemory offset (- s offset) 0)))))

(defn- highest-bit [w]
  (loop [i 0]
    (if (zero? (bit-shift-right w i))
      i
      (recur (inc i)))))

(defn insert ^MutableDirectBuffer [^AtomicBuffer sketch val]
  (let [w (.getShortVolatile sketch 0)
        d (.getByte sketch Short/BYTES)]
    (doseq [j (range d)]
      (let [i (mod (murmur/hash val j) w)
            #_(murmur/truncate (murmur/hash val j) (highest-bit w))]
        (doto sketch
          (.getAndAddLong (+ offset (* j i Long/BYTES)) 1))))
    sketch))

(defn estimate ^long [^AtomicBuffer sketch val]
  (let [w (.getShortVolatile sketch 0)
        d (.getByte sketch Short/BYTES)
        res (->> (for [j (range d)]
                   (let [i (mod (murmur/hash val j) w)
                         #_(murmur/truncate (murmur/hash val j) (highest-bit w))]
                     (.getLongVolatile sketch (+ offset (* j i Long/BYTES)))))
                 #_(remove zero?))]
    (if (seq res)
      (reduce min res)
      0)))

(comment
  (def sktch (create {:w 256 :d 5}))
  (size sktch)
  (size (mem/slice-buffer sktch 0 offset))

  (insert sktch :foo/bar)
  (estimate sktch :foo/bar)
  (estimate sktch :foo/foo))
