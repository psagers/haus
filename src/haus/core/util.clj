(ns haus.core.util
  (:require [clojure.core.async :as async :refer [<!]]
            [clojure.spec.alpha :as s]
            [clojure.walk :as walk]
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
(def sql-date-str? (re-pred #"^\d\d\d\d-\d\d-\d\d$"))
(def tag? (re-pred #"(?i)^[a-z][\w-]{0,19}$"))

(defn to-int [value]
  (Integer/parseInt value))

(s/fdef to-int
  :args (s/cat :value digits?)
  :ret integer?)

(defn unqualify
  "Removes the namespace from a qualified keyword."
  [kw]
  (keyword (name kw)))

(s/fdef unqualify
  :args (s/cat :kw keyword?)
  :ret simple-keyword?)


(defn map-keys
  "Transforms a map by applying a function to each key."
  [f m]
  (into {} (map (fn [[k v]] [(f k) v]) m)))

(defn map-vals
  "Transforms a map by applying a function to each value."
  [f m]
  (into {} (map (fn [[k v]] [k (f v)]) m)))

(defn map-kv
  "Applies a function to each key-value pair in a map and returns a new map
  with the returned values."
  [f m]
  (into {} (map (fn [[k v]] [k (f k v)]) m)))

(defn qualify-keys
  "Converts the keys in a map to qualified keywords."
  [key-ns m]
  (if (map? m)
    (map-keys #(keyword key-ns (name %)) m)))

(s/fdef qualify-keys
  :args (s/cat :key-ns string?
               :m map?)
  :ret (s/nilable map?))

(defn keywordize-keys-safe
  "Like clojure.walk/keywordize-keys, but with find-keyword. Safe for untrusted
  data."
  ([m]
   (keywordize-keys-safe m nil))

  ([m key-ns]
   (letfn [(f [[k v]] (if-let [k' (if (string? k) (find-keyword key-ns k) k)]
                        [k' v]
                        [k v]))]
     (walk/postwalk
       #(if (map? %) (into {} (map f %)) %)
       m))))

(defn stringify-keys
  "The inverse of keywordize-keys."
  [m & {:keys [all?]}]
  (letfn [(stringify [k] (cond
                           (keyword? k) (name k)
                           all? (str k)
                           :else k))]
    (walk/postwalk
      #(if (map? %) (map-keys stringify %) %)
      m)))


(defn deep-merge
  "Recursively merges maps."
  [& args]
  (if (every? map? args)
    (apply merge-with deep-merge args)
    (last args)))

(defn submap?
  [m1 m2]
  (every? (fn [[k v]] (= (get m2 k) v)) m1))


(defmacro have-instance? [cls value]
  `(have (partial instance? ~cls) ~value))

(defmacro have-satisfies? [proto value]
  `(have (partial satisfies? ~proto) ~value))


(defn until-pred
  "Returns a lazy sequence of items from coll util it encounters a value that
  satisfies the predicate function (presumably some kind of failure).

  If no coll is provided, this returns a transducer. As a transducer, it will
  immediately return the first failure (if any) in place of the normal result."
  ([f]
   (fn [rf]
     (fn
       ([] (rf))
       ([result] (rf result))
       ([result input]
        (cond
          (f result) (reduced result)
          (f input) (reduced input)
          :else (rf result input))))))

  ([f coll]
   (take-while (complement f) coll)))

(def until-throwable (until-pred (partial instance? Throwable)))
(def until-failed (until-pred f/failed?))


(defn drain!
  "Closes a channel and drains all values."
  ([channel]
   (drain! channel true))

  ([channel close?]
   (when close?
     (async/close! channel))
   (async/go-loop []
     (when-some [_ (<! channel)]
       (recur)))))
