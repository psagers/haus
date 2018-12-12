(ns haus.ui
  (:require [clojure.string :as str]
            [bidi.bidi :as bidi]
            [haus.ui.categories :as categories]
            [haus.ui.util.events :as events]
            [haus.ui.util.views :as views]
            [pushy.core :as pushy]
            [re-frame.core :as rf]
            [re-graph.core :as re-graph]
            [reagent.core :as reagent]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Routing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def routes
  ["/" [["" ::home]
        ["categories" categories/routes]]])
        ;[true ::not-found]]])


(defn ^:private update-route [route]
  (rf/dispatch [::update-route route]))

(defn ^:private match-route [path]
  (if-let [route (bidi/match-route routes path)]
    route
    (if (str/ends-with? path "/")
      (recur (subs path 0 (-> path count dec)))
      {:handler ::not-found})))

(def history
  (pushy/pushy update-route match-route))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private initial-db []
  {:route {:handler ::initial}  ; bidi route
   :categories {}               ; categories by id
   :people {}})                 ; people by id


(rf/reg-event-fx
  ::initialize
  (fn [_ _]
    (let [db (initial-db)]
      {:db db
       :dispatch-n (list [::re-graph/init :haus]
                         categories/subscribe-event)})))


(rf/reg-event-fx
  ::update-route
  (fn [{:keys [db]} [_ route]]
    (let [old-route (:route db)]
      {:dispatch-n (list [::route-leave old-route]
                         [::set-route route]
                         [::route-enter route])})))


(rf/reg-event-fx
  ::route-enter
  events/route-enter-fx)


(rf/reg-event-fx
  ::route-leave
  events/route-leave-fx)


(rf/reg-event-db
  ::set-route
  (fn [db [_ route]]
    (assoc db :route route)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Subscriptions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(rf/reg-sub
  ::route
  (fn [db _]
    (:route db)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Views
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod views/content ::initial [_]
  ())

(defmethod views/content ::home [_]
  [:h2 "Home"])


(defn root []
  [:div {:class "container"}
   [:div {:class "row"}
    [:div {:class "col"} [:a {:href "/"} "Home"]]
    [:div {:class "col"} [:a {:href "/categories"} "Categories"]]
    [:div {:class "col"} [:a {:href "/bogus"} "Not found"]]]
   [:div {:class "row"}
    [:div {:class "col"}
     [:h1 "Haus"]
     [views/content @(rf/subscribe [::route])]]]])


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; App & initialization
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:export run []
  (rf/dispatch-sync [::initialize])
  (pushy/start! history)

  (reagent/render [root] (js/document.getElementById "app")))
