(ns haus.test.categories_test
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.test :refer [deftest is]]
            [haus.core.util :refer [submap?]]
            [haus.db.categories :as c]
            [haus.test.util :as util]))


(util/use-fixtures)


(def params-spec (s/keys :req [::c/name]))


(deftest categories-db
  (let [conn util/*db-conn*]
    (doseq [params (gen/sample (s/gen params-spec) 100)]
      ; Add a new category and make sure we get it back.
      (let [id (c/insert-category! conn params)]
        (is (every? #(submap? params %) (c/get-categories conn)))
        (is (submap? params (c/get-category conn id)))

        ; Update it and make sure it's changed.
        (let [params (gen/generate (s/gen params-spec))]
          (c/update-category! conn id params)
          (is (submap? params (c/get-category conn id))))

        ; Delete it and make sure it's gone.
        (is (c/delete-category! conn id))
        (is (nil? (c/get-category conn id)))))))


(defn response-for [& args]
  (apply util/response-for (concat args [:qualifier "haus.db.categories"])))

(deftest categories-web
  (let [service-fn (get-in util/*system* [:http :server :io.pedestal.http/service-fn])
        response-for (partial response-for service-fn)]
    (doseq [params (gen/sample (s/gen params-spec) 100)]
      ; Add a new category and make sure we get it back.
      (let [{id ::c/id} (:body (response-for :post "/categories", :json params))
            obj_url (str "/categories/" id)]
        (is (every? #(submap? params %) (:body (response-for :get "/categories"))))
        (is (submap? params (:body (response-for :get obj_url))))

        ; Update it and make sure it's changed.
        (let [params (gen/generate (s/gen params-spec))]
          (response-for :post obj_url, :json params)
          (is (submap? params (:body (response-for :get obj_url)))))

        ; Delete it and make sure it's gone.
        (is (response-for :delete obj_url))
        (is (= 404 (:status (response-for :get obj_url))))))))
