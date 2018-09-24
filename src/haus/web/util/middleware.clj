(ns haus.web.util.middleware
  (:require [clojure.java.jdbc :as jdbc]
            [failjure.core :as f]
            [haus.core.config :as config]
            [haus.db :as db]
            [haus.web.util.http :refer [bad-request conflict server-error]]
            [ring.util.response :refer [response?]]
            [taoensso/timbre :as timbre]
            [slingshot.slingshot :refer [try+]])
  (:import (java.sql SQLIntegrityConstraintViolationException)))

(defn with-logging [handler]
  (fn [req]
    (timbre/with-level (config/log-level)
      (handler req))))

(defn with-db
  "Ensures that we have a database connection in @db/*db-con*. This will not
  overwrite an existing connection, primarily to avoid interfering with test
  transactions.

  TODO: Add connection pooling."
  [handler]
  (fn [req]
    (if (= db/*db-con* db/*db-spec*)
      (jdbc/with-db-connection [con @db/*db-spec*]
        (binding [db/*db-con* (delay con)]
          (handler req)))
      (handler req))))

(defn wrap-errors
  "Adds some default error handlers. If you throw+ a response, we'll just
  return that. Other exceptions may be transated to reasonable HTTP status
  codes before falling back on the default 500."
  [handler]
  (fn [req]
    (try+
     (handler req)
     (catch response? response
       response)
     (catch SQLIntegrityConstraintViolationException e
       (conflict (.getMessage e))))))
