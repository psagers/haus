(ns net.ignorare.haus.web.json
  (:require [clojure.java.io :as io])
  (:import (com.fasterxml.jackson.databind JsonNode ObjectMapper)
           (com.github.fge.jsonschema.main JsonSchema JsonSchemaFactory)))

(def ^:private json-node? (partial instance? JsonNode))
(def ^:private json-schema? (partial instance? JsonSchema))

(defn ^:private node->clj
  "Converts a JsonNode to a Clojure data structure."
  [^JsonNode node {:keys [keywords? bigdecimals?] :or {keywords? true, bigdecimals? true} :as opts}]
  (let [key-fn (if keywords? keyword identity)
        assoc-field (fn [m ^java.util.Map$Entry field]
                      (assoc m (key-fn (.getKey field)) (node->clj (.getValue field) opts)))]
    (cond
      (.isObject node) (reduce assoc-field {} (iterator-seq (.fields node)))
      (.isArray node) (vec (map #(node->clj % opts) (iterator-seq (.elements node))))
      (.isTextual node) (.textValue node)
      (.isBigDecimal node) (.decimalValue node)
      (.isIntegralNumber node) (.longValue node)
      (.isNumber node) (if bigdecimals? (.decimalValue node) (.doubleValue node))
      (.isBoolean node) (.booleanValue node)
      (.isNull node) nil
      :else (assert false (str "Unhandled JsonNode type: " node)))))

(defn ^:private ->node
  "Loads JSON into a JsonNode. This accepts a variety of source types,
  including strings and InputStream instances."
  ^JsonNode [source]
  (-> (ObjectMapper.) (.readTree source)))

(defn ->schema
  "Compiles a JSON source to a JsonSchema object."
  [source]
  (let [factory (JsonSchemaFactory/byDefault)
        node (->node source)]
    (.getJsonSchema factory node)))

(defn load-schema
  "Loads a schema from resources/json-schema."
  [filename]
  (with-open [stream (io/input-stream (io/resource (str "json-schema/" filename)))]
    (->schema stream)))

(defn valid?
  "Returns true iff the given JSON validates against the schema. Use ->schema
  or load-schema to pre-compile your JSON schema."
  [^JsonSchema schema value]
  (let [value (if-not (json-node? value) (->node value) value)
        report (.validate schema value)]
    (.isSuccess report)))

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
