(ns haus.ui
  (:require [reagent.core :as reagent]
            [re-frame.core :as rf]
            [re-graph.core :as rg]))


(rf/reg-event-db
  :initialize
  (fn [_ _]
    {:categories {}}))


(rf/reg-event-db
  :categories/update!
  (fn [db [_ event]]
    (let [{:keys [kind values]} (get-in event [:data :categories])]
      (case kind
        ("RESET") (assoc db :categories (zipmap (map :id values) values))
        ("UPDATE") (update-in db [:categories] #(merge % (zipmap (map :id values) values)))
        ("DELETE") (update-in db [:categories] #(apply dissoc % (map :id values)))))))


(rf/reg-sub
  :categories/all
  (fn [db _]
    (-> db :categories vals)))


(rf/reg-sub
  :categories/sorted
  (fn [db _]
    (sort-by :name @(rf/subscribe [:categories/all]))))


(defn ui []
  [:div {:class "container"}
   [:div {:class "row"}
    [:div {:class "col"}
     [:h1 "Haus"]
     [:h2 "Categories"]
     [:ul
       (for [category @(rf/subscribe [:categories/sorted])]
         ^{:key (:id category)} [:li (:name category)])]]]])


(defn run []
  (rf/dispatch-sync [:initialize])

  (rf/dispatch [::rg/init :haus {:ws-url "ws://127.0.0.1:8080/graphql-ws"
                                 :http-url "http://127.0.0.1:8080/graphql"}])
  (rf/dispatch [::rg/subscribe
                :haus
                :categories/stream
                "{categories{kind values{id name retired}}}"
                {}
                [:categories/update!]])

  (reagent/render [ui]
                  (js/document.getElementById "app")))
