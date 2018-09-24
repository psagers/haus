(ns haus.test.util
  (:require [clojure.data.json :as json]
            [clojure.java.jdbc :as jdbc]
            [clojure.test :refer [is]]
            [com.stuartsierra.component :as component]
            [ring.util.response :refer [find-header]]
            [haus.db :as db]
            [haus.main :as main]))


; The test component system (when tests are running).
(def ^:dynamic *system* nil)

; Tests must use this for all database access. Each test will be run in a
; transaction that gets rolled back at the end.
(def ^:dynamic *db-conn* nil)


(defn ^:private test-system []
  (main/system {} {:env :test
                   :db {:dbname "haus_test"}
                   :logging {:level :warn}}))


(defn ^:private run-with-test-system [f]
  ; First connect to the default database so we can create a fresh test DB.
  (let [db-sys (component/start (db/db-system))
        conn (get-in db-sys [:db :conn])]
    (jdbc/execute! conn [(str "DROP DATABASE IF EXISTS haus_test")] {:transaction? false})
    (jdbc/execute! conn [(str "CREATE DATABASE haus_test TEMPLATE template0 LC_COLLATE 'en_US.UTF-8'")] {:transaction? false})

    ; Now spin up a test system and capture the database connection.
    (let [test-system (component/start (test-system))]
      (binding [*system* test-system
                *db-conn* (get-in test-system [:db :conn])]
        (try
          (db/migrate (db/migratus-config *db-conn*))
          (f)
          (finally
            (component/stop *system*)
            (jdbc/execute! conn [(str "DROP DATABASE IF EXISTS haus_test")] {:transaction? false})
            (component/stop db-sys)))))))

(defn with-test-system
  "This is installed at both the :once level and circleci's :global-fixtures.
  This supports both fast lein tests and repl interaction, but we have to be
  careful not to reenter run-with-test-system."
  [f]
  (if (nil? *system*)
    (run-with-test-system f)
    (f)))

(defn with-transaction
  "Executes a test in a single transaction, which will be rolled back at the
  end."
  [f]
  (jdbc/with-db-transaction [conn *db-conn*]
    (jdbc/db-set-rollback-only! conn)
    (binding [*db-conn* conn]
      (f))))

(defmacro use-fixtures
  "Every test namespace should call this to install our database fixtures. Pass
  additional :each fixtures as arguments."
  [& each]
  `(do
     (clojure.test/use-fixtures :once with-test-system)
     (clojure.test/use-fixtures :each with-transaction ~@each)))

(defn response-content-type
  "Returns the content-type of a response (ignoring parameters)."
  [response]
  (if-let [content-type (second (find-header response "content-type"))]
    (second (re-find #"^(.*?)(?:;|$)" content-type))))

(defn response-json
  "Decodes the body of a response as JSON."
  [response]
  (is (= "application/json" (response-content-type response)))
  (json/read-str (:body response), :key-fn keyword))
