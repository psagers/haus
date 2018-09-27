(ns haus.test.transactions_test
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test]
            [clojure.test.check.generators :as gen]
            [haus.db :as db]
            [haus.db.util.model :as model]
            [haus.db.totals :as tot]
            [haus.db.transactions :as t :refer [transactions]]
            [haus.db.transactions.query :as q]
            [haus.test.util :as util]))

(alias 'stc 'clojure.spec.test.check)


(defn with-people
  "A fixture that inserts some people to work with."
  [f]
  (jdbc/insert-multi! util/*db-conn* :people [{:id 1, :name "Alice"}
                                              {:id 2, :name "Bob"}
                                              {:id 3, :name "Carol"}
                                              {:id 4, :name "David"}])
  (f))

(defn with-categories
  "A fixture that inserts some categories to work with."
  [f]
  (jdbc/insert-multi! util/*db-conn* :categories [{:id 1, :name "Rent"}
                                                  {:id 2, :name "Utilities"}
                                                  {:id 3, :name "Food"}
                                                  {:id 4, :name "Payments"}])
  (f))


(util/use-fixtures with-people with-categories)


(defn splits-gen
  "Returns a generator for ::t/splits that conforms to all of the database
  invariants. Argument is a collection of valid person_id values."
  [person-ids]
  ; First, choose at least 2 people to participate in the transaction and
  ; generate some basic split maps with random participants.
  (gen/let [split-count (gen/choose 2 (count person-ids))
            splits (gen/vector (s/gen ::t/split) split-count)]
    (let [splits (map #(assoc %1 ::t/person_id %2) splits (shuffle person-ids))
          head (first splits)
          tail (rest splits)]
      ; Finally, set the amount of the first split such that it balances the
      ; sum of all of the others.
      (conj tail (assoc head ::t/amount (- (t/sum-splits tail)))))))


(defn transaction-gen
  [spec]
  (s/gen spec {::t/category_id #(gen/choose 1 4)
               ::t/splits #(splits-gen (range 1 5))}))


(deftest transaction-operations
  ; Insert, retrieve, update, and delete a bunch of transactions.
  (let [conn util/*db-conn*]
    (doseq [params (gen/sample (transaction-gen ::t/insert-params) 100)]
      (let [txn_id (model/insert! transactions conn params)]
        (model/get-one transactions conn txn_id)
        (let [params (gen/generate (transaction-gen ::t/update-params))]
          (model/update! transactions conn txn_id params))
        (model/delete! transactions conn txn_id)))

    ; Every transaction was deleted, so the totals table should be all zeros.
    (let [totals (tot/get-totals conn)
          expected (tot/compute-totals conn)]
      (is (= expected totals))
      (is (every? (comp zero? ::tot/amount) totals)))))


(deftest totals
  (let [conn util/*db-conn*]
    ; Insert 100 transactions
    (doseq [params (gen/sample (transaction-gen ::t/insert-params) 100)]
      (model/insert! transactions conn params))

    (let [txn_ids (vec (map :id (jdbc/query conn ["SELECT id FROM transactions"])))]
      ; Modify 30 random transactions.
      (doseq [txn_id (take 30 (shuffle txn_ids))]
        (let [params (gen/generate (transaction-gen ::t/update-params))]
          (model/update! transactions conn txn_id params)))

      ; Delete 30 random transactions.
      (doseq [txn_id (take 30 (shuffle txn_ids))]
        (model/delete! transactions conn txn_id)))

    ; Make sure the totals table is accurate.
    (let [totals (tot/get-totals conn)
          expected (tot/compute-totals conn)]
      (is (= expected totals)))))


(defn search-transactions [conn params]
  (model/query transactions conn, :where (q/transaction-query params), :limit 10))

(s/fdef search-transactions
  :args (s/cat :conn ::db/conn
               :opts ::q/params)
  :ret (s/coll-of ::t/transaction))


(deftest query
  (let [conn util/*db-conn*]
    (doseq [params (gen/sample (transaction-gen ::t/insert-params) 100)]
      (model/insert! transactions conn params))

    (doseq [result (stest/check `search-transactions {::stc/opts {:num-tests 100}
                                                      :gen {::db/conn #(gen/return conn)}})]
      (clojure.test.check.clojure-test/assert-check (::stc/ret result)))))
