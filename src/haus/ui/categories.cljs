(ns haus.ui.categories
  (:require [re-frame.core :as rf]
            [re-graph.core :as re-graph]))


(defn map-by [key-fn s]
  (into {} (map (juxt key-fn identity) s)))


(rf/reg-event-db
  ::update!
  (fn [db [_ event]]
    (let [{:keys [action docs ids]} (get-in event [:data :categories])]
      (case action
        ("RESET") (assoc db :categories (map-by :id docs))
        ("UPDATE") (update-in db [:categories] #(merge % (map-by :id docs)))
        ("DELETE") (update-in db [:categories] #(apply dissoc % ids))))))


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


(defn subscribe []
  (rf/dispatch [::re-graph/subscribe
                :haus
                ::stream
                "{categories{action docs{id name retired} ids}}"
                {}
                [::update!]]))


(defn unsubscribe []
  (rf/dispatch [::re-graph/unsubscribe :haus ::stream]))
