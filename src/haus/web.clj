(ns haus.web
  (:require [com.stuartsierra.component :as component]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.body-params :as body-params]
            [haus.db :as db]
            [haus.web.categories]
            [haus.web.util.http :refer (defresource)]
            [haus.web.util.json]
            [ring.util.response :as ring-resp]
            [taoensso.timbre :refer [info]]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Routes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn db-interceptor
  "Enqueues an interceptor that attaches a database spec to the request."
  [env db]
  (let [enter (if (= env :test)
                #(assoc-in % [:request ::db/spec] (deref (resolve 'haus.test.util/*db-conn*)))
                (let [db-spec (:spec db)]
                  #(assoc-in % [:request ::db/spec] db-spec)))]
    (interceptor/interceptor
      {:name ::db
       :enter enter})))

; No methods allowed at the root for the moment.
(defresource root)

(defn routes [env db]
  [[["/" ^:interceptors [haus.web.util.json/json-body
                         (db-interceptor env db)]
         {:any `root}

      ; Nested routes
      (haus.web.categories/routes "/categories")]]])


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

(defn new-service [config db]
  (let [env (get-in config [:config :env] :prod)]
    {:env env
     ::http/routes (routes env db)
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
          (dev? service) http/dev-interceptors))

(defn create-server [config db]
  (-> (new-service config db)
      apply-env
      add-interceptors
      http/create-server))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Component
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord Server [config db server]
  component/Lifecycle

  (start [this]
    (let [server' (create-server config db)]
      (when-not (test? server')
        (http/start server'))
      (assoc this :server server')))

  (stop [this]
    (http/stop server)
    (assoc this :server nil)))


(defn new-server []
  (map->Server {}))
