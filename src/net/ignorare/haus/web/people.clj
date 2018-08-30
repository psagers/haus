(ns net.ignorare.haus.web.people
  (:require [clojure.spec.alpha :as s]
            [compojure.core :refer [ANY defroutes]]
            [net.ignorare.haus.core.db :as db]
            [net.ignorare.haus.web.http :refer [bad-request defresource
                                                url-join]]
            [ring.util.request :refer [request-url]]
            [ring.util.response :refer [created not-found response]]))

(s/def ::name (s/and string? (complement empty?)))
(s/def ::post-people (s/keys :req-un [::name]))
(s/def ::put-person (s/keys :req-un [::name]))

(defn conform
  "Like clojure.spec.alpha/conform, but returns nil on failure."
  [spec value]
  (let [value' (s/conform spec value)]
    (case value'
      (::s/invalid) nil
      value')))


;
; /people
;


(defresource people)

(defmethod people :get
  [{con :db-con :as req}]
  (let [people (db/get-people con)]
    (response people)))

(defmethod people :post
  [{con :db-con, body :body, :as req}]
  (if-let [{name :name} (conform ::post-people body)]
    (let [person (db/insert-person! con name)
          url-path (url-join (request-url req) (:id person))]
      (created url-path person))
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

(defmethod person :put
  [{con :db-con, {id :id} :params, body :body, :as req}]
  (if-let [{name :name} (conform ::put-person body)]
    (if (db/update-person! con id name)
      (response (db/get-person con id))
      (not-found ""))
    (bad-request "")))

(defmethod person :delete
  [{con :db-con, {id :id} :params, :as req}]
  (if (db/delete-person! con id)
    (response {:id id})
    (not-found "")))

(defn decode-person-params
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
