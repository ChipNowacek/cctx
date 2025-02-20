(ns cctx.transactors.no-op
  (:require [cctx.core :as core]))

(deftype NoOpTransactor [spec]
  core/CCTXTransactor
  (validate [_ context]
    (println "Validating NoOp transaction")
    true)
  
  (transact! [_ context]
    (println "Executing NoOp transaction")
    {:status :success})
  
  (rollback! [_ context]
    (println "Rolling back NoOp transaction")
    {:status :success}))

(defmethod core/create-transactor :no-op
  [spec]
  (->NoOpTransactor spec))
