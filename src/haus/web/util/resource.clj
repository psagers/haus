(ns haus.web.util.resource
  "Tools to quickly generate APIs for models."
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [haus.core.util :as util :refer [have-satisfies?]]
            [haus.db.util.model :as model]
            [haus.web.util.http :refer [bad-request defresource not-found]]
            [io.pedestal.interceptor :as interceptor]
            [ring.util.response :refer [created response]]
            [taoensso.timbre :refer [warn]]
            [taoensso.truss :refer [have]]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Protocol
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol Resource
  "An HTTP resource (and optional subresources)."
  (-routes [this prefix]
    "Generates a single vector of Pedestal routes (terse style), anchored at
    the given path prefix."))

(defprotocol ModelResource
  "An HTTP resource with an underlying Model. This is required for our generic
  handlers."
  (-model [this]
    "A haus.db.util.model/Model instance.")

  (-url-for-obj [this request obj]
    "Returns the URL for a specific model object."))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn routes [resource prefix]
  (-routes resource prefix))

(defn model [resource]
  (-model resource))

(defn url-for-obj [resource request obj]
  (-url-for-obj resource request obj))


(s/def ::resource (partial satisfies? Resource))
(s/def ::model-resource (partial satisfies? ModelResource))

(s/fdef routes
  :args (s/cat :resource ::resource
               :prefix (s/and string? (partial str/starts-with? "/")))
  :ret vector?)

(s/fdef model
  :args (s/cat :resource ::model-resource)
  :ret ::model/model)

(s/fdef url-for-obj
  :args (s/cat :resource ::model-resource
               :request :ring/request
               :obj map?)
  :ret string?)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Interceptors
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn with-resource
  "An interceptor that adds a Resource instance to the request (under
  :haus.web/resource)."
  [resource]
  (let [resource (have-satisfies? Resource resource)]
    (interceptor/interceptor
      {:name ::with-resource
       :enter #(assoc-in % [:request :haus.web/resource] resource)})))

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
  "Returns an interceptor for conforming the body to a spec, returning a 400
  response on failure.

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
; Generic handlers
;
; These require a ModelResource in the request under :haus.web/resource.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defresource index)

(defmethod index :get
  [{resource :haus.web/resource, :as req}]
  (let [model (model (have-satisfies? ModelResource resource))
        db-spec (get req :haus.db/spec)]
    (jdbc/with-db-connection [conn db-spec]
      (response (model/query model conn)))))

(defmethod index :post
  [{resource :haus.web/resource, :as req}]
  (let [model (model (have-satisfies? ModelResource resource))
        db-spec (get req :haus.db/spec)
        body (get req :body)]
    (jdbc/with-db-transaction [conn db-spec]
      (let [obj_id (model/insert! model conn body)
            obj (model/get-one model conn obj_id)]
        (created (url-for-obj resource req obj) obj)))))


(defresource object)

(defmethod object :get
  [{resource :haus.web/resource, :as req}]
  (let [model (model (have-satisfies? ModelResource resource))
        db-spec (get req :haus.db/spec)
        id (get-in req [:path-params :id])]
    (jdbc/with-db-connection [conn db-spec]
      (if-let [obj (model/get-one model conn id)]
        (response obj)
        (not-found "")))))

(defmethod object :post
  [{resource :haus.web/resource, :as req}]
  (let [model (model (have-satisfies? ModelResource resource))
        db-spec (get req :haus.db/spec)
        body (get req :body)
        id (get-in req [:path-params :id])]
    (jdbc/with-db-transaction [conn db-spec]
      (if (model/update! model conn id body)
        (response (model/get-one model conn id))
        (not-found "")))))

(defmethod object :delete
  [{resource :haus.web/resource, :as req}]
  (let [model (model (have-satisfies? ModelResource resource))
        db-spec (get req :haus.db/spec)
        id (get-in req [:path-params :id])]
    (jdbc/with-db-transaction [conn db-spec]
      (if (model/delete! model conn id)
        (response "")
        (not-found "")))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; SimpleResource
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord SimpleResource [model qualifier insert-spec update-spec])

(extend-protocol Resource
  SimpleResource

  (-routes [{:keys [model qualifier insert-spec update-spec] :as this}, prefix]
    (let [model-qualifier (model/qualifier model)]
      [prefix ^:interceptors [(with-resource this)]
              {:any [(keyword qualifier "index")
                     ^:interceptors [(conform-body model-qualifier {:post insert-spec})]
                     `index]}

        ["/:id" ^:constraints {:id #"\d+"}
                ^:interceptors [decode-id-param]
                {:any [(keyword qualifier "object")
                       ^:interceptors [(conform-body model-qualifier {:post update-spec})]
                       `object]}]])))

(extend-protocol ModelResource
  SimpleResource

  (-model [{:keys [model]}]
    model)

  (-url-for-obj [{:keys [model qualifier]}, {:keys [url-for]}, obj]
    (let [model-qualifier (:qualifier model)
          id (get obj (keyword model-qualifier "id"))]
      (@url-for (keyword qualifier "object"), :path-params {:id (have int? id)}))))


(defn simple-resource
  [model qualifier insert-spec update-spec]
  (->SimpleResource model qualifier insert-spec update-spec))

(s/fdef simple-resource
  :args (s/cat :model ::model/model
               :qualifier string?
               :insert-spec qualified-keyword?
               :update-spec qualified-keyword?)
  :ret (partial instance? SimpleResource))
