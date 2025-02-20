(ns cctx.config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
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
