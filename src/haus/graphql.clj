(ns haus.graphql
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [com.stuartsierra.component :as component]
            [com.walmartlabs.lacinia.schema :as schema]
            [com.walmartlabs.lacinia.util :refer [attach-resolvers]]
            [haus.graphql.util :refer [resolve-id resolve-documents]]))


(defn resolve-people [{conn :db} args value]
  (resolve-documents conn :people
                     {:filter {:name {:$type "string"}}}))

(defn resolve-categories [{conn :db} args value]
  (resolve-documents conn :categories
                     {:filter {:name {:$type "string"}}}))

(defn ^:private read-schema [path]
  (with-open [rdr (java.io.PushbackReader. (io/reader path))]
    (edn/read rdr)))

(defn compile-schema []
  (-> (read-schema (io/resource "graphql.edn"))
      (attach-resolvers {:_id resolve-id
                         :queries/people resolve-people
                         :queries/categories resolve-categories})
      (schema/compile)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Component
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

; This also serves as the context for the resolvers.
(defrecord GraphQL [db schema]
  component/Lifecycle

  (start [this]
    (assoc this :schema (compile-schema)))

  (stop [this]
    (assoc this :schema nil)))


(defn new-graphql []
  (map->GraphQL {}))
