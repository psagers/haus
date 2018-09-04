(ns haus.db
  (:require [clojure.core.match :refer [match]]
            [clojure.data.json :as json]
            [clojure.java.jdbc :as jdbc]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [haus.core.config :as config]
            [haus.db.util :refer [where-join]]
            [taoensso.truss :refer [have]]))

; Static database parameters. These can't be overridden by external
; configuration.
(def db-params
  {:dbtype "pgsql"
   :classname "com.impossibl.postgres.jdbc.PGDriver"})

; By default, our database spec will be loaded from the config. The tests will
; override it.
(def ^:dynamic *db-spec*
  (delay (config/deep-merge (get @config/config :db) db-params)))


;
; Primitive queries
;
; These have consistent return types:
;
;   get-things: A list of maps representing (decoded) database rows.
;   get-thing: A single map, as above.
;   insert-thing!: The primary key of the thing inserted.
;   update-thing!: True iff we updated something.
;   delete-thing!: True iff we deleted something.
;


;
; People
;

(defn get-people [con]
  (jdbc/query con ["SELECT * FROM people"]))

(defn get-person [con id]
  (first (jdbc/query con ["SELECT * FROM people WHERE id = ?", id])))

(defn insert-person! [con person]
  (let [[{id :id}] (jdbc/insert! con :people person)]
    id))

(defn update-person! [con id person]
  (let [[n] (jdbc/update! con :people person ["id = ?", id])]
    (> n 0)))

(defn delete-person! [con id]
  (let [[n] (jdbc/delete! con :people ["id = ?", id])]
    (> n 0)))


;
; Categories
;

(defn get-categories [con]
  (jdbc/query con ["SELECT * FROM categories"]))

(defn get-category [con id]
  (first (jdbc/query con ["SELECT * FROM categories WHERE id = ?", id])))

(defn insert-category! [con category]
  (let [[{id :id}] (jdbc/insert! con :categories category)]
    id))

(defn update-category! [con id category]
  (let [[n] (jdbc/update! con :categories category ["id = ?", id])]
    (> n 0)))

(defn delete-category! [con id]
  (let [[n] (jdbc/delete! con :categories ["id = ?", id])]
    (> n 0)))


;
; Transactions
;
; Transactions and splits are managed together. Every transaction should be
; considered to have a virtual field called :splits, which is a sequence of
; split rows.
;
; We do a limited amount of decoding of row values into more usable Clojure
; collections.
;

(defn ^:private decode-splits
  "Decodes a sequence of split rows."
  [splits]
  (letfn [(decode-value [key value]
            (case key
              (:amount) (.setScale (bigdec value) 2)
              value))]
    (vec (filter some? (json/read-str splits, :key-fn keyword, :value-fn decode-value)))))

(defn ^:private decode-transaction
  "Decodes a transaction row."
  [row]
  (-> row
      (update :tags vec)
      (update :splits decode-splits)))

(defn ^:private encode-tags
  "Encodes a vector of tags for storage."
  [tags]
  (let [xform (comp (filter string?)
                    (remove empty?)
                    (map str/lower-case))]
    (to-array (eduction xform tags))))

(defn ^:private encode-transaction
  "Encodes a map of transaction values for storage."
  [txn]
  (cond-> (select-keys txn [:date :category_id :title :description :tags])
          (contains? txn :tags) (update :tags encode-tags)))

(defn ^:private encode-split
  "Encodes a map of split values for storage."
  [split]
  (select-keys split [:person_id :amount]))

(defn ^:private encode-splits
  "Encodes a sequence of split values for storage."
  [splits]
  (->> splits
       (map encode-split)
       (remove #(zero? (get % :amount)))))

(defn ^:private validate-splits
  "Throws an IllegalArgumentException if a sequence of splits are not
  collectively valid. This is a last-ditch effort to keep the database in a
  sane state; unbalanced splits should be caught and handled higher up."
  [splits]
  (when-not (zero? (transduce (map :amount) + splits))
    (throw (IllegalArgumentException. "Splits do not sum to 0"))))


(defmulti ^:private txn-param->clause
  "Renders get-transactions query parameters into WHERE clauses."
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
    [(date :guard string?)] ["date = ?", date]
    [[:lt date]] ["date < ?", date]
    [[:le date]] ["date <= ?", date]
    [[:ge date]] ["date >= ?", date]
    [[:gt date]] ["date > ?", date]
    [[:in start end]] ["date >= ? AND date <= ?", start end]))

(defmethod txn-param->clause :category_id
  [[_ values]]
  (match [values]
    [(id :guard integer?)] ["category_id = ?", id]
    [[:anyof & (ids :guard not-empty)]] ["ARRAY[category_id] <@ ?", (to-array (set (have integer? :in ids)))]
    [[:allof & (ids :guard not-empty)]] ["ARRAY[category_id] @> ?", (to-array (set (have integer? :in ids)))]))

(defmethod txn-param->clause :text
  [[_ values]]
  (let [where "(to_tsvector('english', title || ' ' || description) @@ to_tsquery('english', ?))"]
    (match [values]
      [(word :guard string?)] [where, word]
      [[:anyof & (words :guard not-empty)]] [where, (str/join " | " (set (have string? :in words)))]
      [[:allof & (words :guard not-empty)]] [where, (str/join " & " (set (have string? :in words)))])))

(defmethod txn-param->clause :tag
  [[_ values]]
  (letfn [(tag-array [tags]
            (to-array (map str/lower-case (have string? :in tags))))]
    (match [values]
      [(tag :guard string?)] ["tags && ?", (tag-array [tag])]
      [[:anyof & (tags :guard not-empty)]] ["tags && ?", (tag-array (set (have string? :in tags)))]
      [[:allof & (tags :guard not-empty)]] ["tags @> ?", (tag-array (set (have string? :in tags)))])))

(defmethod txn-param->clause :person_id
  [[_ values]]
  (match [values]
    [(id :guard integer?)]
    ["t.id IN (SELECT transaction_id FROM splits WHERE person_id = ?)", id]

    [[:anyof & (ids :guard not-empty)]]
    ["t.id IN (SELECT transaction_id FROM splits WHERE ARRAY[person_id] && ?)",
     (to-array (set (have integer? :in ids)))]

    ; Defines a filtered splits table for each person_id we're looking for,
    ; then inner-joins them all together.
    [[:allof & (ids :guard not-empty)]]
    (let [ids (vec (sort (set ids)))
          from-clause (reduce #(if (empty? %1)
                                 (format "splits AS s%d" %2)
                                 (format "%s JOIN splits AS s%d USING (transaction_id)" %1 %2))
                        "" ids)
          where-clause (str/join " AND " (map #(format "(s%d.person_id = ?)" %) ids))]
      (concat [(str "t.id IN (SELECT transaction_id FROM " from-clause " WHERE " where-clause ")")] ids))))

(defmethod txn-param->clause :default [_] [])


(defn get-transactions
  "Returns a sequence of transactions. The optional second argument is a
  where-vector (string followed by params)."
  ([con]
   (get-transactions con {}))

  ([con query-params]
   (let [[where & params] (where-join "AND" (map txn-param->clause query-params))
         query (str "SELECT t.id, t.created_at, t.updated_at, t.date, t.category_id, t.title, t.description, t.tags, json_agg(s) AS splits"
                    " FROM transactions AS t LEFT OUTER JOIN splits AS s ON (s.transaction_id = t.id)"
                    (if where (str " WHERE " where))
                    " GROUP BY t.id"
                    " ORDER BY date, id"
                    (if-let [limit (:limit query-params)] (str " LIMIT " limit)))]
     ;(println query ";" params)
     (map decode-transaction
       (jdbc/query con (concat [query] params))))))

(defn get-transaction [con id]
  (first (get-transactions con {:id id})))

(defn insert-transaction!
  "Creates a new transaction. The values should be a map similar to the one
  returned by get-transaction:

    :date (required) - java.sql.Date or SQL date string (YYYY-MM-DD).
    :category_id (required) - id of a category.
    :title (required) - a non-empty string.
    :description (optional) - a longer description.
    :tags (optional) - a sequence of strings.
    :splits (optional) - a sequence of split maps.

  Splits:

    :person_id - id of a person.
    :amount - BigDecimal (or something that PostgreSQL will automatically cast).

  Returns the id of the new transaction."
  [con txn]
  (jdbc/with-db-transaction [con con]
    (let [row (encode-transaction txn)
          splits (encode-splits (get txn :splits []))
          [{txn_id :id}] (jdbc/insert! con :transactions row)]
      (when-not (empty? splits)
        (validate-splits splits)
        (jdbc/insert-multi! con :splits (map #(assoc % :transaction_id txn_id) splits)))
      txn_id)))

(defn update-transaction!
  "Updates an existing transaction. The txn argument is the same as for
  insert-transaction!. All fields are optional, although if tags or splits are
  given, they will replace any existing values."
  [con txn_id txn]
  (jdbc/with-db-transaction [con con]
    (let [row (encode-transaction txn)
          splits (encode-splits (get txn :splits []))]

      ; Apply transaction updates, if any.
      (when-not (empty? row)
        (jdbc/update! con :transactions row ["id = ?", txn_id]))

      ; Apply split updates, if any.
      (when-not (empty? splits)
        (validate-splits splits)
        (jdbc/delete! con :splits ["transaction_id = ?", txn_id])
        (jdbc/insert-multi! con :splits (map #(assoc % :transaction_id txn_id) splits)))))
  true)

(defn delete-transaction!
  "Deletes an existing transaction."
  [con txn_id]
  (let [[n] (jdbc/delete! con :transactions ["id = ?", txn_id])]
    (> n 0)))


;
; Templates
;
; Templates are like transactions, minus a few fields.
;

(defn ^:private decode-template
  "Decodes a template row."
  [row]
  (-> row
      (update :tags vec)
      (update :splits decode-splits)))

(defn ^:private encode-template
  "Encodes a map of template values for storage."
  [tmpl]
  (cond-> (select-keys [:category_id :title :description :tags])
          (contains? tmpl :tags) (update :tags encode-tags)))

(defn get-templates
  "Returns all templates."
  ([con]
   (let [query "SELECT t.id, t.created_at, t.updated_at, t.date, t.category_id, t.title, t.description, t.tags, json_agg(s) AS splits
                FROM templates AS t LEFT OUTER JOIN template_splits AS s ON (s.template_id = t.id)
                GROUP BY t.id"]
     (map decode-template
          (jdbc/query con [query])))))

(defn insert-template!
  "Creates a new template. The template map is the same as the transaction map,
  minus the date."
  [con tmpl]
  (jdbc/with-db-transaction [con con]
    (let [row (encode-template tmpl)
          splits (map encode-split (get tmpl :splits []))
          [{tmpl_id :id}] (jdbc/insert! con :templates row)]
      (when-not (empty? splits)
        (jdbc/insert-multi! con :template_splits (map #(assoc % :template_id tmpl_id) splits)))
      tmpl_id)))

(defn update-template!
  "Updates an existing template."
  [con tmpl_id tmpl]
  (jdbc/with-db-transaction [con con]
    (let [row (encode-template tmpl)
          splits (map encode-split (get tmpl :splits []))]

      (when-not (empty? row)
        (jdbc/update! con :templates row ["id = ?", tmpl_id]))

      (when-not (empty? splits)
        (jdbc/delete! con :splits ["template_id = ?", tmpl_id])
        (jdbc/insert-multi! con :splits (map #(assoc % :template_id tmpl_id) splits)))))
  true)

(defn delete-template!
  "Deletes an existing template."
  [con tmpl_id]
  (let [[n] (jdbc/delete! con :templates ["id = ?", tmpl_id])]
    (> n 0)))


;
; Misc
;

(defn all-tags
  "Returns a list of all known transaction tags."
  [con]
  (map :tag (jdbc/query con ["SELECT DISTINCT unnest(tags) AS tag FROM transactions"])))

(defn get-totals
  "Returns all rows from the totals table."
  [con]
  (jdbc/query con ["SELECT * FROM totals ORDER BY person_id, category_id"]))
