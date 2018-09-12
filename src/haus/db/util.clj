(ns haus.db.util
  (:require [clojure.string :as str]
            [clojure.core.match :refer [match]]
            [taoensso.truss :refer [have]]))


(defn where-join
  "Joins multiple WHERE clauses with a logical operator. Parentheses will be
  added as needed. Each clause should be a vector with a WHERE string followed
  by optional parameters."
  [op clauses]
  (let [force-parens (fn [where]
                       (if-not (and (str/starts-with? (have string? where) "(")
                                    (str/ends-with? (have string? where) ")"))
                         (str "(" where ")")
                         where))
        op (str " " op " ")
        clauses (remove empty? clauses)]
    (match [clauses]
      [([] :seq)] []
      [([clause] :seq)] clause
      :else (vec (apply concat
                        [(str/join op (->> clauses (map first) (map force-parens)))]
                        (map rest clauses))))))
