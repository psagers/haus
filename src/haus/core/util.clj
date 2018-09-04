(ns haus.core.util
  (:require [failjure.core :as f]
            [taoensso.truss :refer [have]]))

;
; Miscellaneous Clojure utilities.
;

(def scalar? (complement coll?))

(defn re-pred
  "Turns a regular expression into a predicate."
  [re]
  (every-pred string? (partial re-matches re)))

(defn map-vals
  "Transforms a map by applying a function to each value."
  [f m]
  (reduce-kv #(assoc %1 %2 (f %3)) {} m))

(defn map-kv
  "Applies a function to each key-value pair in a map and returns a new map
  with the results."
  [f m]
  (reduce-kv #(assoc %1 %2 (f %2 %3)) {} m))

(defn have-instance? [cls value]
  (have (partial instance? cls) value))

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
