(ns net.ignorare.haus.web.http
  (:require [clojure.string :as str]
            [ring.mock.request :refer [header]]
            [ring.util.response :refer [response status]]
            [taoensso.truss :refer [have]]))

;
; Resource definition
;

(defmacro defresource
  "This is a relatively simple wrapper around defmulti. It automatically
  generates a default method that sends an appropriate 405 response. This
  assumes that the handlers will take the request as their sole argument."
  [name]
  `(do
     (defmulti ~name :request-method)

     (defmethod ~name :options
       [req#]
       (-> (response "") (header-allow (resource-methods ~name))))

     (defmethod ~name :default
       [req#]
       (-> (response "") (status 405) (header-allow (resource-methods ~name))))))

(defn resource-methods
  "Returns the HTTP methods supported by a multi-method resource."
  [handler]
  (->> (keys (methods handler))
       (remove #{:default})
       (map name)
       (map str/upper-case)))

(defn header-allow
  "Adds an Allow header with the given HTTP methods."
  [resp allow]
  (header resp "Allow" (str/join ", " allow)))


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
   (-> (response body)
       (status 405)
       (header "Allow" (str/join ", " allow)))))

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
