(ns {{namespace}}
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.java.shell :refer [sh]]))

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

(defn git-status-clean? []
  (let [{:keys [exit out err]} (sh "git" "status" "--porcelain")]
    (empty? out)))

(defn create-rollback-script []
  (let [cctx-name (last (str/split (str *ns*) #"\."))
        current-branch (:out (sh "git" "rev-parse" "--abbrev-ref" "HEAD"))
        rollback-script (str "rollback_" cctx-name ".sh")
        script-content (str "#!/bin/bash\n"
                            "git checkout " current-branch "\n"
                            "git branch -D " cctx-name "\n")]
    (spit rollback-script script-content)
    (.setExecutable (io/file rollback-script) true)
    rollback-script))

(defn create-and-switch-branch [branch-name]
  (sh "git" "checkout" "-b" branch-name))

(defn run-rollback-script [script-name]
  (sh "bash" script-name))

(defn validate-and-transact! []
  (validate-project-root)
  (if-not (git-status-clean?)
    (throw (ex-info "Git working tree is not clean. Please commit or stash changes before proceeding." {}))
    (let [cctx-name (last (str/split (str *ns*) #"\."))
          rollback-script (create-rollback-script)]
      (println "Rollback script created:" rollback-script)
      (try
        (create-and-switch-branch cctx-name)
        (println "Switched to new branch:" cctx-name)
        (println (str "Description: " (:description change-spec)))  
        (println (str "Change spec: " (:changes change-spec)))
        (if (dry-run?)
          (doseq [change (:changes change-spec)]
            (println "Would transact:" (pr-str change)))
          (doseq [change (:changes change-spec)]
            (transact-change change)))
        (catch Exception e
          (println "Error during transaction. Rolling back...")
          (run-rollback-script rollback-script)
          (throw (ex-info "Transaction failed and rolled back" {:cause e})))))))

(defn rollback! []
  (let [cctx-name (last (str/split (str *ns*) #"\."))
        rollback-script (str "rollback_" cctx-name ".sh")]
    (if (.exists (io/file rollback-script))
      (do
        (run-rollback-script rollback-script)
        (println "Rollback completed successfully."))
      (println "Rollback script not found. Manual rollback may be necessary."))))

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