(ns user
  (:require [com.stuartsierra.component :as component]
            [clojure.core.async :as async :refer [<! <!! >! >!!]]
            [clojure.tools.namespace.repl :refer [refresh]]
            [com.walmartlabs.lacinia :as gql]
            [net.ignorare.mongodb.async.client :as mdb]
            [haus.core.util :refer [drain!]]
            [haus.test.util :refer [response-for]]
            [haus.main :as main]))


(defonce system nil)


(defn init []
  (alter-var-root #'system
    (constantly (main/system {:logging {:level :info}}
                             {:env :dev}))))

(defn start []
  (alter-var-root #'system component/start))

(defn stop []
  (alter-var-root #'system #(some-> % component/stop)))

(defn go []
  (init)
  (start))

(defn restart []
  (stop)
  (start))

(defn reset []
  (stop)
  (refresh :after 'user/go))

(defn service-fn
  "Quick access to the http service function."
  []
  (get-in system [:http :server :io.pedestal.http/service-fn]))

(defn db
  "Quick access to the configured database spec."
  []
  (get-in system [:db :spec]))

(defn mdb
  "Quick access to the configured mongodb database."
  []
  (:mongodb system))

(defn gql-schema
  []
  (get-in system [:graphql :schema]))

(defn simplify
  "Simplify ordered maps for REPL convenience."
  [value]
  (letfn [(f [v] (cond
                   (map? v) (into {} v)
                   (sequential? v) (into [] v)
                   :else v))]
    (walk/postwalk f value)))

(defn q
  "GraphQL query shortcut."
  [query]
  (simplify (gql/execute (gql-schema) query nil (:graphql system))))
