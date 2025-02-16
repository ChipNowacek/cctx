(ns cctx.cctx-schema-test
  (:require [clojure.test :refer :all]
            [cctx.cctx-schema :as schema]))

(def sample-cctx
  {:version "1"
   :title "Sample CCTX"
   :description "A sample CCTX for testing"
   :changes [{:id :sample-change
              :type :transform
              :description "Sample change"}]
   :requires []
   :rollback true
   :dry-run true})

(deftest cctx-validation
  (testing "Valid CCTX"
    (is (schema/validate sample-cctx)))

  (testing "Invalid CCTX"
    (is (not (schema/validate {:invalid "data"})))
    (is (seq (schema/explain-errors {:invalid "data"})))))