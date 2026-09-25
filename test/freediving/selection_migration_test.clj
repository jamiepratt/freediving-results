(ns freediving.selection-migration-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.java.shell :as shell]
            [freediving.observations :as observations]
            [freediving.observations-test :as fixture]
            [freediving.reviews :as reviews]
            [freediving.publication :as publication]
            [freediving.public-results :as public]
            [freediving.public-results-test :as public-fixture]
            [freediving.revisions :as revisions]
            [freediving.revisions-test :as revision-fixture]
            [freediving.event-selections :as selections]
            [freediving.corrections :as corrections]))
(def reviewer (System/getenv "FREEDIVING_TEST_REVIEW_URL"))
(def reader-url (System/getenv "FREEDIVING_TEST_PUBLIC_URL"))
(use-fixtures :each
  (fn [f]
    (when-not fixture/admin (throw (ex-info "Isolated PostgreSQL required" {})))
    (fixture/sql! fixture/admin "DROP SCHEMA IF EXISTS freediving CASCADE")
    (observations/migrate! fixture/admin "observations_app")
    (reviews/migrate! fixture/admin "observations_app" "reviews_owner")
    (publication/migrate! fixture/admin "reviews_owner")
    (revisions/migrate! fixture/admin "observations_app" "reviews_owner")
    (public/migrate! fixture/admin "reviews_owner" "reviews_public")
    (f)))
(deftest corrections-installed-after-selection-preserve-authority-invalidation
  (selections/migrate! fixture/admin "reviews_owner")
  (corrections/migrate! fixture/admin "reviews_owner" "corrections_submit")
  (let [target (public-fixture/sample)
        a (revision-fixture/sample "migration-old/1" revision-fixture/scope)
        b (revision-fixture/sample "migration-new/1" revision-fixture/scope)]
    (public-fixture/validate! target "migration-validation")
    (public/refresh! reviewer)
    (is (= 1 (count (public/results reader-url))))
    (revisions/propose! reviewer (revision-fixture/proposal a b "migration-link"))
    (is (= [] (public/results reader-url)) "Pending relationship evidence also invalidates stale cache")
    (public/refresh! reviewer)
    (revisions/decide! reviewer {:id "migration-confirm" :proposal-id "migration-link" :base-revision 0
                                 :action :confirm :actor "synthetic-reviewer" :reason "Synthetic migration check"})
    (is (= [] (public/results reader-url)) "Changed relationship authority must hide stale cache after migration 5")
    (public/refresh! reviewer)
    (is (= 1 (count (public/results reader-url))))))
(deftest corrections-installed-after-html-preserve-policy-two-publication
  (public/activate-html-policy! fixture/admin "hide-existing-public-results" "Synthetic policy migration checkpoint")
  (corrections/migrate! fixture/admin "reviews_owner" "corrections_submit")
  (let [target (public-fixture/sample) d (publication/diagnose reviewer target)]
    (publication/decide! reviewer
                         (merge target {:id "policy-two" :action :validate :base-revision (:revision d)
                                        :review-revision (:review-revision d) :policy-version "extraction-publication/2"
                                        :observation (:observation d) :evidence [{:page 1 :line 1}]
                                        :actor "synthetic-reviewer" :reason "Synthetic source inspected"
                                        :attestations {:source-visual-accuracy true :no-unresolved-substantive-errors true}}))
    (public/refresh! reviewer)
    (is (= 1 (count (public/results reader-url))))
    (is (true? (corrections/assert-submitter! (System/getenv "FREEDIVING_TEST_SUBMIT_URL"))))))
(deftest repeated-full-deployment-preserves-selection-coverage-and-privileges
  (selections/migrate! fixture/admin "reviews_owner")
  (let [a (revision-fixture/sample "coverage-migration/1" revision-fixture/scope)
        scope (select-keys revision-fixture/scope revisions/event-fields)]
    (selections/select! reviewer {:id "migration-gap" :actor "synthetic-reviewer" :reason "Explicit source gap"
                                  :base (selections/snapshot reviewer) :event-scope scope :members [a] :selected []
                                  :coverage {:completeness :partial :gaps ["Synthetic final-session gap"]}})
    (doseq [_ (range 2)]
      (let [run (shell/sh "clojure" "-M" "-m" "freediving.deployment"
                          :env (assoc (into {} (System/getenv)) "FREEDIVING_MIGRATION_URL" fixture/admin))]
        (is (zero? (:exit run)) (:err run)))
      (is (= ["Synthetic final-session gap"] (get-in (public/coverage reader-url) [:events 0 :gaps])))
      (is (= [] (public/results reader-url)))
      (is (thrown? java.sql.SQLException (fixture/sql! reader-url "SELECT * FROM freediving.event_selections")))
      (is (thrown? java.sql.SQLException (fixture/sql! reader-url "SELECT freediving.lock_selection_authority()")))
      (is (true? (corrections/assert-submitter! (System/getenv "FREEDIVING_TEST_SUBMIT_URL")))))))
(defn -main [& _]
  (let [r (clojure.test/run-tests 'freediving.selection-migration-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail r) (:error r))) (System/exit 1))))
