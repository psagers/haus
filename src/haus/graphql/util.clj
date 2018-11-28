(ns haus.graphql.util
  (:require [clojure.core.async :as async]
            [com.walmartlabs.lacinia.async :refer [channel->result]]
            [haus.core.util :refer [until-throwable]]
            [net.ignorare.mongodb.async.client :as db]))


(defn resolve-id [_ _ value]
  (.toHexString (:_id value)))

(defn resolve-documents
  "Wraps db/find-documents in a lacinia promise.

  The promise will yield either a vector of results or an exception."
  [conn coll-name options]
  (let [chan (db/find-documents conn coll-name options)]
    (channel->result (async/transduce until-throwable conj [] chan))))
