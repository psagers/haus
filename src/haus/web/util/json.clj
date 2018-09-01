(ns haus.web.util.json
  "Tools for validating against JSON schemas. Schemas are loaded from resource
  files and used to validate JSON data before it's converted to Clojure data
  structures."
  (:require [slingshot.slingshot :refer [throw+]])
  (:import (com.fasterxml.jackson.databind JsonNode ObjectMapper)
           (com.github.fge.jsonschema.core.report ProcessingReport)
           (com.github.fge.jsonschema.main JsonSchema JsonSchemaFactory)))

(def ^:private json-node? (partial instance? JsonNode))

(defn ^:private node->clj
  "Converts a JsonNode to a Clojure data structure."
  [^JsonNode node {:keys [keywords? bigdecimals?] :or {keywords? true, bigdecimals? true} :as opts}]
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
    (node->clj' node)))

(defn ^:private ->node
  "Loads JSON into a JsonNode. This accepts a variety of source types,
  including strings and InputStreams."
  ^JsonNode [source]
  (-> (ObjectMapper.) (.readTree source)))

(defn load-schema
  "Loads a schema from resources/json-schema."
  ^JsonSchema [filename]
  (let [factory (JsonSchemaFactory/byDefault)
        uri (str "resource:/json-schema/" filename)]
    (.getJsonSchema factory uri)))

(defn validate
  "Validates a JSON value against a schema and returns the ProcessingReport.
  The value may be a JsonNode or anything that can be converted to one."
  [^JsonSchema schema value]
  (let [value (if-not (json-node? value) (->node value) value)]
    (.validate schema value)))

(defn valid?
  "Returns true iff the given JSON validates against the schema. Use
  load-schema to load your schema from disk."
  ^ProcessingReport [^JsonSchema schema value]
  (.isSuccess (validate schema value)))

(defn conform
  "Given a JsonSchema and JSON source, this validates the value and, if
  successful, returns it as a Clojure data structure. Returns nil if validation
  fails. Accepts an option map with :keywords? and :bigdecimals?, both
  defaulting to true."
  ([^JsonSchema schema value]
   (conform schema value {}))

  ([^JsonSchema schema value opts]
   (let [node (->node value)]
     (if (valid? schema node)
       (node->clj node opts)
       nil))))

(defn conform!
  "Given a JsonSchema and JSON source, this validates the value and, if
  successful, returns it as a Clojure data structure. Throws a
  {::failed-validation msg) on failure. Accepts an option map with :keywords?
  and :bigdecimals?, both defaulting to true."
  ([^JsonSchema schema value]
   (conform schema value {}))

  ([^JsonSchema schema value opts]
   (let [node (->node value)
         report (validate schema node)]
     (if (.isSuccess report)
       (node->clj node opts)
       (throw+ {:ex ::failed-validation, :msg (str report)})))))
