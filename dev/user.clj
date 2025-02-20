(ns user
  (:require [cctx.core :as core]
            [cctx.template-registry :as registry]
            [clojure.java.io :as io]
            [clojure.edn :as edn]))

(defn load-test-config []
  (-> "projects.edn"
      io/resource
      slurp
      edn/read-string))

(defn create-test-cctx []
  (let [config (load-test-config)
        project-config (get-in config [:projects "Catalyst"])
        spec {:cctx-name "test-cctx"
              :version :v2
              :type :no-op
              :project-config project-config}]
    (core/create-cctx! spec)))

(comment
  ;; Load the test configuration
  (def test-config (load-test-config))

  ;; Create a test CCTX
  (create-test-cctx)

  ;; Explore available transactors
  (registry/get-transactor-for-template :v2 :no-op)

  ;; Add more development helper functions and explorations here
  )
