(ns cctx.core
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [malli.core :as m]))

(defprotocol CCTXTransactor
  (validate [this context] "Validate the transaction")
  (transact! [this context] "Execute the transaction")
  (rollback! [this context] "Rollback the transaction"))

(defmulti create-transactor :type)

(defn load-cctx-spec [file-path]
  (let [spec (-> file-path io/file slurp edn/read-string)]
    ; Here you would validate the spec
    spec))

(defn create-cctx! [spec-file]
  (let [spec (load-cctx-spec spec-file)
        transactor (create-transactor spec)]
    (when (validate transactor {:spec spec})
      (transact! transactor {:spec spec}))))

; Other core functions like activate-cctx!, deactivate-cctx!, etc. would go here
