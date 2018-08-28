(ns net.ignorare.haus.test.db_test
  (:require [net.ignorare.haus.core.db :as db]
            [clojure.java.jdbc :as jdbc]
            [clojure.test :as test :refer [deftest, testing, is]]
            [taoensso.timbre :as timbre])
  (:import (java.util Date)
           (java.sql SQLIntegrityConstraintViolationException)))

(def test-db-spec
  (assoc db/db-spec :dbname "haus_test"))

(def ^:dynamic db-con db/db-spec)

; Sets up an empty test database with migrations applied.
(defn with-db [f]
  (timbre/with-level :warn
    ; First connect to the default database so we can create a fresh test
    ; database.
    (let [dbname (:dbname test-db-spec)]
      (jdbc/execute! db/db-spec [(str "DROP DATABASE IF EXISTS " dbname)] {:transaction? false})
      (jdbc/execute! db/db-spec [(str "CREATE DATABASE " dbname " TEMPLATE template0 LC_COLLATE 'en_US.UTF-8'")] {:transaction? false}))

    ; Apply all migrations to the test database.
    (db/migrate test-db-spec)

    ; Now we can connect to the test database and run the tests.
    (jdbc/with-db-connection [con test-db-spec]
      (binding [db-con con]
        (f)))

    ; And clean up the test database.
    (jdbc/execute! db/db-spec [(str "DROP DATABASE IF EXISTS " (:dbname test-db-spec))] {:transaction? false})))

; Executes a test in a single transaction, which will be rolled back at the
; end.
(defn with-transaction [f]
  (jdbc/with-db-transaction [t-con db-con]
    (jdbc/db-set-rollback-only! t-con)
    (binding [db-con t-con]
      (f))))

(test/use-fixtures :once with-db)
(test/use-fixtures :each with-transaction)

(defn insert-base-rows []
  (jdbc/insert-multi! db-con :people
                      [{:id 1, :name "Alice"}
                       {:id 2, :name "Bob"}
                       {:id 3, :name "Charlie"}])
  (jdbc/insert-multi! db-con :categories
                      [{:id 1, :name "Rent"}
                       {:id 2, :name "Utilities"}
                       {:id 3, :name "Payments"}]))

(defn person-totals []
  (let [totals (jdbc/query db-con ["SELECT person_id, SUM(amount) AS total FROM totals GROUP BY person_id"])]
    (zipmap (map :person_id totals) (map :total totals))))

(defn category-totals []
  (let [totals (jdbc/query db-con ["SELECT category_id, SUM(amount) AS total FROM totals GROUP BY category_id"])]
    (zipmap (map :category_id totals) (map :total totals))))

(deftest test-new-txn-totals
  (insert-base-rows)
  (let [[{txn_id :id}] (jdbc/insert! db-con :transactions {:date "2018-01-01", :category_id 1, :title "Rent"})]
    (jdbc/insert-multi! db-con :splits
                        [{:transaction_id txn_id, :person_id 1, :amount 700}
                         {:transaction_id txn_id, :person_id 2, :amount -700}])

    (is (= (person-totals) {1 700M, 2 -700M, 3 0M}))
    (is (every? zero? (vals (category-totals))))))

(deftest test-updated-txn-totals
  (insert-base-rows)
  (let [[{txn_id :id}] (jdbc/insert! db-con :transactions {:date "2018-01-01", :category_id 1, :title "Rent"})]
    (jdbc/insert-multi! db-con :splits
                        [{:transaction_id txn_id, :person_id 1, :amount 700}
                         {:transaction_id txn_id, :person_id 2, :amount -700}])
    (jdbc/execute! db-con ["UPDATE splits SET amount = amount * 2 WHERE transaction_id = ?", txn_id])

    (is (= (person-totals) {1 1400M, 2 -1400M, 3 0M}))
    (is (every? zero? (vals (category-totals))))))

(deftest test-deleted-txn-totals
  (insert-base-rows)
  (let [[{txn_id :id}] (jdbc/insert! db-con :transactions {:date "2018-01-01", :category_id 1, :title "Rent"})]
    (jdbc/insert-multi! db-con :splits
                        [{:transaction_id txn_id, :person_id 1, :amount 700}
                         {:transaction_id txn_id, :person_id 2, :amount -700}])
    (jdbc/delete! db-con :transactions ["id = ?", txn_id])

    (is (= (person-totals) {1 0M, 2 0M, 3 0M}))
    (is (every? zero? (vals (category-totals))))))
