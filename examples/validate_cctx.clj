(ns cctx.examples.validate-cctx
  (:require [cctx.cctx-schema :as schema]))

(comment
  ;; Example usage
  (let [cctx-data (read-string (slurp "path/to/your/cctx.edn"))]
    (if (schema/validate cctx-data)
      (println "CCTX is valid!")
      (println "CCTX validation errors:"
               (schema/explain-errors cctx-data)))))