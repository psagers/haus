(ns haus.test.transactions_test
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [haus.db :as db]
            [haus.db.totals :as tot]
            [haus.db.transactions :as t]
            [haus.test.util :as util]))


(defn with-people
  "A fixture that inserts some people to work with."
  [f]
  (jdbc/insert-multi! @db/*db-con* :people [{:id 1, :name "Alice"}
                                            {:id 2, :name "Bob"}
                                            {:id 3, :name "Carol"}
                                            {:id 4, :name "David"}])
  (f))

(defn with-categories
  "A fixture that inserts some categories to work with."
  [f]
  (jdbc/insert-multi! @db/*db-con* :categories [{:id 1, :name "Rent"}
                                                {:id 2, :name "Utilities"}
                                                {:id 3, :name "Food"}
                                                {:id 4, :name "Payments"}])
  (f))


(util/use-fixtures with-people with-categories)


(defn splits-gen
  "Returns a generator for ::t/splits that conforms to all of the database
  invariants. Argument is a collection of valid person_id values."
  [person-ids]
  ; First, choose at least 2 people to participate in the transaction.
  (gen/let [split-count (gen/choose 2 (count person-ids))]
    ; Generate some basic split maps and associate our random participants.
    (let [splits (gen/sample (s/gen ::t/split) split-count)
          splits (map #(assoc %1 ::t/person_id %2) splits (shuffle person-ids))
          head (first splits)
          tail (rest splits)]
      ; Finally, set the amount of the first split such that it balances the
      ; sum of all of the others.
      (conj tail (assoc head ::t/amount (- (t/sum-split-amounts tail)))))))


(def new-txn-spec
  (s/keys :req [::t/date ::t/category_id ::t/title]
          :opt [::t/description ::t/tags ::t/splits]))

(def update-txn-spec
  (s/keys :opt [::t/date ::t/category_id ::t/title ::t/description ::t/tags
                ::t/splits]))

(defn transaction-gen
  [spec]
  (s/gen spec {::t/category_id #(gen/choose 1 4)
               ::t/splits #(splits-gen (range 1 5))}))


(deftest transaction-operations
  ; Insert, retrieve, update, and delete a bunch of transactions.
  (doseq [params (gen/sample (transaction-gen new-txn-spec) 100)]
    (let [txn_id (t/insert-transaction! params)]
      (t/get-transaction txn_id)
      (let [params (gen/generate (transaction-gen update-txn-spec))]
        (t/update-transaction! txn_id params))
      (t/delete-transaction! txn_id)))

  ; Every transaction was deleted, so the totals table should be all zeros.
  (is (every? (comp zero? ::tot/amount) (tot/get-totals)))
  (is (every? (comp zero? ::tot/amount) (tot/compute-totals))))


(deftest totals
  ; Insert 100 transactions
  (doseq [params (gen/sample (transaction-gen new-txn-spec) 100)]
    (t/insert-transaction! params))

  (let [txn_ids (map :id (jdbc/query @db/*db-con* ["SELECT id FROM transactions"]))]
    ; Modify 30 random transactions.
    (doseq [txn_id (take 30 (shuffle txn_ids))]
      (let [params (gen/generate (transaction-gen update-txn-spec))]
        (t/update-transaction! txn_id params)))

    ; Delete 30 random transactions.
    (doseq [txn_id (take 30 (shuffle txn_ids))]
      (t/delete-transaction! txn_id)))

  ; Make sure the totals table is accurate.
  (let [totals (tot/get-totals)
        expected (tot/compute-totals)]
    (is (= expected totals))))
