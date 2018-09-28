(ns haus.db.transactions.query
  "Specs for transaction queries."
  (:require [clojure.core.match :refer [match]]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [haus.core.spec :as spec]
            [haus.db.util.where :as where]
            [taoensso.truss :refer [have]]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Specs
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

; Any date value can be either a java.sql.Date or a "YYYY-MM-DD" string.
(s/def ::-date (s/or :sql-date spec/sql-date
                     :date-str spec/sql-date-str))

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


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Renderer
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti ^:private render-param
  "Renders get-transactions query parameters into WHERE clauses. Argument is a
  MapEntry from a ::params structure."
  first)

(defmethod render-param ::id
  [[_ id]]
  ["id = ?", id])

(defmethod render-param ::before
  [[_ [date id]]]
  ["(date < ? OR (date = ? AND id < ?))", date date id])

(defmethod render-param ::after
  [[_ [date id]]]
  ["(date > ? OR (date = ? AND id > ?))", date date id])

(defmethod render-param ::date
  [[_ arg]]
  (match [arg]
    [[:eq date]] ["date = ?", date]
    [[:lt date]] ["date < ?", date]
    [[:le date]] ["date <= ?", date]
    [[:ge date]] ["date >= ?", date]
    [[:gt date]] ["date > ?", date]
    [[:in start end]] ["date BETWEEN ? AND ?", start end]))

(defmethod render-param ::category_id
  [[_ [op & values]]]
  (match [op values]
    [_ [id]] ["category_id = ?", id]
    [:anyof ids] ["ARRAY[category_id] <@ ?", (to-array (distinct ids))]
    [:allof ids] ["ARRAY[category_id] @> ?", (to-array (distinct ids))]))  ; Not actually useful.

(defmethod render-param ::text
  [[_ [op & values]]]
  (let [where "to_tsvector('english', title || ' ' || description) @@ to_tsquery('english', ?)"]
    (case op
      (:anyof) [where, (str/join " | " (distinct values))]
      (:allof) [where, (str/join " & " (distinct values))])))

(defmethod render-param ::tag
  [[_ [op & values]]]
  (case op
    (:anyof) ["tags && ?", (to-array (distinct values))]
    (:allof) ["tags @> ?", (to-array (distinct values))]))

(defmethod render-param ::person_id
  [[_ [op & values]]]
  (match [op values]
    [_ [id]]
    ["t.id IN (SELECT transaction_id FROM splits WHERE person_id = ?)", id]

    [:anyof ids]
    ["t.id IN (SELECT transaction_id FROM splits WHERE ARRAY[person_id] && ?)",
     (to-array (distinct ids))]

    ; Defines a filtered splits table for each person_id we're looking for,
    ; then inner-joins them all together.
    [:allof ids]
    (let [ids (vec (sort (distinct ids)))
          from-clause (reduce #(if (empty? %1)
                                 (format "splits AS s%d" %2)
                                 (format "%s JOIN splits AS s%d USING (transaction_id)" %1 %2))
                        "" ids)
          where-clause (str/join " AND " (map #(format "(s%d.person_id = ?)" %) ids))]
      (cons (str "t.id IN (SELECT transaction_id FROM " from-clause " WHERE " where-clause ")") ids))))

(defmethod render-param :default [_] [])


(defn ^:private render-params
  [params]
  (let [params (have (partial s/valid? ::params) params)]
    (where/join "AND" (map render-param params))))

(s/fdef render-params
  :args (s/cat :params ::params)
  :ret (s/or :empty empty?
             :not-empty (s/cat :where string?
                               :params (s/+ any?))))

(comment
  (clojure.spec.test.alpha/check `render-params))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Query
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

; Wraps a dictionary of parameters to implement the Where protocol.
(defrecord Query [params])

(extend-protocol where/Where
  Query
  (render [this]
    (render-params (:params this))))

(defn transaction-query [params]
  (->Query params))
