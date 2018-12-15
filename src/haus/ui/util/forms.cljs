(ns haus.ui.util.forms
  (:require [clojure.string :as str]
            [re-frame.core :as rf]))


(defn input [type id value on-change & {:keys [attrs class help-text label]}]
  (let [attrs (into (or attrs {}) [[:type type]
                                   [:id id]
                                   [:class (str/join " " ["input" class])]
                                   [:value value]
                                   [:on-change on-change]])]
    [:div {:class "field"}
     (if label [:label {:class "label" :for id} label])
     [:div {:class "control"}
      [:input attrs]]
     (if help-text [:p {:class "help"} help-text])]))


(defn checkbox [id checked on-change & {:keys [attrs class help-text label]}]
  (let [attrs (into (or attrs {}) [[:type "checkbox"]
                                   [:id id]
                                   [:class (str/join " " ["form-check-input" class])]
                                   [:checked checked]
                                   [:on-change on-change]])]
    [:div {:class "field"}
     [:div {:class "control"}
      [:label {:class "checkbox"}
       [:input attrs] " " label]]
     (if help-text [:p {:class "help"} help-text])]))
