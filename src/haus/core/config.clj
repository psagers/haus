(ns haus.core.config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

; Default configuration, pending merging from the environment.
(def default-config
  {:db {:dbname "haus"
        :user "postgres"}
   :logging {:level :info}})

(defn deep-merge
  "Recursively merges maps."
  [& args]
  (if (every? map? args)
    (apply merge-with deep-merge args)
    (last args)))

(defn load-user-config
  "Loads the user config from the EDN file. The path is taken from the
  HAUS_CONFIG environment variable."
  []
  (if-let [path (System/getenv "HAUS_CONFIG")]
    (with-open [rdr (java.io.PushbackReader. (io/reader path))]
      (edn/read rdr))))

(defn load-config
  "Returns the final, merged config."
  []
  (if-let [user-config (load-user-config)]
    (deep-merge default-config user-config)
    default-config))

; Our final configuration (lazy).
(def config (delay (load-config)))

;
; Convenience accessors
;

(defn log-level [] (get-in @config [:logging :level]))
