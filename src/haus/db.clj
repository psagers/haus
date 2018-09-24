(ns haus.db
  (:require [clojure.spec.alpha :as s]
            [haus.core.config :as config]
            [haus.core.log :as log]
            [haus.core.util :refer [deep-merge to-int]]
            [com.stuartsierra.component :as component]
            [migratus.core :as migratus])
  (:import (com.mchange.v2.c3p0 ComboPooledDataSource)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Specs
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::datasource (partial instance? javax.sql.DataSource))
(s/def ::connection (partial instance? java.sql.Connection))

; Something that clojure.java.jdbc will accept for database operations.
(s/def ::conn (s/or :connection (s/keys :req-un [::connection])
                    :datasource (s/keys :req-un [::datasource])))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Component
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord Database [config conn]
  component/Lifecycle

  (start [this]
    (let [conf (get-in config [:config :db])
          {:keys [host port dbname user password]} conf
          url (format "jdbc:pgsql://%s:%d/%s" host port dbname)
          pool (doto (ComboPooledDataSource.)
                 (.setDriverClass "com.impossibl.postgres.jdbc.PGDriver")
                 (.setJdbcUrl url)
                 (.setUser user)
                 (.setPassword password))]
      (assoc this :conn {:datasource pool})))

  (stop [this]
    (when-let [pool (get conn :datasource)]
      (.close pool))
    (assoc this :conn nil)))


(defn new-database []
  (map->Database {}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Migrations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

; Just enough to run database operations.
(defn db-system []
  (component/system-map
    :config (config/new-config {:logging {:level :warn}})
    :log (component/using (log/new-logging) [:config])
    :db (component/using (new-database) [:config])))


(defn migratus-config [db-conn]
  {:store :database
   :migration-dir "migrations"
   :db db-conn})

(defn migrate [config]
  (migratus/migrate config))

(defn rollback [config]
  (migratus/rollback config))

(defn up [config ids]
  (apply migratus/up ids))

(defn down [config ids]
  (apply migratus/down config ids))

(defn reset [config]
  (migratus/reset config))

(defn pending-list [config]
  (migratus/pending-list config))


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
   (let [sys (component/start (db-system))
         config (migratus-config (get-in sys [:db :conn]))]
     (case action
       ("migrate") (migrate config)
       ("rollback") (rollback config)
       ("up") (up config (map to-int args))
       ("down") (down config (map to-int args))
       ("reset") (reset config)
       ("pending-list") (println (pending-list config))
       (println (str "Unknown db action: " action))))))
