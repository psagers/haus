(ns haus.mongodb
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [com.stuartsierra.component :as component]
            [haus.mongodb.async :refer [block channel-result-callback function
                                        single-result-callback]]
            [haus.mongodb.bson :as bson]
            [haus.mongodb.result :as result])
  (:import (com.mongodb.async.client ClientSession MongoClient MongoClients
                                     MongoDatabase)
           (com.mongodb.client.model IndexOptions)
           (org.bson BsonDocument))
  (:refer-clojure :exclude [find]))


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


(defrecord MongoDB [config ^MongoClient client ^MongoDatabase db]
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
  [{^MongoDatabase db :db} coll-name]
  (let [coll-name (if (keyword? coll-name)
                    (name coll-name)
                    coll-name)]
    (.getCollection db coll-name BsonDocument)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-index!
  "Creates an index on a collection. Takes a key with (optional) options and
  returns a channel that will produce the name of the created index, if
  successful."
  ([conn coll-name key-doc]
   (create-index! conn coll-name key-doc (IndexOptions.)))

  ([conn coll-name key-doc options]
   (let [collection (get-collection conn coll-name)
         result-chan (async/chan 1)]

     (.createIndex collection (bson/to-bson key-doc) options
                   (channel-result-callback result-chan))

     result-chan)))


(defn document-count
  [conn coll-name & {:keys [filter]}]
  (let [collection (get-collection conn coll-name)
        result-chan (async/chan 1)]

    (.countDocuments collection
            (bson/to-bson (or filter {}))
            (channel-result-callback result-chan))

    result-chan))


(defn estimated-count
  [conn coll-name]
  (let [collection (get-collection conn coll-name)
        result-chan (async/chan 1)]

    (.estimatedDocumentCount collection
                             (channel-result-callback result-chan))

    result-chan))


(defn find-documents
  "Returns a channel that produces a sequence of find results. If an error
  occurs, the last value will be an exception."
  [conn coll-name & {:keys [filter sort projection skip limit comment]}]
  (let [collection (get-collection conn coll-name)
        result-chan (async/chan 10)]

     (cond-> (.find collection)
             filter (.filter (bson/to-bson filter))
             sort (.sort (bson/to-bson sort))
             projection (.projection (bson/to-bson projection))
             skip (.skip skip)
             limit (.limit limit)
             comment (.comment comment)
             :always (.map (function bson/from-bson-value))
             :always (.forEach (block #(async/put! result-chan %))
                               (channel-result-callback result-chan)))

     result-chan))


(defn aggregate
  [conn coll-name pipeline]
  (let [collection (get-collection conn coll-name)
        result-chan (async/chan 10)
        pipeline (map bson/to-bson pipeline)]

    (-> (.aggregate collection pipeline)
        (.map (function bson/from-bson-value))
        (.forEach (block #(async/put! result-chan %))
                  (channel-result-callback result-chan)))

    result-chan))


(defn insert!
  "Inserts a document into a collection. Returns a channel that will produce
  either an ObjectId or an exception."
  [conn coll-name doc]
  (let [collection (get-collection conn coll-name)
        result-chan (async/chan 1)]

    (.insertOne collection
                (bson/to-bson-doc doc)
                (channel-result-callback result-chan, :default true))

    result-chan))


(defn insert-many!
  [conn coll-name docs]
  (let [collection (get-collection conn coll-name)
        result-chan (async/chan 1)]

    (.insertMany collection
                 (map bson/to-bson-doc docs)
                 (channel-result-callback result-chan, :default true))

    result-chan))



(defn update-one!
  "Updates at most one matching document."
  [conn coll-name filter update]
  (let [collection (get-collection conn coll-name)
        result-chan (async/chan 1)]

    (.updateOne collection
                (bson/to-bson filter)
                (bson/to-bson update)
                (channel-result-callback result-chan, :result-fn result/update-result))

    result-chan))


(defn update-many!
  "Updates all matching documents."
  [conn coll-name filter update]
  (let [collection (get-collection conn coll-name)
        result-chan (async/chan 1)]

    (.updateMany collection
                 (bson/to-bson filter)
                 (bson/to-bson update)
                 (channel-result-callback result-chan, :result-fn result/update-result))

    result-chan))


(defn replace!
  [conn coll-name filter doc]
  (let [collection (get-collection conn coll-name)
        result-chan (async/chan 1)]

    (.replaceOne collection
                 (bson/to-bson filter)
                 (bson/to-bson doc)
                 (channel-result-callback result-chan, :result-fn result/update-result))

    result-chan))


(defn delete-one!
  "Deletes at most one document. Returns a channel that will report the
  results: {:acknowledged <bool>, [:count: <count>]}."
  [conn coll-name filter]
  (let [collection (get-collection conn coll-name)
        result-chan (async/chan 1)]

    (.deleteOne collection
                (bson/to-bson filter)
                (channel-result-callback result-chan, :result-fn result/delete-result))

    result-chan))


(defn delete-many!
  "Like delete-one!, but will delete all matching documents."
  [conn coll-name filter]
  (let [collection (get-collection conn coll-name)
        result-chan (async/chan 1)]

    (.deleteMany collection
                 (bson/to-bson filter)
                 (channel-result-callback result-chan, :result-fn result/delete-result))

    result-chan))


(comment
  (<!! (create-index! (user/mdb) :categories {:name 1} (-> (IndexOptions.) (.unique true))))

  (<!! (document-count (user/mdb) :categories, :filter {:name {:$regex "^f", :$options "i"}}))
  (<!! (estimated-count (user/mdb) :categories))

  (map :name (<!! (async/into [] (find (user/mdb) :categories
                                       ;:filter {:name {:$regex "^f", :$options "i"}}
                                       :sort {:name 1}))))
  (<!! (async/into [] (aggregate (user/mdb) :categories
                                 [{:$group {:_id {:first-letter {:$substrCP [{:$toLower "$name"} 0 1]}}
                                            :a-name {:$first "$name"}
                                            :count {:$sum 1}
                                            :updated {:$sum :$updated}}}])))
                                  ;{:$count "total"}])))

  (<!! (insert! (user/mdb) :categories {:name "Food"}))
  (<!! (insert-many! (user/mdb) :categories [{:name "Food"}, {:name "Fork"}, {:name "Frond"}]))

  (<!! (update-one! (user/mdb) :categories {:name {:$regex "^f", :$options "i"}} {:$inc {:updated 1}}))
  (<!! (update-many! (user/mdb) :categories {:name {:$regex "^f", :$options "i"}} {:$set {:first-letter "f"}}))
  (<!! (update-many! (user/mdb) :categories {:name {:$regex "^f", :$options "i"}} {:$inc {:updated 1}}))
  (<!! (replace! (user/mdb) :categories {:name "Food"} {:name "Dessert"}))

  (<!! (delete-one! (user/mdb) :categories {:name "Dessert"}))
  (<!! (delete-one! (user/mdb) :categories {:name {:$regex "^f", :$options "i"}}))
  (<!! (delete-many! (user/mdb) :categories {:name {:$regex "^f", :$options "i"}})))
