(ns net.ignorare.haus.core.config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

; Default configuration, pending merging from the environment.
(def default-config
  {:db {:dbname "haus"
        :user "postgres"}
   :logging {:level :info}})

(defn deep-merge
  "Recursively merges maps."
  [& maps]
  (if (every? map? maps)
    (apply merge-with deep-merge maps)
    (last maps)))

(defn load-user-config
  "Loads the user config from the EDN file. The path is taken from the
  HAUS_CONFIG environment variable."
  []
  (some-> (System/getenv "HAUS_CONFIG")
          (io/reader)
          (java.io.PushbackReader.)
          (edn/read)))

(defn load-config
  "Returns the final, merged config."
  []
  (if-some [user-config (load-user-config)]
    (deep-merge default-config user-config)
    default-config))

; Our final configuration (lazy).
(def config (delay (load-config)))

;
; Convenience accessors
;

(defn log-level [] (get-in @config [:logging :level]))
