(ns haus.db.people
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [haus.core.spec :as spec]
            [haus.core.util :as util]
            [haus.db :as db]))


(s/def ::id spec/pos-int-32)
(s/def ::name (s/with-gen (s/and string? #(<= 1 (count %) 50))
                          #(gen/string-alphanumeric)))

; A single person from the database.
(s/def ::person (s/keys :req [::id ::name]))


(def qualify-keys (partial util/qualify-keys (str *ns*)))


(defn get-people []
  (let [rows (jdbc/query @db/*db-con* ["SELECT * FROM people"])]
    (map qualify-keys rows)))

(s/fdef get-people
  :args (s/cat)
  :ret (s/coll-of ::person))


(defn get-person [id]
  (let [rows (jdbc/query @db/*db-con* ["SELECT * FROM people WHERE id = ?", id])]
    (first (map qualify-keys rows))))

(s/fdef get-person
  :args (s/cat :id ::id)
  :ret (s/nilable ::person))


(defn insert-person! [person]
  (let [[{id :id}] (jdbc/insert! @db/*db-con* :people person)]
    id))

(s/fdef insert-person!
  :args (s/cat :person (s/keys :req [::name]))
  :ret ::id)


(defn update-person! [id person]
  (let [[n] (jdbc/update! @db/*db-con* :people person ["id = ?", id])]
    (> n 0)))

(s/fdef update-person!
  :args (s/cat :id ::id
               :person (s/keys :opt [::name]))
  :ret boolean?)


(defn delete-person! [id]
  (let [[n] (jdbc/delete! @db/*db-con* :people ["id = ?", id])]
    (> n 0)))

(s/fdef update-person!
  :args (s/cat :id ::id)
  :ret boolean?)
