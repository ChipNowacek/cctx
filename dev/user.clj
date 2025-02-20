(ns user
  (:require [cctx.core :as core]
            [cctx.config :as config]
            [cctx.project-config :as project]
            [cctx.template-registry :as registry]
            [cctx.cli :as cli]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.repl :refer [dir]]))

(def test-projects-file "/home/chip/Documents/cctx/resources/projects.edn")
(def test-project-name "Catalyst")

(defn load-test-config []
  (-> test-projects-file
      slurp
      edn/read-string))

(defn create-test-cctx 
  ([]
   (create-test-cctx "test-cctx" :v2 :no-op))
  ([cctx-name version type]
   (let [config (load-test-config)
         project-config (project/load-project-config test-projects-file test-project-name)
         spec {:cctx-name cctx-name
               :version version
               :type type
               :project-config project-config}]
     (core/create-cctx! spec {}))))

(defn test-cli-args [& args]
  (cli/parse-opts args))

(defn test-main [& args]
  (apply core/-main args))

(defn reload []
  (require '[user :as user] :reload))

(defn show-ns 
  ([] (show-ns 'user))
  ([ns-name]
   (sort (map (fn [sym]
                [sym (-> (ns-resolve ns-name sym)
                        meta
                        :arglists)])
              (keys (ns-publics ns-name))))))

(comment
  (print (test-cli-args "hi"))
  (show-ns)
  (reload)

  ;; Load and inspect the test configuration
  (def test-config (load-test-config))
  (def project-config (config/load-project-config test-projects-file test-project-name))

  ;; Create a test CCTX with default values
  (create-test-cctx)

  ;; Create a test CCTX with custom values
  (create-test-cctx "custom-cctx" :v2 :add-file)

  ;; Explore available transactors
  (registry/get-transactor-for-template :v2 :no-op)

  ;; Test CLI argument parsing
  (test-cli-args 
   "test-cctx"
   "--template" "basic" 
   "--template-version" "v2"
   "--projects" test-projects-file
   "--project" test-project-name)

  ;; Test main function
  (test-main 
   "test-cctx"
   "--template" "basic" 
   "--template-version" "v2"
   "--projects" test-projects-file
   "--project" test-project-name)

    ;; Add more development helper functions and explorations here
)
