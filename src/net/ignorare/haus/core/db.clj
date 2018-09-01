(ns net.ignorare.haus.core.db
  (:require [clojure.data.json :as json]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [migratus.core :as migratus]
            [net.ignorare.haus.core.config :as config]
            [taoensso.timbre :as timbre]
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
  ; PostgreSQL renders ::money values to JSON as strings, complete with
  ; currency symbol. We need to convert them back to BigDecimal. If the
  ; transaction had no splits, we got "[null]".
  (letfn [(decode-value [key value]
            (case key
              (:amount) (-> (have string? value) (str/replace #"[^-\d\.]" "") (BigDecimal.))
              value))]
    (filter some? (json/read-str splits, :key-fn keyword, :value-fn decode-value))))

(defn ^:private decode-transaction
  "Decodes a transaction row."
  [row]
  (-> row
      (update-in [:tags] vec)
      (update-in [:splits] decode-splits)))

(defn ^:private encode-tags-field
  "Encodes the tags in a transaction map."
  [txn]
  (if (contains? txn :tags)
    (update-in txn [:tags] #(->> % (filter string?) (remove empty?) (to-array)))
    txn))

(defn ^:private encode-transaction
  "Encodes a map of transaction values for storage."
  [txn]
  (-> txn
      (select-keys [:date :category_id :title :description :tags])
      (encode-tags-field)))

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

(defn get-transactions
  "Returns a sequence of transactions. The optional second argument is a
  where-vector (string followed by params)."
  ([con]
   (get-transactions con nil))

  ([con [where & params]]
   (let [query (str "SELECT t.id, t.created_at, t.updated_at, t.date, t.category_id, t.title, t.description, t.tags, json_agg(s) AS splits"
                    " FROM transactions AS t LEFT OUTER JOIN splits AS s ON (s.transaction_id = t.id)"
                    (if where (str " WHERE " where) "")
                    " GROUP BY t.id"
                    " ORDER BY date, id")]
     (map decode-transaction
          (jdbc/query con (concat [query] params))))))

(defn get-transaction [con id]
  (first (get-transactions con ["id = ?", id])))

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
      (update-in [:tags] vec)
      (update-in [:splits] decode-splits)))

(defn ^:private encode-template
  "Encodes a map of template values for storage."
  [tmpl]
  (-> tmpl
      (select-keys [:category_id :title :description :tags])
      (encode-tags-field)))

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


(defn get-totals
  "Returns all rows from the totals table."
  [con]
  (jdbc/query con ["SELECT * FROM totals ORDER BY person_id, category_id"]))


;
; Migration operations
;


(defn migratus-config []
  {:store :database
   :migration-dir "migrations"
   :db @*db-spec*})

(defn migrate []
  (migratus/migrate (migratus-config)))

(defn rollback []
  (migratus/rollback (migratus-config)))

(defn up [& ids]
  (apply migratus/up (migratus-config) ids))

(defn down [& ids]
  (apply migratus/down (migratus-config) ids))

(defn reset []
  (migratus/reset (migratus-config)))

(defn pending-list []
  (migratus/pending-list (migratus-config)))

(defn -main
  "Entry point for a leiningen alias."
  ([]
   (println "Database managment commands:

  migrate: Apply all missing migrations.
  rollback: Unapply the latest migrations.
  up id ...: Apply one or more migrations by numeric identifier.
  down id ...: Unapply one or more migrations by numeric identifier.
  reset: Unapply and reapply all migrations.
  pending-list: List unapplied migrations."))
  ([action & args]
   (timbre/with-level (config/log-level)
     (case action
       ("migrate") (migrate)
       ("rollback") (rollback)
       ("up") (apply up (map #(Integer/parseInt %) args))
       ("down") (apply down (map #(Integer/parseInt %) args))
       ("reset") (reset)
       ("pending-list") (println (pending-list))
       (println (str "Unknown db action: " action))))))
