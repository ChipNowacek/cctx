(ns cctx.cctx-schema
  (:require '[malli.core :as m]
           '[malli.error :as me]))

(def cctx-schema
  [:map
   [:version string?]
   [:title string?]
   [:description string?]
   [:changes
    [:vector
     [:map
      [:id keyword?]
      [:type [:enum :remove-path :transform :add-path :replace]]
      [:description string?]
      [:path {:optional true} [:vector keyword?]]
      [:transform {:optional true} qualified-symbol?]
      [:validation {:optional true} qualified-symbol?]
      [:subtasks {:optional true}
       [:vector
        [:map
         [:id keyword?]
         [:description string?]
         [:validation {:optional true} qualified-symbol?]
         [:transform {:optional true} qualified-symbol?]]]]]]]
   [:requires [:vector string?]]
   [:rollback boolean?]
   [:dry-run boolean?]
   [:research-items {:optional true}
    [:vector
     [:map
      [:id keyword?]
      [:description string?]
      [:status [:enum :unknown :needs-validation :in-progress :validated :rejected]]
      [:impact-paths {:optional true} [:vector [:vector keyword?]]]]]]
   [:conversation {:optional true}
    [:vector
     [:map
      [:date string?]
      [:author string?]
      [:type {:optional true} keyword?]
      [:md string?]
      [:related-research {:optional true} [:vector keyword?]]]]]])

(defn validate
  "Validates a CCTX specification"
  [cctx-data]
  (let [validator (m/validator cctx-schema)]
    (validator cctx-data)))

(defn explain-errors
  "Returns human-readable validation errors for a CCTX specification"
  [cctx-data]
  (when-let [explanation (m/explain cctx-schema cctx-data)]
    (me/humanize explanation)))