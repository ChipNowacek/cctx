(ns cctx.config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

; Remove project-specific code
; Add any general configuration loading if needed

(defn load-config [config-file]
  (-> config-file
      io/resource
      slurp
      edn/read-string))

; Add other general configuration functions as needed
