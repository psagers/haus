(ns haus.web.people
  (:require [compojure.core :refer [ANY defroutes]]
            [haus.db :as db]
            [haus.web.util.generic :as generic :refer [wrap-id-param]]
            [haus.web.util.http :refer [defresource]]
            [haus.web.util.json :refer [load-schema]]
            [ring.util.response :refer [response]]))

(def db-fns
  {:insert-fn db/insert-person!
   :get-fn db/get-person
   :update-fn db/update-person!
   :delete-fn db/delete-person!})


;
; /people
;


(defresource people)

(defmethod people :get
  [{con :db-con}]
  (response (db/get-people con)))

(def post-people-schema (delay (load-schema "people-post.json")))

(defmethod people :post
  [req]
  (generic/new-obj! req db-fns @post-people-schema))


;
; /people/:id
;


(defresource person)

(defmethod person :get
  [req]
  (generic/get-obj req db-fns))

(def put-person-schema (delay (load-schema "person-put.json")))

(defmethod person :put
  [req]
  (generic/update-obj! req db-fns @put-person-schema))

(defmethod person :delete
  [req]
  (generic/delete-obj! req db-fns))

;
;
;

(defroutes routes
  (ANY "/" req people)
  (ANY ["/:id", :id #"\d+"] req (wrap-id-param person)))
