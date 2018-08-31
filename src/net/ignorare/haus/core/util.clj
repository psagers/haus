(ns net.ignorare.haus.core.util)

;
; Miscellaneous Clojure utilities.
;

(defn map-vals
  "Applies a function to each value in a map."
  [f m]
  (reduce-kv #(assoc %1 %2 (f %3)) {} m))
