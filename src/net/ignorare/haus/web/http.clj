(ns net.ignorare.haus.web.http
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [ring.util.response :as resp]
            [taoensso.truss :refer [have]]))

;
; Resource definition
;

(defmacro defresource
  "This is a relatively simple wrapper around defmulti. It automatically
  generates a default method that sends an appropriate 405 response. This
  assumes that the handlers will take the request as their sole argument."
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
  "Returns the HTTP methods supported by a multi-method resource."
  [handler]
  (->> (keys (methods handler))
       (remove #{:default})
       (map name)
       (map str/upper-case)))

(defn header-allow
  "Adds an Allow header with the given HTTP methods. The given methods are
  assumed to be valid upper-case strings."
  [resp allow]
  (update-in resp [:headers "Allow"] (str/join ", " allow)))


;
; Responses
;


(defmacro make-error-response
  "Macro to generate common error responses."
  [name status]
  `(defn ~name
     ([]
      (~name ""))
     ([body#]
      (-> (ring.util.response/response body#) (ring.util.response/status ~status)))))

(make-error-response bad-request 400)
(make-error-response conflict 409)
(make-error-response server-error 500)

(defn method-not-allowed
  "This requires a sequence of HTTP methods for the Allow header."
  ([allow]
   (method-not-allowed allow ""))
  ([allow body]
   (-> (resp/response body)
       (resp/status 405)
       (header-allow allow))))

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
               (str/join "/" (filter (complement empty?) [base, part])))))]
    (reduce join (have some? :in parts))))
