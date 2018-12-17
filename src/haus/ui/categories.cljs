(ns haus.ui.categories
  (:require [clojure.string :as str]
            [haus.ui.util :refer [map-by]]
            [haus.ui.util.events :as events]
            [haus.ui.util.forms :as forms]
            [haus.ui.modal :as modal]
            [haus.ui.util.routes :refer [object-id]]
            [haus.ui.util.views :as views]
            [re-frame.core :as rf]
            [re-graph.core :as re-graph]
            [reagent.core :as r]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Database
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def subscribe-event
  [::re-graph/subscribe :haus ::stream
   "{categories{action docs{id name description retired} ids}}"
   {}
   [::on-subscription-event]])


; Probably never used.
(def unsubscribe-event
  [::re-graph/unsubscribe :haus ::stream])


(defn new-event [vars]
  [::re-graph/mutate
   :haus
   "mutation NewCategory($name: Name!, $description: String) {
      new_category(name: $name, description: $description)
        {id name description retired}
   }"
   vars
   [::on-new-response]])


(defn update-event [vars]
  [::re-graph/mutate
   :haus
   "mutation UpdateCategory($id: ObjectId!, $name: Name, $description: String, $retired: Boolean) {
      update_category(id: $id, name: $name, description: $description, retired: $retired)
        {id name description retired}
   }"
   vars
   [::on-update-response]])


(defn delete-event [vars]
  [::re-graph/mutate
   :haus
   "mutation DeleteCategory($id: ObjectId!) {delete_category(id: $id)}"
   vars
   [::on-delete-response]])


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Routing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def routes
  [["" ::index]])


; Make sure the subscription is up and running on entry. This should be
; redundant.
(defmethod events/route-enter-fx ::index [{:keys [db]} _]
  {:db (assoc db :page {:tab :active
                        :form nil})
   :dispatch subscribe-event})


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(rf/reg-event-db
  ::set-tab
  (fn [db [_ tab]]
    (assoc-in db [:page :tab] tab)))


;
; Modal forms
;

(declare modal-body modal-buttons)

(rf/reg-event-fx
  ::begin-modal
  (fn [{:keys [db]} [_ operation category]]
    (let [modal {:title (str/capitalize (str (name operation) " category"))
                 :body [modal-body]
                 :buttons-right (modal-buttons operation)
                 :on-close [::end-modal]
                 :on-enter [::submit-modal]}]
      {:db (assoc-in db [:page :form] {:operation operation
                                       :category category
                                       :waiting? false})
       :dispatch [::modal/open modal]})))


(rf/reg-event-db
  ::continue-modal
  (fn [db [_ updates]]
    (update-in db [:page :form :category] #(merge % updates))))


(rf/reg-event-fx
  ::submit-modal
  (fn [{:keys [db]} _]
    (if-some [{:keys [operation category]} (get-in db [:page :form])]
      {:db (assoc-in db [:page :form :waiting?] true)
       :dispatch (case operation
                   (:new) (new-event category)
                   (:edit) (update-event category)
                   (:delete) (delete-event category)
                   [::modal/close])})))


(rf/reg-event-db
  ::end-modal
  (fn [db _]
    (assoc-in db [:page :form] nil)))


;
; GraphQL
;

(rf/reg-event-db
  ::on-subscription-event
  (fn [db [_ event]]
    (let [{:keys [action docs ids]} (get-in event [:data :categories])]
      (case action
        ("RESET") (assoc db :categories (map-by :id docs))
        ("UPDATE") (update-in db [:categories] #(merge % (map-by :id docs)))
        ("DELETE") (update-in db [:categories] #(apply dissoc % ids))))))


(rf/reg-event-fx
  ::on-new-response
  (fn [{:keys [db]} [_ resp]]
    (if-some [category (get-in resp [:data :new_category])]
      {:db (assoc-in db [:categories (:id category)] category)
       :dispatch [::modal/close]}
      (js/console.error (clj->js resp)))))


(rf/reg-event-fx
  ::on-update-response
  (fn [{:keys [db]} [_ resp]]
    (if-some [category (get-in resp [:data :update_category])]
      {:db (assoc-in db [:categories (:id category)] category)
       :dispatch [::modal/close]}
      (js/console.error (clj->js resp)))))


(rf/reg-event-fx
  ::on-delete-response
  (fn [{:keys [db]} [_ resp]]
    (if-some [id (get-in resp [:data :delete_category])]
      {:db (update-in db [:categories] #(dissoc % :id))
       :dispatch [::modal/close]}
      (js/console.error (clj->js resp)))))


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
  (fn [category-map _]
    (sort-by :name (vals category-map))))


; L3: Individual categories by id.
(rf/reg-sub
  ::get
  :<- [::map]
  (fn [categories [_ id]]
    (get categories id)))


; L3: Card specification for a single category.
(rf/reg-sub
  ::card
  (fn [[_ id] _]
    {:category (rf/subscribe [::get id])
     :tab (rf/subscribe [::tab])})
  (fn [{:keys [category tab]} _]
    {:category category
     :visible? (if (= tab :retired)
                 (:retired category)
                 (not (:retired category)))}))


; L3: Current modal state. This is our private representation, not the one
; managed by haus.ui.modal.
(rf/reg-sub
  ::form
  :<- [:haus.ui/page]
  (fn [page _]
    (:form page)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Views
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn on-input-change [field]
  (fn [event]
    (let [value (-> event .-target .-value)]
      (rf/dispatch [::continue-modal {field value}]))))

(defn on-checkbox-change [field]
  (fn [event]
    (let [value (-> event .-target .-checked)]
      (rf/dispatch [::continue-modal {field value}]))))


(defn name-input [category]
  (forms/input "text" (:name category) (on-input-change :name)
               :attrs {:required true}
               :label "Name"))

(defn description-input [category]
  (forms/input "text" (:description category) (on-input-change :description)
               :label "Description"))

(defn retired-checkbox [category]
  (forms/checkbox (:retired category) (on-checkbox-change :retired)
                  :label "Retired"
                  :help-text "Retired categories will not be offered for new transactions."))


(defmulti -modal-body
  (fn [operation _] operation))

(defmethod -modal-body :new
  [_ category]
  [:div
   [name-input category]
   [description-input category]])

(defmethod -modal-body :edit
  [_ category]
  [:div
   [name-input category]
   [description-input category]
   [retired-checkbox category]])

(defmethod -modal-body :delete
  [_ category]
  [:p.content "Permenently delete " [:b (:name category)] "?"])

(defmethod -modal-body :default
  [_ _]
  nil)

(defn modal-body []
  (r/with-let [form (rf/subscribe [::form])]
    (let [{:keys [operation category]} @form]
      (-modal-body operation category))))

(defn modal-buttons [operation]
  [[:a.button.is-grey-light {:on-click #(rf/dispatch [::modal/close])} "Cancel"]
   (if (= operation :delete)
     [:a.button.is-danger {:on-click #(rf/dispatch [::submit-modal])} "Delete"]
     [:a.button.is-primary {:on-click #(rf/dispatch [::submit-modal])} "Save"])])


(defn ^:private filter-tabs [& args]
  (r/with-let [tabs (partition 2 args)
               current-id (rf/subscribe [::tab])]
    (into [:ul]
      (for [[tab-id tab-name] tabs]
        [:li {:class (if (= tab-id @current-id) "is-active")}
         [:a {:on-click #(rf/dispatch [::set-tab tab-id])} tab-name]]))))


(defn header []
  [:div.level.is-mobile
   [:div.level-left
    [:div.level-item
     [:div.tabs.is-toggle.is-toggle-rounded
      [filter-tabs :active "Active"
                   :retired "Retired"]]]]
   [:div.level-right
    [:div.level-item
     [:a.button {:on-click #(rf/dispatch [::begin-modal :new {}])}
      [:span.icon.is-small [:i.fas.fa-plus]]
      [:span "New"]]]]])


(defn notification []
  (r/with-let [tab (rf/subscribe [::tab])]
    (case @tab
      (:retired) [:div.notification "Retired categories will not be offered for new transactions."]
      nil)))


(defn ^:private card [id]
  (r/with-let [card (rf/subscribe [::card id])]
    (let [{:keys [category visible?]} @card
          {:keys [name description]} category]
      [:div.column.is-4.is-3-desktop {:class (when-not visible? "is-hidden")}
       [:div.card
        [:header.card-header
         [:p.card-header-title name]]
        [:div.card-content
          [:div.content
           (if (empty? description)
             [:i.has-text-grey-light "No description"]
             [:p description])]]
        [:div.card-footer
          [:a.card-footer-item {:on-click #(rf/dispatch [::begin-modal :edit category])}
           [:span.icon.has-text-dark [:i.fas.fa-pencil-alt]]]
          [:a.card-footer-item {:on-click #(rf/dispatch [::begin-modal :delete category])}
           [:span.icon.has-text-dark [:i.far.fa-trash-alt]]]]]])))


(defn ^:private cards []
  (r/with-let [categories (rf/subscribe [::sorted])]
    (into [:div.columns.is-multiline]
      (for [{:keys [id]} @categories]
        ^{:key id} [card id]))))


(defmethod views/content ::index [_]
  [:div
   [:h1.title.is-hidden-desktop "Categories"]
   [header]
   [notification]
   [cards]])
