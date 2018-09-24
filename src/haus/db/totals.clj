(ns haus.db.totals
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.spec.alpha :as s]
            [haus.core.spec :as spec]
            [haus.core.util :as util]
            [haus.db :as db]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Specs
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::person_id spec/pos-int-32)
(s/def ::category_id spec/pos-int-32)
(s/def ::amount (s/and decimal? (complement zero?) #(< -100000000M % 100000000M)))

(s/def ::total (s/keys :req [::person_id ::category_id ::amount]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-totals
  "Returns all rows from the totals table."
  [conn]
  (jdbc/query conn ["SELECT * FROM totals ORDER BY person_id, category_id"]
              {:qualifier (namespace ::_)}))

(defn compute-totals
  "Returns freshly computed totals. If everything's working, this should always
  produce the same thing as get-totals."
  [conn]
  (jdbc/query conn ["SELECT * FROM dynamic_totals ORDER BY person_id, category_id"]
              {:qualifier (namespace ::_)}))
