(ns freediving.publication-test
  (:require [clojure.test :refer [deftest is use-fixtures run-tests]]
            [freediving.aida-html :as html]
            [freediving.aida-html-test :as html-fixture]
            [freediving.archive-test :as archive-fixture]
            [freediving.observations :as observations]
            [freediving.observations-test :as fixture]
            [freediving.reviews :as reviews]
            [freediving.reviews-test :as review-fixture]
            [freediving.publication :as publication]))
(def reviewer review-fixture/reviewer)
(use-fixtures :each (fn [f]
                      (fixture/sql! fixture/admin "DROP SCHEMA IF EXISTS freediving CASCADE")
                      (observations/migrate! fixture/admin "observations_app")
                      (reviews/migrate! fixture/admin "observations_app" "reviews_owner")
                      (publication/migrate! fixture/admin "reviews_owner") (f)))
(defn sample
  ([] (sample identity))
  ([transform]
   (let [{:keys [root artifact]} (fixture/synthetic 1 "publication-synthetic/1")
         artifact (transform (update artifact :candidates #(mapv (fn [p] (assoc-in p [:parsed :discipline] "CWT")) %)))]
     (fixture/publish! {:root root :artifact artifact})
     (observations/import! fixture/app root (:job-id artifact))
     {:job-id (:job-id artifact) :ordinal 0})))
(defn request [t id]
  (merge t (select-keys (publication/diagnose reviewer t) [:review-revision :policy-version :observation])
         {:id id :base-revision 0 :action :validate :actor "validator" :reason "Checked synthetic source"
          :evidence [{:page 1 :line 1}] :attestations {:source-visual-accuracy true :no-unresolved-substantive-errors true}}))
(deftest explicit-validation-is-separate-from-identity-and-raw-flags
  (let [t (sample) original (observations/inspect fixture/app (:job-id t))]
    (is (:ready? (publication/diagnose reviewer t)))
    (is (false? (:eligible? (publication/diagnose reviewer t))))
    (let [r (request t "v1") d (publication/decide! reviewer r)]
      (is (:eligible? (publication/diagnose reviewer t)))
      (is (= d (publication/decide! reviewer r)))
      (is (= {:outcome :unknown} (:identity (reviews/effective reviewer t)))))
    (is (= (seq (:artifact-bytes original)) (seq (:artifact-bytes (observations/inspect fixture/app (:job-id t))))))
    (is (= :blocked (get-in (observations/inspect fixture/app (:job-id t)) [:artifact :publication :status])))))
(defn -main [& _] (let [r (run-tests 'freediving.publication-test)] (shutdown-agents) (when (pos? (+ (:fail r) (:error r))) (System/exit 1))))

(deftest source-specific-performance-and-explicit-status
  (doseq [fields [{:final-depth 83} {:final-distance 101} {:realized-distance 101} {:status "DQ"}]]
    (fixture/sql! fixture/admin "DROP SCHEMA freediving CASCADE")
    (observations/migrate! fixture/admin "observations_app")
    (reviews/migrate! fixture/admin "observations_app" "reviews_owner")
    (publication/migrate! fixture/admin "reviews_owner")
    (let [t (sample #(update-in % [:candidates 0 :parsed] (fn [p] (merge (dissoc p :performance) fields))))]
      (is (:ready? (publication/diagnose reviewer t))))))

(deftest time-notation-with-unknown-units-remains-publishable
  (let [t (sample #(-> % (update-in [:candidates 0 :parsed] dissoc :performance)
                       (assoc-in [:candidates 0 :parsed :final-time] {:components [5 12] :fraction nil :fraction-digits 0 :notation :colon-separated})
                       (assoc-in [:candidates 0 :unresolved-reasons] [:time-unit-not-explicit])))]
    (is (:ready? (publication/diagnose reviewer t)))))
(deftest migration-requires-review-authority
  (is (thrown? Exception (publication/migrate! fixture/admin "observations_app")))
  (is (thrown? Exception (publication/migrate! fixture/admin "reviews_public"))))

(deftest validation-evidence-must-include-target-row
  (let [t (sample)]
    (is (thrown? Exception (publication/decide! reviewer (assoc (request t "wrong-line") :evidence [{:page 1 :line 2}]))))))

(deftest revocation-review-changes-and-policy-activation-invalidate
  (let [t (sample) r (request t "validate")]
    (publication/decide! reviewer r)
    (reviews/propose! fixture/app (review-fixture/proposal t "identity"))
    (reviews/decide! reviewer {:id "approve" :proposal-id "identity" :action :approve :base-revision 0 :actor "owner" :reason "Synthetic"})
    (is (false? (:eligible? (publication/diagnose reviewer t))))
    (is (some #{:review-revision-changed} (:reasons (publication/diagnose reviewer t))))
    (is (thrown-with-msg? Exception #"Stale review" (publication/decide! reviewer (assoc r :id "stale" :base-revision 1))))
    (publication/decide! reviewer (assoc (request t "new") :base-revision 1))
    (is (:eligible? (publication/diagnose reviewer t)))
    (let [revoked (assoc (request t "revoke") :action :revoke :base-revision 2 :attestations {})]
      (publication/decide! reviewer revoked)
      (is (false? (:eligible? (publication/diagnose reviewer t))))
      (is (= 3 (count (publication/history reviewer t))))
      (is (= (last (publication/history reviewer t)) (publication/decide! reviewer revoked))))
    (publication/decide! reviewer (assoc (request t "again") :base-revision 3))
    (is (thrown? Exception (publication/activate-policy! reviewer "extraction-publication/2" "Forbidden")))
    (publication/activate-policy! fixture/admin "extraction-publication/2" "New required evidence policy")
    (is (false? (:eligible? (publication/diagnose reviewer t))))
    (is (false? (:ready? (publication/diagnose reviewer t))))
    (is (thrown? Exception (publication/activate-policy! fixture/admin publication/current-policy "Cannot resurrect old validation")))
    (is (thrown? Exception (publication/decide! reviewer (assoc (request t "unsupported") :base-revision 4))))))
(deftest evidence-provenance-attestation-and-authority-fail-closed
  (let [t (sample) r (request t "v")]
    (doseq [bad [(assoc r :evidence []) (assoc r :evidence [{:page 99 :line 1}])
                 (assoc-in r [:observation :artifact-sha256] "forged") (assoc r :attestations {})
                 (assoc r :policy-version "old") (assoc r :actor "") (assoc r :unexpected true)]]
      (is (thrown? Exception (publication/decide! reviewer bad))))
    (is (thrown? Exception (publication/decide! fixture/app r)))
    (is (thrown? Exception (publication/decide! (System/getenv "FREEDIVING_TEST_PUBLIC_URL") r)))
    (publication/decide! reviewer r)
    (doseq [url [reviewer fixture/app (System/getenv "FREEDIVING_TEST_PUBLIC_URL")]
            op ["UPDATE freediving.publication_decisions SET id=id" "DELETE FROM freediving.publication_decisions" "TRUNCATE freediving.publication_decisions"]]
      (is (thrown? Exception (fixture/sql! url op))))
    (is (thrown? Exception (publication/history (System/getenv "FREEDIVING_TEST_PUBLIC_URL") t)))
    (is (thrown? Exception (publication/decide! fixture/app r)))
    (is (thrown-with-msg? Exception #"idempotency" (publication/decide! reviewer (assoc r :reason "changed"))))))
(deftest concurrent-decisions-and-retries-serialize
  (let [t (sample) r (request t "v") gate (promise)
        fs (mapv (fn [_] (future @gate (publication/decide! reviewer r))) (range 3))]
    (deliver gate true)
    (is (apply = (mapv deref fs)))
    (is (= 1 (count (publication/history reviewer t))))
    (let [gate (promise) fs (mapv (fn [id] (future @gate (try (publication/decide! reviewer (assoc r :id id :base-revision 1 :action :revoke))
                                                              (catch Exception _ :conflict)))) ["r1" "r2"])]
      (deliver gate true)
      (is (= 1 (count (filter #{:conflict} (mapv deref fs)))))
      (is (= 2 (count (publication/history reviewer t)))))))
(deftest unknown-and-substantive-extraction-errors-block
  (doseq [change [#(assoc-in % [:candidates 0 :unresolved-reasons] [:new-unknown-error])
                  #(assoc-in % [:candidates 0 :unresolved-reasons] [:final-percent-header-ambiguous])
                  #(assoc-in % [:candidates 0 :fields :final-time] {:status :invalid :reason :invalid-time-syntax})
                  #(assoc-in % [:candidates 0 :parsed :discipline] nil)
                  #(assoc-in % [:candidates 0 :parsed :performance] "garbage")
                  #(assoc-in % [:candidates 0 :parsed :performance] ##NaN)
                  #(assoc-in % [:candidates 0 :parsed :performance] ##Inf)
                  #(-> % (assoc-in [:candidates 0 :parse-status] :unparsed) (assoc-in [:candidates 0 :parsed] nil))]]
    (fixture/sql! fixture/admin "DROP SCHEMA freediving CASCADE")
    (observations/migrate! fixture/admin "observations_app")
    (reviews/migrate! fixture/admin "observations_app" "reviews_owner")
    (publication/migrate! fixture/admin "reviews_owner")
    (let [t (sample change)]
      (is (false? (:ready? (publication/diagnose reviewer t))))
      (is (thrown? Exception (publication/decide! reviewer (request t "blocked")))))))
(deftest migration-checksum-and-nonresult-records
  (is (= {:schema-version 3} (publication/migrate! fixture/admin "reviews_owner")))
  (let [t (sample)]
    (fixture/sql! fixture/admin (str "INSERT INTO freediving.observations(job_id,ordinal,candidate_id,kind,classification_reason,payload_edn) VALUES('" (:job-id t) "',2,'fragment','fragment','synthetic','{}')"))
    (let [d (publication/diagnose reviewer (assoc t :ordinal 2))]
      (is (some #{:not-result-row} (:reasons d)))
      (is (false? (:ready? d)))))
  (fixture/sql! fixture/admin "UPDATE freediving.schema_migrations SET sha256='tampered' WHERE version=3")
  (is (thrown-with-msg? Exception #"checksum" (publication/migrate! fixture/admin "reviews_owner"))))

(deftest changed-extraction-version-never-inherits-validation
  (let [t (sample) r (request t "first")
        {:keys [root artifact]} (fixture/synthetic 1 "publication-synthetic/2")
        artifact (update artifact :candidates #(mapv (fn [p] (assoc-in p [:parsed :discipline] "CWT")) %))
        other {:job-id (:job-id artifact) :ordinal 0}]
    (publication/decide! reviewer r)
    (fixture/publish! {:root root :artifact artifact})
    (observations/import! fixture/app root (:job-id artifact))
    (is (:ready? (publication/diagnose reviewer other)))
    (is (false? (:eligible? (publication/diagnose reviewer other))))
    (is (thrown-with-msg? Exception #"provenance" (publication/decide! reviewer (merge r other {:id "copied"}))))
    (is (= [true false] (mapv :eligible? (publication/diagnose-many reviewer [t other]))))))
(deftest concurrent-review-never-leaves-stale-validation-eligible
  (let [t (sample) r (request t "validate") p (review-fixture/proposal t "proposal")]
    (reviews/propose! fixture/app p)
    (let [gate (promise)
          validation (future @gate (try (publication/decide! reviewer r) (catch Exception _ :stale)))
          review (future @gate (reviews/decide! reviewer {:id "approved" :proposal-id "proposal" :base-revision 0 :action :approve :actor "owner" :reason "Synthetic"}))]
      (deliver gate true) @validation @review
      (is (= 1 (:review-revision (publication/diagnose reviewer t))))
      (is (false? (:eligible? (publication/diagnose reviewer t)))))
    (publication/decide! reviewer (assoc (request t "after") :base-revision (:revision (publication/diagnose reviewer t))))
    (is (:eligible? (publication/diagnose reviewer t)))
    (reviews/decide! reviewer {:id "reversed" :event-id "approved" :base-revision 1 :action :reverse :actor "owner" :reason "Reconsider"})
    (is (false? (:eligible? (publication/diagnose reviewer t))))))

(deftest concurrent-policy-activation-never-leaves-old-validation-eligible
  (let [t (sample) r (request t "validate") gate (promise)
        validation (future @gate (try (publication/decide! reviewer r) (catch Exception _ :inactive)))
        activation (future @gate (publication/activate-policy! fixture/admin "extraction-publication/2" "Synthetic policy transition"))]
    (deliver gate true) @validation @activation
    (is (false? (:eligible? (publication/diagnose reviewer t))))
    (is (= "extraction-publication/2" (:active-policy-version (publication/diagnose reviewer t))))))

(deftest html-observations-cannot-enter-pdf-publication-review
  (let [dir (archive-fixture/workspace) root (str dir "/archive")
        hash (html-fixture/register-html root (str dir "/source.html") (html-fixture/document html-fixture/cells))
        job (:job-id (html/extract! root hash {:actor "synthetic" :config {}}))
        target {:job-id job :ordinal 0}]
    (observations/import! fixture/app root job)
    (let [diagnosis (publication/diagnose reviewer target)]
      (is (false? (:ready? diagnosis)))
      (is (false? (:eligible? diagnosis)))
      (is (some #{[:unresolved-extraction-error :html-review-not-supported]} (:reasons diagnosis))))
    (is (thrown? Exception (publication/decide! reviewer (request target "html-not-pdf"))))))
