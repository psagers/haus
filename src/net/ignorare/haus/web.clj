(ns net.ignorare.haus.web
  (:require [clojure.java.jdbc :as jdbc]
            [compojure.core :refer [ANY context defroutes]]
            [compojure.route :refer [not-found]]
            [net.ignorare.haus.core.config :as config]
            [net.ignorare.haus.core.db :as db]
            [net.ignorare.haus.web.categories :as categories]
            [net.ignorare.haus.web.http :refer [conflict]]
            [net.ignorare.haus.web.people :as people]
            [ring.middleware.json :refer [wrap-json-response]]
            [taoensso.timbre :as timbre])
  (:import (java.sql SQLIntegrityConstraintViolationException)))

(defn with-logging [handler]
  (fn [req]
    (timbre/with-level (config/log-level)
      (handler req))))

(defn with-db [handler]
  (fn [req]
    ; Mock requests from the tests will have their own database connections.
    (if-not (contains? req :db-con)
      (jdbc/with-db-connection [con @db/*db-spec*]
        (handler (assoc req :db-con con)))
      (handler req))))

(defn default-errors [handler]
  (fn [req]
    (try
      (handler req)
      (catch SQLIntegrityConstraintViolationException e
        (conflict (.getMessage e))))))

(defroutes routes
  (context "/people" [] people/routes)
  (context "/categories" [] categories/routes)
  (ANY "*" [] (not-found "")))

(defn init []
  (timbre/set-level! :warn))

(def handler
  (-> routes
      (wrap-json-response)
      (default-errors)
      (with-db)
      (with-logging)))
