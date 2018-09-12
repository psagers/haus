(ns haus.db.categories
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [haus.core.spec :as spec]
            [haus.core.util :as util]
            [haus.db :as db]))


(s/def ::id spec/pos-int-32)
(s/def ::name (s/with-gen (s/and string? #(<= 1 (count %) 50))
                          #(gen/string-alphanumeric)))

; A single category from the database.
(s/def ::category (s/keys :req [::id ::name]))


(def qualify-keys (partial util/qualify-keys (str *ns*)))


(defn get-categories []
  (let [rows (jdbc/query @db/*db-con* ["SELECT * FROM categories"])]
    (map qualify-keys rows)))

(s/fdef get-categories
  :args (s/cat)
  :ret (s/coll-of ::category))


(defn get-category [id]
  (let [rows (jdbc/query @db/*db-con* ["SELECT * FROM categories WHERE id = ?", id])]
    (first (map qualify-keys rows))))

(s/fdef get-category
  :args (s/cat :id ::id)
  :ret (s/nilable ::category))


(defn insert-category! [category]
  (let [[{id :id}] (jdbc/insert! @db/*db-con* :categories category)]
    id))

(s/fdef insert-category!
  :args (s/cat :category (s/keys :req [::name]))
  :ret ::id)


(defn update-category! [id category]
  (let [[n] (jdbc/update! @db/*db-con* :categories category ["id = ?", id])]
    (> n 0)))

(s/fdef update-category!
  :args (s/cat :id ::id
               :category (s/keys :opt [::name]))
  :ret boolean?)


(defn delete-category! [id]
  (let [[n] (jdbc/delete! @db/*db-con* :categories ["id = ?", id])]
    (> n 0)))

(s/fdef update-category!
  :args (s/cat :id ::id)
  :ret boolean?)
