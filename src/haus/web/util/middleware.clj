(ns haus.web.util.middleware
  (:require [clojure.java.jdbc :as jdbc]
            [haus.core.config :as config]
            [haus.db :as db]
            [haus.web.util.http :refer [bad-request conflict]]
            [slingshot.slingshot :refer [try+]]
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
    (try+
     (handler req)
     (catch SQLIntegrityConstraintViolationException e
       (conflict (.getMessage e)))
     (catch [:ex :haus.web.util.json/failed-validation] {msg :msg}
       (bad-request msg)))))
