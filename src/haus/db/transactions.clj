(ns haus.db.transactions
  (:require [clojure.data.json :as json]
            [clojure.instant :as inst]
            [clojure.java.jdbc :as jdbc]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.string :as str]
            [haus.core.spec :as spec]
            [haus.core.util :as util]
            [haus.db.util.model :as model]
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
  (letfn [(amount-gen' [[magnitude sign]]
            (-> (bigdec magnitude)
                (+ 9999M)
                (/ 100M)
                (* sign)))]
    (gen/fmap amount-gen' (gen/tuple (gen/large-integer* {:min 1, :max 999990000})
                                     (gen/elements [-1 1])))))


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
                           gen/string-ascii))
(s/def ::description (s/with-gen string? gen/string-ascii))
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
; Model
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord Transactions [])

(extend-protocol model/Model
  Transactions

  (-qualifier [this]
    "haus.db.transactions")

  (-query [this conn {:keys [where limit]}]
          (let [[where-sql & where-params] (where/render where)
                sql (str "SELECT t.*, json_agg(s) AS splits"
                         " FROM transactions AS t LEFT OUTER JOIN splits AS s ON (s.transaction_id = t.id)"
                         (if where-sql (str " WHERE " where-sql))
                         " GROUP BY t.id"
                         " ORDER BY t.date, t.id"
                         (if limit (str " LIMIT " limit)))]
            (jdbc/query conn (cons sql where-params)
                        {:qualifier "haus.db.transactions"
                         :row-fn decode-transaction})))

  (-insert! [this conn obj]
    (jdbc/with-db-transaction [conn conn]
      (let [row (encode-transaction obj)
            splits (encode-splits (get obj ::splits []))
            [{id :id}] (jdbc/insert! conn :transactions row)]

        ; Add the splits, if any.
        (when-let [splits (not-empty splits)]
          (validate-splits splits)
          (jdbc/insert-multi! conn :splits (map #(assoc % ::transaction_id id) splits)))

        ; Return the new row id.
        id)))

  (-update! [this conn id obj]
    (jdbc/with-db-transaction [conn conn]
      (let [row (encode-transaction obj)
            splits (encode-splits (get obj ::splits []))]

        ; Apply transaction updates, if any.
        (when-let [row (not-empty row)]
          (jdbc/update! conn :transactions row ["id = ?", id]))

        ; Apply split updates, if any.
        (when-let [splits (not-empty splits)]
          (validate-splits splits)
          (jdbc/delete! conn :splits ["transaction_id = ?", id])
          (jdbc/insert-multi! conn :splits (map #(assoc % ::transaction_id id) splits)))

        ; Did we update anything?
        (not-every? empty? [row splits]))))

  (-delete! [this conn id]
    (let [[n] (jdbc/delete! conn :transactions ["id = ?", id])]
      (> n 0))))


; The transactions model.
(def transactions (->Transactions))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Misc
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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
