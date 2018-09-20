(ns haus.web
  (:require [compojure.core :refer [ANY context defroutes]]
            [compojure.route :refer [not-found]]
            [haus.web.categories :as categories]
            [haus.web.people :as people]
            [haus.web.transactions :as transactions]
            [haus.web.util.middleware :refer [with-db with-logging wrap-errors]]
            [haus.web.util.json :refer [wrap-json-body wrap-json-response]]
            [ring.middleware.head :refer [wrap-head]]
            ;[ring.middleware.nested-params :refer [wrap-nested-params]]
            [ring.middleware.params :refer [wrap-params]]
            [taoensso.timbre :as timbre]))


(defroutes routes
  (context "/people" [] people/routes)
  (context "/categories" [] categories/routes)
  (context "/transactions" [] transactions/routes)
  (ANY "*" [] (not-found "")))


(defn init []
  (timbre/set-level! :warn))


(def handler
  (-> routes
      (wrap-errors)
      ;(wrap-nested-params)
      (wrap-params)
      (wrap-json-body :bigdec true)
      (wrap-json-response)
      (wrap-head)
      (with-db)
      (with-logging)))
