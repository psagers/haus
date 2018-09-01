(ns net.ignorare.haus.web
  (:require [compojure.core :refer [ANY context defroutes]]
            [compojure.route :refer [not-found]]
            [net.ignorare.haus.api.categories :as categories]
            [net.ignorare.haus.api.people :as people]
            [net.ignorare.haus.web.middleware :refer [default-errors with-db
                                                      with-logging]]
            [ring.middleware.json :refer [wrap-json-response]]
            [taoensso.timbre :as timbre]))

(defroutes routes
  (context "/people" [] people/routes)
  (context "/categories" [] categories/routes)
  (ANY "*" [] (not-found "")))

(defn init []
  (timbre/set-level! :warn))

(def handler
  (-> routes
      (default-errors)
      (wrap-json-response)
      (with-db)
      (with-logging)))
