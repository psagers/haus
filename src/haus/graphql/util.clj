(ns haus.graphql.util
  (:require [clojure.core.async :as async :refer [<! >! go]]
            [com.walmartlabs.lacinia.async :refer [channel->result]]
            [com.walmartlabs.lacinia.schema :as schema]
            [haus.core.util :refer [remove-nil-values until-throwable]]
            [net.ignorare.mongodb.async.collection :as collection]
            [net.ignorare.mongodb.async.session :as session]
            [net.ignorare.mongodb.async.subscriber :as sub])
  (:import (org.bson.types ObjectId)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Scalars
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn parse-object-id [value]
  (try
    (if (string? value)
      (ObjectId. value)
      (schema/coercion-failure "expected a hexadecimal string."))
    (catch IllegalArgumentException e
      (schema/coercion-failure (.getMessage e)))
    (catch Throwable _
      nil)))

(defn serialize-object-id [value]
  (if (instance? ObjectId value)
    (.toHexString value)
    (schema/coercion-failure "expected ObjectId")))


(defn parse-name [value]
  (if ((every-pred string? seq) value)
    value
    (schema/coercion-failure "expected a non-empty string.")))

(defn serialize-name [value]
  (if (string? value)
    value
    (schema/coercion-failure "expected a string.")))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Resolvers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn resolve-id [_ _ value]
  (:_id value))

(defn resolve-documents
  "Wraps collection/find in a lacinia promise.

  The promise will yield either a vector of results or an exception."
  [conn coll-name find-opts]
  (let [chan (sub/begin (collection/find conn coll-name find-opts))]
    (channel->result (async/into [] chan))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Mutations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn new-document
  "Generic GraphQL mutation handler for adding a new document."
  [conn coll-name args]
  (let [id (ObjectId.)]
    (-> (collection/find-one-and-replace! conn coll-name
                                          {:_id id}
                                          (assoc args :_id id)
                                          {:upsert true
                                           :return-document :after})
        (sub/begin)
        (channel->result))))


(defn update-document
  "Generic GraphQL mutation handler for updating an existing document."
  [conn coll-name args]
  (let [id (:id args)
        args (-> args (dissoc :id) remove-nil-values)]
    (-> (collection/find-one-and-update! conn coll-name
                                         {:_id id}
                                         {:$set args}
                                         {:return-document :after})
        (sub/first)
        (channel->result))))


(defn delete-document
  "Generic GraphQL mutation handler for deleting a document."
  [conn coll-name args]
  (let [id (:id args)
        ch (sub/first (collection/delete-one! conn coll-name {:_id id}))]
    (-> (async/map #(some-> % :count (> 0)) [ch])
        (channel->result))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Streamers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private reset-event [docs]
  {:action :RESET, :docs docs})

(defn ^:private update-event [docs]
  {:action :UPDATE, :docs docs})

(defn ^:private delete-event [ids]
  {:action :DELETE, :ids ids})


(def ^:private watch-pipeline-crud
  [{:$match {:operationType {:$in ["insert" "update" "replace" "delete"]}}}])


(defn ^:private -stream-documents
  "Helper for stream-documents, below."
  [conn coll-name find-opts callback events-sub-p]
  (go
    ;(println "New graphql stream:" coll-name (.hashCode conn))

    (let [docs (<! (->> (collection/find conn coll-name find-opts)
                        (sub/begin)
                        (async/into [])))]
      (callback (reset-event docs)))

    (let [opTime (some-> conn :session .getOperationTime)
          ; XXX: :start-at-operation-time seems to just produce an empty stream.
          events-sub (collection/watch conn coll-name {:pipeline watch-pipeline-crud})
                                                       ;:start-at-operation-time opTime})
          events-ch (sub/begin events-sub 1)]

      (>! events-sub-p events-sub)

      (loop []
        (if-some [event (<! events-ch)]
          ; See if the document still matches our original query.
          (let [doc-id (get-in event [:documentKey :_id])
                find-opts' (assoc-in find-opts [:filter :_id] doc-id)]
            (if-some [doc (<! (sub/first (collection/find conn coll-name find-opts')))]
              (callback (update-event [doc]))
              (callback (delete-event [doc-id])))
            (recur))
          (callback nil))))))

    ;(println "Finished graphql stream:" coll-name (.hashCode conn))))


(defn stream-documents
  "Turns a collection query into a lacinia stream. This sends the initial
  results of the query immediately and then streams any changes as they happen.

  conn: The MongoDB connection map.
  coll-name: The collection name.
  find-opts: Options (e.g. filter) for collection/find.
  callback: The lacinia streamer callback."
  [conn coll-name find-opts callback]
  (let [events-sub-p (async/promise-chan)]
    (session/with-session conn
                          -stream-documents
                          [coll-name find-opts callback events-sub-p])

    (fn end-stream []
      (async/take! events-sub-p #(some-> % sub/cancel!)))))
