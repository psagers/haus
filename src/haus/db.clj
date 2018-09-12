(ns haus.db
  (:require [haus.core.config :as config]
            [migratus.core :as migratus]
            [taoensso.timbre :as timbre]))


; Static database parameters. These can't be overridden by external
; configuration.
(def db-params
  {:dbtype "pgsql"
   :classname "com.impossibl.postgres.jdbc.PGDriver"})

; By default, our database spec will be loaded from the config. The tests will
; override it.
(def ^:dynamic *db-spec*
  (delay (config/deep-merge (get @config/config :db) db-params)))

; The current database connection (requires dereference). Initially, this just
; points to the spec, which is suitable for migration operations and REPL
; interaction. This can be re-bound within requests, transactions, and any
; other context that would like a persistent connection.
(def ^:dynamic *db-con* *db-spec*)


(defmacro with-db-transaction
  "Executes forms inside a SQL transaction. *db-con* will be re-bound inside
  this form."
  [& body]
  `(jdbc/with-db-transaction [con# @db/*db-con*]
     (binding [*db-con* (delay con#)]
       ~@body)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Migrations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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
