(ns haus.web.util.generic
  "Generic handlers for simple APIs. These essentially connect HTTP resources
  directly to their corresponding database resources. If any substantial
  conversion is required between the two layers, these will probably hinder
  more than help."
  (:require [clojure.spec.alpha :as s]
            [clojure.java.jdbc :as jdbc]
            [failjure.core :as f]
            [haus.core.util :as util]
            [haus.db :as db]
            [haus.web.util.http :refer [bad-request not-found url-join]]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.http.route :as route]
            [ring.core.spec]
            [ring.util.request :refer [request-url]]
            [ring.util.response :refer [created response]]
            [taoensso.truss :refer [have]]
            [taoensso.timbre :refer [info]]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Specs
;
; These are keys for the second parameter of each generic handler.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

; A namespace for qualifying keys in the JSON body.
(s/def ::key-ns string?)

; A spec to validate the JSON body.
(s/def ::spec any?)

; A function that retrieves a database object.
(s/def ::get-fn (s/fspec :args (s/cat :conn ::db/conn
                                      :id pos-int?)
                         :ret map?))

; A function that inserts a database object.
(s/def ::insert-fn (s/fspec :args (s/cat :conn ::db/conn
                                         :obj map?)
                            :ret pos-int?))

; A function that updates a database object.
(s/def ::update-fn (s/fspec :args (s/cat :conn ::db/conn
                                         :id pos-int?
                                         :obj map?)
                            :ret pos-int?))

; A function that deletes a database object.
(s/def ::delete-fn (s/fspec :args (s/cat :conn ::db/conn
                                         :id pos-int?)
                            :ret boolean?))

; A function that generates a URL for an object ID.
(s/def ::url-fn (s/fspec :args (s/cat :req :ring/request
                                      :obj_id (s/nilable pos-int?))
                         :ret string?))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Util
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private conform-body
  [key-ns spec body]
  (let [body (util/keywordize-keys-safe body key-ns)
        body' (s/conform spec body)]
    (if-not (= body' ::s/invalid)
      body'
      (bad-request (s/explain-str spec body)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Generic handlers
;
; Each handler takes a request and a map. The map contains parameters for
; conforming JSON bodies along with functions for accessing the database. Each
; handler requires a specific subset of these parameters and ignores the rest.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn new-obj!
  "Generic handler for creating a new object."
  [{db-spec ::db/spec, body :body, :as req} {:keys [key-ns spec get-fn insert-fn url-fn]}]
  (jdbc/with-db-transaction [conn db-spec]
    (f/attempt-all [obj (conform-body key-ns spec body)
                    obj_id (insert-fn conn obj)]
      (created (url-fn req (have int? obj_id))
               (get-fn conn obj_id)))))

(s/fdef new-obj!
  :args (s/cat :req :ring/request
               :opts (s/keys :req-un [::key-ns ::spec ::get-fn ::insert-fn ::url-fn]))
  :ret :ring/response)


(defn get-obj
  "Generic handler for retrieving a single object."
  [{db-spec ::db/spec, {id :id} :path-params} {:keys [get-fn]}]
  (jdbc/with-db-connection [conn db-spec]
    (if-let [obj (get-fn conn id)]
      (response obj)
      (not-found ""))))

(s/fdef get-obj
  :args (s/cat :req :ring/request
               :opts (s/keys :req-un [::get-fn]))
  :ret :ring/response)


(defn update-obj!
  "Generic handler for updating an existing object."
  [{db-spec ::db/spec, {id :id} :path-params, body :body} {:keys [key-ns spec get-fn update-fn]}]
  (jdbc/with-db-transaction [conn db-spec]
    (f/attempt-all [obj (conform-body key-ns spec body)]
      (if (update-fn conn id obj)
        (response (get-fn conn id))
        (not-found "")))))

(s/fdef update-obj!
  :args (s/cat :req :ring/request
               :opts (s/keys :req-un [::key-ns ::spec ::get-fn ::update-fn]))
  :ret :ring/response)


(defn delete-obj!
  "Generic handler for deleting an existing object."
  [{db-spec ::db/spec, {id :id} :path-params} {:keys [delete-fn]}]
  (jdbc/with-db-transaction [conn db-spec]
    (if (delete-fn conn id)
      (response "")
      (not-found ""))))

(s/fdef update-obj!
  :args (s/cat :req :ring/request
               :opts (s/keys :req-un [::delete-fn]))
  :ret :ring/response)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Other helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^{:doc "Interceptor to decode the :id param as an integer."} decode-id-param
  (interceptor/interceptor
    {:name ::decode-id-param
     :enter #(update-in % [:request :path-params :id] util/to-int)}))

(defn wrap-id-param
  "Middleware to decode the :id param as an integer."
  [handler]
  (fn [req]
    (let [req (update-in req [:params :id] #(Integer/parseInt %))]
      (handler req))))

(s/fdef wrap-id-param
  :args (s/cat :handler :ring/handler)
  :ret :ring/handler)
