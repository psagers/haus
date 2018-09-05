(ns haus.web.transactions.query
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [failjure.core :as f]
            [haus.core.spec :as spec]
            [haus.core.util :as util]))


;
; Specs
;
; We make extensive use of s/conformer to parse strings into native values and
; to remove the extra structure that s/conform adds.
;

(defn ^:private setlike
  [item]
  (spec/flat-or :exact item
           :multi (s/cat :op (spec/token "allof" "anyof")
                         :values (s/+ item))))

(s/def ::int-string (spec/simple-conformer util/digits? util/to-int))
(s/def ::tag-string (spec/simple-conformer util/tag? str/lower-case))
(s/def ::pk (s/tuple util/sql-date? ::int-string))


(s/def ::before ::pk)
(s/def ::after ::pk)

(s/def ::date
  (spec/flat-or :exact util/sql-date?
           :comp (s/tuple (spec/token "lt" "le" "ge" "gt") util/sql-date?)
           :range (s/tuple (spec/token "in") util/sql-date? util/sql-date?)))

(s/def ::category_id (setlike ::int-string))
(s/def ::text (setlike string?))
(s/def ::tag (setlike ::tag-string))
(s/def ::person_id (setlike ::int-string))
(s/def ::limit ::int-string)

(s/def ::params
  (spec/exclusive-keys :opt-un [::before ::after ::date ::category_id ::text
                                ::tag ::person_id ::limit]))


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
  (let [explanation (s/explain-data ::params params)
        problems (::s/problems explanation)
        fields (vec (set (map #(get-in % [:path 0]) problems)))
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
        query (s/conform ::params params)]
    (if (= query ::s/invalid)
      (fail params)
      query)))
