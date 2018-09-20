(ns haus.web.util.http
  (:require [clojure.string :as str]
            [failjure.core :as f]
            [ring.util.response :as resp]
            [taoensso.truss :refer [have]]))

;
; Resource definition
;
; Resource handlers are multi-methods that use the HTTP method (:get, :post,
; etc.) as the dispatch value. defresource is a convenience macro to
; automatically generate the :options method as well as a default handler to
; generate a 405 (Method not allowed) response.
;

(defmacro defresource
  "This is a relatively simple wrapper around defmulti. It automatically
  generates a default method that sends an appropriate 405 response. This
  assumes that the handlers will take the request as their sole argument."
  {:requires [resp/response]}
  [resource-name]
  `(do
     (defmulti ~resource-name :request-method)

     (defmethod ~resource-name :options
       [_req#]
       (-> (resp/response "") (header-allow (resource-methods ~resource-name))))

     (defmethod ~resource-name :default
       [_req#]
       (-> (method-not-allowed (resource-methods ~resource-name))))))

(defn resource-methods
  "Returns the HTTP methods (as strings) supported by a multi-method resource."
  [handler]
  (sequence (comp (remove #{:default})
                  (map name)
                  (map str/upper-case))
    (keys (methods handler))))

(defn header-allow
  "Adds an Allow header with the given HTTP methods. The methods are assumed to
  be valid upper-case strings."
  [resp allow]
  (assoc-in resp [:headers "Allow"] (str/join ", " allow)))


;
; Responses
;

; A FinalResponse is a ring response that should halt the current request
; handler and be returned immediately. This is normally used for failures, so
; it integrates with the failjure library.
(defrecord FinalResponse [status headers body]
  f/HasFailed
  (failed? [self] true)
  (message [self] (:body self)))

(defmacro make-final-response
  "Macro to generate common error responses."
  [name status]
  `(defn ~name
     ~(str "Generates an HTTP " status " response.")
     ([]
      (~name ""))
     ([body#]
      (->FinalResponse ~status {} body#))))

(make-final-response bad-request 400)
(make-final-response conflict 409)
(make-final-response server-error 500)

(defn method-not-allowed
  "This requires a sequence of HTTP methods for the Allow header."
  ([allow]
   (method-not-allowed allow ""))
  ([allow body]
   (->FinalResponse 405 {"Allow" (str/join ", " allow)} body)))

(defn url-join
  "Joins a sequence of URI path components into a single path. This does not do
  any resolution of relative paths or anything; it's really just a URI-friendly
  version of str/join."
  [& parts]
  (letfn [(join
            ([] "")
            ([base part]
             (let [base (str/replace base #"/+$" "")
                   part (str/replace part #"^/+" "")]
               (str/join "/" (filter not-empty [base, part])))))]
    (reduce join (have some? :in parts))))
