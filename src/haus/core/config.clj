(ns haus.core.config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [haus.core.util :refer [deep-merge]]
            [com.stuartsierra.component :as component]
            [taoensso.truss :refer [have]]))


; Default configuration, pending merging from the environment.
(def ^:private default-config
  {:db {:host "localhost"
        :port 5432
        :dbname "haus"
        :user "postgres"
        :password nil}
   :mongodb {:host "localhost"
             :port nil
             :dbname "haus"
             :user nil
             :password nil}
   :logging {:level :info}})

(defn ^:private load-user-config
  "Loads the user config from the EDN file. The path is taken from the
  HAUS_CONFIG environment variable."
  []
  (if-let [path (System/getenv "HAUS_CONFIG")]
    (with-open [rdr (java.io.PushbackReader. (io/reader path))]
      (edn/read rdr))))

(defn ^:private load-config
  "Returns the final, merged config."
  [base-config override-config]
  (let [user-config (or (load-user-config) {})]
    (deep-merge default-config base-config user-config override-config)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Component
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

; Configuration is defined in four layers: default < base < user < override.
; Defaults are built in; base is provided by the system definition; user comes
; from the environment; and overrides allow special systems (such as the test
; system) to have the last word.
(defrecord Config [base override config]
  component/Lifecycle

  (start [this]
    (let [config (load-config base override)]
      (assoc this :config config)))

  (stop [this]
    this))


(defn new-config
  ([]
   (new-config {} {}))

  ([base]
   (new-config base {}))

  ([base override]
   (map->Config {:base (have map? base)
                 :override (have map? override)})))
