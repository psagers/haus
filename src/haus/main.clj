(ns haus.main
  (:gen-class) ; for -main method in uberjar
  (:require [com.stuartsierra.component :as component]
            [haus.core.config :as config]
            [haus.core.log :as log]
            [haus.db :as db]
            [haus.web :as web]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Component
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn system
  ([]
   (system {} {}))

  ([base-config]
   (system base-config {}))

  ([base-config override-config]
   (component/system-map
     :config (config/new-config base-config override-config)
     :log (component/using
            (log/new-logging) [:config])
     :db (component/using
           (db/new-database) [:config])
     :http (component/using
             (web/new-server) [:config :db]))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Main (production)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn -main
  "The entry-point for 'lein run'"
  [& args]
  (let [sys (component/start (system))]
    (.join (get-in sys [:http :server :io.pedestal.http/server]))))
