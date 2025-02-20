(ns cctx.transactors.v2.no-op
  (:require [cctx.core :as core]))

(deftype NoOpTransactor [spec]
  core/CCTXTransactor
  (validate [_ context]
    (println "Validating NoOp transaction (v2)")
    true)
  
  (transact! [_ context]
    (println "Executing NoOp transaction (v2)")
    {:status :success})
  
  (rollback! [_ context]
    (println "Rolling back NoOp transaction (v2)")
    {:status :success}))

(defmethod core/create-transactor [:v2 :no-op]
  [spec]
  (->NoOpTransactor spec))
