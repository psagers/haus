(ns haus.graphql
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [com.stuartsierra.component :as component]
            [com.walmartlabs.lacinia.schema :as schema]
            [com.walmartlabs.lacinia.util :refer [attach-resolvers
                                                  attach-streamers]]
            [haus.graphql.util :refer [resolve-id resolve-documents
                                       stream-documents]]))

(defn stream-people [{conn :db, changes :changes} args callback]
  (stream-documents conn
                    (:pub changes)
                    :people
                    {:filter {:name {:$type "string"}}}
                    callback))

(defn stream-categories [{conn :db, changes :changes} args callback]
  (stream-documents conn
                    (:pub changes)
                    :categories
                    {:filter {:name {:$type "string"}}}
                    callback))


(defn ^:private read-schema [path]
  (with-open [rdr (java.io.PushbackReader. (io/reader path))]
    (edn/read rdr)))

(defn compile-schema []
  (-> (read-schema (io/resource "graphql.edn"))
      (attach-resolvers {:_id resolve-id})
      (attach-streamers {:subscriptions/people stream-people
                         :subscriptions/categories stream-categories})
      (schema/compile)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Component
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

; This also serves as the context for the resolvers.
(defrecord GraphQL [db changes schema]
  component/Lifecycle

  (start [this]
    (assoc this :schema (compile-schema)))

  (stop [this]
    (assoc this :schema nil)))


(defn new-graphql []
  (map->GraphQL {}))
