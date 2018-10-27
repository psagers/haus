(ns haus.mongodb.bson
  "Utilities for working with BSON values."
  (:require [taoensso.truss :refer [have]])
  (:import (java.util.regex Pattern)
           (org.bson BsonDocument)
           (org.bson.types Decimal128 ObjectId)
           (com.mongodb.async.client MongoClients)))


; Maps BsonRegularExpression option characters to Pattern flags.
(def ^:private regex-options
  {\i Pattern/CASE_INSENSITIVE
   \m Pattern/MULTILINE
   \s Pattern/DOTALL
   \u Pattern/UNICODE_CHARACTER_CLASS
   \x Pattern/COMMENTS})


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Clojure -> BSON
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare to-bson-value)

(defmulti to-bson
  "Converts values (usually maps) to objects that implement
  org.bson.conversions.Bson."
  class)

(defmethod to-bson org.bson.conversions.Bson [value]
  value)

(defmethod to-bson clojure.lang.IPersistentMap [value]
  (to-bson-value value))


(defmulti to-bson-doc
  "Converts values (usually maps) to BsonDocument objects."
  class)

(defmethod to-bson-doc BsonDocument [value]
  value)

(defmethod to-bson-doc org.bson.conversions.Bson [value]
  (.toBsonDocument value BsonDocument (MongoClients/getDefaultCodecRegistry)))

(defmethod to-bson-doc clojure.lang.IPersistentMap [value]
  (to-bson-value value))


(defmulti to-bson-value
  "Converts applicable Clojure values to BsonValue objects."
  class)

(defmethod to-bson-value clojure.lang.IPersistentMap [value]
  (org.bson.BsonDocument. (map to-bson-value value)))

(defmethod to-bson-value clojure.lang.MapEntry [[k v]]
  (let [k' (if (keyword? k) (name k) (have string? k))
        v' (to-bson-value v)]
    (org.bson.BsonElement. k' v')))

(defmethod to-bson-value clojure.lang.Sequential [value]
  (org.bson.BsonArray. (map to-bson-value value)))

(defmethod to-bson-value Boolean [value]
  (org.bson.BsonBoolean. value))

(defmethod to-bson-value Float [value]
  (org.bson.BsonDouble. value))

(defmethod to-bson-value Double [value]
  (org.bson.BsonDouble. value))

(defmethod to-bson-value Integer [value]
  (org.bson.BsonInt64. value))

(defmethod to-bson-value Long [value]
  (org.bson.BsonInt64. value))

(defmethod to-bson-value java.math.BigDecimal [value]
  (org.bson.BsonDecimal128. (Decimal128. value)))

(defmethod to-bson-value String [value]
  (org.bson.BsonString. value))

(defmethod to-bson-value clojure.lang.Keyword [value]
  (org.bson.BsonString. (str value)))

(defmethod to-bson-value java.util.Date [^java.util.Date value]
  (org.bson.BsonDateTime. (.getTime value)))

(defmethod to-bson-value Pattern [^Pattern value]
  (letfn [(options [flags] (->> regex-options
                                (keep (fn [[opt flag]] (if (bit-test flags flag) opt)))
                                (sort)  ; BsonRegularExpression requires these to be sorted.
                                (apply str)))]
    (org.bson.BsonRegularExpression. (.pattern value) (options (.flags value)))))

(defmethod to-bson-value ObjectId [value]
  (org.bson.BsonObjectId. value))

(defmethod to-bson-value nil [_]
  (org.bson.BsonNull.))

(defmethod to-bson-value org.bson.BsonValue [value]
  value)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; BSON -> Clojure
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare -from-bson-value)

(defn from-bson-value
  "Public API.

  Options:

    :keywordize? - true (the default) to convert map keys to keywords.
    :qualifier - an optional namespace for map keywords."
  [bson-value & opts] (-from-bson-value bson-value (apply array-map opts)))


(defmulti -from-bson-value
  (fn [bson-value _] (class bson-value)))

(defmethod -from-bson-value org.bson.BsonDocument [bson-value opts]
  (into {} (map #(-from-bson-value % opts) bson-value)))

(defmethod -from-bson-value java.util.Map$Entry
  [entry, {:keys [keywordize? qualifier] :or {keywordize? true, qualifier nil} :as opts}]
  (let [k (cond->> (.getKey entry)
                   keywordize? (keyword qualifier))
        v (-from-bson-value (.getValue entry) opts)]
    [(have some? k) (have some? v)]))

(defmethod -from-bson-value org.bson.BsonArray [bson-value opts]
  (into [] (map #(-from-bson-value % opts) bson-value)))

(defmethod -from-bson-value org.bson.BsonDecimal128 [bson-value _opts]
  (.bigDecimalValue (.getValue bson-value)))

(defmethod -from-bson-value org.bson.BsonDateTime [bson-value _opts]
  (java.util.Date. (.getValue bson-value)))

;(defmethod -from-bson-value org.bson.BsonJavaScript [bson-value _opts]
;  (org.bson.types.Code. (.getCode bson-value)))

;(defmethod -from-bson-value org.bson.BsonJavaScriptWithScope [bson-value opts]
;  (org.bson.types.CodeWithScope.
;    (.getCode bson-value)
;    (-> bson-value (.getScope) (from-bson :keywordize? false) (org.bson.Document.))))

(defmethod -from-bson-value org.bson.BsonRegularExpression [bson-value _opts]
  (let [flags (reduce bit-or 0 (keep regex-options (.getOptions bson-value)))]
    (Pattern/compile (.getPattern bson-value) flags)))

(defmethod -from-bson-value org.bson.BsonNull [_bson-value _opts]
  nil)

; The simple BsonValue subclasses all have a getValue method that returns the
; obvious thing.
(defmethod -from-bson-value :default [bson-value _opts]
  (.getValue bson-value))
