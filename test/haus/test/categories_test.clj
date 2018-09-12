(ns haus.test.categories_test
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.test :refer [deftest is]]
            [haus.core.util :refer [submap?]]
            [haus.db.categories :as categories]
            [haus.test.util :as util]))


(util/use-fixtures)


(def params-spec (s/keys :req [::categories/name]))


(deftest categories
  (doseq [params (gen/sample (s/gen params-spec) 100)]
    (let [id (categories/insert-category! params)]
      (is (every? #(submap? params %) (categories/get-categories)))
      (is (submap? params (categories/get-category id)))
      (let [params (gen/generate (s/gen params-spec))]
        (categories/update-category! id params)
        (is (submap? params (categories/get-category id))))
      (is (categories/delete-category! id))
      (is (nil? (categories/get-category id))))))
