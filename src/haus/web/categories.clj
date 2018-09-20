(ns haus.web.categories
  (:require [compojure.core :refer [ANY defroutes]]
            [haus.db.categories :as db]
            [haus.web.util.generic :as generic :refer [wrap-id-param]]
            [haus.web.util.http :refer [defresource]]
            [ring.util.response :refer [response]]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Generic options
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def generic-opts
  {:key-ns "haus.db.categories"
   :get-fn db/get-category
   :insert-fn db/insert-category!
   :update-fn db/update-category!
   :delete-fn db/delete-category!})

(defn opts-with-spec
  [spec]
  (assoc generic-opts :spec spec))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; /categories
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defresource categories)

(defmethod categories :get
  [req]
  (response (db/get-categories)))

(defmethod categories :post
  [req]
  (generic/new-obj! req (opts-with-spec ::db/insert-params)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; /categories/:id
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defresource category)

(defmethod category :get
  [req]
  (generic/get-obj req generic-opts))

(defmethod category :post
  [req]
  (generic/update-obj! req (opts-with-spec ::db/update-params)))

(defmethod category :delete
  [req]
  (generic/delete-obj! req generic-opts))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Routes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defroutes routes
  (ANY "/" req categories)
  (ANY ["/:id", :id #"\d+"] req (wrap-id-param category)))
