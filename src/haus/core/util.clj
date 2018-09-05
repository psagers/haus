(ns haus.core.util
  (:require [clojure.walk :as walk]
            [failjure.core :as f]
            [taoensso.truss :refer [have]]))

;
; Miscellaneous Clojure utilities.
;

(defn re-pred
  "Turns a regular expression into a predicate."
  [re]
  (every-pred string? (partial re-matches re)))

(def scalar? (complement coll?))
(def digits? (re-pred #"^\d+$"))
(def sql-date? (re-pred #"^\d\d\d\d-\d\d-\d\d$"))
(def tag? (re-pred #"(?i)^[a-z][\w-]*$"))

(defn to-int [value]
  (Integer/parseInt value))

(defn unqualify
  "Removes the namespace from a qualified keyword."
  [kw]
  (keyword (name (have keyword? kw))))

(defn map-vals
  "Transforms a map by applying a function to each value."
  [f m]
  (reduce-kv #(assoc %1 %2 (f %3)) {} m))

(defn map-kv
  "Applies a function to each key-value pair in a map and returns a new map
  with the updated values."
  [f m]
  (reduce-kv #(assoc %1 %2 (f %2 %3)) {} m))

(defn have-instance? [cls value]
  (have (partial instance? cls) value))

(defn keywordize-keys-safe
  "Like clojure.walk/keywordize-keys, but with find-keyword. Safe for untrusted
  data."
  [m]
  (let [f (fn [[k v]] (if (string? k) [(find-keyword k) v] [k v]))]
    (walk/postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) m)))

(defn until-failed
  "Returns a lazy sequence of items from coll util it encounters a failure.
  If no coll is provided, this returns a transducer. As a transducer, it will
  immediately return the first failure (if any) in place of the normal result."
  ([]
   (fn [rf]
     (fn
       ([] (rf))
       ([result] (rf result))
       ([result input]
        (cond
          (f/failed? result) (reduced result)
          (f/failed? input) (reduced input)
          :else (rf result input))))))

  ([coll]
   (take-while (complement f/failed?) coll)))
