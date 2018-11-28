(ns haus.mongodb
  (:require [com.stuartsierra.component :as component])
  (:import (com.mongodb.reactivestreams.client MongoClient MongoClients
                                               MongoDatabase)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Component
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn connection-string
  "Constructs a MongoDB connection string from the database config map. The
  host and dbname are required."
  [{:keys [host port dbname user password]}]
  (let [auth-part (if user
                    (if password
                      (str user ":" password "@")
                      (str user "@"))
                    "")
        host-part (if port
                    (str host ":" port)
                    host)]
    (str "mongodb://" auth-part host-part "/" dbname)))


(defrecord MongoDB [config, ^MongoClient client, ^MongoDatabase db]
  component/Lifecycle

  (start [this]
    (let [conf (get-in config [:config :mongodb])
          url (connection-string conf)
          client (MongoClients/create url)
          db (.getDatabase client (:dbname conf))]
      (assoc this :client client
                  :db db)))

  (stop [this]
    (when client
      (.close client))
    (assoc this :client nil
                :db nil)))


(defn new-database []
  (map->MongoDB {}))
