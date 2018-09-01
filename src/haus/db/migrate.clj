(ns haus.db.migrate
  "Database migration operations."
  (:require [migratus.core :as migratus]
            [haus.core.config :as config]
            [haus.db :refer [*db-spec*]]
            [taoensso.timbre :as timbre]))


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
