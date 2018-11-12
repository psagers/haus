(ns haus.mongodb.bson
  "Utilities for working with BSON values."
  (:require [clojure.string :as str]
            [clojure.walk :refer [postwalk]]
            [haus.core.util :refer [have-instance? have-satisfies?
                                    stringify-keys]]
            [taoensso.truss :refer [have]])
  (:import (java.util.regex Pattern)
           (com.mongodb.async.client MongoClients)
           (org.bson BsonDocument Document)
           (org.bson.types Decimal128 ObjectId)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Util
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

; Maps BsonRegularExpression option characters to Pattern flags.
(def ^:private regex-options
  {\i Pattern/CASE_INSENSITIVE
   \m Pattern/MULTILINE
   \s Pattern/DOTALL
   \u Pattern/UNICODE_CHARACTER_CLASS
   \x Pattern/COMMENTS})

(defn ^:private regex-options->flags [options]
  (reduce bit-or 0 (keep regex-options options)))

(defn ^:private regex-flags->options [flags]
  (->> regex-options
       (keep (fn [[opt flag]] (if-not (zero? (bit-and flags flag)) opt)))
       (sort)  ; BsonRegularExpression requires these to be sorted.
       (apply str)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Clojure -> BSON
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare bson-value)


(defprotocol IntoBson
  (-bson [this] "Converts values (usually maps) to objects that implement org.bson.conversions.Bson."))

(defn bson [this]
  (-bson (have-satisfies? IntoBson this)))

(extend-protocol IntoBson
  org.bson.conversions.Bson
  (-bson [this]
    this)

  clojure.lang.IPersistentMap
  (-bson [this]
    (bson-value this)))


(defprotocol IntoBsonDocument
  (-bson-doc [this] "Converts values (usually maps) to BsonDocument objects."))

(defn bson-doc [this]
  (-bson-doc (have-satisfies? IntoBsonDocument this)))

(extend-protocol IntoBsonDocument
  BsonDocument
  (-bson-doc [this]
    this)

  org.bson.conversions.Bson
  (-bson-doc [this]
    (.toBsonDocument this BsonDocument (MongoClients/getDefaultCodecRegistry)))

  clojure.lang.IPersistentMap
  (-bson-doc [this]
    (bson-value this)))


(defprotocol IntoDocument
  (-document [this] "Converts values (usually maps) to Document objects."))

(defn document [this]
  (-document (have-satisfies? IntoDocument this)))

(extend-protocol IntoDocument
  Document
  (-document [this]
    this)

  clojure.lang.IPersistentMap
  (-document [this]
    (postwalk #(if (map? %) (Document. (stringify-keys %)) %) this)))


(defprotocol IntoBsonValue
  (-bson-value [this]  "Converts applicable Clojure values to BsonValue objects."))

(defn bson-value [this]
  (-bson-value (have-satisfies? IntoBsonValue this)))

(extend-protocol IntoBsonValue
  org.bson.BsonValue
  (-bson-value [this]
    this)

  clojure.lang.IPersistentMap
  (-bson-value [this]
    (org.bson.BsonDocument. (map -bson-value this)))

  clojure.lang.MapEntry
  (-bson-value [[k v]]
    (let [k' (if (keyword? k) (name k) (have string? k))
          v' (bson-value v)]
      (org.bson.BsonElement. k' v')))

  clojure.lang.Sequential
  (-bson-value [this]
    (org.bson.BsonArray. (map bson-value this)))

  Boolean
  (-bson-value [this]
    (org.bson.BsonBoolean. this))

  Integer
  (-bson-value [this]
    (org.bson.BsonInt64. (long this)))

  Long
  (-bson-value [this]
    (org.bson.BsonInt64. this))

  Float
  (-bson-value [this]
    (org.bson.BsonDouble. (double this)))

  Double
  (-bson-value [this]
    (org.bson.BsonDouble. this))

  java.math.BigDecimal
  (-bson-value [this]
    (org.bson.BsonDecimal128. (Decimal128. this)))

  String
  (-bson-value [this]
    (org.bson.BsonString. this))

  clojure.lang.Keyword
  (-bson-value [this]
    (org.bson.BsonSymbol. (str/replace-first (str this) #"^:" "")))

  java.util.Date
  (-bson-value [this]
    (org.bson.BsonDateTime. (.getTime this)))

  java.util.regex.Pattern
  (-bson-value [this]
    (let [pattern (.pattern this)
          options (regex-flags->options (.flags this))]
      (org.bson.BsonRegularExpression. pattern options)))

  org.bson.types.ObjectId
  (-bson-value [this]
    (org.bson.BsonObjectId. this))

  org.bson.types.Binary
  (-from-bson-value [this]
    (org.bson.BsonBinary. (.getType this) (.getData this)))

  org.bson.types.BSONTimestamp
  (-bson-value [this]
    (org.bson.BsonTimestamp. (.getTime this) (.getInc this)))

  org.bson.types.Code
  (-bson-value [this]
    (org.bson.BsonJavaScript. (.getCode this)))

  org.bson.types.CodeWithScope
  (-bson-value [this]
    (org.bson.BsonJavaScriptWithScope. (.getCode this)
                                       (bson-doc (.getScope this))))

  nil
  (-bson-value [_this]
    (org.bson.BsonNull.)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; BSON -> Clojure
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol FromBsonValue
  (-from-bson-value [this opts]))

(defn from-bson-value
  "Public API.

  Options:

    :keywordize? - true (the default) to convert map keys to keywords.
    :qualifier - an optional namespace for map keywords."
  ([bson-value]
   (from-bson-value bson-value {}))

  ([bson-value opts]
   (-from-bson-value (have-instance? org.bson.BsonValue bson-value) opts)))


(extend-protocol FromBsonValue
  org.bson.BsonDocument
  (-from-bson-value [this opts]
    (into {} (map #(-from-bson-value % opts) this)))

  ; Helper for BsonDocument
  java.util.Map$Entry
  (-from-bson-value [this, {:keys [keywordize? qualifier] :or {keywordize? true, qualifier nil} :as opts}]
    (let [k (cond->> (.getKey this)
                     keywordize? (keyword qualifier))
          v (-from-bson-value (.getValue this) opts)]
      [(have some? k) v]))

  org.bson.BsonArray
  (-from-bson-value [this opts]
    (into [] (map #(-from-bson-value % opts) this)))

  org.bson.BsonBinary
  (-from-bson-value [this _opts]
    (org.bson.types.Binary. (.getType this) (.getData this)))

  org.bson.BsonDateTime
  (-from-bson-value [this _opts]
    (java.util.Date. (.getValue this)))

  org.bson.BsonDecimal128
  (-from-bson-value [this _opts]
    (.bigDecimalValue (.decimal128Value this)))

  org.bson.BsonJavaScript
  (-from-bson-value [this _opts]
    (org.bson.types.Code. (.getCode this)))

  org.bson.BsonJavaScriptWithScope
  (-from-bson-value [this _opts]
    (org.bson.types.CodeWithScope.
      (.getCode this)
      (-> (.getScope this) (from-bson-value {:keywordize? false}) (document))))

  org.bson.BsonInt32
  (-from-bson-value [this _opts]
    (.intValue this))

  org.bson.BsonInt64
  (-from-bson-value [this _opts]
    (.longValue this))

  org.bson.BsonNull
  (-from-bson-value [_this _opts]
    nil)

  org.bson.BsonObjectId
  (-from-bson-value [this _opts]
    (.getValue this))

  org.bson.BsonRegularExpression
  (-from-bson-value [this _opts]
    (let [pattern (.getPattern this)
          flags (regex-options->flags (.getOptions this))]
      (Pattern/compile pattern flags)))

  org.bson.BsonString
  (-from-bson-value [this _opts]
    (.getValue this))

  org.bson.BsonSymbol
  (-from-bson-value [this _opts]
    (keyword (.getSymbol this)))

  org.bson.BsonTimestamp
  (-from-bson-value [this _opts]
    (org.bson.types.BSONTimestamp. (.getTime this) (.getInc this))))
