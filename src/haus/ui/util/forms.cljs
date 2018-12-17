(ns haus.ui.util.forms
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]))


(defn input [type value on-change & {:keys [attrs class help-text label]}]
  (r/with-let [id (gensym "field-")]
    (let [attrs (into (or attrs {}) [[:type type]
                                     [:id id]
                                     [:class (str/join " " ["input" class])]
                                     [:value value]
                                     [:on-change on-change]])]
      [:div.field
       (if label [:label.label {:for id} label])
       [:div.control
        [:input attrs]]
       (if help-text [:p {:class "help"} help-text])])))


(defn checkbox [checked on-change & {:keys [attrs class help-text label]}]
  (let [attrs (into (or attrs {}) [[:type "checkbox"]
                                   [:class (str/join " " ["form-check-input" class])]
                                   [:checked checked]
                                   [:on-change on-change]])]
    [:div.field
     [:div.control
      [:label.checkbox
       [:input attrs] " " label]]
     (if help-text [:p.help help-text])]))
