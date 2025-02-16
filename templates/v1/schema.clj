(ns cctx.templates.v1.schema
  (:require [malli.core :as m]
            [malli.error :as me]))

(def TemplateSchema
  [:map-of :keyword
   [:map
    [:name string?]
    [:desc string?]
    [:spec [:map
           [:title string?]
           [:description string?]
           [:changes [:sequential
                     [:map
                      [:id keyword?]
                      [:type keyword?]
                      [:description {:optional true} string?]
                      [:path {:optional true} string?]
                      [:template {:optional true} string?]
                      [:executable {:optional true} boolean?]
                      [:transform {:optional true} any?]
                      [:validation {:optional true} any?]]]]
           [:requires {:optional true} [:sequential keyword?]]
           [:rollback {:optional true} boolean?]
           [:dry-run {:optional true} boolean?]]]]])

(defn validate-templates [templates]
  (if (m/validate TemplateSchema templates)
    templates
    (let [errors (me/humanize (m/explain TemplateSchema templates))]
      (throw (ex-info "Invalid templates configuration"
                     {:errors errors})))))