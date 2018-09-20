(ns haus.test.util
  (:require [clojure.data.json :as json]
            [clojure.java.jdbc :as jdbc]
            [clojure.test :refer [is]]
            [haus.db :as db]
            [ring.util.response :refer [find-header]]
            [taoensso.timbre :as timbre]))

(defn with-logging
  [f]
  (timbre/with-level :warn
    (f)))

(defn ^:private run-with-test-db
  "Sets up an empty test database with migrations applied."
  [f]
  (let [test-db-spec (update @db/*db-spec* :dbname #(str % "_test"))
        dbname (:dbname test-db-spec)]
    ; First connect to the default database so we can create a fresh test DB.
    (jdbc/execute! @db/*db-spec* [(str "DROP DATABASE IF EXISTS " dbname)] {:transaction? false})
    (jdbc/execute! @db/*db-spec* [(str "CREATE DATABASE " dbname " TEMPLATE template0 LC_COLLATE 'en_US.UTF-8'")] {:transaction? false})

    ; Switch to the test DB.
    (try
      (binding [db/*db-spec* (delay test-db-spec)]
        (db/migrate)
        (db/with-db-connection
          (f)))
      (finally
        (jdbc/execute! @db/*db-spec* [(str "DROP DATABASE IF EXISTS " dbname)] {:transaction? false})))))

(defn with-db
  "This is installed at both the :once level and circleci's :global-fixtures.
  This supports both fast lein tests and repl interaction, but we have to be
  careful not to reenter run-with-test-db."
  [f]
  (if (map? @db/*db-con*)
    (run-with-test-db f)
    (f)))

(defn with-transaction
  "Executes a test in a single transaction, which will be rolled back at the
  end."
  [f]
  (db/with-db-transaction
    (jdbc/db-set-rollback-only! @db/*db-con*)
    (f)))

(defmacro use-fixtures
  "Every test namespace should call this to install our database fixtures. Pass
  additional :each fixtures as arguments."
  [& each]
  `(do
     (clojure.test/use-fixtures :once with-logging with-db)
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
