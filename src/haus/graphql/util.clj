(ns haus.graphql.util
  (:require [clojure.core.async :as async :refer [<! go]]
            [com.walmartlabs.lacinia.async :refer [channel->result]]
            [com.walmartlabs.lacinia.schema :refer [tag-with-type]]
            [haus.core.util :refer [until-throwable]]
            [net.ignorare.mongodb.async.collection :as collection]
            [net.ignorare.mongodb.async.subscriber :as sub]))


(defn resolve-id [_ _ value]
  (.toHexString (:_id value)))


(defn resolve-documents
  "Wraps collection/find in a lacinia promise.

  The promise will yield either a vector of results or an exception."
  [conn coll-name find-opts]
  (let [chan (sub/begin (collection/find conn coll-name find-opts))]
    (channel->result (async/into [] chan))))


(defn stream-event
  "Builds a standard subscription stream event."
  ([kind]
   (stream-event kind nil))

  ([kind values]
   {:kind kind, :values values}))


(defn stream-documents
  "Turns a collection query into a lacinia stream. This sends the initial
  results of the query immediately and then streams any changes as they happen.

  conn: The MongoDB connection map.
  changes-pub: The change stream pub from haus.changes.
  coll-name: The collection name.
  type-name: The GraphQL schema type of normal results.
  find-opts: Options (e.g. filter) for collection/find.
  callback: The lacinia streamer callback.

  TODO: Ideally, we should have no possibility of blocking changes-pub. One can
  imagine a scheme by which we detect a slow GraphQL consumer, unsubscribe from
  changes, and start again with a RESET event."
  [conn changes-pub coll-name find-opts callback]
  (let [coll-name (name coll-name)
        ctrl-ch (async/chan)
        events-ch (async/chan 10)]

    (go
      ;(println "New graphql stream:" coll-name (.hashCode ctrl-ch))

      (async/sub changes-pub coll-name events-ch)

      (let [docs (->> (collection/find conn coll-name find-opts)
                      (sub/begin)
                      (async/into [])
                      (<!))]
        (callback (stream-event :RESET docs)))

      (loop []
        (when (async/alt!
                ctrl-ch false  ; Closing the control channel ends the stream.

                events-ch
                ([event]
                 (when event
                   (if-some [doc-id (get-in event [:documentKey :_id])]
                     ; See if the document still matches our original query.
                     (let [find-opts' (assoc-in find-opts [:filter :_id] doc-id)
                           doc (<! (sub/first (collection/find conn coll-name find-opts')))]
                       (if doc
                         (callback (stream-event :UPDATE [doc]))
                         (callback (stream-event :DELETE [{:_id doc-id}])))))
                   true)))  ; Keep going
          (recur)))

      (async/unsub changes-pub coll-name events-ch))

      ;(println "Finished graphql stream:" coll-name (.hashCode ctrl-ch)))

    (fn end-stream []
      (async/close! ctrl-ch))))
