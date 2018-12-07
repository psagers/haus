(ns haus.ui
  (:require [reagent.core :as reagent]
            [re-frame.core :as rf]
            [re-graph.core :as re-graph]
            [haus.ui.categories :as categories]))


(rf/reg-event-db
  :initialize
  (fn [_ _]
    {:categories {}}))


(defn category-item [category]
  [:li (if (:retired category)
         [:i (:name category)]
         (:name category))])


(defn root []
  [:div {:class "container"}
   [:div {:class "row"}
    [:div {:class "col"}
     [:h1 "Haus"]
     [:h2 "Categories"]
     [:ul
       (for [category @(rf/subscribe [::categories/sorted])]
         ^{:key (:id category)} [category-item category])]]]])


(defn ^:export run []
  (rf/dispatch-sync [:initialize])
  (rf/dispatch [::re-graph/init :haus])

  (reagent/render [root]
                  (js/document.getElementById "app"))

  (categories/subscribe))
