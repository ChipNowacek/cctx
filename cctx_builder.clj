#!/usr/bin/env bb

(ns cctx.cctx-builder
  (:require [babashka.cli :as cli]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [malli.core :as m]
            [malli.error :as me]))

(def projects-schema
  [:map
   [:projects
    [:map-of
     :string
     [:map
      [:name string?]
      [:project-root string?]
      [:cctx-dir string?]
      [:cctxs-dir string?]
      [:project-has-templates boolean?]
      [:project-templates-dir {:optional true} string?]]]]])

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
            :overwrite-existing :boolean}
   :require [:template :template-version :projects :project]
   :spec {:template {:desc "Template to use"}
          :template-version {:desc "Template version"}
          :projects {:desc "Path to projects config file"}
          :project {:desc "Project name from config"}
          :overwrite-existing {:desc "Delete existing CCTX if it exists"}}})

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
    (if (.exists schema-file)
      (let [schema (-> schema-file slurp edn/read-string)
            validator (m/validator schema)]
        validator)
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
            (if-not (validate-templates templates)
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

(def transactor-template
  "(defn transact-change [change]
  (case (:type change)
    :edit (println \"Edit not implemented yet\")
    :script (when (:executable change)
             (-> (Runtime/getRuntime)
                 (.exec (into-array String 
                         [(str project-root \"/\" (:path change))]))))
    :add-file (let [file-path (str project-root \"/\" (:path change))
                   file (io/file file-path)]
               (.mkdirs (.getParentFile file))
               (spit file (:template change))
               (when (:executable change)
                 (.setExecutable file true)))
    :transform (when-let [transform-fn (:transform change)]
                (transform-fn))
    (throw (ex-info \"Unknown change type\" {:change change}))))

(defn dry-run? []
  (:dry-run change-spec))

(defn validate-and-transact! []
  (validate-project-root)
  (if (dry-run?)
    (doseq [change (:changes change-spec)]
      (println \"Would transact:\" (pr-str change)))
    (doseq [change (:changes change-spec)]
      (transact-change change))))")

(defn create-cctx! [cctx-name {:keys [template template-version project projects overwrite-existing] :as opts}]
  (let [project-config (load-project-config projects project)
        template-data (load-template project-config template template-version)
        snake-name (kebab->snake cctx-name)
        cctx-dir (io/file (:project-root project-config) 
                         (:cctxs-dir project-config)
                         snake-name)]
    (when (.exists cctx-dir)
      (if overwrite-existing
        (delete-directory-recursive cctx-dir)
        (throw (ex-info "CCTX already exists"
                       {:cctx-name cctx-name
                        :cctx-dir (.getPath cctx-dir)}))))
    (.mkdirs cctx-dir)
    (spit (io/file cctx-dir "cctx.clj")
          (str "(ns dev.cctx.cctxs." cctx-name ".cctx\n"
               "  (:require [clojure.java.io :as io]))\n\n"
               "(def project-root \"" (:project-root project-config) "\")\n\n"
               "(defn validate-project-root\n"
               "  \"Validates that PROJECT_ROOT matches the project root where this CCTX was created.\"\n"
               "  []\n"
               "  (let [env-root (System/getenv \"PROJECT_ROOT\")]\n"
               "    (when-not env-root\n"
               "      (throw (ex-info \"PROJECT_ROOT environment variable must be set\" {})))\n"
               "    (when-not (= env-root project-root)\n"
               "      (throw (ex-info \"PROJECT_ROOT does not match CCTX project root\"\n"
               "                      {:expected project-root\n"
               "                       :actual env-root})))))\n\n"
               "(def change-spec\n"
               (pr-str (assoc (:spec template-data) :title cctx-name))
               ")\n\n"
               transactor-template
               "\n"))))

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