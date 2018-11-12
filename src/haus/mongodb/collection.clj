(ns haus.mongodb.collection
  (:require [haus.mongodb.bson :as bson]
            [haus.mongodb.model :as model])
  (:import (com.mongodb.async SingleResultCallback)
           (com.mongodb.async.client MongoCollection)))


(defn create-index!
  "Creates an index on a collection.

    key-doc: IntoBson
    options: IntoIndexOptions"
  [^MongoCollection collection, ^SingleResultCallback callback, key-doc options]
  (.createIndex collection
                (bson/bson key-doc)
                (model/index-options options)
                callback))


(defn create-indexes!
  "Creates multiple indexes on a collection.

    indexes: sequence of IntoIndexModel"
  [^MongoCollection collection, ^SingleResultCallback callback, indexes]
  (.createIndexes collection
                  (map model/index-model indexes)
                  callback))


(defn count-documents
  [^MongoCollection collection, ^SingleResultCallback callback, {:keys [filter]}]
  (.countDocuments collection
                   (bson/bson (or filter {}))
                   callback))


(defn estimated-document-count
  [^MongoCollection collection, ^SingleResultCallback callback]
  (.estimatedDocumentCount collection
                           callback))


(defn find-documents
  "Returns a channel that produces a sequence of find results. If an error
  occurs, the last value will be an exception."
  [^MongoCollection collection {:keys [collation comment filter limit
                                       projection qualifier skip sort]}]
  (cond-> (.find collection)
          (some? collation) (.collation (model/collation collation))
          (some? comment) (.comment comment)
          (some? filter) (.filter (bson/bson filter))
          (some? limit) (.limit limit)
          (some? projection) (.projection (bson/bson projection))
          (some? skip) (.skip skip)
          (some? sort) (.sort (bson/bson sort))))


(defn aggregate
  [^MongoCollection collection, pipeline]
  (.aggregate collection (map bson/bson pipeline)))


(defn insert!
  "Inserts a document into a collection. Returns a channel that will produce
  either an ObjectId or an exception."
  [^MongoCollection collection, ^SingleResultCallback callback, doc]
  (.insertOne collection
              (bson/bson-doc doc)
              callback))


(defn insert-many!
  [^MongoCollection collection, ^SingleResultCallback callback, docs]
  (.insertMany collection
               (map bson/bson-doc docs)
               callback))


(defn update-one!
  "Updates at most one matching document."
  [^MongoCollection collection, ^SingleResultCallback callback, filter update]
  (.updateOne collection
              (bson/bson filter)
              (bson/bson update)
              callback))


(defn update-many!
  "Updates all matching documents."
  [^MongoCollection collection, ^SingleResultCallback callback, filter update]
  (.updateMany collection
               (bson/bson filter)
               (bson/bson update)
               callback))


(defn replace!
  [^MongoCollection collection, ^SingleResultCallback callback, filter doc]
  (.replaceOne collection
               (bson/bson filter)
               (bson/bson doc)
               callback))


(defn delete-one!
  "Deletes at most one document. Returns a channel that will report the
  results: {:acknowledged <bool>, [:count: <count>]}."
  [^MongoCollection collection, ^SingleResultCallback callback, filter]
  (.deleteOne collection
              (bson/bson filter)
              callback))


(defn delete-many!
  "Like delete-one!, but will delete all matching documents."
  [^MongoCollection collection, ^SingleResultCallback callback, filter]
  (.deleteMany collection
               (bson/bson filter)
               callback))
