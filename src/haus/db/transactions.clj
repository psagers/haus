(ns haus.db.transactions
  (:require [clojure.core.match :refer [match]]
            [clojure.data.json :as json]
            [clojure.instant :as inst]
            [clojure.java.jdbc :as jdbc]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as sgen]
            [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [haus.core.spec :as spec]
            [haus.core.util :as util]
            [haus.db :as db]
            [haus.db.transactions.query :as q]
            [haus.db.util.where :as where]
            [taoensso.truss :refer [have]]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Specs
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn sum-splits
  "A helper function to sum the amounts of a sequence of splits."
  [splits]
  (transduce (map ::amount) + splits))

(defn amount-gen
  "Generates amount values in a sensible range. This starts at [-]100.00M to
  avoid generating a bunch of 0.0x values. It's cosmetic as much as anything."
  []
  (gen/let [magnitude (gen/large-integer* {:min 1, :max 999990000})
            sign (gen/elements [-1 1])]
    (-> (bigdec magnitude)
        (+ 9999M)
        (/ 100M)
        (* sign))))


(s/def ::transaction_id pos-int?)
(s/def ::person_id spec/pos-int-32)
(s/def ::amount (s/with-gen (s/and decimal? (complement zero?) #(< -100000000M % 100000000M))
                            amount-gen))
(s/def ::split (s/keys :req [::person_id ::amount]))

(s/def ::id pos-int?)
(s/def ::created_at spec/sql-timestamp)
(s/def ::updated_at spec/sql-timestamp)
(s/def ::date (s/or :sql-date spec/sql-date
                    :java-date spec/java-date
                    :joda-datetime spec/joda-datetime
                    :string spec/sql-date-str))
(s/def ::category_id spec/pos-int-32)
(s/def ::title (s/with-gen (s/and string? #(<= 1 (count %) 50))
                           (fn [] gen/string-ascii)))
(s/def ::description (s/with-gen string? (fn [] gen/string-ascii)))
(s/def ::tags (s/coll-of spec/tag))
(s/def ::splits (s/and (s/coll-of ::split)
                       #(or (empty? %) (apply distinct? (map ::person_id %)))
                       #(zero? (sum-splits %))))

; A single transaction from the database, after decoding.
(s/def ::transaction (s/keys :req [::id ::created_at ::updated_at ::date ::category_id
                                   ::title ::description ::tags ::splits]))

(s/def ::insert-params (s/keys :req [::date ::category_id ::title]
                               :opt [::description ::tags ::splits]))

(s/def ::update-params (s/keys :opt [::date ::category_id ::title ::description ::tags
                                     ::splits]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Row -> Clojure
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private decode-splits
  "Decodes a sequence of split rows."
  [splits]
  (letfn [(decode-value [key value]
            (case key
              (::amount) (-> value (bigdec) (.setScale 2))
              value))]
    (vec (filter some? (json/read-str (have string? splits)
                                      :key-fn (partial keyword (namespace ::_))
                                      :value-fn decode-value)))))

(defn ^:private decode-transaction
  "Decodes a transaction row."
  [row]
  (-> row
      (update ::tags vec)
      (update ::splits decode-splits)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Clojure -> Row
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private encode-tags
  "Encodes a vector of tags for storage."
  [tags]
  (->> tags
       (eduction (filter string?) (remove empty?) (map str/lower-case) (distinct))
       (to-array)))

(defn ^:private encode-date
  "Encodes supported date formats for storage."
  [date]
  (cond
    (util/sql-date-str? date) (recur (inst/read-instant-date date))
    (instance? java.sql.Date date) date
    (instance? java.util.Date date) (java.sql.Date. (.getTime date))
    (instance? org.joda.time.DateTime date) (java.sql.Date. (.getMillis date))
    :else (throw (IllegalArgumentException. (str "Unsupported date value: " date)))))

(defn ^:private encode-transaction
  "Encodes a map of transaction values for storage."
  [txn]
  (cond-> (select-keys txn [::date ::category_id ::title ::description ::tags])
          (contains? txn ::date) (update ::date encode-date)
          (contains? txn ::tags) (update ::tags encode-tags)))

(defn ^:private encode-split
  "Encodes a map of split values for storage."
  [split]
  (-> split
      (select-keys [::person_id ::amount])
      (update ::amount #(some-> % (bigdec) (.setScale 2)))))

(defn ^:private encode-splits
  "Encodes a sequence of split values for storage."
  [splits]
  (->> splits
       (map encode-split)
       (remove (comp zero? ::amount))))

(defn ^:private validate-splits
  "Throws an IllegalArgumentException if a sequence of splits are not
  collectively valid. This is a last-ditch effort to keep the database in a
  sane state; unbalanced splits should be caught and handled higher up."
  [splits]
  (when-not (zero? (sum-splits splits))
    (throw (IllegalArgumentException. "Splits do not sum to 0"))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Query parameters
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti ^:private render-query-param
  "Renders get-transactions query parameters into WHERE clauses. Argument is a
  MapEntry from a conformed ::q/params structure."
  first)

(defmethod render-query-param ::q/id
  [[_ id]]
  ["id = ?", id])

(defmethod render-query-param ::q/before
  [[_ [[_ date] id]]]
  ["(date < ? OR (date = ? AND id < ?))", date date id])

(defmethod render-query-param ::q/after
  [[_ [[_ date] id]]]
  ["(date > ? OR (date = ? AND id > ?))", date date id])

(defmethod render-query-param ::q/date
  [[_ arg]]
  (match [(s/unform ::q/date arg)]
    [[:eq date]] ["date = ?", date]
    [[:lt date]] ["date < ?", date]
    [[:le date]] ["date <= ?", date]
    [[:ge date]] ["date >= ?", date]
    [[:gt date]] ["date > ?", date]
    [[:in start end]] ["date BETWEEN ? AND ?", start end]))

(defmethod render-query-param ::q/category_id
  [[_ {:keys [op values]}]]
  (match [op values]
    [_ [value]] ["category_id = ?", value]
    [:anyof ids] ["ARRAY[category_id] <@ ?", (to-array (distinct ids))]
    [:allof ids] ["ARRAY[category_id] @> ?", (to-array (distinct ids))]))

(defmethod render-query-param ::q/text
  [[_ {:keys [op values]}]]
  (let [where "to_tsvector('english', title || ' ' || description) @@ to_tsquery('english', ?)"]
    (case op
      (:anyof) [where, (str/join " | " (distinct values))]
      (:allof) [where, (str/join " & " (distinct values))])))

(defmethod render-query-param ::q/tag
  [[_ {:keys [op values]}]]
  (case op
    (:anyof) ["tags && ?", (to-array (distinct values))]
    (:allof) ["tags @> ?", (to-array (distinct values))]))

(defmethod render-query-param ::q/person_id
  [[_ {:keys [op values]}]]
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

(defmethod render-query-param :default [_] [])


(defn render-query-params
  "Renders transaction query parameters into a WHERE vector."
  [params]
  (let [params' (s/conform ::q/params params)]
    (if-not (= params' ::s/invalid)
      (where/join "AND" (map render-query-param params'))
      (throw (IllegalArgumentException. (s/explain-str ::q/params params))))))

(s/fdef render-query-params
  :args (s/cat :params ::q/params)
  :ret (s/or :empty empty?
             :not-empty (s/cat :where string?
                               :params (s/+ any?))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-transactions
  "Returns a sequence of transactions. The optional argument is a map of query
  parameters."
  ([conn]
   (get-transactions conn {}))

  ([conn params]
   (let [[where & where-params] (render-query-params params)
         limit (::q/limit params)
         query (str "SELECT t.*, json_agg(s) AS splits"
                    " FROM transactions AS t LEFT OUTER JOIN splits AS s ON (s.transaction_id = t.id)"
                    (if where (str " WHERE " where))
                    " GROUP BY t.id"
                    " ORDER BY t.date, t.id"
                    (if limit (str " LIMIT " limit)))]
     (jdbc/query conn (cons query where-params)
                 {:qualifier (namespace ::_)
                  :row-fn decode-transaction}))))

(s/fdef get-transactions
  :args (s/cat :conn :haus.db/conn
               :params (s/? ::q/params))
  :ret (s/coll-of ::transaction))

(defn get-transaction [conn id]
  (first (get-transactions conn {::q/id id})))

(s/fdef get-transaction
  :args (s/cat :conn :haus.db/conn
               :id pos-int?)
  :ret (s/nilable ::transaction))

(defn insert-transaction!
  "Creates a new transaction. The values should be a map similar to the one
  returned by get-transaction:

    ::date (required) - java.sql.Date or SQL date string (YYYY-MM-DD).
    ::category_id (required) - id of a category.
    ::title (required) - a non-empty string.
    ::description (optional) - a longer description.
    ::tags (optional) - a sequence of strings.
    ::splits (optional) - a sequence of split maps.

  Splits:

    ::person_id - id of a person.
    ::amount - BigDecimal (or something that PostgreSQL will automatically cast).

  Returns the id of the new transaction."
  [conn txn]
  (jdbc/with-db-transaction [conn conn]
    (let [row (encode-transaction txn)
          splits (encode-splits (get txn ::splits []))
          [{txn_id :id}] (jdbc/insert! conn :transactions row)]
      (when-not (empty? splits)
        (validate-splits splits)
        (jdbc/insert-multi! conn :splits (map #(assoc % ::transaction_id txn_id) splits)))
      txn_id)))

(s/fdef insert-transaction!
  :args (s/cat :conn :haus.db/conn
               :txn ::insert-params)
  :ret pos-int?)

(defn update-transaction!
  "Updates an existing transaction. The txn argument is the same as for
  insert-transaction!. All fields are optional, although if tags or splits are
  given, they will replace any existing values."
  [conn txn_id txn]
  (jdbc/with-db-transaction [conn conn]
    (let [row (encode-transaction txn)
          splits (encode-splits (get txn ::splits []))]

      ; Apply transaction updates, if any.
      (when-not (empty? row)
        (jdbc/update! conn :transactions row ["id = ?", txn_id]))

      ; Apply split updates, if any.
      (when-not (empty? splits)
        (validate-splits splits)
        (jdbc/delete! conn :splits ["transaction_id = ?", txn_id])
        (jdbc/insert-multi! conn :splits (map #(assoc % ::transaction_id txn_id) splits)))

      true)))

(s/fdef update-transaction!
  :args (s/cat :conn :haus.db/conn
               :txn_id ::id
               :txn ::update-params)
  :ret boolean?)

(defn delete-transaction!
  "Deletes an existing transaction."
  [conn txn_id]
  (let [[n] (jdbc/delete! conn :transactions ["id = ?", txn_id])]
    (> n 0)))

(s/fdef delete-transaction!
  :args (s/cat :conn :haus.db/conn
               :txn_id ::id)
  :ret boolean?)

(defn all-tags
  "Returns a list of all known transaction tags."
  [conn]
  (jdbc/query conn ["SELECT DISTINCT unnest(tags) AS tag FROM transactions ORDER BY tag"]
              {:raw? true
               :row-fn :tag
               :result-set-fn vec}))

(s/fdef all-tags
  :args (s/cat :conn :haus.db/conn)
  :ret (s/coll-of spec/tag))
