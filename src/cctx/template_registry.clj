(ns cctx.template-registry
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def registry
  (-> "transactor_registry.edn"
      io/resource
      slurp
      edn/read-string))

(defn get-transactor-for-template [version template-type]
  (if-let [transactor-sym (get-in registry [version template-type])]
    (try
      (requiring-resolve transactor-sym)
      (catch Exception e
        (throw (ex-info "Failed to load transactor"
                        {:version version
                         :template-type template-type
                         :error (.getMessage e)}))))
    (throw (ex-info "No transactor found for template type"
                    {:version version
                     :template-type template-type}))))
