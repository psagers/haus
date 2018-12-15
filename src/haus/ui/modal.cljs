(ns haus.ui.modal
  (:require [re-frame.core :as rf]
            [re-pressed.core :as rp]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(rf/reg-event-fx
  ::begin
  (fn [{:keys [db]} [_ title body & {:keys [on-cancel on-save]}]]
    {:db (assoc db :modal {:title title
                           :body body
                           :on-cancel on-cancel
                           :on-save on-save})
     :dispatch [::rp/set-keydown-rules {:event-keys [[[::cancel] [{:which 27}]]
                                                     [[::save] [{:which 13}]]]
                                        :always-listen-keys [{:which 27} {:which 13}]}]}))


(rf/reg-event-fx
  ::cancel
  (fn [{:keys [db]} _]
    {:db (assoc db :modal nil)
     :dispatch-n (list [::rp/set-keydown-rules {}]
                       (get-in db [:modal :on-cancel]))}))


(rf/reg-event-fx
  ::save
  (fn [{:keys [db]} _]
    {:db (assoc db :modal nil)
     :dispatch-n (list [::rp/set-keydown-rules {}]
                       (get-in db [:modal :on-save]))}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Subscriptions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(rf/reg-sub ::modal (fn [db _] (:modal db)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Views
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn view []
  (let [modal @(rf/subscribe [::modal])]
    [:div {:class (str "modal" (if modal " is-active"))}
     [:div {:class "modal-background"}]
     [:div {:class "modal-card"}
      [:div {:class "modal-card-head"}
       [:p {:class "modal-card-title"} (:title modal)]
       [:button {:class "delete"
                 :on-click #(rf/dispatch [::cancel])}]]
      [:div {:class "modal-card-body"}
       (:body modal)]
      [:div {:class "modal-card-foot"
             :style {:justify-content "flex-end"}}
       [:button {:type "button", :class "button is-light"
                 :on-click #(rf/dispatch [::cancel])}
        "Cancel"]
       [:button {:type "button", :class "button is-primary"
                 :on-click #(rf/dispatch [::save])}
        "Save"]]]]))
