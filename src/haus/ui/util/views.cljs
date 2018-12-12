(ns haus.ui.util.views)


(defn not-found []
  [:h2 "404 - Not Found"])


; The content view takes the bidi route as the single argument.
(defmulti content :handler)

(defmethod content :default [_]
  [not-found])
