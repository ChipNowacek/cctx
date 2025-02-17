(ns {{namespace}}
  (:require [clojure.java.io :as io]))

(def project-root "{{project-root}}")

(defn get-project-root []
  (System/getenv "PROJECT_ROOT"))

(defn validate-project-root
  "Validates that PROJECT_ROOT matches the project root where this CCTX was created or is '/app'."
  []
  (let [env-root (get-project-root)]
    (when-not env-root
      (throw (ex-info "PROJECT_ROOT environment variable must be set" {})))
    (when-not (or (= env-root project-root)
                  (= env-root "/app"))
      (throw (ex-info "PROJECT_ROOT does not match CCTX project root or '/app'"
                      {:expected [project-root "/app"]
                       :actual env-root})))))

(def change-spec
  {:title "{{title}}"
   :description "{{description}}"
   :changes {{changes}}
   :dry-run {{dry-run}}
   :rollback {{rollback}}
   :requires {{requires}}})

(defn transact-change [change]
  (case (:type change)
    :edit (println "Edit not implemented yet")
    :script (when (:executable change)
             (-> (Runtime/getRuntime)
                 (.exec (into-array String 
                         [(str project-root "/" (:path change))]))))
    :add-file (let [file-path (str project-root "/" (:path change))
                   file (io/file file-path)]
               (.mkdirs (.getParentFile file))
               (spit file (:template change))
               (when (:executable change)
                 (.setExecutable file true)))
    :transform (when-let [transform-fn (:transform change)]
                (transform-fn))
    (throw (ex-info "Unknown change type" {:change change}))))

(defn dry-run? []
  (:dry-run change-spec))

(defn validate-and-transact! []
  (validate-project-root)
  (println (str "Description: " (:description change-spec)))  
  (println (str "Change spec: " (:changes change-spec)))  
  (if (dry-run?)
    (doseq [change (:changes change-spec)]
      (println "Would transact:" (pr-str change)))
    (doseq [change (:changes change-spec)]
      (transact-change change))))

(comment
  ;; Example REPL usage
  
  (validate-project-root)
  
  ;; Test dry run
  (validate-and-transact!)
  
  ;; Run specific changes
  (transact-change (first (:changes change-spec)))
  
  ;; Development scratch pad
  
  ,)

;; Comments and Development Notes
;; ============================
;; Creation Date: 2025-02-17 14:39:06
;; Template: no-op (version v1)
;;
;; Implementation Notes:
;; -------------------
;; 
;; Testing Notes:
;; ------------
;; 
;; Outstanding Issues:
;; -----(set-project-root! "/path/to/project")------------
;;