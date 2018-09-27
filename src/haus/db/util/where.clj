(ns haus.db.util.where
  (:require [clojure.string :as str]
            [clojure.core.match :refer [match]]
            [taoensso.truss :refer [have]]))


(defprotocol Where
  "Represents query parameters to be rendered into a WHERE clause."
  (render [this] "Returns a where-vector: [clause, params*]"))


(defn join
  "Joins multiple WHERE clauses with a logical operator. Parentheses will be
  added as needed. Each clause should be a vector with a WHERE string followed
  by optional parameters."
  ([op clauses]
   (join op clauses {}))

  ([op clauses {:keys [auto-paren?] :or {auto-paren? true}}]
   (let [force-parens (fn [clause]
                        (if-not (and (str/starts-with? (have string? clause) "(")
                                     (str/ends-with? clause ")"))
                          (str "(" clause ")")
                          clause))
         op (str " " op " ")
         clauses (remove empty? clauses)]
     (match [clauses]
       [([] :seq)] []
       [([clause] :seq)] clause
       :else (let [xforms (filter some? [(map first), (when auto-paren? (map force-parens))])
                   sql* (eduction (apply comp xforms) clauses)]
               (vec (cons (str/join op sql*)
                          (apply concat (map rest clauses)))))))))


(extend-protocol Where
  ; A map is rendered by simply ANDing a bunch of "key = ?" terms.
  clojure.lang.IPersistentMap
  (render [this]
    (letfn [(render-key [k]
              (cond (keyword? k) (name k)
                    (string? k) k
                    :else (str k)))
            (render-entry [[k v]]
              [(str (render-key k) " = ?"), v])]
      (let [clauses (map render-entry this)]
        (join "AND" clauses {:auto-paren? false})))))
