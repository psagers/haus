(ns haus.web.transactions
  (:require [compojure.core :refer [ANY defroutes]]
            [haus.db :as db]
            [haus.web.util.generic :refer [delete-obj! get-obj
                                                   wrap-id-param]]
            [haus.web.util.http :refer [bad-request defresource
                                                url-join]]
            [haus.web.util.json :as json :refer [load-schema]]
            [ring.util.request :refer [request-url]]
            [ring.util.response :refer [created not-found response]]
            [taoensso.truss :refer [have]]))

(def db-fns
  {:insert-fn db/insert-transaction!
   :get-fn db/get-transaction
   :update-fn db/update-transaction!
   :delete-fn db/delete-transaction!})

(defn imbalanced?
  "True if this transaction's splits don't add up to zero."
  [txn]
  (not (zero? (transduce (map :amount) + (:splits txn)))))


;
; /transactions
;


(defresource transactions)

(defmethod transactions :get
  "TODO: Query parameters (date/cursor, category, fulltext, tags, person?)"
  [{con :db-con}]
  (response (db/get-transactions con)))

(def post-transactions-schema (delay (load-schema "transactions-post.json")))

(defmethod transactions :post
  [{con :db-con, body :body, :as req}]
  (let [txn (json/conform @post-transactions-schema body)]
    (cond
      (nil? txn) (bad-request "")
      (imbalanced? txn) (bad-request {:msg "Split amounts must sum to 0."})
      :else (let [transaction_id (db/insert-transaction! con txn)]
              (created (url-join (request-url req) (have int? transaction_id)))))))


;
; /transactions/:id
;


(defresource transaction)

(defmethod transaction :get
  [req]
  (get-obj req db-fns))

(def put-transaction-schema (delay (load-schema "transaction-put.json")))

(defmethod transaction :put
  [{con :db-con, {id :id} :params, body :body}]
  (let [txn (json/conform @put-transaction-schema body)]
    (cond
      (nil? txn) (bad-request "")
      (imbalanced? txn) (bad-request {:msg "Split amounts must sum to 0."})
      :else (if (db/update-transaction! con id txn)
              (response (db/get-transaction con id))
              (not-found "")))))

(defmethod transaction :delete
  [req]
  (delete-obj! req db-fns))

;
;
;

(defroutes routes
  (ANY "/" req transactions)
  (ANY ["/:id", :id #"\d+"] req (wrap-id-param transaction)))
