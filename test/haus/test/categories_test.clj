(ns haus.test.categories_test
  (:require [clojure.test :refer [deftest is testing]]
            [haus.test.util :as util :refer [*db-con*]]
            [net.ignorare.haus.core.db :as db]
            [net.ignorare.haus.web :refer [handler]]
            [ring.mock.request :refer [json-body]]
            [ring.util.response :refer [find-header]]))

(util/use-fixtures)

(deftest test-get-categories
  (testing "Empty"
    (let [req (util/request :get "/categories")
          resp (handler req)]
      (is (= 200 (:status resp)))
      (is (empty? (util/response-json resp)))))

  (testing "Found"
    (db/insert-category! *db-con* {:name "Expenses"})
    (db/insert-category! *db-con* {:name "Payments"})

    (let [req (util/request :get "/categories")
          resp (handler req)]
      (is (= 200 (:status resp)))
      (is (= '("Expenses" "Payments")
             (sort (map :name (util/response-json resp))))))))

(deftest test-get-category
  (testing "Invalid id"
    (let [req (util/request :get "/categories/bogus")
          resp (handler req)]
      (is (= 404 (:status resp)))))

  (testing "Not found"
    (let [req (util/request :get "/categories/1")
          resp (handler req)]
      (is (= 404 (:status resp)))))

  (testing "Found"
    (let [id (db/insert-category! *db-con* {:name "Expenses"})
          req (util/request :get (str "/categories/" id))
          resp (handler req)]
      (is (= 200 (:status resp)))
      (is (= "Expenses" (:name (util/response-json resp)))))))

(deftest test-new-category-fail
  (testing "Invalid body"
    (let [req (-> (util/request :post "/categories")
                  (json-body {:namex "Expenses"}))
          resp (handler req)]
      (is (= 400 (:status resp)))))

  (testing "Invalid name"
    (let [req (-> (util/request :post "/categories")
                  (json-body {:name ""}))
          resp (handler req)]
      (is (= 400 (:status resp))))))

(deftest test-new-category
  (testing "Success"
    (let [req (-> (util/request :post "/categories")
                  (json-body {:name "Expenses"}))
          resp (handler req)]
      (is (= 201 (:status resp)))
      (let [location (second (find-header resp "Location"))]
        (is (.find (.matcher #"/categories/\d+$" location)))))))

(deftest test-update-category
  (let [id (db/insert-category! *db-con* {:name "Expenses"})
        req (-> (util/request :put (str "/categories/" id))
                (json-body {:name "Alicia"}))
        resp (handler req)]
    (is (= 200 (:status resp)))
    (is (= {:id id, :name "Alicia"} (util/response-json resp) (db/get-category *db-con* id)))))

(deftest test-delete-category)
