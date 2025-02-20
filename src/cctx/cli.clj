(ns cctx.cli
  (:require [clojure.tools.cli :as cli]))

(def cli-options
  [["-t" "--template TEMPLATE" "Template to use"
    :parse-fn keyword]
   ["-v" "--template-version VERSION" "Template version"]
   ["-p" "--projects FILE" "Path to projects config file"]
   ["-n" "--project NAME" "Project name from config"]
   ["-o" "--overwrite-existing" "Prompt to overwrite existing CCTX if it exists"]
   ["-f" "--overwrite-force" "Overwrite existing CCTX without prompting"]
   ["-h" "--help"]])

(defn parse-opts [args]
  (cli/parse-opts args cli-options))
