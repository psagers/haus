(ns haus.web.transactions.query
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [failjure.core :as f]
            [haus.core.spec :as spec]
            [haus.core.util :as util]))


;
; Specs
;
; We make extensive use of s/conformer to parse strings into native values.
;

(defn ^:private setlike
  [item]
  (s/or :exact item
        :multi (s/cat :op (s/? (spec/token nil "allof" "anyof"))
                      :values (s/* item))))

(s/def ::pk (s/tuple util/sql-date-str? spec/int-like))


(s/def ::before ::pk)
(s/def ::after ::pk)

(s/def ::date
  (s/or :exact util/sql-date-str?
        :comp (s/tuple (spec/token nil "lt" "le" "ge" "gt") util/sql-date-str?)
        :range (s/tuple (spec/token nil "in") util/sql-date-str? util/sql-date-str?)))

(s/def ::category_id (setlike (s/and spec/int-like pos-int?)))
(s/def ::text (setlike (s/and string? not-empty)))
(s/def ::tag (setlike (spec/simple-conformer util/tag? str/lower-case)))
(s/def ::person_id (setlike (s/and spec/int-like pos-int?)))

(s/def ::limit (s/and spec/int-like pos-int?))

(s/def ::where-params
  (s/keys :opt-un [::before ::after ::date ::category_id ::text
                   ::tag ::person_id]))

(s/def ::limit-params
  (s/keys :opt-un [::limit]))


;
; API helpers
;

(defn ^:private error-message
  "Field-specific error messages."
  [param]
  (case param
    (:before :after) "Requires exactly two arguments: a SQL date and an integer."
    (:date) "Requires one of three forms: <sql-date>; [lt|le|gt|ge <sql-date>]; [in <sql-date> <sql-date>]"
    (:category_id :text :tag :person_id) "Requires one of two forms: <value>; [anyof|allof <value> ...]"
    (:limit) "Requires an integer."
    "Invalid"))

(defn ^:private fail
  "Returns a failure with error messages for the given (invalid) params."
  [params]
  (let [explanation (s/explain-data ::where-params params)
        problems (::s/problems explanation)
        fields (vec (distinct (map #(get-in % [:path 0]) problems)))
        messages (zipmap fields (map error-message fields))]
    (f/fail messages)))


;
; API
;

(defn conform
  "Conforms a map of query params. Unknown keys are removed. If successful,
  returns a structure suitable for db/get-transactions. Otherwise, returns a
  failure with a map of parameter names (as keywords) to error messages."
  [params]
  (let [params (util/keywordize-keys-safe params)
        query (s/conform ::where-params params)]
    (if (= query ::s/invalid)
      (fail params)
      query)))
