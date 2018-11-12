(ns haus.mongodb.model
  "Protocols for converting simple Clojure values into objects and enumerations
  from com.mongodb.client.model."
  (:require [haus.core.util :refer [have-satisfies?]]
            [haus.mongodb.bson :as bson]
            [taoensso.truss :refer [have]])
  (:import (com.mongodb.client.model Collation IndexModel IndexOptions)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; com.mongodb.client.model.CollationAlternate
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol IntoCollationAlternate
  (-collation-alternate [this] "Converts suitable values to CollationAlternate objects."))

(defn collation-alternate [value]
  (-collation-alternate (have-satisfies? IntoCollationAlternate value)))

(extend-protocol IntoCollationAlternate
  com.mongodb.client.model.CollationAlternate
  (-collation-alternate [this] this)

  clojure.lang.Keyword
  (-collation-alternate [this]
    (case this
      (:non-ignorable) com.mongodb.client.model.CollationAlternate/NON_IGNORABLE
      (:shifted) com.mongodb.client.model.CollationAlternate/SHIFTED
      (throw (IllegalArgumentException. (str "Invalid CollationAlternate keyword: " this)))))

  String
  (-collation-alternate [this]
    (com.mongodb.client.model.CollationAlternate/fromString this)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; com.mongodb.client.model.CollationCaseFirst
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol IntoCollationCaseFirst
  (-collation-case-first [this] "Converts suitable values to CollationCaseFirst objects."))

(defn collation-case-first [value]
  (-collation-case-first (have-satisfies? IntoCollationCaseFirst value)))

(extend-protocol IntoCollationCaseFirst
  com.mongodb.client.model.CollationCaseFirst
  (-collation-case-first [this] this)

  clojure.lang.Keyword
  (-collation-case-first [this]
    (case this
      (:lower) com.mongodb.client.model.CollationCaseFirst/LOWER
      (:off) com.mongodb.client.model.CollationCaseFirst/OFF
      (:upper) com.mongodb.client.model.CollationCaseFirst/UPPER
      (throw (IllegalArgumentException. (str "Invalid CollationCaseFirst keyword: " this)))))

  String
  (-collation-case-first [this]
    (com.mongodb.client.model.CollationCaseFirst/fromString this)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; com.mongodb.client.model.CollationMaxVariable
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol IntoCollationMaxVariable
  (-collation-max-variable [this] "Converts suitable values to CollationMaxVariable objects."))

(defn collation-max-variable [value]
  (-collation-max-variable (have-satisfies? IntoCollationMaxVariable value)))

(extend-protocol IntoCollationMaxVariable
  com.mongodb.client.model.CollationMaxVariable
  (-collation-max-variable [this] this)

  clojure.lang.Keyword
  (-collation-max-variable [this]
    (case this
      (:punct) com.mongodb.client.model.CollationMaxVariable/PUNCT
      (:space) com.mongodb.client.model.CollationMaxVariable/SPACE
      (throw (IllegalArgumentException. (str "Invalid CollationMaxVariable keyword: " this)))))

  String
  (-collation-max-variable [this]
    (com.mongodb.client.model.CollationMaxVariable/fromString this)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; com.mongodb.client.model.CollationStrength
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol IntoCollationStrength
  (-collation-strength [this] "Converts suitable values to CollationStrength objects."))

(defn collation-strength [value]
  (-collation-strength (have-satisfies? IntoCollationStrength value)))

(extend-protocol IntoCollationStrength
  com.mongodb.client.model.CollationStrength
  (-collation-strength [this] this)

  clojure.lang.Keyword
  (-collation-strength [this]
    (case this
      (:primary) com.mongodb.client.model.CollationStrength/PRIMARY
      (:secondary) com.mongodb.client.model.CollationStrength/SECONDARY
      (:tertiary) com.mongodb.client.model.CollationStrength/TERTIARY
      (:quaternary) com.mongodb.client.model.CollationStrength/QUATERNARY
      (:identical) com.mongodb.client.model.CollationStrength/IDENTICAL
      (throw (IllegalArgumentException. (str "Invalid CollationStrength keyword: " this)))))

  Number
  (-collation-strength [this]
    (com.mongodb.client.model.CollationStrength/fromInt (int this))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; com.mongodb.client.model.Collation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol IntoCollation
  (-collation [this] "Converts suitable values to Collation objects."))

(defn collation [value]
  (-collation (have-satisfies? IntoCollation value)))

(extend-protocol IntoCollation
  Collation
  (-collation [this] this)

  clojure.lang.IPersistentMap
  (-collation [{:keys [backwards case-level collation-alternate collation-case-first
                       collation-max-variable collation-strength locale normalization
                       numeric-ordering]}]
    (let [builder (cond-> (Collation/builder)
                          (some? backwards) (.backwards backwards)
                          (some? case-level) (.caseLevel case-level)
                          (some? collation-alternate) (.collationAlternate (haus.mongodb.model/collation-alternate collation-alternate))
                          (some? collation-case-first) (.collationCaseFirst (haus.mongodb.model/collation-case-first collation-case-first))
                          (some? collation-max-variable) (.collationMaxVariable (haus.mongodb.model/collation-max-variable collation-max-variable))
                          (some? collation-strength) (.collationStrength (haus.mongodb.model/collation-strength collation-strength))
                          (some? locale) (.locale locale)
                          (some? normalization) (.normalization normalization)
                          (some? numeric-ordering) (.numericOrdering numeric-ordering))]
      (.build builder))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; java.util.concurrent.TimeUnit
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol IntoTimeUnit
  (-time-unit [this] "Converts suitable values to TimeUnit objects."))

(defn time-unit [value]
  (-time-unit (have-satisfies? IntoTimeUnit value)))

(extend-protocol IntoTimeUnit
  java.util.concurrent.TimeUnit
  (-time-unit [this] this)

  clojure.lang.Keyword
  (-time-unit [this]
    (case this
      (:days) java.util.concurrent.TimeUnit/DAYS
      (:hours) java.util.concurrent.TimeUnit/HOURS
      (:microseconds) java.util.concurrent.TimeUnit/MICROSECONDS
      (:milliseconds) java.util.concurrent.TimeUnit/MILLISECONDS
      (:minutes) java.util.concurrent.TimeUnit/MINUTES
      (:nanoseconds) java.util.concurrent.TimeUnit/NANOSECONDS
      (:seconds) java.util.concurrent.TimeUnit/SECONDS
      (throw (IllegalArgumentException. (str "Invalid TimeUnit keyword: " this))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; com.mongodb.client.model.IndexOptions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol IntoIndexOptions
  (-index-options [this] "Converts suitable values to IndexOptions objects."))

(defn index-options [value]
  (-index-options (have-satisfies? IntoIndexOptions value)))

(defn ^:private set-expire-after
  [^IndexOptions options [duration unit]]
  (.expireAfter options duration (haus.mongodb.model/time-unit unit)))

(extend-protocol IntoIndexOptions
  IndexOptions
  (-index-options [this] this)

  clojure.lang.IPersistentMap
  (-index-options [{:keys [background bits bucket-size collation default-language
                           expire-after language-override max min name
                           partial-filter-expression sparse sphere-version storage-engine
                           text-version unique version weights]}]
    (cond-> (IndexOptions.)
            (some? background) (.background background)
            (some? bits) (.bits bits)
            (some? bucket-size) (.bucketSize bucket-size)
            (some? collation) (.collation (haus.mongodb.model/collation collation))
            (some? default-language) (.defaultLanguage default-language)
            (some? expire-after) (set-expire-after expire-after)
            (some? language-override) (.languageOverride language-override)
            (some? max) (.max max)
            (some? min) (.min min)
            (some? name) (.name name)
            (some? partial-filter-expression) (.partialFilterExpression (bson/bson partial-filter-expression))
            (some? sparse) (.sparse sparse)
            (some? sphere-version) (.sphereVersion sphere-version)
            (some? storage-engine) (.storageEngine (bson/bson storage-engine))
            (some? text-version) (.textVersion text-version)
            (some? unique) (.unique unique)
            (some? version) (.version version)
            (some? weights) (.weights (bson/bson weights))))

  nil
  (-index-options [_]
    (IndexOptions.)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; com.mongodb.client.model.IndexModel
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol IntoIndexModel
  (-index-model [this] "Converts suitable values to IndexModel objects."))

(defn index-model [value]
  (-index-model (have-satisfies? IntoIndexModel value)))

(extend-protocol IntoIndexModel
  IndexModel
  (-index-model [this] this)

  clojure.lang.IPersistentMap
  (-index-model [keys]
    (IndexModel. (bson/bson keys)))

  clojure.lang.IPersistentVector
  (-index-model [[keys options]]
    (IndexModel. (bson/bson keys) (index-options options))))
