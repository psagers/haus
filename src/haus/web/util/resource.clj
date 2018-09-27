(ns haus.web.util.resource
  "Tools to quickly generate APIs for models."
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.spec.alpha :as s]
            [haus.core.util :as util]
            [haus.db.util.model :as model]
            [haus.web.util.http :refer [bad-request defresource not-found]]
            [io.pedestal.interceptor :as interceptor]
            [ring.util.response :refer [created response]]
            [taoensso.truss :refer [have]]
            [taoensso.timbre :refer [info]]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Protocol and API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol Resource
  "An HTTP resource (and optional subresources)."
  (routes [this prefix]
    "Generates a single vector of Pedestal routes (terse style), anchored at
    the given path prefix."))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Interceptors
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn with-resource
  "An interceptor that adds a Resource instance to the request (under
  :haus.web/resource)."
  [resource]
  (interceptor/interceptor
    {:name ::with-resource
     :enter #(assoc-in % [:request :haus.web/resource] resource)}))

(defn ^:private -conform-body
  "Returns a new context with either a conformed body or a 400 response."
  [context qualifier spec]
  (let [body (-> context
                 (get-in [:request :body])
                 (util/keywordize-keys-safe qualifier))
        body' (s/conform spec body)]
     (if (not= body' ::s/invalid)
       (assoc-in context [:request :body] body')
       (assoc context :response
              (bad-request (s/explain-str spec body))))))

(defn conform-body
  "Returns an interceptor for conforming the body to a spec.

  qualifier: A namespace (as a string) to qualify incoming keys.
  specs: A map of request methods to body specs."
  [qualifier specs]
  (interceptor/interceptor
    {:name ::conform-body
     :enter (fn [context]
              (let [meth (get-in context [:request :request-method])
                    spec (get specs meth)]
                (if spec
                  (-conform-body context qualifier spec)
                  context)))}))

(def ^{:doc "Interceptor to decode the :id path-param as an integer."}
  decode-id-param
  (interceptor/interceptor
    {:name ::decode-id-param
     :enter #(update-in % [:request :path-params :id] util/to-int)}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; SimpleResource
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defresource index)

(defmethod index :get
  [{resource :haus.web/resource, :as req}]
  (let [model (get resource :model)
        db-spec (get req :haus.db/spec)]
    (jdbc/with-db-connection [conn db-spec]
      (response (model/query model conn)))))

(defmethod index :post
  [{resource :haus.web/resource, :as req}]
  (let [model (get resource :model)
        qualifier (get resource :qualifier)
        db-spec (get req :haus.db/spec)
        body (get req :body)
        url-for (get req :url-for)]
    (info req)
    (jdbc/with-db-transaction [conn db-spec]
      (let [obj_id (model/insert! model conn body)]
        (created (@url-for (keyword qualifier "object"), :path-params {:id (have int? obj_id)})
                 (model/get-one model conn obj_id))))))


(defresource object)

(defmethod object :get
  [{resource :haus.web/resource, :as req}]
  (let [model (get resource :model)
        db-spec (get req :haus.db/spec)
        id (get-in req [:path-params :id])]
    (jdbc/with-db-connection [conn db-spec]
      (if-let [obj (model/get-one model conn id)]
        (response obj)
        (not-found "")))))

(defmethod object :post
  [{resource :haus.web/resource, :as req}]
  (let [model (get resource :model)
        db-spec (get req :haus.db/spec)
        body (get req :body)
        id (get-in req [:path-params :id])]
    (jdbc/with-db-transaction [conn db-spec]
      (if (model/update! model conn id body)
        (response (model/get-one model conn id))
        (not-found "")))))

(defmethod object :delete
  [{resource :haus.web/resource, :as req}]
  (let [model (get resource :model)
        db-spec (get req :haus.db/spec)
        id (get-in req [:path-params :id])]
    (jdbc/with-db-transaction [conn db-spec]
      (if (model/delete! model conn id)
        (response "")
        (not-found "")))))


(defrecord SimpleResource [qualifier model insert-spec update-spec]
  Resource
  (routes [this prefix]
    (let [model-qualifier (:qualifier model)]
      [prefix ^:interceptors [(with-resource this)]
              {:any [(keyword qualifier "index")
                     ^:interceptors [(conform-body model-qualifier {:post insert-spec})]
                     `index]}

        ["/:id" ^:constraints {:id #"\d+"}
                ^:interceptors [decode-id-param]
                {:any [(keyword qualifier "object")
                       ^:interceptors [(conform-body model-qualifier {:post update-spec})]
                       `object]}]])))


(defn simple-resource
  [qualifier model insert-spec update-spec]
  (->SimpleResource qualifier model insert-spec update-spec))
