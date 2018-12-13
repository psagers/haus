(ns haus.ui.categories
  (:require [haus.ui.util :refer [map-by]]
            [haus.ui.util.events :as events]
            [haus.ui.util.routes :refer [object-id]]
            [haus.ui.util.views :as views]
            [re-frame.core :as rf]
            [re-graph.core :as re-graph]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Database
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def subscribe-event
  [::re-graph/subscribe
   :haus
   ::stream
   "{categories{action docs{id name retired} ids}}"
   {}
   [::update!]])


; Probably never used.
(def unsubscribe-event
  [::re-graph/unsubscribe :haus ::stream])


(defn update-event [vars]
  [::re-graph/mutate
   :haus
   "mutation UpdateCategory($id: ObjectId!, $name: Name, $retired: Boolean) {
      update_category(id: $id, name: $name, retired: $retired) {id name retired}
   }"
   vars
   [::on-update-response]])


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Routing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def routes
  [["" ::index]])


; Make sure the subscription is up and running on entry. This should be
; redundant.
(defmethod events/route-enter-fx ::index [_ _]
  {:dispatch subscribe-event})


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(rf/reg-event-db
  ::update!
  (fn [db [_ event]]
    (let [{:keys [action docs ids]} (get-in event [:data :categories])]
      (case action
        ("RESET") (assoc db :categories (map-by :id docs))
        ("UPDATE") (update-in db [:categories] #(merge % (map-by :id docs)))
        ("DELETE") (update-in db [:categories] #(apply dissoc % ids))))))


(declare modal-body)

(rf/reg-event-fx
  ::begin-editing
  (fn [{:keys [db]} [_ category]]
    {:db (assoc-in db [:page :editing] category)
     :dispatch [:haus.ui/modal-begin "Edit category" [modal-body]
                                     :on-cancel [::cancel-editing]
                                     :on-save [::finish-editing]]}))


(rf/reg-event-db
  ::continue-editing
  (fn [db [_ updates]]
    (update-in db [:page :editing] #(merge % updates))))


(rf/reg-event-db
  ::cancel-editing
  (fn [db _]
    (assoc-in db [:page :editing] nil)))


(rf/reg-event-fx
  ::finish-editing
  (fn [{:keys [db]} _]
    (if-some [category (get-in db [:page :editing])]
      {:db (assoc-in db [:page :editing] nil)
       :dispatch (update-event category)})))


(rf/reg-event-fx
  ::on-update-response
  (fn [{:keys [db]} [_ resp]]
    (if-some [category (get-in resp [:data :update_category])]
      {:db (assoc-in db [:categories (:id category)] category)}
      {})))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Subscriptions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

; L2: Map of categories by id.
(rf/reg-sub
  ::map
  (fn [db _]
    (:categories db)))


; L3: Sorted list of categories.
(rf/reg-sub
  ::sorted
  :<- [::map]
  (fn [categories _]
    (sort-by :name (vals categories))))


; L3: Individual categories by id.
(rf/reg-sub
  ::get
  :<- [::map]
  (fn [categories [_ id]]
    (get categories id)))


(rf/reg-sub
  ::editing
  :<- [:haus.ui/page]
  (fn [page _]
    (:editing page)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Views
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn name-changed [event]
  (let [value (-> event .-target .-value)]
    (rf/dispatch [::continue-editing {:name value}])))


(defn retired-changed [event]
  (let [value (-> event .-target .-checked)]
    (rf/dispatch [::continue-editing {:retired value}])))


(defn update-name [event]
  (rf/dispatch [::finish-editing]))


(defn modal-body []
  (let [category @(rf/subscribe [::editing])]
    [:div
     [:div {:class "form-group"}
      [:label {:for "modal-name"} "Name"]
      [:input {:type "text"
               :id "modal-name"
               :class "form-control"
               :required true
               :value (:name category)
               :size 10
               :data-id (:id category)
               :on-change #(rf/dispatch [::continue-editing {:name (-> % .-target .-value)}])}]]
     [:div {:class "form-group form-check"}
      [:input {:type "checkbox"
               :id "modal-retired"
               :class "form-check-input"
               :checked (:retired category)
               :on-change #(rf/dispatch [::continue-editing {:retired (-> % .-target .-checked)}])}]
      [:label {:class "form-check-label", :for "modal-retired"} "Retired"]
      [:small {:class "form-text text-muted"}
       "Retired categories will not be offered for new transactions."]]]))


(defn ^:private category-item [{:keys [id name retired] :as category} editing?]
  [:li
    (if editing?
      [:input {:type "text", :size 10, :value name, :data-id id
               :on-change name-changed, :on-blur update-name}]
      [:span {:on-click #(rf/dispatch [::begin-editing category])}
        (if retired [:i name] name)])])


(defmethod views/content ::index [_]
  [:div
   [:h2 "Categories"]
   [:ul
    ;(let [editing @(rf/subscribe [::editing])]
      (for [category @(rf/subscribe [::sorted])]
        ;(if (= (:id editing) (:id category))
        ;  ^{:key (:id category)} [category-item editing true]
          ^{:key (:id category)} [category-item category false])]])
