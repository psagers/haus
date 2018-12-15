(ns haus.ui.categories
  (:require [haus.ui.util :refer [map-by]]
            [haus.ui.util.events :as events]
            [haus.ui.util.forms :as forms]
            [haus.ui.util.routes :refer [object-id]]
            [haus.ui.util.views :as views]
            [re-frame.core :as rf]
            [re-graph.core :as re-graph]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Database
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def subscribe-event
  [::re-graph/subscribe :haus ::stream
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
(defmethod events/route-enter-fx ::index [{:keys [db]} _]
  {:db (assoc db :page {:tab :active})
   :dispatch subscribe-event})


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


(rf/reg-event-db
  ::set-tab
  (fn [db [_ tab]]
    (assoc-in db [:page :tab] tab)))


(declare modal-body)

(rf/reg-event-fx
  ::begin-editing
  (fn [{:keys [db]} [_ category]]
    {:db (assoc-in db [:page :editing] category)
     :dispatch [:haus.ui.modal/begin "Edit category" [modal-body]
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
      {})))  ; TODO: report error?


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Subscriptions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

; L2: Map of categories by id.
(rf/reg-sub
  ::map
  (fn [db _]
    (:categories db)))


; L3: Currently active tab.
(rf/reg-sub
  ::tab
  :<- [:haus.ui/page]
  (fn [page _]
    (:tab page)))


; L3: Sorted list of categories.
(rf/reg-sub
  ::sorted
  :<- [::map]
  (fn [categories _]
    (sort-by :name (vals categories))))


(rf/reg-sub
  ::visible
  :<- [::sorted]
  (fn [categories [_ tab]]
    (-> (case tab
          (:active) (remove :retired categories)
          (:retired) (filter :retired categories)
          categories)
        vec)))


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
     (forms/input "text" "form-name" (:name category) name-changed
                  :attrs {:required true}
                  :label "Name")
     (forms/checkbox "form-retired" (:retired category) retired-changed
                     :label "Retired"
                     :help-text "Retired categories will not be offered for new transactions.")]))


(defn ^:private categories-for-tab [categories tab]
  (-> (case tab
        (:active) (remove :retired categories)
        (:retired) (filter :retired categories)
        categories)
      vec))

(defn ^:private visible? [category tab]
  (let [retired? (:retired category)]
    (if (= tab :active)
      (not retired?)
      retired?)))

(defn ^:private category-tab [id name]
  (let [current @(rf/subscribe [::tab])]
    [:li {:class (if (= id current) "is-active")}
     [:a {:on-click #(rf/dispatch [::set-tab id])} name]]))

(defn ^:private category-item [{:keys [id name description retired] :as category}]
  (let [active-tab @(rf/subscribe [::tab])]
    [:div {:class (str "column is-one-third is-one-quarter-desktop"
                       (if-not (visible? category active-tab) " is-hidden"))}
     [:div {:class "card"}
      [:header {:class "card-header"}
        [:p {:class "card-header-title"} name]]
      [:div {:class "card-content"}
        [:div {:class "content"}
         (if description [:p description] [:i "No description"])]]
      [:div {:class "card-footer"}
        [:a {:class "card-footer-item"
             :on-click #(rf/dispatch [::begin-editing category])}
         "Edit"]]]]))

(defmethod views/content ::index [_]
  (let [active-tab @(rf/subscribe [::tab])
        categories @(rf/subscribe [::sorted])]
   [:div
    [:h1 {:class "title is-hidden-desktop"} "Categories"]

    [:div {:class "tabs is-boxed"}
     [:ul
      [category-tab :active "Active"]
      [category-tab :retired "Retired"]]]

    (if (= active-tab :retired)
      [:div {:class "notification"} "Retired categories will not be offered for new transactions."])

    [:div {:class "columns is-multiline is-tablet"}
     (for [category @(rf/subscribe [::sorted])]
       ^{:key (:id category)} [category-item category])]]))
