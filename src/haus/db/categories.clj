(ns haus.db.categories
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

; A single category from the database.
(s/def ::category (s/keys :req [::id ::name]))

; Parameters for inserting a new category.
(s/def ::insert-params (spec/exclusive-keys :req [::name]))

; Parameters for updating an existing category.
(s/def ::update-params (spec/exclusive-keys :req [::name]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-categories [conn]
  (jdbc/query conn ["SELECT * FROM categories"]
              {:qualifier (namespace ::_)}))

(s/fdef get-categories
  :args (s/cat :conn :haus.db/conn)
  :ret (s/coll-of ::category))


(defn get-category [conn id]
  (jdbc/query conn ["SELECT * FROM categories WHERE id = ?", id]
              {:qualifier (namespace ::_)
               :result-set-fn first}))

(s/fdef get-category
  :args (s/cat :conn :haus.db/conn
               :id ::id)
  :ret (s/nilable ::category))


(defn insert-category! [conn category]
  (let [[{id :id}] (jdbc/insert! conn :categories category)]
    id))

(s/fdef insert-category!
  :args (s/cat :conn :haus.db/conn
               :category ::insert-params)
  :ret ::id)


(defn update-category! [conn id category]
  (let [[n] (jdbc/update! conn :categories category ["id = ?", id])]
    (> n 0)))

(s/fdef update-category!
  :args (s/cat :conn :haus.db/conn
               :id ::id
               :category ::update-params)
  :ret boolean?)


(defn delete-category! [conn id]
  (let [[n] (jdbc/delete! conn :categories ["id = ?", id])]
    (> n 0)))

(s/fdef delete-category!
  :args (s/cat :conn :haus.db/conn
               :id ::id)
  :ret boolean?)
