(ns haus.test.util
  (:require [clojure.data.json :as json]
            [clojure.java.jdbc :as jdbc]
            [clojure.test :refer [is]]
            [com.stuartsierra.component :as component]
            [ring.util.response :refer [find-header]]
            [io.pedestal.test]
            [haus.db :as db]
            [haus.main :as main]
            [taoensso.timbre :refer [warn]]
            [taoensso.truss :refer [have]]))


; The running test component system (when tests are running).
(def ^:dynamic *system* nil)

; Tests must use this for all database access. Each test will be run in a
; transaction that gets rolled back at the end.
(def ^:dynamic *db-conn* nil)

; The system we'll be testing under.
(defn ^:private test-system [dbname]
  (main/system {:logging {:level :warn}}
               {:env :test
                :db {:dbname dbname}}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Fixtures
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private run-with-test-system [f]
  ; We'll need a normal database connection to create the test database.
  (let [db-sys (component/start (db/db-system))
        dbname (str (get-in db-sys [:config :config :db :dbname]) "_test")
        spec (get-in db-sys [:db :spec])]
    (jdbc/execute! spec [(str "DROP DATABASE IF EXISTS " dbname)] {:transaction? false})
    (jdbc/execute! spec [(str "CREATE DATABASE " dbname " TEMPLATE template0 LC_COLLATE 'en_US.UTF-8'")] {:transaction? false})

    ; Now spin up a test system and capture the database connection.
    (let [test-sys (component/start (test-system dbname))]
      (binding [*system* test-sys
                *db-conn* (get-in test-sys [:db :spec])]
        (try
          (db/migrate *db-conn*)
          (f)
          (finally
            (component/stop test-sys)
            (jdbc/execute! spec [(str "DROP DATABASE IF EXISTS " dbname)] {:transaction? false})
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


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Utils
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private json-response? [response]
  (if-let [[_ content-type] (find-header response "content-type")]
    (not-empty (re-find #"^application/(.+\+)?json" content-type))))

(defn response-for
  "A wrapper for io.pedestal.test/response-for that handles JSON and qualified
  keywords."
  [service-fn verb url & {:keys [json body headers qualifier]}]
  (let [[body headers] (if json
                          [(json/write-str (have coll? json)) (assoc headers "Content-Type" "application/json")]
                          [body headers])
         response (io.pedestal.test/response-for (have fn? service-fn)
                                                 (have keyword? verb)
                                                 (have string? url)
                                                 :body body
                                                 :headers headers)]
    (if (json-response? response)
      (update response :body #(json/read-str %, :key-fn (partial keyword qualifier)))
      response)))
