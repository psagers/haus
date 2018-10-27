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
  and then closes it. If the result is nil--presumably because the callback is
  of type SingleResultCallback<Void>--the channel will simply be closed on
  success.

  Key-value parameters:

    :result-fn - a function to transform the (non-nil) result.
    :default - a default to use in place of a successful nil result."
  [chan & {:keys [result-fn default] :or {result-fn identity}}]
  (reify com.mongodb.async.SingleResultCallback
    (onResult [this result err]
      (cond
        (some? err) (async/put! chan err)
        (some? result) (async/put! chan (result-fn result))
        (some? default) (async/put! chan default))
      (async/close! chan))))
