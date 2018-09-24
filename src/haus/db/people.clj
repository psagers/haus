(ns haus.db.people
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [haus.core.spec :as spec]
            [haus.core.util :as util]))


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
; API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-people [conn]
  (jdbc/query conn ["SELECT * FROM people"]
              {:qualifier (namespace ::_)}))

(s/fdef get-people
  :args (s/cat :conn :haus.db/conn)
  :ret (s/coll-of ::person))


(defn get-person [conn id]
  (jdbc/query conn ["SELECT * FROM people WHERE id = ?", id]
              {:qualifier (namespace ::_)
               :result-set-fn first}))

(s/fdef get-person
  :args (s/cat :conn :haus.db/conn
               :id ::id)
  :ret (s/nilable ::person))


(defn insert-person! [conn person]
  (let [[{id :id}] (jdbc/insert! conn :people person)]
    id))

(s/fdef insert-person!
  :args (s/cat :conn :haus.db/conn
               :person ::insert-params)
  :ret ::id)


(defn update-person! [conn id person]
  (let [[n] (jdbc/update! conn :people person ["id = ?", id])]
    (> n 0)))

(s/fdef update-person!
  :args (s/cat :conn :haus.db/conn
               :id ::id
               :person ::update-params)
  :ret boolean?)


(defn delete-person! [conn id]
  (let [[n] (jdbc/delete! conn :people ["id = ?", id])]
    (> n 0)))

(s/fdef delete-person!
  :args (s/cat :conn :haus.db/conn
               :id ::id)
  :ret boolean?)
