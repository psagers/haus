(ns haus.mongodb.result
  (:import (com.mongodb.client.result DeleteResult UpdateResult)))


(defn delete-result
  [^DeleteResult result]
  (if (.wasAcknowledged result)
    {:acknowledged? true, :count (.getDeletedCount result)}
    {:acknowledged? false}))


(defn update-result
  [^UpdateResult result]
  (if (.wasAcknowledged result)
    {:acknowledged? true,
     :matched (.getMatchedCount result)
     :modified (.getModifiedCount result)
     :upserted-id (.getUpsertedId result)}
    {:acknowledged? false}))
