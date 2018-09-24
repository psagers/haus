(ns haus.web
  (:require [com.stuartsierra.component :as component]
            [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.body-params :as body-params]
            [ring.util.response :as ring-resp]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Routes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def routes #{})


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Pedestal
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

; Run locally for development.
(defn dev? [service]
  (= (:env service) :dev))

; Make availble for unit tests (don't start the server).
(defn test? [service]
  (= (:env service) :test))

; Run in production.
(defn prod? [service]
  (= (:env service) :prod))

(defn new-service [config]
  (let [env (get-in config [:config :env] :prod)]
    {:env env
     ::http/routes routes
     ::http/type :jetty
     ::http/port (get-in config [:http :port] 8080)
     ::http/join? false}))

(defn apply-env [service]
  (case (:env service)
    (:dev) (-> service
               (assoc ::http/allowed-origins {:creds true :allowed-origins (constantly true)})
               (assoc ::http/secure-headers {:content-security-policy-settings {:object-src "'none'"}}))
    service))

(defn add-interceptors [service]
  (cond-> (http/default-interceptors service)
          (dev? service) (http/dev-interceptors)))

(defn create-server [config]
  (-> (new-service config)
      apply-env
      add-interceptors
      http/create-server))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Component
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord Server [config db server]
  component/Lifecycle

  (start [this]
    (let [server' (create-server config)]
      (when-not (test? server')
        (http/start server'))
      (assoc this :server server')))

  (stop [this]
    (http/stop server)
    (assoc this :server nil)))


(defn new-server []
  (map->Server {}))
