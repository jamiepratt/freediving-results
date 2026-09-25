(ns freediving.deployment
  "Deployment migrations only. Never seeds, reviews or publishes records."
  (:require [freediving.observations :as observations]
            [freediving.reviews :as reviews]
            [freediving.revisions :as revisions]
            [freediving.publication :as publication]
            [freediving.public-results :as public]
            [freediving.corrections :as corrections]
            [freediving.evaluation-labels :as labels]))
(defn -main [& _]
  (let [url (System/getenv "FREEDIVING_MIGRATION_URL")]
    (when-not url (throw (ex-info "Migration URL required" {})))
    (observations/migrate! url "observations_app")
    (reviews/migrate! url "observations_app" "reviews_owner")
    (publication/migrate! url "reviews_owner")
    (public/migrate! url "reviews_owner" "reviews_public")
    (corrections/migrate! url "reviews_owner" "corrections_submit")
    (labels/migrate! url "reviews_owner" :real)
    (revisions/migrate! url "observations_app" "reviews_owner")
    (println "Applied migrations 1-8; no records published.")))
