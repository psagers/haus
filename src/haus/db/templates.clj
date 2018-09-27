(ns haus.db.templates
  (:require [clojure.data.json :as json]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Row -> Clojure
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private decode-splits
  "Decodes a sequence of split rows."
  [splits]
  (letfn [(decode-value [key value]
            (case key
              (::amount) (-> value (bigdec) (.setScale 2))
              value))]
    (vec (filter some? (json/read-str splits
                                      :key-fn (partial keyword (namespace ::a))
                                      :value-fn decode-value)))))

(defn ^:private decode-template
  "Decodes a template row."
  [row]
  (-> row
      (update :tags vec)
      (update :splits decode-splits)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Clojure -> Row
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private encode-tags
  "Encodes a vector of tags for storage."
  [tags]
  (->> tags
       (eduction (filter string?) (remove empty?) (map str/lower-case) (distinct))
       (to-array)))

(defn ^:private encode-split
  "Encodes a map of split values for storage."
  [split]
  (select-keys split [::person_id ::amount]))

(defn ^:private encode-template
  "Encodes a map of template values for storage."
  [tmpl]
  (cond-> (select-keys [:category_id :title :description :tags])
          (contains? tmpl :tags) (update :tags encode-tags)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-templates
  "Returns all templates."
  ([con]
   (let [query "SELECT t.*, json_agg(s) AS splits
                FROM templates AS t LEFT OUTER JOIN template_splits AS s ON (s.template_id = t.id)
                GROUP BY t.id"]
     (map decode-template
          (jdbc/query con [query])))))

(defn insert-template!
  "Creates a new template. The template map is the same as the transaction map,
  minus the date."
  [con tmpl]
  (jdbc/with-db-transaction [con con]
    (let [row (encode-template tmpl)
          splits (map encode-split (get tmpl :splits []))
          [{tmpl_id :id}] (jdbc/insert! con :templates row)]
      (when-let [splits (not-empty splits)]
        (jdbc/insert-multi! con :template_splits (map #(assoc % :template_id tmpl_id) splits)))
      tmpl_id)))

(defn update-template!
  "Updates an existing template."
  [con tmpl_id tmpl]
  (jdbc/with-db-transaction [con con]
    (let [row (encode-template tmpl)
          splits (map encode-split (get tmpl :splits []))]

      (when-let [row (not-empty row)]
        (jdbc/update! con :templates row ["id = ?", tmpl_id]))

      (when-let [splits (not-empty splits)]
        (jdbc/delete! con :splits ["template_id = ?", tmpl_id])
        (jdbc/insert-multi! con :splits (map #(assoc % :template_id tmpl_id) splits))))
    true))

(defn delete-template!
  "Deletes an existing template."
  [con tmpl_id]
  (let [[n] (jdbc/delete! con :templates ["id = ?", tmpl_id])]
    (> n 0)))
