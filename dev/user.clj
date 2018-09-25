(ns user
  (:require [com.stuartsierra.component :as component]
            [clojure.tools.namespace.repl :refer [refresh]]
            [io.pedestal.test :refer [response-for]]
            [haus.main :as main]))


(def system nil)


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
  (get-in system [:db :conn]))
