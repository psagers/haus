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
; Generic handlers
;
; Each handler takes a request and a map. The map contains parameters for
; conforming JSON bodies along with functions for accessing the database. Each
; handler requires a specific subset of these parameters and ignores the rest.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn new-obj!
  "Generic handler for creating a new object."
  [{db-spec ::db/spec, body :body, :as req} {:keys [get-fn insert-fn url-fn]}]
  (jdbc/with-db-transaction [conn db-spec]
    (let [obj_id (insert-fn conn body)]
      (created (url-fn req (have int? obj_id))
               (get-fn conn obj_id)))))

(s/fdef new-obj!
  :args (s/cat :req :ring/request
               :opts (s/keys :req-un [::get-fn ::insert-fn ::url-fn]))
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
  [{db-spec ::db/spec, {id :id} :path-params, body :body} {:keys [get-fn update-fn]}]
  (jdbc/with-db-transaction [conn db-spec]
      (if (update-fn conn id body)
        (response (get-fn conn id))
        (not-found ""))))

(s/fdef update-obj!
  :args (s/cat :req :ring/request
               :opts (s/keys :req-un [::get-fn ::update-fn]))
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

(defn ^:private conform-body-enter [qualifier specs]
  (fn [context]
    (let [verb (get-in context [:request :request-method])]
      (if-let [spec (get specs verb)]
        (let [body (-> context
                       (get-in [:request :body])
                       (util/keywordize-keys-safe qualifier))
              body' (s/conform spec body)]
           (if-not (= body' ::s/invalid)
             (assoc-in context [:request :body] body')
             (assoc context :response
                    (bad-request (s/explain-str spec body)))))
        context))))

(defn conform-body
  "Returns an interceptor for conforming the body to a spec.

  qualifier: A namespace (as a string) to qualify incoming keys.
  specs: A map of request methods to specs."
  [qualifier specs]
  (interceptor/interceptor
    {:name ::conform-body
     :enter (conform-body-enter qualifier specs)}))

(def ^{:doc "Interceptor to decode the :id path-param as an integer."}
  decode-id-param
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
