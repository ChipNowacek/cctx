(ns cctx.core
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [malli.core :as m]
            [cctx.template-registry :as registry]
            [cctx.config :as config]
            [cctx.cli :as cli]
            [clojure.string :as str])
  (:gen-class))

(defprotocol CCTXTransactor
  (validate [this context] "Validate the transaction")
  (transact! [this context] "Execute the transaction")
  (rollback! [this context] "Rollback the transaction"))

(defmulti create-transactor (juxt :version :type))

(defmethod create-transactor :default [spec]
  (let [transactor-fn (registry/get-transactor-for-template (:version spec) (:type spec))]
    (transactor-fn spec)))

(defn load-cctx-spec [file-path]
  (let [spec (-> file-path io/file slurp edn/read-string)]
    ; Here you would validate the spec
    spec))

(defn create-cctx! [{:keys [cctx-name project-config] :as spec}]
  (let [transactor (create-transactor spec)
        context {:spec spec
                 :project-config project-config
                 :cctx-name cctx-name}]
    (when (validate transactor context)
      (transact! transactor context))))

(defn -main [& args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args)]
    (cond
      (:help options)
      (println summary)

      errors
      (do (run! println errors)
          (System/exit 1))

      (empty? arguments)
      (do (println "Error: CCTX name is required")
          (println summary)
          (System/exit 1))

      :else
      (let [cctx-name (first arguments)]
        (try
          (let [project-config (config/load-project-config (:projects options) (:project options))]
            (println "Creating CCTX:" cctx-name)
            (println "Project config:" project-config)
            ; Here you would call your create-cctx! function
            )
          (catch Exception e
            (println "Error:" (.getMessage e))
            (System/exit 1)))))))

