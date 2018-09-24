(ns haus.core.log
  (:require [haus.core.config :as config]
            [com.stuartsierra.component :as component]
            [taoensso.timbre :as timbre]))


(defrecord Logging [config]
  component/Lifecycle

  (start [this]
    (let [conf (get-in config [:config :logging])]
      (timbre/set-level! (:level conf)))
    this)

  (stop [this]
    this))


(defn new-logging []
  (map->Logging {}))
