(ns haus.web.util.json
  "Tools for working with JSON, both input and output."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [failjure.core :as f]
            [haus.web.util.http :refer [bad-request]]
            [ring.util.response :refer [header find-header update-header]]
            [io.pedestal.interceptor :as interceptor]
            [slingshot.slingshot :refer [throw+]]
            [taoensso.timbre :refer [info]]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Interceptor
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private json-request? [request]
  (if-let [content-type (:content-type request)]
    (not-empty (re-find #"^application/(.+\+)?json" content-type))))

(defn ^:private read-json
  [request]
  (if (json-request? request)
    (if-let [body (:body request)]
      (with-open [rdr (io/reader body)]
        (json/read rdr)))))

(defn ^:private decode-json-request
  [request]
  (if-let [body (read-json request)]
    (assoc request :body body)
    request))

(defn ^:private encode-json-response
  "Encodes applicable response bodies as JSON."
  [response]
  (let [body (:body response)
        content-type (find-header response "content-type")]
    (if (and (coll? body) (not content-type))
      (-> response
          (assoc :body (json/write-str body))
          (header "Content-Type" "application/json; charset=utf-8"))
      response)))

(def json-body
  (interceptor/interceptor
    {:name ::json-body
     :enter #(update-in % [:request] decode-json-request)
     :leave #(update-in % [:response] encode-json-response)}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; JDBC
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

; clj-time monkey-patches jdbc, so the date types are a little unpredictable,
; depending on whether clj-time has been loaded.

(defmulti encode-timestamp class)

(defmethod encode-timestamp java.sql.Timestamp
  [^java.sql.Timestamp datetime]
  (quot (.getTime datetime) 1000))

(defmethod encode-timestamp org.joda.time.DateTime
  [^org.joda.time.DateTime datetime]
  (quot (.getMillis datetime) 1000))

(defmulti encode-date class)

(defmethod encode-date java.sql.Date
  [^java.sql.Date date]
  (str date))

(defmethod encode-date org.joda.time.DateTime
  [^org.joda.time.DateTime date]
  (let [formatter (org.joda.time.format.DateTimeFormat/forPattern "yyyy-MM-dd")]
    (.print formatter date)))
