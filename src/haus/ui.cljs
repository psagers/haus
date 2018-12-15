(ns haus.ui
  (:require [clojure.string :as str]
            [bidi.bidi :as bidi]
            [haus.ui.categories :as categories]
            [haus.ui.modal :as modal]
            [haus.ui.util.events :as events]
            [haus.ui.util.views :as views]
            [pushy.core :as pushy]
            [re-frame.core :as rf]
            [re-graph.core :as re-graph]
            [re-pressed.core :as rp]
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
   :navbar {:expanded? false}
   :page {}                     ; Volatile state for the current route
   :modal nil                   ; Bootstrap modal
   :categories {}               ; categories by id
   :people {}})                 ; people by id


(rf/reg-event-fx
  ::initialize
  (fn [_ _]
    (let [db (initial-db)]
      {:db db
       :dispatch-n (list [::rp/add-keyboard-event-listener "keydown"]
                         [::re-graph/init :haus]
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


; Changing the route automatically resets various other attributes.
(rf/reg-event-db
  ::set-route
  (fn [db [_ route]]
    (-> db
        (assoc :route route
               :page {}
               :modal nil)
        (assoc-in [:navbar :expanded?] false))))


(rf/reg-event-db
  ::toggle-navbar
  (fn [db _]
    (update-in db [:navbar :expanded?] not)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Subscriptions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(rf/reg-sub ::route (fn [db _] (:route db)))
(rf/reg-sub ::page (fn [db _] (:page db)))
(rf/reg-sub ::navbar (fn [db _] (:navbar db)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Views
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod views/content ::initial [_]
  ())

(defmethod views/content ::home [_]
  [:div {:class "content"}
   [:h2 "Home"]])


(defn ^:private navbar-brand []
  (let [{:keys [expanded?]} @(rf/subscribe [::navbar])]
    [:div {:class "navbar-brand"}
     [:a {:class (str "navbar-burger burger" (if expanded? " is-active"))
          :on-click #(rf/dispatch [::toggle-navbar])}
      [:span {:aria-hidden true}]
      [:span {:aria-hidden true}]
      [:span {:aria-hidden true}]]]))


(defn ^:private nav-link
  [label handler & params]
  (let [{current :handler} @(rf/subscribe [::route])
        path (apply bidi/path-for routes handler params)]
    [:a {:class (str "navbar-item" (if (= current handler) " is-active"))
         :href path}
     label]))


(defn ^:private navbar-menu []
  (let [{:keys [expanded?]} @(rf/subscribe [::navbar])]
    [:div {:class (str "navbar-menu" (if expanded? " is-active"))}
     [:div {:class "navbar-start"}
      [nav-link "Home" ::home]
      [nav-link "Categories" ::categories/index]]
     [:div {:class "navbar-end"}]]))


(defn root []
  [:div
    [:nav {:class "navbar is-dark"}
     [:div {:class "container"}
      [navbar-brand]
      [navbar-menu]]]
    [:section {:class "section"}
     [:div {:class "container"}
       [views/content @(rf/subscribe [::route])]]]
    [modal/view]])


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; App & initialization
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:dev/after-load install-root []
  (reagent/render [root] (js/document.getElementById "app")))


(defn ^:export run []
  (rf/dispatch-sync [::initialize])
  (pushy/start! history)
  (install-root))
