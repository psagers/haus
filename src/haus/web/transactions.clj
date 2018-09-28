(ns haus.web.transactions
  (:require [haus.db.transactions :as db]
            [haus.web.util.json :as json]
            [haus.web.util.resource :as resource]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Query params
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;(defn ^:private conform-body
;  "Validates a request body and additionally ensures that splits (if given) sum
;  to zero. Returns a failed util/Response on failure."
;  [schema body]
;  (f/attempt-all [txn (json/conform schema body)
;                  balanced? (zero? (transduce (map :amount) + (:splits txn)))]
;    (if balanced?
;      txn
;      (http/bad-request "Split amounts must sum to 0"))))


;(defn ^:private conform-pk-param
;  "Implementation for :before and :after."
;  [[key value]]
;  (match [value]
;    [[(date :guard sql-date-str?) (id :guard digits?)]]  [(keyword key) [date (to-int id)]]
;    :else  (http/bad-request (format "'%s' takes two arguments: a SQL date and an integer." key))))

;(defn ^:private conform-setlike-param
;  "Conforms a standard exact/anyof/allof parameter. Takes an optional predicate
;  and parse function to conform individual values."
;  ([kv]
;   (conform-setlike-param any? identity kv))

;  ([pred parse [key value]]
;   (letfn [(pred-multi [coll]
;             (and (not-empty coll)
;                  (every? pred coll)))]
;     (match [value]
;       [(exact :guard pred)]
;       [(keyword key) (parse exact)]

;       [[(op :guard #{"anyof" "allof"}) & (args :guard pred-multi)]]
;       [(keyword key) (vec (concat [(keyword op)] (map parse args)))]

;       :else
;       (http/bad-request (format "'%s' takes onf two forms: <value>; [anyof|allof value ...]" key))))))

;(defmulti ^:private conform-param
;  "Validates a single get-transactions request parameter and returns a
;  key-value pair in the form expected by haus.db."
;  (comp find-keyword first))

;(defmethod conform-param :before [param]
;  (conform-pk-param param))

;(defmethod conform-param :after [param]
;  (conform-pk-param param))

;(defmethod conform-param :date
;  [[_ value]]
;  (match [value]
;    [(date :guard sql-date-str?)]
;    [:date date]

;    [[(op :guard #{"lt" "le" "ge" "gt"}) (date :guard sql-date-str?)]]
;    [:date [(keyword op) date]]

;    [["in" (date1 :guard sql-date-str?) (date2 :guard sql-date-str?)]]
;    [:date [:in date1 date2]]

;    :else
;    (http/bad-request "'date' takes one of three forms: <sql-date>; [lt|le|gt|ge <sql-date>]; [in <sql-date> <sql-date>]")))

;(defmethod conform-param :category_id [param]
;  (conform-setlike-param digits? to-int param))

;(defmethod conform-param :text [param]
;  (conform-setlike-param param))

;(defmethod conform-param :tag [param]
;  (conform-setlike-param tag? str/lower-case param))

;(defmethod conform-param :person_id [param]
;  (conform-setlike-param digits? to-int param))

;(defmethod conform-param :limit
;  [[_ value]]
;  (if (digits? value)
;    [:limit (to-int value)]
;    (http/bad-request "'limit' must be an integer.")))

;(defmethod conform-param :default
;  [[k _]]
;  (http/bad-request (str "Unknown query parameter: " k)))

;(defn conform-params
;  "Conforms /transactions query params. Returns a failed util/Response on any
;  malformed or unsupported parameters."
;  [params]
;  (let [xform (comp (map conform-param)
;                    (until-failed))]
;    (Query (transduce xform conj {} params))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Resource
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private encode-transaction
  [txn]
  (-> txn
      (update ::db/created_at json/encode-timestamp)
      (update ::db/updated_at json/encode-timestamp)
      (update ::db/date json/encode-date)))


(defrecord Transactions [])

(extend-type Transactions
  resource/Resource

  (-routes [this prefix]
    (resource/standard-routes this prefix "haus.web.transactions"
                              ::db/insert-params ::db/update-params))

  resource/ModelResource

  (-model [this]
    db/transactions)

  (-url-for-obj [this {:keys [url-for]} {id ::db/id}]
     (@url-for ::object, :path-params {:id id}))

  resource/EncodedResource

  (-encode-obj [this obj]
    (encode-transaction obj)))


(def transactions (->Transactions))
