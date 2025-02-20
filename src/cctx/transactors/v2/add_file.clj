(ns cctx.transactors.v2.add-file
  (:require [cctx.core :as core]
            [clojure.java.io :as io]))

(deftype AddFileTransactor [spec]
  core/CCTXTransactor
  (validate [_ context]
    (let [changes (:changes (:spec context))
          project-root (:project-root context)]
      (every? (fn [{:keys [path content]}]
                (let [target-file (io/file project-root path)]
                  (and content
                       (not (.exists target-file))
                       (.exists (.getParentFile target-file)))))
              changes)))
  
  (transact! [_ context]
    (let [changes (:changes (:spec context))
          project-root (:project-root context)]
      (doseq [{:keys [path content]} changes]
        (let [target-file (io/file project-root path)]
          (io/make-parents target-file)
          (spit target-file content)))
      {:status :success
       :files (mapv :path changes)}))
  
  (rollback! [_ context]
    (let [changes (:changes (:spec context))
          project-root (:project-root context)]
      (doseq [{:keys [path]} changes]
        (let [target-file (io/file project-root path)]
          (when (.exists target-file)
            (.delete target-file))))
      {:status :success
       :files (mapv :path changes)})))

(defmethod core/create-transactor [:v2 :add-file]
  [spec]
  (->AddFileTransactor spec))