(ns haus.main
  (:gen-class) ; for -main method in uberjar
  (:require [com.stuartsierra.component :as component]
            [haus.core.config :as config]
            [haus.core.log :as log]
            [haus.db :as db]
            [haus.graphql :as graphql]
            [haus.mongodb :as mongodb]
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
     :mongodb (component/using
                (mongodb/new-database) [:config])
     ;:changes (component/using
     ;           (changes/new-changes) {:db :mongodb})
     :graphql (component/using
                (graphql/new-graphql) {:db :mongodb})
     :http (component/using
             (web/new-server) [:config :db :mongodb]))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Main (production)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn -main
  [& args]
  (let [sys (component/start (system))]
    (.join (get-in sys [:http :server :io.pedestal.http/server]))))
