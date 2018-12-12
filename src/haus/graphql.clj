(ns haus.graphql
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [com.stuartsierra.component :as component]
            [com.walmartlabs.lacinia.schema :as l.s]
            [com.walmartlabs.lacinia.util :as l.u]
            [haus.graphql.util :as g.u]))


(defn new-category [{conn :db} args _]
  (g.u/new-document conn :categories
                  (assoc args :retired false)))

(defn update-category [{conn :db} args _]
  (g.u/update-document conn :categories args))

(defn delete-category [{conn :db} args _]
  (g.u/delete-document conn :categories args))


(defn stream-people [{conn :db} args callback]
  (let [find-opts {:filter {:name {:$type "string"}}}]
    (g.u/stream-documents conn :people find-opts callback)))


(defn stream-categories [{conn :db} args callback]
  (let [find-opts {:filter {:name {:$type "string"}}}]
    (g.u/stream-documents conn :categories find-opts callback)))


(defn ^:private read-schema [path]
  (with-open [rdr (java.io.PushbackReader. (io/reader path))]
    (edn/read rdr)))

(defn compile-schema []
  (-> (read-schema (io/resource "graphql.edn"))
      (l.u/attach-scalar-transformers {:scalars/parse-object-id g.u/parse-object-id
                                       :scalars/serialize-object-id g.u/serialize-object-id
                                       :scalars/parse-name g.u/parse-name
                                       :scalars/serialize-name g.u/serialize-name})
      (l.u/attach-resolvers {:_id g.u/resolve-id
                             :mutations/new-category new-category
                             :mutations/update-category update-category
                             :mutations/delete-category delete-category})
      (l.u/attach-streamers {:subscriptions/people stream-people
                             :subscriptions/categories stream-categories})
      (l.s/compile)))


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
