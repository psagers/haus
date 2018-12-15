(ns haus.web
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [com.stuartsierra.component :as component]
            [com.walmartlabs.lacinia.pedestal :as lp]
            [haus.db.categories :as categories]
            [haus.db.people :as people]
            [haus.web.transactions :refer [transactions]]
            [haus.web.util.http :refer [defresource]]
            [haus.web.util.json :as json]
            [haus.web.util.resource :as resource]
            [hiccup.page :refer [html5]]
            [io.pedestal.http :as http]
            [io.pedestal.http.route.definition.table :as table]
            [io.pedestal.interceptor :as interceptor]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Routes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;(defn db-interceptor
;  "An interceptor that attaches a database spec to the request."
;  [env db]
;  (let [enter (if (= env :test)
;                #(assoc-in % [:request :haus.db/spec] (deref (resolve 'haus.test.util/*db-conn*)))
;                (let [db-spec (:spec db)]
;                  #(assoc-in % [:request :haus.db/spec] db-spec)))]
;    (interceptor/interceptor
;      {:name ::db
;       :enter enter})))

;; No methods allowed at the root for the moment.
;(defresource root)

;(def categories
;  (resource/simple-resource categories/model
;                            "haus.web.categories"
;                            ::categories/insert-params
;                            ::categories/update-params))

;(def people
;  (resource/simple-resource people/model
;                            "haus.web.people"
;                            ::people/insert-params
;                            ::people/update-params))

;(defn routes [env db]
;  [[["/" ^:interceptors [json/json-body
;                         (db-interceptor env db)]
;         {:any `root}

;      ; Nested routes
;      (resource/routes categories "/categories")
;      (resource/routes people "/people")
;      (resource/routes transactions "/transactions")]]])


(defn page
  "Renders the main page."
  [js-name]
  (html5
    [:head
     [:meta {:charset "utf-8"}]
     [:meta {:name "viewport"
             :content "width=device-width, initial-scale=1"}]
     [:title "Haus"]
     [:link {:rel "stylesheet" :href "/css/haus.css"}]]
    [:body
     [:div#app
      [:h1 "Loading app..."]]
     [:script {:src (str "/js/" js-name)}]]))


(defn ^:private read-js-manifest []
  (with-open [rdr (java.io.PushbackReader. (io/reader (io/resource "public/js/manifest.edn")))]
    (edn/read rdr)))


(defn ^:private main-js-filename []
  (loop [manifest (read-js-manifest)]
    (let [module (first manifest)]
      (if (= (:module-id module) :main)
        (:output-name module)
        (recur (rest manifest))))))


(defn app
  [request]
  {:status 200,
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (page (main-js-filename))})


; All unmatched paths should render the app page. The client app can display
; what it likes, including a 404 message.
(def app-interceptor
  {:name ::app
   :leave (fn [context]
            (if-not (http/response? (:response context))
              (assoc context :response (-> context :request app))
              context))})


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

(defn new-service [config graphql]
  (let [env (get-in config [:config :env])
        service (lp/service-map (:schema graphql)
                                {:app-context graphql
                                 :async true
                                 :env env
                                 :graphiql (= env :dev)
                                 :ide-path "/graphiql"
                                 :path "/graphql"
                                 :subscriptions true
                                 :subscriptions-path "/graphql-ws"})]
    (-> service
        (assoc :env env
               ::http/type :jetty
               ::http/host (get-in config [:http :host] "localhost")
               ::http/port (get-in config [:http :port] 8000)
               ::http/resource-path "public"
               ::http/not-found-interceptor app-interceptor
               ::http/join? false))))

(defn apply-env [service]
  (case (:env service)
    (:dev) (-> service
               (assoc ::http/allowed-origins {:creds true :allowed-origins (constantly true)})
               (assoc ::http/secure-headers {:content-security-policy-settings {:object-src "'none'"}}))
    service))

(defn add-interceptors [service]
  (cond-> (http/default-interceptors service)
          (dev? service) http/dev-interceptors))

(defn create-server [config graphql]
  (-> (new-service config graphql)
      apply-env
      add-interceptors
      http/create-server))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Component
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;(defrecord Server [config db mongodb server]
(defrecord Server [config graphql server]
  component/Lifecycle

  (start [this]
    (let [server' (create-server config graphql)]
      (when-not (test? server')
        (http/start server'))
      (assoc this :server server')))

  (stop [this]
    (http/stop server)
    (assoc this :server nil)))


(defn new-server []
  (map->Server {}))
