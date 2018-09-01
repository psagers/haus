(ns haus.web.transactions
  (:require [compojure.core :refer [ANY defroutes]]
            [haus.db :as db]
            [haus.web.util.generic :as generic :refer [wrap-id-param]]
            [haus.web.util.http :refer [defresource url-join]]
            [haus.web.util.json :as json]
            [ring.util.request :refer [request-url]]
            [ring.util.response :refer [created not-found response]]
            [taoensso.truss :refer [have]]))

(def db-fns
  {:insert-fn db/insert-transaction!
   :get-fn db/get-transaction
   :update-fn db/update-transaction!
   :delete-fn db/delete-transaction!})

(defn conform-body!
  "Validates a request body and additionally ensures that splits (if given) sum
  to zero."
  [schema body]
  (let [txn (json/conform! schema body)
        balanced? (zero? (transduce (map :amount) + (:splits txn)))]
    (when-not balanced?
      (json/throw-invalid "Split amounts must sum to 0"))
    txn))


;
; /transactions
;


(defresource transactions)

; TODO: Query parameters (date/cursor, category, fulltext, tags, person?)
(defmethod transactions :get
  [{con :db-con}]
  (response (db/get-transactions con)))

(def post-transactions-schema (delay (json/load-schema "transactions-post.json")))

(defmethod transactions :post
  [{con :db-con, body :body, :as req}]
  (let [txn (conform-body! @post-transactions-schema body)
        txn_id (db/insert-transaction! con txn)]
    (created (url-join (request-url req) (have int? txn_id)))))


;
; /transactions/:id
;


(defresource transaction)

(defmethod transaction :get
  [req]
  (generic/get-obj req db-fns))

(def put-transaction-schema (delay (json/load-schema "transaction-put.json")))

(defmethod transaction :put
  [{con :db-con, {id :id} :params, body :body}]
  (let [txn (conform-body! @post-transactions-schema body)]
    (if (db/update-transaction! con id txn)
      (response (db/get-transaction con id))
      (not-found ""))))

(defmethod transaction :delete
  [req]
  (generic/delete-obj! req db-fns))

;
;
;

(defroutes routes
  (ANY "/" req transactions)
  (ANY ["/:id", :id #"\d+"] req (wrap-id-param transaction)))
