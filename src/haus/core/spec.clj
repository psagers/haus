(ns haus.core.spec
  "Wrappers and utilities for spec."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen]
            [clojure.test.check.generators :as gen]
            [clojure.string :as str]
            [haus.core.util :as util :refer [unqualify]])
  (:import (org.joda.time DateTime)))


(defn simple-conformer
  "A wrapper around s/conformer that takes a predicate and parse function."
  ([pred parse]
   (simple-conformer pred parse identity))

  ([pred parse unparse]
   (s/conformer #(if (pred %) (parse %) ::s/invalid) unparse)))


(def pos-int-32 (s/int-in 1 0x80000000))

(def int-like
  "A spec that conforms integers in either native or string form. This does not
  unform, but is idempotent."
  (letfn [(conform-value [value]
            (cond
              (integer? value) value
              (util/digits? value) (Integer/parseInt value)
              :else ::s/invalid))]
    (s/conformer conform-value identity)))

(def tag
  "A spec that matches and generates valid tags."
  (s/with-gen (simple-conformer util/tag? str/lower-case)
              (fn []
                (gen/let [head gen/char-alpha
                          tail (gen/vector (gen/elements "abcdefghijklmnopqrstuvwxyz0123456789_-") 0 19)]
                  (apply str head tail)))))

(def sql-timestamp
  "A spec that matches Inst and generates java.sql.Timestamp."
  (s/with-gen inst?
              (fn [] (gen/fmap #(java.sql.Timestamp. (* % 1000))
                               (gen/choose 1420099200 1577865600)))))

(def sql-date
  "A spec that matches Inst and generates java.sql.Date."
  (s/with-gen inst?
              (fn [] (gen/fmap #(java.sql.Date. (* % 1000))
                               (gen/choose 1420099200 1577865600)))))

(def java-date
  "A spec that matches Inst and generates java.util.Date."
  (s/with-gen inst?
              (fn [] (gen/fmap #(java.util.Date. (* % 1000))
                               (gen/choose 1420099200 1577865600)))))


(def joda-datetime
  "A spec that matches and generates org.joda.time.DateTime."
  (s/with-gen (partial instance? org.joda.time.DateTime)
              (fn [] (gen/fmap #(org.joda.time.DateTime. (* % 1000))
                               (gen/choose 1420099200 1577865600)))))

(def sql-date-str
  "A spec that matches and generates SQL date strings."
  (s/with-gen util/sql-date-str?
              (fn [] (gen/fmap #(str (java.sql.Date. (* % 1000)))
                               (gen/choose 1420099200 1577865600)))))

(defn token
  "A spec that defines a string token to be keywordized. Takes a (string)
  namespace for the conformed keywords (nil is fine) and a collection of
  acceptable string values. This does not unform, but is idempotent."
  [key-ns & values]
  (let [pred (set values)
        conform-value (fn [value]
                        (cond
                          (pred value) (keyword key-ns value)

                          (and (simple-keyword? value)
                               (pred (name value)))  (keyword key-ns (name value))

                          (and (qualified-keyword? value)
                               (= (namespace value) key-ns)
                               (pred (name value)))  value

                          :else ::s/invalid))]
    (s/conformer conform-value identity)))


(defn ^:private valid-keys
  "Takes the same arguments as s/keys, but just returns all valid keys."
  [& {:keys [req opt req-un opt-un]
      :or {req [], opt [], req-un [], opt-un []}}]
  (set (concat req opt (map unqualify req-un) (map unqualify opt-un))))

(defmacro strict-keys
  "Like s/keys, but fails if there are any extra keys."
  [& args]
  (let [valid (apply valid-keys args)]
    `(s/and
       (s/keys ~@args)
       #(every? ~valid (keys %)))))

(defmacro exclusive-keys
  "Like s/keys, but removes any extra keys."
  [& args]
  (let [valid (apply valid-keys args)]
    `(s/and
       (s/keys ~@args)
       (s/conformer #(select-keys % ~valid) identity))))
