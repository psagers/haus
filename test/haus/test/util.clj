(ns haus.test.util
  (:require [clojure.data.json :as json]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [clojure.test :refer [is]]
            [net.ignorare.haus.core.db :as db]
            [ring.mock.request :as mock]
            [ring.util.response :refer [find-header]]
            [taoensso.timbre :as timbre]))

(def ^:dynamic *db-con* nil)

(defn with-logging
  [f]
  (timbre/with-level :warn
    (f)))

(defn ^:private run-with-test-db
  "Sets up an empty test database with migrations applied."
  [f]
  (let [dbname (str (get @db/*db-spec* :dbname) "_test")
        test-db-spec (assoc @db/*db-spec* :dbname dbname)]
    ; First connect to the default database so we can create a fresh test DB.
    (jdbc/execute! @db/*db-spec* [(str "DROP DATABASE IF EXISTS " dbname)] {:transaction? false})
    (jdbc/execute! @db/*db-spec* [(str "CREATE DATABASE " dbname " TEMPLATE template0 LC_COLLATE 'en_US.UTF-8'")] {:transaction? false})

    ; Switch to the test DB. Tests must use the request function below to
    ; install this connection into mock requests.
    (try
      (binding [db/*db-spec* (delay test-db-spec)]
        (db/migrate)
        (jdbc/with-db-connection [con @db/*db-spec*]
          (binding [*db-con* con]
            (f))))
      (finally
        (jdbc/execute! @db/*db-spec* [(str "DROP DATABASE IF EXISTS " dbname)] {:transaction? false})))))

(defn with-db
  "This is installed at both the :once level and circleci's :global-fixtures.
  This supports both fast lein tests and repl interaction, but we have to be
  careful not to reenter."
  [f]
  (if (nil? *db-con*)
    (run-with-test-db f)
    (f)))

(defn with-transaction
  "Executes a test in a single transaction, which will be rolled back at the
  end."
  [f]
  (jdbc/with-db-transaction [t-con *db-con*]
    (jdbc/db-set-rollback-only! t-con)
    (binding [*db-con* t-con]
      (f))))

(defmacro use-fixtures
  "Every test namespace should call this to install our database fixtures."
  []
  `(do
     (clojure.test/use-fixtures :once with-logging with-db)
     (clojure.test/use-fixtures :each with-transaction)))

(defn request
  "Returns a mock request with our database connection attached."
  [& args]
  (-> (apply mock/request args)
      (assoc :db-con *db-con*)))

(defn response-mimetype
  "Returns the mimetype of a response (ignoring content-type parameters)."
  [response]
  (some-> (find-header response "content-type")
          (second)
          (str/split #";")
          (first)))

(defn response-json
  "Decodes the body of a response as JSON."
  [response]
  (is (= "application/json" (response-mimetype response)))
  (json/read-str (:body response), :key-fn keyword))
