#!/usr/bin/env bb

(ns cctx.cctx-builder
  (:require [babashka.cli :as cli]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.java.shell :refer [sh]]  ; Add this line
            [malli.core :as m]
            [malli.error :as me]))

(def projects-schema
  [:map
   [:projects
    [:map-of
     :string
     [:and
      [:map
       [:name string?]
       [:project-root string?]
       [:dev-in-container? boolean?]
       [:container-project-root {:optional true} string?]
       [:cctx-dir string?]
       [:cctxs-dir string?]
       [:project-has-templates boolean?]
       [:project-templates-dir {:optional true} string?]]
      [:fn
       {:error/message "container-project-root is required when dev-in-container? is true"}
       (fn [{:keys [dev-in-container? container-project-root]}]
         (if dev-in-container?
           (boolean container-project-root)
           true))]]]]])

(defn load-project-config [projects-file project-name]
  (let [config (-> projects-file slurp edn/read-string)
        validator (m/validator projects-schema)]
    (when-not (validator config)
      (let [explanation (-> (m/explainer projects-schema)
                            (#(% config))
                            (me/humanize))]
        (throw (ex-info "Invalid projects configuration"
                        {:explanation explanation}))))
    (if-let [project (get-in config [:projects project-name])]
      project
      (throw (ex-info "Project not found in config"
                      {:project project-name
                       :available-projects (keys (:projects config))})))))

(def cli-opts
  {:coerce {:template :keyword
            :template-version :string
            :project :string
            :overwrite-existing :boolean
            :overwrite-force :boolean}
   :require [:template :template-version :projects :project]
   :spec {:template {:desc "Template to use"}
          :template-version {:desc "Template version"}
          :projects {:desc "Path to projects config file"}
          :project {:desc "Project name from config"}
          :overwrite-existing {:desc "Prompt to overwrite existing CCTX if it exists"}
          :overwrite-force {:desc "Overwrite existing CCTX without prompting"}}})

(defn find-templates-dir [project-config version]
  (let [project-root (:project-root project-config)
        builder-templates (io/file "templates" version)
        project-templates (when (:project-has-templates project-config)
                            (io/file project-root
                                     (:project-templates-dir project-config)
                                     version))]
    (cond
      (and project-templates (.exists project-templates)) project-templates
      (.exists builder-templates) builder-templates
      :else (throw (ex-info "Templates directory not found"
                            {:version version
                             :searched-paths [project-templates builder-templates]})))))

(defn get-valid-versions [project-config]
  (let [search-dirs [(when (:project-has-templates project-config)
                       (io/file (:project-root project-config)
                                (:project-templates-dir project-config)))
                     (io/file "templates")]]
    (->> search-dirs
         (remove nil?)
         (filter #(.exists %))
         (mapcat #(.listFiles %))
         (filter #(.isDirectory %))
         (map #(.getName %))
         (filter #(.startsWith % "v"))
         (into #{}))))

(defn load-template-schema [templates-dir version]
  (let [schema-file (io/file templates-dir "schema.edn")]
    (println "Attempting to load schema from:" (.getAbsolutePath schema-file))
    (if (.exists schema-file)
      (let [schema (-> schema-file slurp edn/read-string)]
        (println "Loaded schema:" schema)
        (try
          (m/validator schema)  ; Changed from m/schema to m/validator
          (catch Exception e
            (println "Error creating Malli schema:" (.getMessage e))
            (throw (ex-info "Invalid schema definition" {:schema schema :error (.getMessage e)})))))
      (throw (ex-info "Template schema not found"
                      {:version version
                       :schema-path (.getPath schema-file)})))))

(defn load-template [project-config template-name version]
  (let [valid-versions (get-valid-versions project-config)]
    (if-not (contains? valid-versions version)
      (throw (ex-info (str "Invalid template version. Must be one of: " (str/join ", " (sort valid-versions)))
                      {:version version
                       :valid-versions valid-versions}))
      (let [templates-dir (find-templates-dir project-config version)
            template-file (io/file templates-dir "templates.edn")  ; Changed from cctx-templates.edn
            validate-templates (load-template-schema templates-dir version)]
        (if (.exists template-file)
          (let [templates (-> template-file slurp edn/read-string)]
            (if-not (validate-templates templates)  ; Changed from (validate-templates templates) to (validate-templates templates)
              (throw (ex-info "Invalid templates configuration"
                              {:version version
                               :templates templates}))
              (if-let [template (get templates template-name)]
                template
                (throw (ex-info "Template not found"
                                {:template template-name
                                 :version version})))))
          (throw (ex-info "Template version not found"
                          {:version version})))))))

(defn kebab->snake [s]
  (str/replace s "-" "_"))

(defn delete-directory-recursive
  "Recursively delete a directory."
  [^java.io.File file]
  (when (.exists file)
    (when (.isDirectory file)
      (doseq [child-file (.listFiles file)]
        (delete-directory-recursive child-file)))
    (.delete file)))

(defn apply-replacements [content replace-map]
  (reduce (fn [acc [k v]] (str/replace acc k v)) content replace-map))

(defn git-status-clean? [repo-path]
  (let [{:keys [exit out]} (apply sh (concat ["git" "-C" repo-path "status" "--porcelain"]))]
    (and (zero? exit) (str/blank? out))))

(defn branch-exists? [project-root branch-name]
  (zero? (:exit (apply sh ["git" "-C" project-root "rev-parse" "--verify" branch-name]))))

(defn prompt-overwrite [cctx-name]
  (print (str "CCTX '" cctx-name "' already exists. Overwrite? (y/N): "))
  (flush)
  (let [response (read-line)]
    (= (str/lower-case response) "y")))

(defn sanitize-cctx-name [name]
  (-> name
      (str/replace #"[^a-zA-Z0-9-_.]" "_")
      (str/replace #"^[^a-zA-Z_]" "_")))

(defn format-change-spec [spec]
  (letfn [(format-value [v]
            (cond
              (string? v) (pr-str v)
              (or (map? v) (vector? v))
                (str/replace (with-out-str (clojure.pprint/pprint v)) #",\s*\n" "\n")
              :else (pr-str v)))]
    (str "{\n"
         (str/join "\n"
                   (for [[k v] spec]
                     (str "  " k " " (format-value v))))
         "\n}")))

(defn create-cctx! [cctx-name {:keys [template template-version project projects overwrite-existing overwrite-force] :as opts}]
  (let [project-config (load-project-config projects project)
        project-root (:project-root project-config)]
    
    ; Check git status before proceeding
    (when-not (git-status-clean? project-root)
      (throw (ex-info "Git working tree is not clean. Please commit or stash changes before creating a CCTX."
                      {:project project
                       :project-root project-root})))
    
    (let [template-data (load-template project-config template template-version)
          sanitized-name (sanitize-cctx-name cctx-name)
          snake-name (kebab->snake sanitized-name)
          cctxs-dir (:cctxs-dir project-config)
          cctx-fully-qualified-name (str project-root "/" cctxs-dir "/" snake-name)
          cctx-dir (io/file project-root cctxs-dir snake-name)
          cctx-template-path (str "templates/" template-version "/cctx_templates/cctx.clj")
          readme-template-path (str "templates/" template-version "/cctx_templates/README.md")
          cctx-template (slurp (io/file cctx-template-path))
          readme-template (slurp (io/file readme-template-path))
          spec (:spec template-data)
          formatted-spec (format-change-spec spec)
          in-container (:dev-in-container? project-config)
          container-root (when in-container
                          (or (:container-project-root project-config)
                              (throw (ex-info "Container project root required when dev-in-container? is true"
                                            {:project project
                                             :project-config project-config}))))
          tx-project-root (if in-container container-root project-root)
          tx-cctx-dir (str tx-project-root "/" cctxs-dir "/" snake-name)
          namespace (str (str/replace cctxs-dir "/" ".") "." sanitized-name ".cctx")
          replace-map {"{{current-dir}}" tx-cctx-dir
                      "{{namespace}}" namespace
                      "{{cctx-name}}" cctx-name
                      "{{cctx-dir}}" (:cctx-dir project-config)
                      "{{cctxs-dir}}" cctxs-dir
                      "{{in-container?}}" (str in-container)
                      ;; "{{project-root}}" project-root
                      ;; "{{container-project-root}}" (or container-root "")
                      "{{tx-project-root}}" tx-project-root
                      "{{change-spec}}" formatted-spec  ; Use the formatted spec
                      "{{title}}" (pr-str (:title spec))
                      "{{description}}" (pr-str (:description spec))
                      "{{changes}}" (pr-str (:changes spec))
                      "{{dry-run}}" (str (:dry-run spec true))
                      "{{rollback}}" (str (:rollback spec true))
                      "{{requires}}" (pr-str (:requires spec []))
                      "{{project}}" project}
          cctx-content (reduce-kv str/replace cctx-template replace-map)
          container-regex #"(?s)\{\{#container\}\}(.*?)\{\{/container\}\}"
          non-container-regex #"(?s)\{\{\^container\}\}(.*?)\{\{/\^container\}\}"
          process-readme (fn [content]
                           (-> content
                               (str/replace container-regex (if in-container "$1" ""))
                               (str/replace non-container-regex (if in-container "" "$1"))
                               (apply-replacements replace-map)
                               (str/replace #"\n{3,}" "\n\n")))
          readme-content (process-readme readme-template)
          files #{"cctx.clj" "README.md" ".cctx-state.edn"}
          branch-name snake-name]
      (when (.exists cctx-dir)
        (cond
          overwrite-force
          (delete-directory-recursive cctx-dir)
          
          overwrite-existing
          (if (prompt-overwrite cctx-name)
            (delete-directory-recursive cctx-dir)
            (throw (ex-info "CCTX creation cancelled by user"
                            {:cctx-name cctx-name
                             :cctx-dir (.getPath cctx-dir)})))
          
          :else
          (throw (ex-info "CCTX already exists"
                          {:cctx-name cctx-name
                           :cctx-dir (.getPath cctx-dir)}))))
      
      ; Check if branch already exists
      (when (branch-exists? project-root branch-name)
        (cond
          overwrite-force
          (println "Warning: Branch" branch-name "already exists. It will be overwritten.")
          
          overwrite-existing
          (if (prompt-overwrite branch-name)
            (println "Branch" branch-name "will be overwritten.")
            (throw (ex-info "CCTX creation cancelled by user"
                            {:cctx-name cctx-name
                             :branch-name branch-name})))
          
          :else
          (throw (ex-info "CCTX branch already exists"
                          {:cctx-name cctx-name
                           :branch-name branch-name}))))
      
      ; Remember current branch
      (let [current-branch (str/trim (:out (apply sh ["git" "-C" project-root "rev-parse" "--abbrev-ref" "HEAD"])))]
        
        ; Create new branch or switch to existing one
        (if (branch-exists? project-root branch-name)
          (apply sh ["git" "-C" project-root "checkout" branch-name])
          (apply sh ["git" "-C" project-root "checkout" "-b" branch-name]))
        
        ; Create CCTX files
        (.mkdirs cctx-dir)
        (spit (io/file cctx-dir "cctx.clj") cctx-content)
        (spit (io/file cctx-dir "README.md") readme-content)
        
        ; Add files to git
        (apply sh ["git" "-C" project-root "add" (.getPath cctx-dir)])
        
        ; Stash changes
        (let [{:keys [exit out]} (apply sh ["git" "-C" project-root "stash" "push" "-m" (str "CCTX: " cctx-name)])]
          (if (zero? exit)
            (println "CCTX changes stashed successfully.")
            (println "Warning: Failed to stash CCTX changes. Exit code:" exit)))
        
        ; Switch back to original branch
        (apply sh ["git" "-C" project-root "checkout" current-branch])
        
        (println "CCTX created and stashed on branch:" branch-name)))))

(defn -main [& args]
  (if (< (count args) 1)
    (println "Usage: cctx_builder.clj <name> 
              --template <template> 
              --template-version <version> 
              --projects <config-file> 
              --project <project-name> 
              --overwrite-existing")
    (let [opts (cli/parse-opts args cli-opts)
          name (first args)]
      (create-cctx! name opts))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))

(comment

  ;; Test loading project config
  (def test-projects-file "/home/chip/Documents/cctx/projects.edn")
  (def test-project-name "Catalyst")
  (load-project-config test-projects-file test-project-name)

  ;; Test loading template
  (def test-project-config (load-project-config test-projects-file test-project-name))
  (def test-template-name :basic)
  (def test-template-version "v1")
  (load-template test-project-config test-template-name test-template-version)

  ;; Test creating a CCTX
  (create-cctx! "test-cctx"
                {:template :basic
                 :template-version "v1"
                 :projects test-projects-file
                 :project test-project-name
                 :overwrite-existing true})

  ;; Test git-status-clean?
  (git-status-clean? (:project-root test-project-config))

  ;; Test command-line argument parsing
  (cli/parse-opts ["test-cctx"
                   "--template" "basic"
                   "--template-version" "v1"
                   "--projects" "/path/to/projects.edn"
                   "--project" "Catalyst"
                   "--overwrite-existing"]
                  cli-opts)

  ;; Run main function (comment out when not testing)
  #_(-main "test-cctx"
           "--template" "basic"
           "--template-version" "v1"
           "--projects" "/home/chip/Documents/cctx/projects.edn"
           "--project" "Catalyst"
           "--overwrite-existing")
)