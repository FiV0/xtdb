(ns xtdb.trie.arrow-hash-trie
  (:require [xtdb.util :as util]
            [xtdb.vector.reader :as vr])
  (:import [java.lang AutoCloseable]
           [org.apache.arrow.vector VectorSchemaRoot]
           [xtdb.trie ArrowHashTrie]))

(definterface IArrowHashTrieWrapper
  (^xtdb.vector.RelationReader trieReader [])
  (^xtdb.trie.ArrowHashTrie arrowHashTrie [])
  (^String trieFile []))

(deftype ArrowHashTrieWrapper [^VectorSchemaRoot root
                               ^ArrowHashTrie trie
                               ^String trie-file]
  IArrowHashTrieWrapper
  (trieReader [_] (vr/<-root root))
  (arrowHashTrie [_] trie)
  (trieFile [_] trie-file)

  AutoCloseable
  (close [_]
    (util/try-close root)))
