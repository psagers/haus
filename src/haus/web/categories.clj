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
  {:get-fn db/get-category
   :insert-fn db/insert-category!
   :update-fn db/update-category!
   :delete-fn db/delete-category!
   :url-fn url-for})


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; /categories
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defresource categories)

(defmethod categories :get
  [{db-spec :haus.db/spec}]
  (response (db/get-categories db-spec)))

(defmethod categories :post
  [req]
  (generic/new-obj! req generic-opts))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; /categories/:id
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defresource category)

(defmethod category :get
  [req]
  (generic/get-obj req generic-opts))

(defmethod category :post
  [req]
  (generic/update-obj! req generic-opts))

(defmethod category :delete
  [req]
  (generic/delete-obj! req generic-opts))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Routes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn conform-body
  [specs]
  (generic/conform-body "haus.db.categories" specs))

(defn routes [prefix]
  [prefix ^:interceptors [(conform-body {:post ::db/insert-params})]
          {:any `categories}

    ["/:id" ^:constraints {:id #"\d+"}
            ^:interceptors [generic/decode-id-param
                            (conform-body {:post ::db/update-params})]
            {:any `category}]])
