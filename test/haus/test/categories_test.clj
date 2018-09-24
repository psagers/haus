(ns haus.test.categories_test
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.test :refer [deftest is]]
            [haus.core.util :refer [submap?]]
            [haus.db.categories :as c]
            [haus.test.util :as util]))


(util/use-fixtures)


(def params-spec (s/keys :req [::c/name]))


(deftest categories
  (let [conn util/*db-conn*]
    (doseq [params (gen/sample (s/gen params-spec) 100)]
      (let [id (c/insert-category! conn params)]
        (is (every? #(submap? params %) (c/get-categories conn)))
        (is (submap? params (c/get-category conn id)))
        (let [params (gen/generate (s/gen params-spec))]
          (c/update-category! conn id params)
          (is (submap? params (c/get-category conn id))))
        (is (c/delete-category! conn id))
        (is (nil? (c/get-category conn id)))))))
