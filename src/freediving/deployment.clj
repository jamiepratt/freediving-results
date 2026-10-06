(ns freediving.deployment
  "Deployment migrations only. Never seeds, reviews or publishes records."
  (:require [freediving.observations :as observations]
            [freediving.reviews :as reviews]
            [freediving.revisions :as revisions]
            [freediving.event-selections :as selections]
            [freediving.publication :as publication]
            [freediving.public-results :as public]
            [freediving.public-sporting :as sporting]
            [freediving.corrections :as corrections]
            [freediving.evaluation-labels :as labels]
            [freediving.batch-evidence-db :as batch-evidence]))
(defn migrate! [url]
  (when-not url (throw (ex-info "Migration URL required" {})))
  (observations/migrate! url "observations_app")
  (reviews/migrate! url "observations_app" "reviews_owner")
  (publication/migrate! url "reviews_owner")
  (public/migrate! url "reviews_owner" "reviews_public" {:defer-html-view? true})
  (corrections/migrate! url "reviews_owner" "corrections_submit")
  (labels/migrate! url "reviews_owner" :real)
  (revisions/migrate! url "observations_app" "reviews_owner")
  (public/migrate! url "reviews_owner" "reviews_public")
  (selections/migrate! url "reviews_owner")
  (batch-evidence/migrate! url "observations_app")
  (sporting/migrate! url "reviews_public")
  {:schema-version 22})
(defn -main [& _]
  (migrate! (System/getenv "FREEDIVING_MIGRATION_URL"))
  (println "Applied migrations 1-22; no records published."))
