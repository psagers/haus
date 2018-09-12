(ns haus.test.people_test
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.test :refer [deftest is]]
            [haus.core.util :refer [submap?]]
            [haus.db.people :as people]
            [haus.test.util :as util]))


(util/use-fixtures)


(def params-spec (s/keys :req [::people/name]))


(deftest people
  (doseq [params (gen/sample (s/gen params-spec) 100)]
    (let [id (people/insert-person! params)]
      (is (every? #(submap? params %) (people/get-people)))
      (is (submap? params (people/get-person id)))
      (let [params (gen/generate (s/gen params-spec))]
        (people/update-person! id params)
        (is (submap? params (people/get-person id))))
      (is (people/delete-person! id))
      (is (nil? (people/get-person id))))))
