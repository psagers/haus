(ns haus.ui.util.events)


(defmulti route-enter-fx
  (fn [cfx [_ route]] (:handler route)))

(defmethod route-enter-fx :default [_ _]
  {})


(defmulti route-leave-fx
  (fn [cfx [_ route]] (:handler route)))

(defmethod route-leave-fx :default [_ _]
  {})
