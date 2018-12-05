(ns haus.changes
  (:require [clojure.core.async :as async]
            [com.stuartsierra.component :as component]
            [net.ignorare.mongodb.async.database :as database]
            [net.ignorare.mongodb.async.subscriber :as subscriber]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Component
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord Changes [db subscriber pub]
  component/Lifecycle

  (start [this]
    (let [subscriber (database/watch db)
          ch (subscriber/begin subscriber)
          pub (async/pub ch (comp :coll :ns))]
      (assoc this :subscriber subscriber
                  :pub pub)))

  (stop [this]
    (subscriber/cancel! subscriber)
    (assoc this :subscriber nil
                :pub nil)))


(defn new-changes []
  (map->Changes {}))
