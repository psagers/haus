(ns net.ignorare.haus.web.people
  (:require [compojure.core :refer [ANY defroutes]]
            [net.ignorare.haus.core.db :as db]
            [net.ignorare.haus.web.http :refer [bad-request defresource
                                                url-join]]
            [net.ignorare.haus.web.json :as json :refer [load-schema]]
            [ring.util.request :refer [request-url]]
            [ring.util.response :refer [created not-found response]]
            [taoensso.truss :refer [have]]))


;
; /people
;


(defresource people)

(defmethod people :get
  [{con :db-con :as req}]
  (let [people (db/get-people con)]
    (response people)))

(def post-people-schema (delay (load-schema "people/post.json")))

(defmethod people :post
  [{con :db-con, body :body, :as req}]
  (if-let [{name :name} (json/conform @post-people-schema body)]
    (let [person_id (db/insert-person! con name)]
      (created (url-join (request-url req) (have int? person_id))))
    (bad-request "")))


;
; /people/:id
;


(defresource person)

(defmethod person :get
  [{con :db-con, {id :id} :params, :as req}]
  (if-let [person (db/get-person con id)]
    (response person)
    (not-found "")))

(def put-person-schema (delay (load-schema "person/put.json")))

(defmethod person :put
  [{con :db-con, {id :id} :params, body :body, :as req}]
  (if-let [{name :name} (json/conform @put-person-schema body)]
    (if (db/update-person! con id name)
      (response (db/get-person con id))
      (not-found ""))
    (bad-request "")))

(defmethod person :delete
  [{con :db-con, {id :id} :params, :as req}]
  (if (db/delete-person! con id)
    (response "")
    (not-found "")))

(defn ^:private decode-person-params
  "Middleware to decode person-resource URI params."
  [handler]
  (fn [req]
    (let [req (update-in req [:params :id] #(Integer/parseInt %))]
      (handler req))))

;
;
;

(defroutes routes
  (ANY "/" req people)
  (ANY ["/:id", :id #"\d+"] req (decode-person-params person)))
