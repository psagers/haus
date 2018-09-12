(ns haus.db.transactions
  (:require [clojure.core.match :refer [match]]
            [clojure.data.json :as json]
            [clojure.instant :as inst]
            [clojure.java.jdbc :as jdbc]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [haus.core.spec :as spec]
            [haus.core.util :as util]
            [haus.db :as db]
            [haus.db.util :refer [where-join]]
            [taoensso.truss :refer [have]]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Specs
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn sum-split-amounts
  "A helper function to sum the amounts of a sequence of splits."
  [splits]
  (transduce (map ::amount) + splits))

(defn amount-gen
  "Generates amount values in a sensible range. This starts at [-]100.00M to
  avoid generating a bunch of 0.0x values. It's cosmetic as much as anything."
  []
  (gen/let [magnitude (gen/large-integer* {:min 1, :max 9999990000})
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
(s/def ::tag (s/with-gen (spec/simple-conformer util/tag? str/lower-case)
                         (fn [] gen/string-alphanumeric)))
(s/def ::tags (s/coll-of ::tag))
(s/def ::splits (s/and (s/coll-of ::split)
                       #(or (empty? %) (apply distinct? (map ::person_id %)))
                       #(zero? (sum-split-amounts %))))


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
    (vec (filter some? (json/read-str splits
                                      :key-fn (partial keyword (namespace ::a))
                                      :value-fn decode-value)))))

(defn ^:private decode-transaction
  "Decodes a transaction row."
  [row]
  (-> (util/qualify-keys (namespace ::a) row)
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
  (select-keys split [::person_id ::amount]))

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
  (when-not (zero? (sum-split-amounts splits))
    (throw (IllegalArgumentException. "Splits do not sum to 0"))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Query parameters
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti ^:private txn-param->clause
  "Renders get-transactions query parameters into WHERE clauses. Argument is a
  MapEntry."
  first)

(defmethod txn-param->clause :id
  [[_ id]]
  ["id = ?", id])

(defmethod txn-param->clause :before
  [[_ [date id]]]
  ["(date < ? OR (date = ? AND id < ?))", date date id])

(defmethod txn-param->clause :after
  [[_ [date id]]]
  ["(date > ? OR (date = ? AND id > ?))", date date id])

(defmethod txn-param->clause :date
  [[_ args]]
  (match [args]
    [[:lt date]] ["date < ?", date]
    [[:le date]] ["date <= ?", date]
    [[:ge date]] ["date >= ?", date]
    [[:gt date]] ["date > ?", date]
    [[:in start end]] ["date BETWEEN ? AND ?", start end]
    [date] ["date = ?", date]))

(defmethod txn-param->clause :category_id
  [[_ values]]
  (match [values]
    [[:anyof & ids]] ["ARRAY[category_id] <@ ?", (to-array (set (have integer? :in ids)))]
    [[:allof & ids]] ["ARRAY[category_id] @> ?", (to-array (set (have integer? :in ids)))]
    [id] ["category_id = ?", id]))

(defmethod txn-param->clause :text
  [[_ values]]
  (let [where "(to_tsvector('english', title || ' ' || description) @@ to_tsquery('english', ?))"]
    (match [values]
      [[:anyof & words]] [where, (str/join " | " (set (have string? :in words)))]
      [[:allof & words]] [where, (str/join " & " (set (have string? :in words)))]
      [word] [where, word])))

(defmethod txn-param->clause :tag
  [[_ values]]
  (letfn [(tag-array [tags]
            (to-array (map str/lower-case (have string? :in tags))))]
    (match [values]
      [[:anyof & tags]] ["tags && ?", (tag-array (set (have string? :in tags)))]
      [[:allof & tags]] ["tags @> ?", (tag-array (set (have string? :in tags)))]
      [tag] ["tags && ?", (tag-array [tag])])))

(defmethod txn-param->clause :person_id
  [[_ values]]
  (match [values]
    [[:anyof & ids]]
    ["t.id IN (SELECT transaction_id FROM splits WHERE ARRAY[person_id] && ?)",
     (to-array (set (have integer? :in ids)))]

    ; Defines a filtered splits table for each person_id we're looking for,
    ; then inner-joins them all together.
    [[:allof & ids]]
    (let [ids (vec (sort (set ids)))
          from-clause (reduce #(if (empty? %1)
                                 (format "splits AS s%d" %2)
                                 (format "%s JOIN splits AS s%d USING (transaction_id)" %1 %2))
                        "" ids)
          where-clause (str/join " AND " (map #(format "(s%d.person_id = ?)" %) ids))]
      (concat [(str "t.id IN (SELECT transaction_id FROM " from-clause " WHERE " where-clause ")")] ids))

    [id]
    ["t.id IN (SELECT transaction_id FROM splits WHERE person_id = ?)", id]))

(defmethod txn-param->clause :default [_] [])


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-transactions
  "Returns a sequence of transactions. The optional argument is a map of query
  parameters."
  ([]
   (get-transactions {}))

  ([query-params]
   (let [[where & params] (where-join "AND" (map txn-param->clause query-params))
         query (str "SELECT t.id, t.created_at, t.updated_at, t.date, t.category_id, t.title, t.description, t.tags, json_agg(s) AS splits"
                    " FROM transactions AS t LEFT OUTER JOIN splits AS s ON (s.transaction_id = t.id)"
                    (if where (str " WHERE " where))
                    " GROUP BY t.id"
                    " ORDER BY date, id"
                    (if-let [limit (:limit query-params)] (str " LIMIT " limit)))]
     ;(println query ";" params)
     (map decode-transaction
       (jdbc/query @db/*db-con* (concat [query] params))))))

(defn get-transaction [id]
  (first (get-transactions {:id id})))

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
  [txn]
  (jdbc/with-db-transaction [con @db/*db-con*]
    (let [row (encode-transaction txn)
          splits (encode-splits (get txn ::splits []))
          [{txn_id :id}] (jdbc/insert! con :transactions row)]
      (when-not (empty? splits)
        (validate-splits splits)
        (jdbc/insert-multi! con :splits (map #(assoc % ::transaction_id txn_id) splits)))
      txn_id)))

(defn update-transaction!
  "Updates an existing transaction. The txn argument is the same as for
  insert-transaction!. All fields are optional, although if tags or splits are
  given, they will replace any existing values."
  [txn_id txn]
  (jdbc/with-db-transaction [con @db/*db-con*]
    (let [row (encode-transaction txn)
          splits (encode-splits (get txn ::splits []))]

      ; Apply transaction updates, if any.
      (when-not (empty? row)
        (jdbc/update! con :transactions row ["id = ?", txn_id]))

      ; Apply split updates, if any.
      (when-not (empty? splits)
        (validate-splits splits)
        (jdbc/delete! con :splits ["transaction_id = ?", txn_id])
        (jdbc/insert-multi! con :splits (map #(assoc % ::transaction_id txn_id) splits)))

      true)))

(defn delete-transaction!
  "Deletes an existing transaction."
  [txn_id]
  (let [[n] (jdbc/delete! @db/*db-con* :transactions ["id = ?", txn_id])]
    (> n 0)))

(defn all-tags
  "Returns a list of all known transaction tags."
  [con]
  (map :tag (jdbc/query con ["SELECT DISTINCT unnest(tags) AS tag FROM transactions"])))
