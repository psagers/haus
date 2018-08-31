(ns net.ignorare.haus.web.categories
  (:require [compojure.core :refer [ANY defroutes]]
            [net.ignorare.haus.core.db :as db]
            [net.ignorare.haus.web.http :refer [bad-request defresource
                                                url-join]]
            [net.ignorare.haus.web.json :as json :refer [load-schema]]
            [ring.util.request :refer [request-url]]
            [ring.util.response :refer [created not-found response]]
            [taoensso.truss :refer [have]]))


;
; /categories
;


(defresource categories)

(defmethod categories :get
  [{con :db-con :as req}]
  (let [categories (db/get-categories con)]
    (response categories)))

(def post-categories-schema (delay (load-schema "categories/post.json")))

(defmethod categories :post
  [{con :db-con, body :body, :as req}]
  (if-let [{name :name} (json/conform @post-categories-schema body)]
    (let [category_id (db/insert-category! con name)]
      (created (url-join (request-url req) (have int? category_id))))
    (bad-request "")))


;
; /categories/:id
;


(defresource category)

(defmethod category :get
  [{con :db-con, {id :id} :params, :as req}]
  (if-let [category (db/get-category con id)]
    (response category)
    (not-found "")))

(def put-category-schema (delay (load-schema "category/put.json")))

(defmethod category :put
  [{con :db-con, {id :id} :params, body :body, :as req}]
  (if-let [{name :name} (json/conform @put-category-schema body)]
    (if (db/update-category! con id name)
      (response (db/get-category con id))
      (not-found ""))
    (bad-request "")))

(defmethod category :delete
  [{con :db-con, {id :id} :params, :as req}]
  (if (db/delete-category! con id)
    (response "")
    (not-found "")))

(defn ^:private decode-category-params
  "Middleware to decode category-resource URI params."
  [handler]
  (fn [req]
    (let [req (update-in req [:params :id] #(Integer/parseInt %))]
      (handler req))))

;
;
;

(defroutes routes
  (ANY "/" req categories)
  (ANY ["/:id", :id #"\d+"] req (decode-category-params category)))
