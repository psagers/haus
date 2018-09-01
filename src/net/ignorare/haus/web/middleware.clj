(ns net.ignorare.haus.web.middleware
  (:require [clojure.java.jdbc :as jdbc]
            [net.ignorare.haus.core.config :as config]
            [net.ignorare.haus.db :as db]
            [net.ignorare.haus.web.http :refer [conflict]]
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
