(ns haus.test.db_test
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.test :refer [deftest is]]
            [haus.test.util :as util :refer [*db-con*]]
            [net.ignorare.haus.core.db :as db]
            [net.ignorare.haus.core.util :refer [map-vals]]))

(util/use-fixtures)


;
; Test utilities
;


(defn insert-base-rows
  "Inserts some people and categories to play with."
  []
  (jdbc/insert-multi! *db-con* :people
                      [{:id 1, :name "Alice"}
                       {:id 2, :name "Bob"}
                       {:id 3, :name "Charlie"}])
  (jdbc/insert-multi! *db-con* :categories
                      [{:id 1, :name "Rent"}
                       {:id 2, :name "Utilities"}
                       {:id 3, :name "Payments"}]))

(defn make-splits
  "Converts a {person_id amount} map to a sequence of split maps."
  [amounts]
  (map (fn [[k v]] {:person_id k, :amount v}) amounts))

(def base-transaction {:date "2018-01-01", :category_id 1, :title "Testing"})

(defn get-totals
  "Returns a two-level map of totals: {person_id {category_id amount}}"
  []
  (reduce
   (fn [m {:keys [person_id category_id amount]}] (assoc-in m [person_id category_id] amount))
   {}
   (db/get-totals *db-con*)))

(defn person-totals
  "Returns a map of person_id to aggregated totals."
  ([]
   (person-totals (get-totals)))

  ([totals]
   (map-vals (partial transduce (map val) +) totals)))


;
; Tests
;
; These are mostly testing basic transaction manipulation along with the
; database's real-time aggregation.
;


(deftest test-new-txn
  (insert-base-rows)
  (db/insert-transaction! *db-con* (merge base-transaction
                                        {:splits (make-splits {1 700, 2 -700})}))

  (is (= (person-totals) {1 700M, 2 -700M, 3 0M})))

(deftest test-update-splits
  (insert-base-rows)
  (let [txn_id (db/insert-transaction! *db-con* (merge base-transaction
                                                     {:splits (make-splits {1 700, 2 -700})}))]
    (db/update-transaction! *db-con* txn_id {:splits (make-splits {1 1400, 2 -1400})}))

  (is (= (person-totals) {1 1400M, 2 -1400M, 3 0M})))

(deftest test-delete-txn
  (insert-base-rows)
  (let [txn_id (db/insert-transaction! *db-con* (merge base-transaction
                                                     {:splits (make-splits {1 700, 2 -700})}))]
    (jdbc/delete! *db-con* :transactions ["id = ?", txn_id]))

  (is (= (person-totals) {1 0M, 2 0M, 3 0M})))

(deftest test-change-category
  (insert-base-rows)
  (let [_       (db/insert-transaction! *db-con* (merge base-transaction {:category_id 1, :splits (make-splits {1 800, 2 -400, 3 -400})}))
        util_id (db/insert-transaction! *db-con* (merge base-transaction {:category_id 1, :splits (make-splits {1 50 2 -25, 3 -25})}))
        _       (db/insert-transaction! *db-con* (merge base-transaction {:category_id 3, :splits (make-splits {1 -425, 3 425})}))]
    (db/update-transaction! *db-con* util_id {:category_id 2}))

  (let [totals (get-totals)]
    (is (= (person-totals totals) {1 425M, 2 -425M, 3 0M}))
    (is (= (get totals 1) {1 800M, 2 50M, 3 -425M}))
    (is (= (get totals 2) {1 -400M, 2 -25M, 3 0M}))
    (is (= (get totals 3) {1 -400M, 2 -25M, 3 425M}))))
