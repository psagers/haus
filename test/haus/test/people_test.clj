(ns haus.test.people_test
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.test :refer [deftest is]]
            [haus.core.util :refer [submap?]]
            [haus.db.people :as people :refer [model]]
            [haus.db.util.model :as model]
            [haus.test.util :as util]))


(util/use-fixtures)


(def params-spec (s/keys :req [::people/name]))


(deftest people-db
  (let [conn util/*db-conn*]
    (doseq [params (gen/sample (s/gen params-spec) 100)]
      ; Add a new person and make sure we get it back.
      (let [id (model/insert! model conn params)]
        (is (every? #(submap? params %) (model/query model conn)))
        (is (submap? params (model/get-one model conn id)))

        ; Update it and make sure it's changed.
        (let [params (gen/generate (s/gen params-spec))]
          (model/update! model conn id params)
          (is (submap? params (model/get-one model conn id))))

        ; Delete it and make sure it's gone.
        (is (model/delete! model conn id))
        (is (nil? (model/get-one model conn id)))))))


(defn response-for [& args]
  (apply util/response-for (concat args [:qualifier "haus.db.people"])))

(deftest people-web
  (let [service-fn (get-in util/*system* [:http :server :io.pedestal.http/service-fn])
        response-for (partial response-for service-fn)]
    (doseq [params (gen/sample (s/gen params-spec) 100)]
      ; Add a new person and make sure we get it back.
      (let [{id ::people/id} (:body (response-for :post "/people", :json params))
            obj_url (str "/people/" id)]
        (is (every? #(submap? params %) (:body (response-for :get "/people"))))
        (is (submap? params (:body (response-for :get obj_url))))

        ; Update it and make sure it's changed.
        (let [params (gen/generate (s/gen params-spec))]
          (response-for :post obj_url, :json params)
          (is (submap? params (:body (response-for :get obj_url)))))

        ; Delete it and make sure it's gone.
        (is (response-for :delete obj_url))
        (is (= 404 (:status (response-for :get obj_url))))))))
