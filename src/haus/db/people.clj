(ns haus.db.people
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [haus.core.spec :as spec]
            [haus.core.util :as util]
            [haus.db :as db]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Spec
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::id spec/pos-int-32)
(s/def ::name (s/with-gen (s/and string? #(<= 1 (count %) 50))
                          #(gen/string-alphanumeric)))

; A single person from the database.
(s/def ::person (s/keys :req [::id ::name]))

; Parameters for inserting a new person.
(s/def ::insert-params (spec/exclusive-keys :req [::name]))

; Parameters for updating an existing person.
(s/def ::update-params (spec/exclusive-keys :req [::name]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Util
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def qualify-keys (partial util/qualify-keys (str *ns*)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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
  :args (s/cat :person ::insert-params)
  :ret ::id)


(defn update-person! [id person]
  (let [[n] (jdbc/update! @db/*db-con* :people person ["id = ?", id])]
    (> n 0)))

(s/fdef update-person!
  :args (s/cat :id ::id
               :person ::update-params)
  :ret boolean?)


(defn delete-person! [id]
  (let [[n] (jdbc/delete! @db/*db-con* :people ["id = ?", id])]
    (> n 0)))

(s/fdef delete-person!
  :args (s/cat :id ::id)
  :ret boolean?)
