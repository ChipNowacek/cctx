(ns {{namespace}}
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.java.shell :refer [sh]]))

(def tx-project-root "{{tx-project-root}}")
(def cctxs-dir "{{cctxs-dir}}")
(def cctx-name "{{cctx-name}}")
(def current-dir "{{current-dir}}")
(def in-container? {{in-container?}})

(defn get-project-root []
  (System/getenv "PROJECT_ROOT"))

(defn validate-project-root
  "Validates that PROJECT_ROOT matches the project root where this CCTX was created or the container project root."
  []
  (let [env-root (get-project-root)]
    (when-not env-root
      (throw (ex-info "PROJECT_ROOT environment variable must be set" {})))
    (when-not (= env-root tx-project-root)
      (throw (ex-info "PROJECT_ROOT does not match project root"
                      {:expected tx-project-root
                       :actual env-root})))))

(def change-spec
  {:title {{title}}
   :description {{description}}
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
                         [(str tx-project-root "/" (:path change))]))))
    :add-file (let [file-path (str tx-project-root "/" (:path change))
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

(defn set-safe-directory []
  (let [{:keys [exit err]} (sh "git" "config" "--global" "--add" "safe.directory" tx-project-root)]
    (when-not (zero? exit)
      (println "Warning: Failed to set safe directory. Error:" err))))

(defn git-cmd [& args]
  (let [full-command (concat ["git" "-C" tx-project-root] args)]
    (println "Executing git command:" (str/join " " full-command))
    (apply sh full-command)))

(defn in-git-repo? []
  (set-safe-directory)  ; Set safe directory before checking
  (let [{:keys [exit out err]} (git-cmd "rev-parse" "--is-inside-work-tree")]
    (println "in-git-repo? exit code:" exit)
    (println "in-git-repo? stdout:" out)
    (println "in-git-repo? stderr:" err)
    (zero? exit)))

(defn get-relative-path [full-path]
  (when (str/starts-with? full-path current-dir)
    (str/replace full-path (str current-dir "/") "")))

(defn load-manifest []
  (let [manifest-file (io/file current-dir ".manifest")]
    (when (.exists manifest-file)
      (let [manifest-entries (-> manifest-file slurp str/split-lines)]
        (->> manifest-entries
             (map #(str current-dir "/" %))
             set)))))

(defn expected-files? [untracked-files]
  (let [manifest-files (load-manifest)]
    (if manifest-files
      (every? manifest-files untracked-files)
      false)))

(defn git-status-clean? []
  (if (in-git-repo?)
    (let [{:keys [exit out err]} (git-cmd "status" "--porcelain" "--untracked-files=all")]
      (if (zero? exit)
        (let [untracked-files (->> (str/split-lines out)
                                  (map #(str/replace % #"^\?\? " ""))
                                  (map #(str tx-project-root "/" %)))]
          (if (expected-files? untracked-files)
            true
            (do
              (println "Warning: Unexpected files found. Only files listed in .manifest are allowed.")
              (println "Unexpected files:" (str/join ", " 
                        (->> untracked-files
                             (remove #(expected-files? [%]))
                             (map #(str/replace % (str tx-project-root "/") "")))))
              false)))
        (do
          (println "Warning: Git command failed. Error:" err)
          false)))
    (do
      (println "Warning: Not in a git repository.")
      false)))

(defn create-rollback-script []
  (let [current-branch (:out (git-cmd "rev-parse" "--abbrev-ref" "HEAD"))]
    (when (str/blank? current-branch)
      (throw (ex-info "Unable to determine current branch. CCTX cannot proceed without rollback capability." 
                      {:cctx-name cctx-name})))
    (let [rollback-script (str current-dir "/rollback.sh")
          manifest-file (io/file current-dir ".manifest")
          script-content (str "#!/bin/bash\n"
                            "# Switch back and clean up branch\n"
                            "git checkout " current-branch "\n"
                            "git branch -D " cctx-name "\n")]
      (spit rollback-script script-content)
      (.setExecutable (io/file rollback-script) true)
      ;; Update manifest to include rollback script
      (when (.exists manifest-file)
        (let [current-manifest (str/split-lines (slurp manifest-file))]
          (spit manifest-file (str/join "\n" (conj current-manifest "rollback.sh")))))
      (println "Rollback script created:" rollback-script)
      rollback-script)))

(defn run-rollback-script [script]
  (println "Running rollback script:" script)
  (let [{:keys [exit out err]} (sh "bash" script)]
    (println "Rollback script exit code:" exit)
    (println "Rollback script stdout:" out)
    (println "Rollback script stderr:" err)
    (when-not (zero? exit)
      (throw (ex-info "Rollback script failed" {:exit exit :out out :err err})))))

(defn create-and-switch-branch [branch-name]
  (let [{:keys [exit out err]} (git-cmd "checkout" "-b" branch-name)]
    (if (zero? exit)
      (println "Created and switched to new branch:" branch-name)
      (throw (ex-info "Failed to create and switch to new branch"
                      {:branch branch-name
                       :exit exit
                       :out out
                       :err err})))))

(defn validate-and-transact! []
  (validate-project-root)
  (println "Transaction project root:" tx-project-root)
  (println "Current working directory:" current-dir)
  (println "In container:" in-container?)
  (set-safe-directory)  ; Set safe directory before any git operations
  (if-not (in-git-repo?)
    (throw (ex-info "Not in a git repository. CCTX cannot proceed." 
                    {:tx-project-root tx-project-root
                     :current-dir current-dir
                     :in-container in-container?}))
    (if-not (git-status-clean?)
      (throw (ex-info "Git working tree is not clean. Please commit or stash changes before proceeding." {}))
      (let [rollback-script (try
                              (create-rollback-script)
                              (catch Exception e
                                (println "Error creating rollback script:" (.getMessage e))
                                (throw (ex-info "CCTX cannot proceed without rollback capability" {:cause e}))))] 
        (println "Rollback script created:" rollback-script)
        (try
          (create-and-switch-branch cctx-name)
          (println (str "Description: " (:description change-spec))) 
          (println (str "Transaction project root:" tx-project-root))
          (println (str "Change spec: " (:changes change-spec)))
          (println (str "Current working directory:" (System/getProperty "user.dir")))
          (if (dry-run?)
            (doseq [change (:changes change-spec)] 
              (println "Would transact:" (pr-str change)))
            (doseq [change (:changes change-spec)] 
              (transact-change change)))
          (catch Exception e
            (println "Error during transaction. Rolling back...")
            (run-rollback-script rollback-script) 
            (throw (ex-info "Transaction failed and rolled back" {:cause e}))))))))

(defn rollback! []
  (let [rollback-script (str current-dir "/rollback.sh")]
    (if (.exists (io/file rollback-script))
      (do
        (run-rollback-script rollback-script)
        (println "Rollback completed successfully."))
      (println "Rollback script not found. Manual rollback may be necessary."))))

(comment
  ;; Core validation and setup
  (validate-project-root)
  (get-project-root)
  (print in-container?)
  
  ;; Git and manifest testing
  (git-status-clean?)
  (in-git-repo?)
  (load-manifest)
  (get-relative-path "dev/cctx/cctxs/test_nothing/cctx.clj")
  
  ;; Path debugging
  (let [test-path "dev/cctx/cctxs/test_nothing/cctx.clj"]
    {:full-path test-path
     :relative-path (get-relative-path test-path)
     :in-manifest? (expected-files? [test-path])})
  
  ;; Git operations
  (git-cmd "status" "--porcelain" "--untracked-files=all")
  (git-cmd "rev-parse" "--is-inside-work-tree")
  (git-cmd "rev-parse" "--show-toplevel")
  
  ;; Environment info
  {:current-dir current-dir
   :project-root tx-project-root
   :tx-project-root tx-project-root
   :manifest-files (load-manifest)}
  
  ;; Change spec inspection
  (def sample-change (first (:changes change-spec)))
  (keys change-spec)
  (:changes change-spec)
  
  ;; Transaction testing
  (binding [change-spec (assoc change-spec :dry-run true)]
    (validate-and-transact!))
  (transact-change sample-change)
  
  ;; Rollback testing
  (create-rollback-script)
  (.exists (io/file (str current-dir "/rollback.sh")))
  
  ;; Full workflow test (dry run)
  (do
    (validate-project-root)
    (git-status-clean?)
    (validate-and-transact!)
    (rollback!))
)

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