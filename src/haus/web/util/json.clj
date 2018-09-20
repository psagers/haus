(ns haus.web.util.json
  "Tools for working with JSON, both input and output."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [failjure.core :as f]
            [haus.web.util.http :refer [bad-request]]
            [ring.util.response :refer [update-header]]
            [slingshot.slingshot :refer [throw+]])
  (:import (com.fasterxml.jackson.core JsonParseException)
           (com.fasterxml.jackson.databind JsonNode ObjectMapper)
           (com.github.fge.jsonschema.core.report ProcessingReport)
           (com.github.fge.jsonschema.main JsonSchema JsonSchemaFactory)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Request
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private json-request? [request]
  (if-let [ctype (get-in request [:headers "content-type"])]
    (not (empty? (re-find #"^application/(.+\+)?json" ctype)))))

(defn ^:private read-json
  [request opts]
  (if (json-request? request)
    (if-let [body (:body request)]
      (with-open [rdr (io/reader body)]
        (apply json/read rdr opts)))))

(defn ^:private json-body-request
  [request opts]
  (if-let [body (read-json request opts)]
    (assoc request :body body)
    request))

(defn wrap-json-body
  "Middleware that parses the body of JSON request maps, and replaces the :body
  key with the parsed data structure. Requests without a JSON content type are
  unaffected. Accepts options for clojure.data.json/read."
  ([handler & opts]
   (fn
     ([request]
      (try
        (let [request' (json-body-request request opts)]
          (handler request'))
        (catch Exception e
          (bad-request (str "Malformed JSON in request body: " (.getMessage e)))))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Response
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn json-response
  "Encodes applicable response bodies as JSON."
  [response]
  (if (coll? (:body response))
    (-> response
        (update-in [:body] json/write-str)
        (update-header "content-type" #(or % "application/json; charset=utf-8")))
    response))

(defn wrap-json-response
  "Middleware that encodes applicable response bodies as JSON."
  [handler]
  (fn [request]
    (json-response (handler request))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; JSON Schema
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private node->clj
  "Converts a JsonNode to a Clojure data structure."
  ([node]
   (node->clj node {}))

  ([^JsonNode node {:keys [keywords? bigdecimals?] :or {keywords? true, bigdecimals? true} :as opts}]
   (letfn [; Functions for converting object fields.
           (key-fn [^java.util.Map$Entry field]
             (cond-> (.getKey field), keywords? keyword))

           (value-fn [^java.util.Map$Entry field]
                     (node->clj' (.getValue field)))

           (node->clj' [^JsonNode node]
                       (cond
                         (.isObject node) (into {} (map (juxt key-fn value-fn) (iterator-seq (.fields node))))
                         (.isArray node) (vec (map node->clj' (iterator-seq (.elements node))))
                         (.isTextual node) (.textValue node)
                         (.isBigDecimal node) (.decimalValue node)
                         (.isIntegralNumber node) (.longValue node)
                         (.isNumber node) (if bigdecimals? (.decimalValue node) (.doubleValue node))
                         (.isBoolean node) (.booleanValue node)
                         (.isNull node) nil
                         :else (assert false (str "Unhandled JsonNode type: " node))))]
     (node->clj' node))))

(defn load-schema
  "Loads a schema from resources/json-schema."
  ^JsonSchema [filename]
  (let [factory (JsonSchemaFactory/byDefault)
        uri (str "resource:/json-schema/" filename)]
    (.getJsonSchema factory uri)))

(defn ^:private report-message
  "Renders a ProcessingReport into a human-readable string."
  [^ProcessingReport report]
  (let [error (node->clj (.asJson (first report)))]
    (str/join ": " (remove empty? [(get-in error [:instance :pointer])
                                   (get error :message)]))))

(defn conform
  "Validates a request body against a JSON schema. Returns either the decoded
  value or an HTTP response with an error status. Accepts an option map with
  :keywords? and :bigdecimals?, both defaulting to true.

  The body can be either an InputStream or a string."
  ([^JsonSchema schema body]
   (conform schema body {}))

  ([^JsonSchema schema body opts]
   (try
     (let [node (-> (ObjectMapper.) (.readTree body))
           report (.validate schema node)]
       (if (.isSuccess report)
         (node->clj node opts)
         (bad-request (report-message report))))
     (catch JsonParseException e
       (bad-request (.getMessage e))))))

(defn conform!
  "Calls conform and throws the result if it's an error."
  [& args]
  (f/when-let-failed? [err (apply conform args)]
    (throw+ err)))


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
