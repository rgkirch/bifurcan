(ns bifurcan.durable-test
  (:require
   [primitive-math :as p]
   [byte-streams :as bs]
   [clojure.edn :as edn]
   [clojure.test :refer :all]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [clojure.test.check.clojure-test :as ct :refer [defspec]]
   [bifurcan.collection-test :as coll]
   [bifurcan.test-utils :as u :refer [iterations]])
  (:import
   [java.nio
    ByteBuffer]
   [io.lacuna.bifurcan
    Map
    List
    Maps
    IEntry
    DurableInput
    DurableOutput
    DurableMap
    DurableList]
   [io.lacuna.bifurcan.hash
    PerlHash]
   [io.lacuna.bifurcan.encodings
    SelfDescribing]
   [io.lacuna.bifurcan.durable.blocks
    HashMap
    HashTable
    HashTable$Entry
    HashTable$Writer
    SkipTable
    SkipTable$Writer
    SkipTable$Entry]
   [io.lacuna.bifurcan.durable
    Util
    BlockPrefix
    BlockPrefix$BlockType
    DurableAccumulator
    ChunkSort]))

(set! *warn-on-reflection* true)

(defn ->to-int-fn [f]
  (reify java.util.function.ToIntFunction
    (applyAsInt [_ x]
      (f x))))

(defn ->fn [f]
  (reify java.util.function.Function
    (apply [_ x]
      (f x))))

(defn ->bi-consumer [f]
  (reify java.util.function.BiConsumer
    (accept [_ a b]
      (f a b))))

(def gen-pos-int
  (gen/such-that
    #(not= 0 %)
    (gen/fmap
      #(Math/abs (p/int %))
      gen/large-integer)))

(def gen-small-pos-int
  (gen/such-that
    #(not= 0 %)
    (gen/fmap
      #(Math/abs (p/int %))
      gen/int)))

(def edn-encoding
  (SelfDescribing.
    "edn"
    2
    (->bi-consumer
      (fn [o ^DurableOutput out]
        (.write out (.getBytes (pr-str o) "utf-8"))))
    (->fn
      (fn [^DurableInput in]
        (let [ary (byte-array (.remaining in))]
          (.readFully in ary)
          (edn/read-string (String. ary "utf-8")))))))

;;; Util

(defspec test-vlq-roundtrip iterations
  (prop/for-all [n gen-pos-int]
    (let [out (doto (DurableAccumulator.)
                (.writeVLQ n))
          in  (->> out
                .contents
                DurableInput/from)]
      (= n (.readVLQ in)))))

(defspec test-prefixed-vlq-roundtrip iterations
  (prop/for-all [n gen-pos-int
                 bits (gen/choose 0 6)]
    (let [out (DurableAccumulator.)
          _   (Util/writePrefixedVLQ 0 bits n out)
          in  (->> out
                .contents
                DurableInput/from)]
      (= n (Util/readPrefixedVLQ (.readByte in) bits in)))))

;;; Prefix

(defspec test-prefix-roundtrip iterations
  (prop/for-all [n gen-pos-int
                 type (->> (BlockPrefix$BlockType/values)
                        (map gen/return)
                        gen/one-of)]
    (let [out (DurableAccumulator.)
          p   (BlockPrefix. n type)
          _   (.encode p out)
          p'  (->> out
                .contents
                DurableInput/from
                BlockPrefix/decode)]
      #_(prn p p')
      (= p p'))))

;;; HashTable

(defn ^HashTable create-hash-table [entries]
  (let [hash->offset (into {} entries)
        writer       (HashTable$Writer. 0.98)
        _            (doseq [[hash offset] hash->offset]
                       (.put writer hash offset))]
    (HashTable. (DurableInput/from (.contents writer)) (.entryBytes writer))))

(defspec test-durable-hash-table iterations
  (prop/for-all [entries (gen/such-that
                           (complement empty?)
                           (gen/list (gen/tuple gen-pos-int gen-pos-int)))]
    (let [t (create-hash-table entries)]
      (every?
        (fn [[hash offset]]
          (= offset (.offset ^HashTable$Entry (.get t hash))))
        (into {} entries)))))

;; SkipTable

(defn ^SkipTable create-skip-table [entry-offsets]
  (let [writer  (SkipTable$Writer.)
        entries (reductions #(map + %1 %2) entry-offsets)
        _       (doseq [[index offset] entries]
                  (.append writer index offset))]
    (SkipTable.
      (.sliceBlock (DurableInput/from (.contents writer)) BlockPrefix$BlockType/TABLE)
      (.tiers writer))))

(defn print-skip-table [^DurableInput in]
  (->> (repeatedly #(when (pos? (.remaining in)) (.readVLQ in)))
    (take-while identity)))

(defspec test-durable-skip-table iterations
  (prop/for-all [entry-offsets (gen/such-that
                                 (complement empty?)
                                 (gen/list (gen/tuple gen-small-pos-int gen-small-pos-int)))]
    (let [t (create-skip-table entry-offsets)]
      (every?
        (fn [[index offset]]
          (let [^SkipTable$Entry e (.floor t index)]
            (and
              (= index (.index e))
              (= offset (.offset e)))))
        (reductions #(map + %1 %2) entry-offsets)))))

;;; SortedChunk

(def hash-fn
  (->to-int-fn hash))

(defspec test-sort-map-entries iterations
  (prop/for-all [entries (gen/list (gen/tuple gen-pos-int gen-pos-int))]
    (let [m (into {} entries)
          m' (->> (HashMap/sortEntries
                    (.entries (Map/from ^java.util.Map m))
                    hash-fn)
               iterator-seq
               (map
                 (fn [^IEntry e]
                   [(.key e) (.value e)])))]
      (= (sort-by #(hash (key %)) m) m'))))

;;; DurableMap

(defspec test-durable-map iterations
  (prop/for-all [m (coll/map-gen #(Map.))]
    (let [m' (DurableMap/save m edn-encoding)]
      (and
        (= m m')
        (->> (range (.size m'))
          (every?
            (fn [^long i]
              (= i (->> (.nth m' i) .key (.indexOf m'))))))))))

;;; DurableList

 (defspec test-durable-list iterations
  (prop/for-all [l (coll/list-gen #(List.))]
    (let [l' (DurableList/save (.iterator ^Iterable l) edn-encoding)]
      (= l l'))))
