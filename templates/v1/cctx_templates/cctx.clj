(ns {{namespace}}
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.java.shell :refer [sh]]
            [clojure.edn :as edn]
            [malli.core :as m]
            [malli.error :as me]))

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

(def cctx-state-schema
  [:map
   [:version string?]
   [:cctx-name string?]
   [:original-branch {:optional true} string?]
   [:files [:set string?]]
   [:status [:enum :initialized :active :inactive :completed]]])

(defn load-state []
  (let [state-file (io/file current-dir ".cctx-state.edn")]
    (when (.exists state-file)
      (let [state (-> state-file slurp edn/read-string)]
        (if (m/validate cctx-state-schema state)
          state
          (throw (ex-info "Invalid CCTX state file" 
                         {:file (.getPath state-file)
                          :errors (m/explain cctx-state-schema state)})))))))

(defn save-state! [state]
  (if (m/validate cctx-state-schema state)
    (spit (io/file current-dir ".cctx-state.edn") (pr-str state))
    (throw (ex-info "Invalid CCTX state" 
                   {:state state
                    :errors (m/explain cctx-state-schema state)}))))

(defn init-state! [files]
  (let [fully-qualified-files (set (map #(str current-dir "/" %) files))]
    (save-state!
      {:version "1"
       :cctx-name cctx-name
       :files fully-qualified-files
       :stashes []
       :status :initialized})))

(defn update-state! [f & args]
  (let [current-state (or (load-state)
                         (throw (ex-info "CCTX state file not found" 
                                       {:dir current-dir})))
        new-state (apply update current-state f args)]
    (save-state! new-state)))

(defn transact-change! [change]
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

(defn current-branch []
  (let [{:keys [exit out]} (git-cmd "rev-parse" "--abbrev-ref" "HEAD")]
    (when (zero? exit)
      (str/trim out))))

(defn stash-changes [description]
  (let [{:keys [exit out err]} (git-cmd "stash" "push" "-m" description)]
    (if (zero? exit)
      (if (str/blank? out)
        (do
          (println "Warning: Git stash succeeded but produced no output.")
          nil)
        (or (re-find #"stash@\{[0-9]+\}" out)
            (do
              (println "Warning: Unexpected stash output format:" out)
              nil)))
      (do
        (println "Error during git stash:" err)
        nil))))

(defn apply-stash [stash-ref]
  (let [{:keys [exit]} (git-cmd "stash" "apply" stash-ref)]
    (zero? exit)))

(defn drop-stash [stash-ref]
  (let [{:keys [exit]} (git-cmd "stash" "drop" stash-ref)]
    (zero? exit)))

(defn checkout-branch [branch-name]
  (let [{:keys [exit]} (git-cmd "checkout" branch-name)]
    (zero? exit)))

(defn create-branch [branch-name]
  (let [{:keys [exit]} (git-cmd "checkout" "-b" branch-name)]
    (zero? exit)))

(defn branch-exists? [branch-name]
  (let [{:keys [exit]} (git-cmd "rev-parse" "--verify" branch-name)]
    (zero? exit)))

(defn ensure-branch []
  (or (branch-exists? cctx-name)
      (create-branch cctx-name)))

(defn init-cctx! []
  (validate-project-root)
  (when (in-git-repo?)
    (let [state (load-state)]
      (if state
        state  ; Return existing state if found
        (let [files #{"cctx.clj" "README.md" ".cctx-state.edn"}]
          (println "Initializing state with files:" files)
          (try
            (init-state! files)
            (catch Exception e
              (println "Error initializing state:" (.getMessage e))
              (println "State that failed validation:" (pr-str {:version "1"
                                                                :cctx-name cctx-name
                                                                :files files
                                                                :stashes []
                                                                :status :initialized}))
              (when-let [explanation (m/explain cctx-state-schema {:version "1"
                                                                   :cctx-name cctx-name
                                                                   :files files
                                                                   :stashes []
                                                                   :status :initialized})]
                (println "Validation errors:" (me/humanize explanation)))))
          (load-state))))))

(defn git-status-clean? []
  (let [{:keys [exit out]} (git-cmd "status" "--porcelain")]
    (and (zero? exit) (str/blank? out))))

(defn activate-cctx! []
  (validate-project-root)
  (when-not (in-git-repo?)
    (throw (ex-info "Not in a git repository" {:dir current-dir})))
  
  (when-not (git-status-clean?)
    (throw (ex-info "Git working tree is not clean. Please commit or stash changes before activating CCTX." {})))

  (let [orig-branch (current-branch)]
    (when (and orig-branch (not= orig-branch cctx-name))
      (ensure-branch)
      (update-state! assoc :original-branch orig-branch)
      
      ; Unstash changes if there's a stash for this CCTX
      (let [stash-ref (str "stash^{/" cctx-name)]
        (when (zero? (:exit (git-cmd "rev-parse" "--verify" stash-ref)))
          (apply-stash stash-ref)
          (drop-stash stash-ref))))
    
    (update-state! assoc :status :active)))

(defn deactivate-cctx! []
  (validate-project-root)
  (let [state (load-state)
        orig-branch (:original-branch state)]
    (when orig-branch
      (stash-changes (str "CCTX work on " cctx-name))
      (when (checkout-branch orig-branch)
        (update-state! assoc :status :inactive)))))

(defn complete-cctx! []
  (validate-project-root)
  (update-state! assoc :status :completed))

(defn get-relative-path [full-path]
  (when (str/starts-with? full-path current-dir)
    (str/replace full-path (str current-dir "/") "")))

(defn create-rollback-script []
  (let [current-branch (:out (git-cmd "rev-parse" "--abbrev-ref" "HEAD"))]
    (when (str/blank? current-branch)
      (throw (ex-info "Unable to determine current branch. CCTX cannot proceed without rollback capability." 
                      {:cctx-name cctx-name})))
    (let [rollback-script (str current-dir "/rollback.sh")
          script-content (str "#!/bin/bash\n"
                            "# Switch back and clean up branch\n"
                            "git checkout " current-branch "\n"
                            "git branch -D " cctx-name "\n")]
      (spit rollback-script script-content)
      (.setExecutable (io/file rollback-script) true)
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

(defn create-and-switch-branch []
  (let [{:keys [exit out err]} (git-cmd "checkout" "-b" cctx-name)]
    (if (zero? exit)
      (println "Created and switched to new branch:" cctx-name)
      (throw (ex-info "Failed to create and switch to new branch"
                      {:branch cctx-name
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
          (create-and-switch-branch)
          (println (str "Description: " (:description change-spec))) 
          (println (str "Transaction project root:" tx-project-root))
          (println (str "Change spec: " (:changes change-spec)))
          (println (str "Current working directory:" (System/getProperty "user.dir")))
          (if (dry-run?)
            (doseq [change (:changes change-spec)] 
              (println "Would transact:" (pr-str change)))
            (doseq [change (:changes change-spec)] 
              (transact-change! change)))
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
  
  ;; State management testing
  (init-state! #{"cctx.clj" "README.md" ".cctx-state.edn"})
  (load-state)
  (update-state! assoc :status :active)
  
  ;; Git and manifest testing
  (in-git-repo?)
  (get-relative-path "dev/cctx/cctxs/test_nothing/cctx.clj")
  
  ;; Path debugging
  (let [test-path "dev/cctx/cctxs/test_nothing/cctx.clj"]
    {:full-path test-path
     :relative-path (get-relative-path test-path)})
  
  ;; Git operations
  (git-cmd "status" "--porcelain" "--untracked-files=all")
  (git-cmd "rev-parse" "--is-inside-work-tree")
  (git-cmd "rev-parse" "--show-toplevel")
  
  ;; Environment info
  {:current-dir current-dir
   :project-root tx-project-root
   :tx-project-root tx-project-root}
  
  ;; Change spec inspection
  (def sample-change (first (:changes change-spec)))
  (keys change-spec)
  (:changes change-spec)
  
  ;; Transaction testing
  (binding [change-spec (assoc change-spec :dry-run true)]
    (validate-and-transact!))
  (transact-change! sample-change)
  
  ;; Rollback testing
  (create-rollback-script)
  (.exists (io/file (str current-dir "/rollback.sh")))
  
  ;; Full workflow test (dry run)
  (do
    (init-cctx!)
    (activate-cctx!)
    (validate-and-transact!)
    (deactivate-cctx!)
    (complete-cctx!))
  
  ;; State transitions
  (init-cctx!)
  (activate-cctx!)
  (deactivate-cctx!)
  (complete-cctx!)
  
  ;; Check state after each transition
  (load-state)
  
  ;; Detailed init-cctx! testing
  (do
    (println "Testing init-cctx!")
    (let [result (init-cctx!)]
      (println "init-cctx! result:" result)
      (println "Current state:" (load-state))))
  
  ;; Validate state structure
  (m/validate cctx-state-schema (load-state))
  
  ;; Print detailed state explanation if invalid
  (when-let [explanation (m/explain cctx-state-schema (load-state))]
    (println "State validation errors:" (me/humanize explanation)))
  
  ;; Test git-status-clean? with more logging
  (let [result (git-status-clean?)]
    (println "git-status-clean? result:" result)
    (println "Current state:" (load-state))
    (println "Current directory:" current-dir)
    (println "Project root:" tx-project-root)
    result)
  
  ;; Test fully qualified file paths in state
  (do
    (init-state! #{"cctx.clj" "README.md" ".cctx-state.edn"})
    (let [state (load-state)]
      (println "Initialized state:")
      (println "Files:" (:files state))
      (println "All files fully qualified:" (every? #(str/starts-with? % current-dir) (:files state)))))
)