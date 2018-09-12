(ns haus.test.people_test
  (:require [clojure.test :refer [deftest is testing]]
            [haus.test.util :as util :refer [*db-con*]]
            [haus.db :as db]
            [haus.web :refer [handler]]
            [ring.mock.request :refer [json-body]]
            [ring.util.response :refer [find-header]]))

(util/use-fixtures)

(deftest test-get-people
  (testing "Empty"
    (let [req (util/request :get "/people")
          resp (handler req)]
      (is (= 200 (:status resp)))
      (is (empty? (util/response-json resp)))))

  (testing "Found"
    (db/insert-person! *db-con* {:name "Alice"})
    (db/insert-person! *db-con* {:name "Bob"})

    (let [req (util/request :get "/people")
          resp (handler req)]
      (is (= 200 (:status resp)))
      (is (= '("Alice" "Bob")
             (sort (map :name (util/response-json resp))))))))

(deftest test-get-person
  (testing "Invalid id"
    (let [req (util/request :get "/people/bogus")
          resp (handler req)]
      (is (= 404 (:status resp)))))

  (testing "Not found"
    (let [req (util/request :get "/people/1")
          resp (handler req)]
      (is (= 404 (:status resp)))))

  (testing "Found"
    (let [id (db/insert-person! *db-con* {:name "Alice"})
          req (util/request :get (str "/people/" id))
          resp (handler req)]
      (is (= 200 (:status resp)))
      (is (= "Alice" (:name (util/response-json resp)))))))

(deftest test-new-person-fail
  (testing "Invalid body"
    (let [req (-> (util/request :post "/people")
                  (json-body {:namex "Alice"}))
          resp (handler req)]
      (is (= 400 (:status resp)))))

  (testing "Invalid name"
    (let [req (-> (util/request :post "/people")
                  (json-body {:name ""}))
          resp (handler req)]
      (is (= 400 (:status resp))))))

(deftest test-new-person
  (testing "Success"
    (let [req (-> (util/request :post "/people")
                  (json-body {:name "Alice"}))
          resp (handler req)]
      (is (= 201 (:status resp)))
      (let [location (second (find-header resp "Location"))]
        (is (.find (.matcher #"/people/\d+$" location)))))))

(deftest test-update-person
  (let [id (db/insert-person! *db-con* {:name "Alice"})
        req (-> (util/request :put (str "/people/" id))
                (json-body {:name "Alicia"}))
        resp (handler req)]
    (is (= 200 (:status resp)))
    (is (= {:id id, :name "Alicia"} (util/response-json resp) (db/get-person *db-con* id)))))
