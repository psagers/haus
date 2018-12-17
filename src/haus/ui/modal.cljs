(ns haus.ui.modal
  (:require [re-frame.core :as rf]
            [re-pressed.core :as rp]
            [reagent.core :as r]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

; modal: {
;   :title <component>
;   :body <component>
;   :buttons-left <list of components>
;   :buttons-right <list of components>
;   :on-close <event>
;   :on-enter <event>
; }
(rf/reg-event-fx
  ::open
  (fn [{:keys [db]} [_ modal]]
    {:db (assoc db :modal modal)
     :dispatch [::rp/set-keydown-rules {:event-keys [[[::close] [{:which 27}]]
                                                     [[::enter] [{:which 13}]]]
                                        :always-listen-keys [{:which 27} {:which 13}]}]}))


(rf/reg-event-fx
  ::close
  (fn [{:keys [db]} _]
    {:db (assoc db :modal nil)
     :dispatch-n [[::rp/set-keydown-rules {}]
                  (get-in db [:modal :on-close])]}))


(rf/reg-event-fx
  ::enter
  (fn [{:keys [db]} _]
    {:dispatch-n [(get-in db [:modal :on-enter])]}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Subscriptions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(rf/reg-sub ::modal (fn [db _] (:modal db)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Views
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn view []
  (r/with-let [rmodal (rf/subscribe [::modal])]
    (let [{:keys [title body buttons-left buttons-right] :as modal} @rmodal]
      [:div#modal.modal {:class (if modal "is-active")}
       [:div.modal-background]
       [:div.modal-card
        [:div.modal-card-head
         [:p.modal-card-title title]
         [:button.delete {:on-click #(rf/dispatch [::close])}]]

        [:div.modal-card-body body]

        [:div.modal-card-foot
         [:div.level.is-modal
          (into [:div.level-left] (for [button buttons-left] [:div.level-item button]))
          (into [:div.level-right] (for [button buttons-right] [:div.level-item button]))]]]])))
