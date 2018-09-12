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


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Row -> Clojure
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private decode-total
  "Decodes a row from the totals table."
  [row]
  (util/qualify-keys (namespace ::a) row))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-totals
  "Returns all rows from the totals table."
  []
  (map decode-total
    (jdbc/query @db/*db-con* ["SELECT * FROM totals ORDER BY person_id, category_id"])))

(defn compute-totals
  "Returns freshly computed totals. If everything's working, this should always
  produce the same thing as get-totals."
  []
  (map decode-total
    (jdbc/query @db/*db-con* ["SELECT * FROM dynamic_totals ORDER BY person_id, category_id"])))
