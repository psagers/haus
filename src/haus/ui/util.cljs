(ns haus.ui.util)


(defn map-by [key-fn s]
  (into {} (map (juxt key-fn identity) s)))
