(ns net.ignorare.haus.api.categories
  (:require [compojure.core :refer [ANY defroutes]]
            [net.ignorare.haus.db :as db]
            [net.ignorare.haus.web.generic :refer [delete-obj! get-obj new-obj!
                                                   update-obj! wrap-id-param]]
            [net.ignorare.haus.web.http :refer [defresource]]
            [net.ignorare.haus.web.json :refer [load-schema]]
            [ring.util.response :refer [response]]))

(def db-fns
  {:insert-fn db/insert-category!
   :get-fn db/get-category
   :update-fn db/update-category!
   :delete-fn db/delete-category!})


;
; /categories
;


(defresource categories)

(defmethod categories :get
  [{con :db-con}]
  (response (db/get-categories con)))

(def post-categories-schema (delay (load-schema "categories-post.json")))

(defmethod categories :post
  [req]
  (new-obj! req db-fns @post-categories-schema))


;
; /categories/:id
;


(defresource category)

(defmethod category :get
  [req]
  (get-obj req db-fns))

(def put-category-schema (delay (load-schema "category-put.json")))

(defmethod category :put
  [req]
  (update-obj! req db-fns @put-category-schema))

(defmethod category :delete
  [req]
  (delete-obj! req db-fns))

;
;
;

(defroutes routes
  (ANY "/" req categories)
  (ANY ["/:id", :id #"\d+"] req (wrap-id-param category)))
