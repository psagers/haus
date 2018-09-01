(ns haus.web
  (:require [compojure.core :refer [ANY context defroutes]]
            [compojure.route :refer [not-found]]
            [haus.web.categories :as categories]
            [haus.web.people :as people]
            [haus.web.util.middleware :refer [default-errors with-db
                                              with-logging]]
            [ring.middleware.head :refer [wrap-head]]
            [ring.middleware.json :refer [wrap-json-response]]
            [ring.middleware.lint :refer [wrap-lint]]
            [ring.middleware.params :refer [wrap-params]]
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
      (wrap-params)
      (wrap-json-response)
      (wrap-lint)
      (with-db)
      (with-logging)
      (wrap-head)))
