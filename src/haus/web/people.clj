(ns haus.web.people
  (:require [compojure.core :refer [ANY defroutes]]
            [haus.db.people :as db]
            [haus.web.util.generic :as generic :refer [wrap-id-param]]
            [haus.web.util.http :refer [defresource]]
            [ring.util.response :refer [response]]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Generic options
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def generic-opts
  {:key-ns "haus.db.people"
   :get-fn db/get-person
   :insert-fn db/insert-person!
   :update-fn db/update-person!
   :delete-fn db/delete-person!})

(defn opts-with-spec
  [spec]
  (assoc generic-opts :spec spec))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; /people
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defresource people)

(defmethod people :get
  [req]
  (response (db/get-people)))

(defmethod people :post
  [req]
  (generic/new-obj! req (opts-with-spec ::db/insert-params)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; /people/:id
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defresource person)

(defmethod person :get
  [req]
  (generic/get-obj req generic-opts))

(defmethod person :post
  [req]
  (generic/update-obj! req (opts-with-spec ::db/update-params)))

(defmethod person :delete
  [req]
  (generic/delete-obj! req generic-opts))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Routes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defroutes routes
  (ANY "/" req people)
  (ANY ["/:id", :id #"\d+"] req (wrap-id-param person)))
