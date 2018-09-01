(ns net.ignorare.haus.api.people
  (:require [compojure.core :refer [ANY defroutes]]
            [net.ignorare.haus.db :as db]
            [net.ignorare.haus.web.generic :refer [delete-obj! get-obj new-obj!
                                                   update-obj! wrap-id-param]]
            [net.ignorare.haus.web.http :refer [defresource]]
            [net.ignorare.haus.web.json :refer [load-schema]]
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
  (new-obj! req db-fns @post-people-schema))


;
; /people/:id
;


(defresource person)

(defmethod person :get
  [req]
  (get-obj req db-fns))

(def put-person-schema (delay (load-schema "person-put.json")))

(defmethod person :put
  [req]
  (update-obj! req db-fns @put-person-schema))

(defmethod person :delete
  [req]
  (delete-obj! req db-fns))

;
;
;

(defroutes routes
  (ANY "/" req people)
  (ANY ["/:id", :id #"\d+"] req (wrap-id-param person)))
