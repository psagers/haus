(ns haus.web.categories
  (:require [haus.db.categories :as db]
            [haus.web.util.generic :as generic]
            [haus.web.util.http :refer [defresource]]
            [clojure.core.async :as async]
            [ring.util.response :refer [response]]
            [io.pedestal.http.route :as route]
            [taoensso.timbre :refer [info]]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Generic options
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn url-for [req category_id]
  (if category_id
    (route/url-for ::category, :request req
                               :path-params {:id category_id})
    (route/url-for ::categories, :request req)))


(def generic-opts
  {:key-ns "haus.db.categories"
   :get-fn db/get-category
   :insert-fn db/insert-category!
   :update-fn db/update-category!
   :delete-fn db/delete-category!
   :url-fn url-for})

(defn opts-with-spec
  [spec]
  (assoc generic-opts :spec spec))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; /categories
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defresource categories)

(defmethod categories :get
  [{db-spec :haus.db/spec}]
  (response (db/get-categories db-spec)))

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

(defn routes [prefix]
  [prefix {:any `categories}
    ["/:id" ^:constraints {:id #"\d+"}
            ^:interceptors [generic/decode-id-param]
            {:any `category}]])
