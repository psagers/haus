(ns haus.db.util.model
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.spec.alpha :as s]
            [haus.core.spec :as spec]
            [haus.db.util.where :as where]
            [taoensso.truss :refer [have]]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Protocol
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol Model
  "A general interface to one of our SQL tables."
  (-qualifier [this]
    "A namespace (string) for qualifying map keys.")

  (-query [this conn opts])
  (-insert! [this conn obj])
  (-update! [this conn id obj])
  (-delete! [this conn id]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn qualifier [model]
  (-qualifier model))

(defn query [model conn & opts]
  (-query model conn (apply array-map opts)))

(defn get-one [model conn id]
  (first (-query model conn {:where {:id id}})))

(defn insert! [model conn obj]
  (-insert! model conn obj))

(defn update! [model conn id obj]
  (-update! model conn id obj))

(defn delete! [model conn id]
  (-delete! model conn id))


(s/def ::model (spec/satisfies Model))

(s/fdef qualifier
  :args (s/cat :model ::model)
  :ret string?)

(s/fdef query
  :args (s/cat :model ::model
               :conn :haus.db/conn
               :opts (s/* (s/cat :opt keyword? :val any?)))
  :ret seq?)

(s/fdef get-one
  :args (s/cat :model ::model
               :conn :haus.db/conn
               :id pos-int?)
  :ret (s/nilable map?))

(s/fdef insert!
  :args (s/cat :model ::model
               :conn :haus.db/conn
               :obj map?)
  :ret pos-int?)

(s/fdef update!
  :args (s/cat :model ::model
               :conn :haus.db/conn
               :id pos-int?
               :obj map?)
  :ret boolean?)

(s/fdef delete!
  :args (s/cat :model ::model
               :conn :haus.db/conn
               :id pos-int?)
  :ret boolean?)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; SimpleModel
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

; A simple model defined by the table name and a namespace qualifier for map
; keys.
(defrecord SimpleModel [table qualifier])

(defn simple-model [table qualifier]
  (->SimpleModel table qualifier))

(extend-type SimpleModel
  Model

  (-qualifier [{:keys [qualifier]}]
    qualifier)

  (-query [{:keys [table qualifier]} conn {:keys [where limit]}]
    (let [[where-sql & where-params] (not-empty (where/render where))
          sql (str "SELECT * FROM " table
                   (if where-sql (str " WHERE " (have string? where-sql)))
                   (if limit (str " LIMIT " (have integer? limit))))]
      (jdbc/query conn (cons sql where-params)
                  {:qualifier qualifier})))

  (-insert! [{table :table} conn obj]
    (let [[{id :id}] (jdbc/insert! conn table obj)]
      id))

  (-update! [{table :table} conn id obj]
    (let [[n] (jdbc/update! conn table obj ["id = ?", id])]
      (> n 0)))

  (-delete! [{table :table} conn id]
    (let [[n] (jdbc/delete! conn table ["id = ?", id])]
      (> n 0))))
