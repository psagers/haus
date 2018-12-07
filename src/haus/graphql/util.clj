(ns haus.graphql.util
  (:require [clojure.core.async :as async :refer [<! >! go]]
            [com.walmartlabs.lacinia.async :refer [channel->result]]
            [com.walmartlabs.lacinia.schema :refer [tag-with-type]]
            [haus.core.util :refer [until-throwable]]
            [net.ignorare.mongodb.async.collection :as collection]
            [net.ignorare.mongodb.async.session :as session]
            [net.ignorare.mongodb.async.subscriber :as sub]))


(defn resolve-id [_ _ value]
  (.toHexString (:_id value)))


(defn resolve-documents
  "Wraps collection/find in a lacinia promise.

  The promise will yield either a vector of results or an exception."
  [conn coll-name find-opts]
  (let [chan (sub/begin (collection/find conn coll-name find-opts))]
    (channel->result (async/into [] chan))))


(defn reset-event [docs]
  {:action :RESET, :docs docs})

(defn update-event [docs]
  {:action :UPDATE, :docs docs})

(defn delete-event [ids]
  {:action :DELETE, :ids ids})


(def ^:private watch-pipeline-crud
  [{:$match {:operationType {:$in ["insert" "update" "replace" "delete"]}}}])


(defn ^:private -stream-documents
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


(defn --stream-documents
  "Turns a collection query into a lacinia stream. This sends the initial
  results of the query immediately and then streams any changes as they happen.

  conn: The MongoDB connection map.
  coll-name: The collection name.
  find-opts: Options (e.g. filter) for collection/find.
  callback: The lacinia streamer callback."
  [conn coll-name find-opts callback]
  (let [coll-name (name coll-name)
        events-sub (collection/watch conn coll-name {:pipeline watch-pipeline-crud})
        events-ch (sub/begin events-sub 1)]

    (go
      ;(println "New graphql stream:" coll-name (.hashCode ctrl-ch))

      (let [docs (<! (->> (collection/find conn coll-name find-opts)
                          (sub/begin)
                          (async/into [])))]
        (callback (reset-event docs)))

      (loop []
        (if-some [event (<! events-ch)]
          ; See if the document still matches our original query.
          (let [doc-id (get-in event [:documentKey :_id])
                find-opts' (assoc-in find-opts [:filter :_id] doc-id)]
            (if-some [doc (<! (sub/first (collection/find conn coll-name find-opts')))]
              (callback (update-event [doc]))
              (callback (delete-event [doc-id])))
            (recur))
          (callback nil))))

      ;(println "Finished graphql stream:" coll-name (.hashCode ctrl-ch)))

    (fn end-stream []
      (sub/cancel! events-sub))))
