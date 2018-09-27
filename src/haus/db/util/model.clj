(ns haus.db.util.model
  (:require [clojure.java.jdbc :as jdbc]
            [haus.db.util.where :as where]))


(defprotocol Model
  "A general interface to a SQL table."
  (query [this conn] [this conn where])
  (insert! [this conn obj])
  (update! [this conn id obj])
  (delete! [this conn id]))


(defn get-one [model conn id]
  (first (query model conn {:id id})))


; A simple model defined by the table name and a namespace qualifier for map
; keys.
(defrecord SimpleModel [table qualifier])


(defn simple-model [table qualifier]
  (->SimpleModel table qualifier))


(extend-protocol Model
  SimpleModel
  (query ([this conn]
          (query this conn {}))

         ([{:keys [table qualifier]} conn where]
          (let [[where & params] (not-empty (where/render where))
                sql (str "SELECT * FROM " table
                         (if where (str " WHERE " where)))]
            (jdbc/query conn (cons sql params)
                        {:qualifier qualifier}))))

  (insert! [{table :table} conn obj]
    (let [[{id :id}] (jdbc/insert! conn table obj)]
      id))

  (update! [{table :table} conn id obj]
    (let [[n] (jdbc/update! conn table obj ["id = ?", id])]
      (> n 0)))

  (delete! [{table :table} conn id]
    (let [[n] (jdbc/delete! conn table ["id = ?", id])]
      (> n 0))))
