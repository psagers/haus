(ns haus.mongodb
  (:require [clojure.core.async :as async :refer [<!!]]
            [clojure.spec.alpha :as s]
            [com.stuartsierra.component :as component]
            [haus.mongodb.async :refer [block channel-result-callback function]]
            [haus.mongodb.bson :as bson]
            [haus.mongodb.collection :as collection]
            [haus.mongodb.model :as model]
            [haus.mongodb.result :as result])
  (:import (com.mongodb.async.client ClientSession MongoClient MongoClients
                                     MongoCollection MongoDatabase)
           (org.bson BsonDocument)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Specs
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::client (partial instance? MongoClient))
(s/def ::db (partial instance? MongoDatabase))
(s/def ::session (partial instance? ClientSession))

; Something that our MongoDB APIs will accept for database operations. This
; will generally be a MongoDB component (below).
(s/def ::conn (s/keys :req-un [::client ::db]
                      :opt-un [::session]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Component
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private connection-string
  "Constructs a MongoDB connection string from the database config map. The
  host and dbname are required."
  [{:keys [host port dbname user password]}]
  (let [auth-part (if user
                    (if password
                      (format "%s:%s@" user)
                      (format "%s@" user))
                    "")
        host-part (if port
                    (format "%s:%d" host port)
                    host)
        db-part (format "/%s" dbname)]
    (str "mongodb://" auth-part host-part db-part)))


(defrecord MongoDB [config, ^MongoClient client, ^MongoDatabase db]
  component/Lifecycle

  (start [this]
    (let [conf (get-in config [:config :mongodb])
          url (connection-string conf)
          client (MongoClients/create url)
          db (.getDatabase client (:dbname conf))]
      (-> this
          (assoc :client client)
          (assoc :db db))))

  (stop [this]
    (when client
      (.close client))
    (-> this
        (assoc :client nil)
        (assoc :db nil))))


(defn new-database []
  (map->MongoDB {}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Util
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ^:private get-collection
  ^MongoCollection [{^MongoDatabase db :db} coll-name]
  (.getCollection db (name coll-name) BsonDocument))


(defn ^:private call-collection
  "Wraps the asynchronous collection APIs that use SingleResultCallback.

    conn: A database connection. coll-name: The name of a collection (keyword
          or string).
    f: A function matching [<MongoCollection> <SingleResultCallback> & args].
    args: Arguments to f.

  Key-value options:

    :result-fn - A function to apply to the raw result."
  [conn coll-name f args & {:keys [result-fn]
                            :or {result-fn identity}}]
  (let [collection (get-collection conn coll-name)
        result-chan (async/chan 1)
        callback (channel-result-callback result-chan, :result-fn result-fn)]
    (apply f collection callback args)
    result-chan))


(defn ^:private iter-collection
  "Wraps the asynchronous collection APIs that return MongoIterable.

    conn: A database connection.
    coll-name: The name of a collection (keyword or string).
    f: A function matching [<MongoCollection> & args] that returns a
      MongoIterable.
    args: Arguments to f.

  Key-value options:

    :chan-buf - Result channel buffer or depth.
    :result-fn - A function to apply to the raw results. Defaults to
                 from-bson-value, which is almost always correct."
  [conn coll-name f args & {:keys [chan-buf result-fn]
                            :or {chan-buf 10, result-fn bson/from-bson-value}}]
  (let [collection (get-collection conn coll-name)
        result-chan (async/chan chan-buf)]

    (.. (apply f collection args)
        (map (function result-fn))
        (forEach (block #(some->> % (async/put! result-chan)))
                 (channel-result-callback result-chan)))

    result-chan))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-index!
  "Creates an index on a collection.

    key-doc: IntoBson
    options: IntoIndexOptions"
  [conn coll-name key-doc options]
  (call-collection conn coll-name
                   collection/create-index!
                   [key-doc options]))


(defn create-indexes!
  "Creates multiple indexes on a collection.

    indexes: sequence of IntoIndexModel"
  [conn coll-name indexes]
  (call-collection conn coll-name
                   collection/create-indexes!
                   [indexes]
                   :result-fn vec))


(defn count-documents
  [conn coll-name args]
  (call-collection conn coll-name
                   collection/count-documents
                   [args]))


(defn estimated-document-count
  [conn coll-name]
  (call-collection conn coll-name
                   collection/estimated-document-count
                   []))


(defn find-documents
  "Returns a channel that produces a sequence of documents. If an error occurs,
  the last value will be an exception."
  [conn coll-name find-opts]
  (iter-collection conn coll-name
                   collection/find-documents
                   [find-opts]))


(defn aggregate
  [conn coll-name pipeline]
  (iter-collection conn coll-name
                   collection/aggregate
                   [pipeline]))


(defn insert!
  "Inserts a document into a collection. Returns a channel that will produce
  either an ObjectId or an exception."
  [conn coll-name doc]
  (call-collection conn coll-name
                   collection/insert!
                   [doc]
                   :result-fn (constantly true)))


(defn insert-many!
  [conn coll-name docs]
  (call-collection conn coll-name
                   collection/insert-many!
                   [docs]
                   :result-fn (constantly true)))


(defn update-one!
  "Updates at most one matching document."
  [conn coll-name filter update]
  (call-collection conn coll-name
                   collection/update-one!
                   [filter update]
                   :result-fn result/update-result))


(defn update-many!
  "Updates all matching documents."
  [conn coll-name filter update]
  (call-collection conn coll-name
                   collection/update-many!
                   [filter update]
                   :result-fn result/update-result))


(defn replace!
  [conn coll-name filter doc]
  (call-collection conn coll-name
                   collection/replace!
                   [filter doc]
                   :result-fn result/update-result))


(defn delete-one!
  "Deletes at most one document. Returns a channel that will report the
  results: {:acknowledged <bool>, [:count: <count>]}."
  [conn coll-name filter]
  (call-collection conn coll-name
                   collection/delete-one!
                   [filter]
                   :result-fn result/delete-result))


(defn delete-many!
  "Like delete-one!, but will delete all matching documents."
  [conn coll-name filter]
  (call-collection conn coll-name
                   collection/delete-many!
                   [filter]
                   :result-fn result/delete-result))


(comment
  (<!! (create-index! (user/mdb) :categories
                      {:name 1}, {:collation {:locale "en_US"
                                              :collation-strength 2}
                                  :unique true}))
  (<!! (create-indexes! (user/mdb) :categories
                        [[{:name 1}, {:collation {:locale "en_US"
                                                  :collation-strength 2}
                                      :unique true}]]))

  (<!! (count-documents (user/mdb) :categories {:filter {:name {:$regex "^f", :$options "i"}}}))
  (<!! (estimated-document-count (user/mdb) :categories))

  (keep :name (<!! (async/into [] (find-documents (user/mdb) :categories
                                            {:collation {:locale "en_US", :collation-strength 2}
                                             ;:filter {:name {:$lt "p"}}
                                             :sort {:name 1}}))))
  (<!! (async/into [] (aggregate (user/mdb) :categories
                                 [{:$group {:_id {:first-letter {:$substrCP [{:$toLower "$name"} 0 1]}}
                                            :a-name {:$first "$name"}
                                            :count {:$sum 1}
                                            :updated {:$sum :$updated}}}])))
                                  ;{:$count "total"}])))

  (<!! (insert! (user/mdb) :categories {:sym :a.b/c}))
  (<!! (insert-many! (user/mdb) :categories [{:name "Food"}, {:name "Fork"}, {:name "Frond"}]))

  (<!! (update-one! (user/mdb) :categories {:name {:$regex "^f", :$options "i"}} {:$inc {:updated 1}}))
  (<!! (update-many! (user/mdb) :categories {:name {:$regex "^f", :$options "i"}} {:$set {:first-letter "f"}}))
  (<!! (update-many! (user/mdb) :categories {:name {:$regex "^f", :$options "i"}} {:$inc {:updated 1}}))
  (<!! (replace! (user/mdb) :categories {:name "Food"} {:name "Dessert"}))

  (<!! (delete-one! (user/mdb) :categories {:name "Food"}))
  (<!! (delete-one! (user/mdb) :categories {:name {:$regex "^f", :$options "i"}}))
  (<!! (delete-many! (user/mdb) :categories {:name {:$regex "^f", :$options "i"}})))
