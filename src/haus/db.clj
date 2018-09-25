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

(defrecord Database [config spec]
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
      (assoc this :spec {:datasource pool})))

  (stop [this]
    (when-let [pool (get spec :datasource)]
      (.close pool))
    (assoc this :spec nil)))


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


(defn migratus-config [db-spec]
  {:store :database
   :migration-dir "migrations"
   :db db-spec})

(defn migrate [db-spec]
  (migratus/migrate (migratus-config db-spec)))

(defn rollback [db-spec]
  (migratus/rollback (migratus-config db-spec)))

(defn up [db-spec ids]
  (apply migratus/up (migratus-config db-spec) ids))

(defn down [db-spec ids]
  (apply migratus/down (migratus-config db-spec) ids))

(defn reset [db-spec]
  (migratus/reset (migratus-config db-spec)))

(defn pending-list [db-spec]
  (migratus/pending-list (migratus-config db-spec)))


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
         db-spec (get-in sys [:db :spec])]
     (case action
       ("migrate") (migrate db-spec)
       ("rollback") (rollback db-spec)
       ("up") (up db-spec (map to-int args))
       ("down") (down db-spec (map to-int args))
       ("reset") (reset db-spec)
       ("pending-list") (println (pending-list db-spec))
       (println (str "Unknown db action: " action))))))
