(ns haus.graphql.util
  (:require [clojure.core.async :as async :refer [<!]]
            [com.walmartlabs.lacinia.async :refer [channel->result]]
            [haus.core.util :refer [until-throwable]]
            [net.ignorare.mongodb.async.client :as db]))


(defn resolve-id [_ _ value]
  (.toHexString (:_id value)))

(defn resolve-documents
  "Wraps db/find-documents in a lacinia promise.

  The promise will yield either a vector of results or an exception."
  [conn coll-name find-opts]
  (let [chan (db/find-documents conn coll-name find-opts)]
    (channel->result (async/transduce until-throwable conj [] chan))))

(defn stream-documents
  "Wraps db/find-documents in a lacinia stream."
  [conn coll-name find-opts callback]
  (let [find-chan (db/find-documents conn coll-name find-opts)
        watch-chan (db/watch conn coll-name)]
    (async/go
      (loop [doc (<! find-chan)]
        (when doc
          (callback doc)
          (recur (<! find-chan))))

      (loop [event (<! watch-chan)]
        (when event
          (let [doc-id (get-in event [:documentKey :_id])
                find-opts' (update-in find-opts [:filter :_id] doc-id)
                doc (<! (db/find-documents conn coll-name find-opts'))]
            (if doc
              (callback doc)
              (callback {:_id doc-id, :$deleted true}))
            (recur (<! watch-chan))))))

    (fn end-stream []
      (async/close! find-chan)
      (async/close! watch-chan))))
