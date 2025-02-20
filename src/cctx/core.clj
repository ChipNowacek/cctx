(ns cctx.core
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [malli.core :as m]
            [cctx.template-registry :as registry]))

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

