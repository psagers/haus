(ns haus.mongodb.async
  "Utilities for working with asynchronous driver APIs."
  (:require [clojure.core.async :as async]))


(defn block
  "Wraps a single-argument callback function in a reified com.mongodb.Block."
  [f]
  (reify com.mongodb.Block (apply [this t] (f t))))


(defn function
  "Wraps a single-argument callback function in a reified com.mongodb.Function."
  [f]
  (reify com.mongodb.Function (apply [this t] (f t))))


(defn single-result-callback
  "Wraps a two-argument callback function in a reified
  com.mongodb.async.SingleResultCallback."
  [f]
  (reify com.mongodb.async.SingleResultCallback
    (onResult [this result err]
       (f result err))))


(defn channel-result-callback
  "Returns a SingleResultCallback that places the result or error on a channel
  and then closes it.

  Key-value parameters:

    :result-fn - a function to transform the (non-nil) result."
  [chan & {:keys [result-fn] :or {result-fn identity}}]
  (reify com.mongodb.async.SingleResultCallback
    (onResult [this result err]
      (if (some? err)
        (async/put! chan err)
        (when-let [result (result-fn result)]
          (async/put! chan result)))
      (async/close! chan))))
