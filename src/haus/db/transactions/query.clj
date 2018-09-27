(ns haus.db.transactions.query
  "Specs for transaction queries."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.string :as str]
            [haus.core.spec :as spec]
            [haus.core.util :as util]))


; Any date value can be either a java.sql.Date or a "YYYY-MM-DD" string.
(s/def ::-date (s/or :sql-date spec/sql-date
                     :date-str spec/sql-date-str))

(s/def ::-tag (s/with-gen (spec/simple-conformer util/tag? str/lower-case)
                          (fn [] gen/string-alphanumeric)))

(defn any-or-all
  "Generates an :anyof/:allof spec with a sub-spec for the values."
  [value-spec]
  (s/cat :op #{:anyof :allof}
         :values (s/+ value-spec)))

(s/def ::id pos-int?)
(s/def ::before (s/tuple ::-date ::id))
(s/def ::after (s/tuple ::-date ::id))
(s/def ::date (s/or :comp (s/tuple #{:eq :lt :le :ge :gt} ::-date)
                    :range (s/tuple #{:in} ::-date ::-date)))
(s/def ::category_id (any-or-all spec/pos-int-32))
(s/def ::text (any-or-all (s/and string? not-empty)))
(s/def ::tag (any-or-all spec/tag))
(s/def ::person_id (any-or-all spec/pos-int-32))
(s/def ::limit pos-int?)

(s/def ::params (s/keys :opt [::id ::before ::after ::date ::category_id ::text
                              ::tag ::person_id ::limit]))
