(ns haus.web.transactions
  (:require [clojure.core.match :refer [match]]
            [clojure.string :as str]
            [compojure.core :refer [ANY defroutes]]
            [failjure.core :as f]
            [haus.core.util :refer [re-pred until-failed]]
            [haus.db :as db]
            [haus.web.util.generic :as generic :refer [wrap-id-param]]
            [haus.web.util.http :as http]
            [haus.web.util.json :as json]
            [ring.util.request :refer [request-url]]
            [ring.util.response :refer [created not-found response]]
            [taoensso.truss :refer [have]]))


;
; Request processing
;

(defn ^:private conform-body
  "Validates a request body and additionally ensures that splits (if given) sum
  to zero. Returns a failed util/Response on failure."
  [schema body]
  (f/attempt-all [txn (json/conform schema body)
                  balanced? (zero? (transduce (map :amount) + (:splits txn)))]
    (if balanced?
      txn
      (http/bad-request "Split amounts must sum to 0"))))


(def ^:private digits? (re-pred #"^\d$"))
(def ^:private sql-date? (re-pred #"^\d\d\d\d-\d\d-\d\d$"))
(def ^:private tag? (re-pred #"(?i)^[a-z][\w-]*$"))

(defn ^:private to-int [value]
  (Integer/parseInt (have digits? value)))

(defn ^:private conform-pk-param
  "Implementation for :before and :after."
  [[key value]]
  (match [value]
    [[(date :guard sql-date?) (id :guard digits?)]]  [(keyword key) [date (Integer/parseInt id)]]
    :else  (http/bad-request (format "'%s' takes two arguments: a SQL date and an integer." key))))

(defn ^:private conform-setlike-param
  "Conforms a standard exact/anyof/allof parameter. Takes an optional predicate
  and parse function to conform individual values."
  ([kv]
   (conform-setlike-param any? identity kv))

  ([pred parse [key value]]
   (letfn [(pred-multi [coll]
             (and (not-empty coll)
                  (every? pred coll)))]
     (match [value]
       [(exact :guard pred)]
       [(keyword key) (parse exact)]

       [[(op :guard #{"anyof" "allof"}) & (args :guard pred-multi)]]
       [(keyword key) (vec (concat [(keyword op)] (map parse args)))]

       :else
       (http/bad-request (format "'%s' takes onf two forms: <value>; [anyof|allof value ...]" key))))))

(defmulti ^:private conform-param
  "Validates a single get-transactions request parameter and returns a
  key-value pair in the form expected by haus.db."
  (comp find-keyword first))

(defmethod conform-param :before [param]
  (conform-pk-param param))

(defmethod conform-param :after [param]
  (conform-pk-param param))

(defmethod conform-param :date
  [[_ value]]
  (match [value]
    [(date :guard sql-date?)]
    [:date date]

    [[(op :guard #{"lt" "le" "ge" "gt"}) (date :guard sql-date?)]]
    [:date [(keyword op) date]]

    [["in" (date1 :guard sql-date?) (date2 :guard sql-date?)]]
    [:date [:in date1 date2]]

    :else
    (http/bad-request "'date' takes one of three forms: <sql-date>; [lt|le|gt|ge <sql-date>]; [in <sql-date> <sql-date>]")))

(defmethod conform-param :category_id [param]
  (conform-setlike-param digits? to-int param))

(defmethod conform-param :text [param]
  (conform-setlike-param param))

(defmethod conform-param :tag [param]
  (conform-setlike-param tag? str/lower-case param))

(defmethod conform-param :person_id [param]
  (conform-setlike-param digits? to-int param))

(defmethod conform-param :limit
  [[_ value]]
  (if (digits? value)
    [:limit (to-int value)]
    (http/bad-request "'limit' must be an integer.")))

(defmethod conform-param :default
  [[k _]]
  (http/bad-request (str "Unknown query parameter: " k)))

(defn conform-params
  "Conforms /transactions query params. Returns a failed util/Response on any
  malformed or unsupported parameters."
  [params]
  (let [xform (comp (map conform-param)
                    (until-failed))]
    (transduce xform conj {} params)))


;
; Response encoding
;

(defn ^:private encode-transaction
  [txn]
  (-> txn
      (update :created_at json/encode-timestamp)
      (update :updated_at json/encode-timestamp)
      (update :date json/encode-date)))


;
; For generics
;

(def db-fns
  {:insert-fn db/insert-transaction!
   :get-fn db/get-transaction
   :update-fn db/update-transaction!
   :delete-fn db/delete-transaction!})


;
; /transactions
;

(http/defresource transactions)

(defmethod transactions :get
  [{con :db-con, params :params}]
  (f/attempt-all [params (conform-params params)]
    (response (map encode-transaction
                 (db/get-transactions con params)))))

(def ^:private post-transactions-schema (delay (json/load-schema "transactions-post.json")))

(defmethod transactions :post
  [{con :db-con, body :body, :as req}]
  (f/attempt-all [txn (conform-body @post-transactions-schema body)
                  txn_id (db/insert-transaction! con txn)]
    (created (http/url-join (request-url req) (have int? txn_id)))))


;
; /transactions/:id
;

(http/defresource transaction)

(defmethod transaction :get
  [req]
  (generic/get-obj req db-fns))

(def ^:private put-transaction-schema (delay (json/load-schema "transaction-put.json")))

(defmethod transaction :put
  [{con :db-con, {id :id} :params, body :body}]
  (f/attempt-all [txn (conform-body @put-transaction-schema body)]
    (if (db/update-transaction! con id txn)
      (response (db/get-transaction con id))
      (not-found ""))))

(defmethod transaction :delete
  [req]
  (generic/delete-obj! req db-fns))


;
; Handler
;

(defroutes routes
  (ANY "/" req transactions)
  (ANY ["/:id", :id #"\d+"] req (wrap-id-param transaction)))
