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
  (let [conn util/*db-conn*]
    (doseq [params (gen/sample (s/gen params-spec) 100)]
      (let [id (people/insert-person! conn params)]
        (is (every? #(submap? params %) (people/get-people conn)))
        (is (submap? params (people/get-person conn id)))
        (let [params (gen/generate (s/gen params-spec))]
          (people/update-person! conn id params)
          (is (submap? params (people/get-person conn id))))
        (is (people/delete-person! conn id))
        (is (nil? (people/get-person conn id)))))))
