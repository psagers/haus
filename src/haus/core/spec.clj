(ns haus.core.spec
  "Wrappers and utilities for spec."
  (:require [clojure.spec.alpha :as s]
            [haus.core.util :as util]))


(defn simple-conformer
  "A wrapper around s/conformer that takes a predicate and parse function."
  [pred parse]
  (s/conformer #(if (pred %) (parse %) ::s/invalid)))

(defn token
  "A spec helper that defines a string token to be keywordized. Takes one or
  more valid token values as arguments."
  [& values]
  (simple-conformer (set values) keyword))

(defmacro flat-or
  "Like s/or, but removes the extra layer of structure."
  [& args]
  `(s/and
     (s/or ~@args)
     (s/conformer second)))

(defn ^:private valid-keys
  "Returns the set of valid keys from the arguments of s/keys."
  [key-args]
  (let [key-args (apply array-map key-args)
        qualified (concat (get key-args :req []) (get key-args :opt []))
        unqualified (map util/unqualify (concat (get key-args :req-un []) (get key-args :opt-un [])))]
    (set (concat qualified unqualified))))

(defmacro strict-keys
  "Like s/keys, but fails if there are any extra keys."
  [& args]
  (let [valid (valid-keys args)]
    `(s/and
       (s/keys ~@args)
       #(every? ~valid (keys %)))))

(defmacro exclusive-keys
  "Like s/keys, but removes any extra keys."
  [& args]
  (let [valid (valid-keys args)]
    `(s/and
       (s/keys ~@args)
       (s/conformer #(select-keys % ~valid)))))
