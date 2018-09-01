(ns net.ignorare.haus.web.generic
  (:require [compojure.route :refer [not-found]]
            [net.ignorare.haus.web.http :refer [bad-request url-join]]
            [net.ignorare.haus.web.json :as json]
            [ring.util.request :refer [request-url]]
            [ring.util.response :refer [created response]]
            [taoensso.truss :refer [have]]))

;
; Generic handlers that follow our API and DB conventions.
;
; Each one takes a request and a map of database functions (:insert-fn,
; :get-fn, :udpate-fn, :delete-fn) as the first two arguments. Database
; functions follow the conventions documented in net.ignorare.haus.core.db.
; Handlers that need to process the request body additionally take a JsonSchema
; object.
;

(defn new-obj!
  "Generic handler for creating a new object."
  [{con :db-con, body :body, :as req} {:keys [insert-fn]} schema]
  (if-let [obj (json/conform schema body)]
    (let [obj_id (insert-fn con obj)]
      (created (url-join (request-url req) (have int? obj_id))))
    (bad-request "")))

(defn get-obj
  "Generic handler for retrieving a single object."
  [{con :db-con, {id :id} :params} {:keys [get-fn]}]
  (if-let [obj (get-fn con id)]
    (response obj)
    (not-found "")))

(defn update-obj!
  "Generic handler for updating an existing object."
  [{con :db-con, {id :id} :params, body :body} {:keys [update-fn get-fn]} schema]
  (if-let [obj (json/conform schema body)]
    (if (update-fn con id obj)
      (response (get-fn con id))
      (not-found ""))
    (bad-request "")))

(defn delete-obj!
  "Generic handler for deleting an existing object."
  [{con :db-con, {id :id} :params} {:keys [delete-fn]}]
  (if (delete-fn con id)
    (response "")
    (not-found "")))

;
; Other helpers
;

(defn wrap-id-param
  "Middleware to decode the :id param as an integer."
  [handler]
  (fn [req]
    (let [req (update-in req [:params :id] #(Integer/parseInt %))]
      (handler req))))