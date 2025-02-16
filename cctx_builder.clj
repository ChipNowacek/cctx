#!/usr/bin/env bb

(ns cctx.cctx-builder
  (:require [babashka.cli :as cli]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [schema :as v1]))

(def project-root
  (or (System/getenv "PROJECT_ROOT")
      (do
        (println "ERROR: PROJECT_ROOT environment variable must be set")
        (System/exit 1))))

(def cli-opts
  {:coerce {:template :keyword
            :template-version :string}
   :require [:template :template-version]
   :spec {:template {:desc "Template to use"}
          :template-version {:desc "Template version"}}})

(defn get-valid-versions []
  (let [templates-dir (io/file (str project-root "/dev/cctx/templates"))]
    (->> (.listFiles templates-dir)
         (filter #(.isDirectory %))
         (map #(.getName %))
         (filter #(.startsWith % "v"))
         (into #{}))))

(defn require-schema [version]
  (requiring-resolve (symbol (str "cctx.templates." version ".schema/validate-templates"))))

(defn load-template [template-name version]
  (let [valid-versions (get-valid-versions)]
    (if-not (contains? valid-versions version)
      (throw (ex-info (str "Invalid template version. Must be one of: " (str/join ", " (sort valid-versions)))
                     {:version version
                      :valid-versions valid-versions}))
      (let [template-file (io/file (str project-root "/dev/cctx/templates/" version "/cctx-templates.edn"))
            validate-templates (require-schema version)]
        (if (.exists template-file)
          (let [templates (-> template-file slurp edn/read-string)
                validated-templates (validate-templates templates)]
            (if-let [template (get validated-templates template-name)]
              template
              (throw (ex-info "Template not found" 
                            {:template template-name
                             :version version}))))
          (throw (ex-info "Template version not found"
                        {:version version})))))))

(defn kebab->snake [s]
  (str/replace s "-" "_"))

(defn create-cctx! [cctx-name {:keys [template template-version] :as opts}]
  (let [template-data (load-template template template-version)
        snake-name (kebab->snake cctx-name)
        cctx-dir (io/file (str project-root "/dev/cctx/cctxs/" snake-name))]
    (.mkdirs cctx-dir)
    (spit (io/file cctx-dir "cctx.clj")
          (str "(ns dev.cctx.cctxs." cctx-name ".cctx)\n\n"
               "(def change-spec\n"
               (pr-str (assoc (:spec template-data) :title cctx-name))
               ")\n"))))

(defn -main [& args]
  (if (< (count args) 1)
    (println "Usage: cctx_builder.clj <name> --template <template> --template-version <version>")
    (let [opts (cli/parse-opts args cli-opts)
          _ (println "DEBUG - Args:" args)
          _ (println "DEBUG - Parsed opts:" opts)
          name (first args)]
      (create-cctx! name opts))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))