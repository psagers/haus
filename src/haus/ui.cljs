(ns haus.ui
  (:require [reagent.core :as reagent]))


(defn ui []
  [:div
   [:h1 "Haus"]])


(defn run []
  (reagent/render [ui]
                  (js/document.getElementById "app")))
