(ns haus.web.util.resource
  "Tools to quickly generate APIs for models."
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [haus.core.util :as util :refer [have-satisfies?]]
            [haus.core.spec :as spec]
            [haus.db.util.model :as model]
            [haus.db.util.where :refer [Where]]
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
  "A resource with an underlying Model. This is required for our generic
  handlers."
  (-model [this]
    "A haus.db.util.model/Model instance.")

  (-url-for-obj [this request obj]
    "Returns the URL for a specific model object."))

(defprotocol EncodedResource
  "A resource that requires additional encoding before being fed to the JSON
  encoder."
  (-encode-obj [this obj]
    "Converts a single query result to a JSON-friendly map."))

(defprotocol FilteredResource
  "A resource that can be filtered with query params."
  (-params->where [this query-params]
    "Generates a Where instance from query params. May return nil.")

  (-params->limit [this query-params]
    "Extracts a DB query limit from query params. May return nil."))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn routes [resource prefix]
  (-routes (have-satisfies? Resource resource) prefix))

(defn model [resource]
  (-model (have-satisfies? ModelResource resource)))

(defn url-for-obj [resource request obj]
  (-url-for-obj (have-satisfies? ModelResource resource) request obj))

(defn encode-obj [resource row]
  (-encode-obj (have-satisfies? EncodedResource resource) row))

(defn params->where [resource query-params]
  (-params->where (have-satisfies? FilteredResource resource) query-params))

(defn params->limit [resource query-params]
  (-params->limit (have-satisfies? FilteredResource resource) query-params))


(s/def ::resource (spec/satisfies Resource))
(s/def ::model-resource (spec/satisfies ModelResource))
(s/def ::encoded-resource (spec/satisfies EncodedResource))
(s/def ::filtered-resource (spec/satisfies FilteredResource))

(s/def ::query-params
  (s/map-of string? (s/or :string string?
                          :vector (s/coll-of string?, :kind vector?))))

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

(s/fdef encode-obj
  :args (s/cat :resource ::encoded-resource
               :obj map?)
  :ret map?)

(s/fdef params->where
  :args (s/cat :resource ::filtered-resource
               :query-params ::query-params)

  :ret (s/nilable (spec/satisfies Where)))

(s/fdef params->limit
  :args (s/cat :resource ::filtered-resource
               :query-params ::query-params)
  :ret (s/nilable pos-int?))


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

(defn with-encoded-resource
  "Uses an EncodedResource to encode responses in preparation for JSON."
  [resource]
  (let [resource (have-satisfies? EncodedResource resource)
        encode-obj (partial encode-obj resource)]
    (interceptor/interceptor
      {:name ::with-resource
       :leave (fn [context] (update-in context [:response :body]
                              (fn [body] (cond
                                           (map? body) (encode-obj body)
                                           (coll? body) (map encode-obj body)
                                           :else body))))})))

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

(defn standard-routes
  "Generates standard routes for a ModelResource."
  [resource prefix qualifier insert-spec update-spec]
  (let [resource (have-satisfies? ModelResource resource)
        model-qualifier (model/qualifier (model resource))
        interceptors (cond-> ^:interceptors [(with-resource resource)]
                             (satisfies? EncodedResource resource) (conj (with-encoded-resource resource)))]
    [prefix interceptors
            {:any [(keyword qualifier "index")
                   ^:interceptors [(conform-body model-qualifier {:post insert-spec})]
                   `index]}

      ["/:id" ^:constraints {:id #"\d+"}
              ^:interceptors [decode-id-param]
              {:any [(keyword qualifier "object")
                     ^:interceptors [(conform-body model-qualifier {:post update-spec})]
                     `object]}]]))


(defrecord SimpleResource [model qualifier insert-spec update-spec])

(extend-type SimpleResource
  Resource

  (-routes [{:keys [qualifier insert-spec update-spec] :as this} prefix]
    (standard-routes this prefix qualifier insert-spec update-spec))

  ModelResource

  (-model [{:keys [model]}]
    model)

  (-url-for-obj [{:keys [qualifier model]} {:keys [url-for]} obj]
    (let [model-qualifier (:qualifier model)
          id (get obj (keyword model-qualifier "id"))]
      (@url-for (keyword qualifier "object"), :path-params {:id (have int? id)}))))


(defn simple-resource
  ([fields]
   (map->SimpleResource fields))

  ([model qualifier insert-spec update-spec]
   (->SimpleResource model qualifier insert-spec update-spec)))
