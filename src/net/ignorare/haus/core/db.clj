(ns net.ignorare.haus.core.db
  (:require [taoensso.timbre :as timbre]
            [migratus.core :as migratus]))

; TODO: Define these dynamically.
(def log-level :info)

(def db-spec
  {:dbtype "pgsql"
   :classname "com.impossibl.postgres.jdbc.PGDriver"
   :dbname "haus"
   :user "postgres"})

(defn config [db-spec]
  {:store :database
   :migration-dir "migrations"
   :db db-spec})

;

;
; Migration operations
;

(defn migrate [db-spec]
  (migratus/migrate (config db-spec)))

(defn rollback [db-spec]
  (migratus/rollback (config db-spec)))

(defn up [db-spec & ids]
  (apply migratus/up (config db-spec) ids))

(defn down [db-spec & ids]
  (apply migratus/down (config db-spec) ids))

(defn reset [db-spec]
  (migratus/reset (config db-spec)))

(defn pending-list [db-spec]
  (migratus/pending-list (config db-spec)))

; For Leiningen aliases.
(defn -main
  ([]
   (println "Database managment commands:

  migrate: Apply all missing migrations.
  rollback: Unapply the latest migrations.
  up id ...: Apply one or more migrations by numeric identifier.
  down id ...: Unapply one or more migrations by numeric identifier.
  reset: Unapply and reapply all migrations.
  pending-list: List unapplied migrations."))
  ([action & args]
   (timbre/with-level log-level
     (case action
       ("migrate") (migrate db-spec)
       ("rollback") (rollback db-spec)
       ("up") (apply up db-spec (map #(Integer/parseInt %) args))
       ("down") (apply down db-spec (map #(Integer/parseInt %) args))
       ("reset") (reset db-spec)
       ("pending-list") (println (pending-list db-spec))
       (println (str "Unknown db action: " action))))))
