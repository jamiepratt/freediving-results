(ns freediving.public-results-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.java.shell :as shell]
            [clojure.java.io :as io]
            [freediving.observations :as observations]
            [freediving.aida-html :as html]
            [freediving.aida-html-test :as html-fixture]
            [freediving.archive-test :as archive-fixture]
            [freediving.observations-test :as fixture]
            [freediving.reviews :as reviews]
            [freediving.reviews-test :as review-fixture]
            [freediving.publication :as publication]
            [freediving.public-results :as public]))
(deftest deep-public-field-allowlist
  (let [fields {:source-name "Original" :representation "ABC" :private "SECRET"
                :final-time {:minutes 2 :seconds 10 :raw "2:10" :prompt "SECRET"}
                :performance {:private "SECRET"}}]
    (is (= {:source-name "Original" :representation "ABC" :final-time {:minutes 2 :seconds 10 :raw "2:10"}}
           (public/public-fields fields)))))
(defn -main [& _]
  (let [r (clojure.test/run-tests 'freediving.public-results-test)]
    (shutdown-agents) (when (pos? (+ (:fail r) (:error r))) (System/exit 1))))
(deftest exact-time-syntax-and-depth-are-preserved
  (is (= {:final-depth 80 :final-distance 150 :final-time {:components [2 10] :fraction "01" :fraction-digits 2 :notation :colon-separated}}
         (public/public-fields {:final-depth 80 :final-distance 150 :final-time {:components [2 10] :fraction "01" :fraction-digits 2 :notation :colon-separated :secret "hidden"}}))))

(def reviewer (System/getenv "FREEDIVING_TEST_REVIEW_URL"))
(def reader-url (System/getenv "FREEDIVING_TEST_PUBLIC_URL"))
(use-fixtures :each (fn [f]
                      (fixture/sql! fixture/admin "DROP SCHEMA IF EXISTS freediving CASCADE")
                      (observations/migrate! fixture/admin "observations_app")
                      (reviews/migrate! fixture/admin "observations_app" "reviews_owner")
                      (publication/migrate! fixture/admin "reviews_owner")
                      (public/migrate! fixture/admin "reviews_owner" "reviews_public") (f)))
(defn sample []
  (let [{:keys [root artifact]} (fixture/synthetic 1 "projection-synthetic/1")
        artifact (update artifact :candidates #(mapv (fn [c] (assoc-in c [:parsed :discipline] "CWT")) %))]
    (fixture/publish! {:root root :artifact artifact})
    (observations/import! fixture/app root (:job-id artifact))
    {:job-id (:job-id artifact) :ordinal 0}))
(defn validate! [target id & [action]]
  (let [d (publication/diagnose reviewer target)]
    (publication/decide! reviewer
                         (merge target {:id id :action (or action :validate) :base-revision (:revision d)
                                        :review-revision (:review-revision d) :policy-version publication/current-policy
                                        :observation (:observation d) :evidence [{:page 1 :line (inc (:ordinal target))}] :actor "PRIVATE-ACTOR"
                                        :attestations {:source-visual-accuracy true :no-unresolved-substantive-errors true}
                                        :reason "Synthetic validation"}))))
(defn decide! [id action subject base]
  (reviews/decide! reviewer (merge {:id id :action action :base-revision base :actor "PRIVATE-ACTOR" :reason "Checked source"}
                                   {(if (= action :reverse) :event-id :proposal-id) subject})))
(deftest eligible-source-name-without-identity-is-public-but-proposals-are-private
  (let [t (sample) before (observations/inspect fixture/app (:job-id t))]
    (is (= [] (public/results reader-url)))
    (validate! t "v1")
    (reviews/propose! fixture/app (assoc (review-fixture/proposal t "PRIVATE-PROPOSAL") :reason "PRIVATE-REASON"))
    (is (= {:refreshed 1} (public/refresh! reviewer)))
    (let [rows (public/search-source-name reader-url "Éxample") row (first rows) printed (pr-str rows)]
      (is (= 1 (count rows)))
      (is (= {:status :unresolved} (:identity row)))
      (is (= "001" (get-in row [:raw-values :performance])))
      (is (= "https://example.org/results.pdf" (get-in row [:citations 0 :final-url])))
      (is (= row (public/result reader-url (:result-id row))))
      (is (= nil (public/result reader-url "private-or-absent")))
      (is (not (re-find #"PRIVATE|artifact_bytes|artifact-path|future/unknown|db-role|body_edn|request" printed)))
      (is (= 1 (:results (public/coverage reader-url)))))
    (is (= (:observations before) (:observations (observations/inspect fixture/app (:job-id t)))))
    (doseq [table ["observations" "extractions" "review_proposals" "review_decisions" "publication_decisions" "public_projection_cache"]]
      (is (thrown? java.sql.SQLException (fixture/sql! reader-url (str "SELECT * FROM freediving." table)))))
    (is (thrown? java.sql.SQLException (fixture/sql! reader-url "DELETE FROM freediving.public_results")))
    (is (thrown? Exception (public/refresh! reader-url)))))
(deftest correction-reversal-revocation-and-original-source-search
  (let [t (sample) p (merge (review-fixture/proposal t "correct")
                            {:field :source-name :category :name-normalization :before "Éxample" :after "Example"})]
    (validate! t "v1") (public/refresh! reviewer)
    (reviews/propose! fixture/app p) (decide! "a1" :approve "correct" 0)
    (is (= [] (public/results reader-url)))
    (is (= {:refreshed 0} (public/refresh! reviewer)))
    (validate! t "v2") (public/refresh! reviewer)
    (let [row (first (public/search-source-name reader-url "Éxample"))]
      (is (= "Example" (get-in row [:effective :source-name])))
      (is (= "Éxample" (get-in row [:original :source-name])))
      (is (= true (get-in row [:correction-audit 0 :effective?]))))
    (decide! "r1" :reverse "a1" 1)
    (is (= [] (public/results reader-url)))
    (validate! t "v3") (public/refresh! reviewer)
    (let [row (first (public/results reader-url))]
      (is (= "Éxample" (get-in row [:effective :source-name])))
      (is (= [:approve :reverse] (mapv :action (:correction-audit row))))
      (is (every? false? (map :effective? (:correction-audit row))))
      (is (= "Example" (get-in row [:correction-audit 1 :before])))
      (is (= "Éxample" (get-in row [:correction-audit 1 :after])))
      (is (= (get-in row [:correction-audit 0 :event-id]) (get-in row [:correction-audit 1 :reverses]))))
    (validate! t "revoke" :revoke)
    (is (= [] (public/results reader-url)))
    (is (= 0 (:results (public/coverage reader-url))))))
(deftest private-anchor-never-leaks-through-identity-or-counts
  (let [t (sample) ref (review-fixture/cross-reference)
        p (assoc (review-fixture/proposal t "anchor") :identity-target ref :evidence [ref]
                 :after {:outcome :matched :identity-id (str "local-observation:" (:job-id ref) ":0")})]
    (reviews/propose! fixture/app p) (decide! "a1" :approve "anchor" 0)
    (validate! t "v1") (public/refresh! reviewer)
    (let [row (first (public/results reader-url))]
      (is (= {:status :unresolved} (:identity row)))
      (is (not (.contains (pr-str row) (:job-id ref))))
      (is (= [] (public/athlete-history reader-url (get-in p [:after :identity-id]))))
      (is (= 0 (:approved_identities (public/coverage reader-url)))))))
(deftest approved-public-history-and-reversal
  (let [t (sample)]
    (reviews/propose! fixture/app (review-fixture/proposal t "identity"))
    (decide! "a1" :approve "identity" 0)
    (validate! t "v1") (public/refresh! reviewer)
    (let [row (first (public/results reader-url)) id (get-in row [:identity :id])]
      (is (= :approved (get-in row [:identity :status])))
      (is (= [row] (public/athlete-history reader-url id)))
      (decide! "r1" :reverse "a1" 1)
      (is (= [] (public/athlete-history reader-url id)))
      (validate! t "v2") (public/refresh! reviewer)
      (is (= [] (public/athlete-history reader-url id)))
      (is (= {:status :unresolved} (:identity (first (public/results reader-url))))))))
(deftest approved-local-match-includes-public-anchor-and-reversal-restores-both
  (let [a (sample) b (assoc a :ordinal 1)
        inspection (observations/inspect fixture/app (:job-id a))
        anchor (second (:observations inspection))
        ref {:job-id (:job-id b) :ordinal 1 :candidate-id (:candidate_id anchor)
             :source-sha256 (get-in inspection [:artifact :source-sha256])
             :artifact-sha256 (.formatHex (java.util.HexFormat/of)
                                          (.digest (java.security.MessageDigest/getInstance "SHA-256")
                                                   ^bytes (:artifact-bytes inspection)))
             :page 1 :line 2}
        p (assoc (review-fixture/proposal a "linked")
                 :identity-target ref :evidence [{:page 1 :line 1} ref]
                 :after {:outcome :matched :identity-id (str "local-observation:" (:job-id b) ":1")})]
    (reviews/propose! fixture/app p)
    (decide! "approve-link" :approve "linked" 0)
    (validate! a "validate-a")
    (validate! b "validate-b")
    (is (= {:refreshed 2} (public/refresh! reviewer)))
    (let [rows (public/results reader-url) id (some #(get-in % [:identity :id]) rows)]
      (is (= 2 (count rows)))
      (is (some? id))
      (is (= 2 (count (public/athlete-history reader-url id))))
      (is (= #{id} (set (map #(get-in % [:identity :id]) rows)))))
    (decide! "reverse-link" :reverse "approve-link" 1)
    (validate! a "revalidate-a")
    (public/refresh! reviewer)
    (is (every? #(= {:status :unresolved} (:identity %)) (public/results reader-url)))
    (is (= 0 (:approved_identities (public/coverage reader-url))))))
(deftest migration-restores-least-privilege-and-rejects-unsafe-roles
  (fixture/sql! fixture/admin "GRANT SELECT ON ALL TABLES IN SCHEMA freediving TO reviews_public")
  (public/migrate! fixture/admin "reviews_owner" "reviews_public")
  (is (thrown? java.sql.SQLException (fixture/sql! reader-url "SELECT * FROM freediving.extractions")))
  (is (thrown? Exception (public/migrate! fixture/admin "reviews_owner" "reviews_owner")))
  (fixture/sql! fixture/admin "GRANT observations_app TO reviews_public")
  (try (is (thrown? Exception (public/migrate! fixture/admin "reviews_owner" "reviews_public")))
       (finally (fixture/sql! fixture/admin "REVOKE observations_app FROM reviews_public"))))
(deftest cli-is-strict-and-does-not-echo-private-database-errors
  (let [r (shell/sh "java" "-cp" (System/getProperty "java.class.path") "clojure.main" "-m" "freediving.public-results" "list" "PRIVATE")]
    (is (= 1 (:exit r)))
    (is (= "Public results operation failed\n" (:err r)))))
(deftest correction-with-private-only-evidence-is-withheld
  (let [t (sample) ref (review-fixture/cross-reference)
        p (merge (review-fixture/proposal t "private-evidence")
                 {:field :source-name :category :name-normalization :before "Éxample" :after "Corrected"
                  :evidence [ref]})]
    (reviews/propose! fixture/app p) (decide! "a1" :approve "private-evidence" 0)
    (validate! t "v1")
    (is (= {:refreshed 0} (public/refresh! reviewer)))
    (is (= [] (public/results reader-url)))))
(deftest caller-print-settings-cannot-corrupt-projection
  (let [t (sample)]
    (validate! t "v1")
    (binding [*print-length* 1 *print-level* 1] (public/refresh! reviewer))
    (is (= "Éxample" (get-in (first (public/results reader-url)) [:original :source-name])))))
(deftest transitive-private-evidence-cannot-create-public-dangling-references
  (let [a (sample) b (assoc a :ordinal 1) c (review-fixture/cross-reference)
        b-ref (merge (:observation (publication/diagnose reviewer b)) {:page 1 :line 2})]
    (doseq [[target id ref] [[b "b-correction" c] [a "a-correction" b-ref]]]
      (reviews/propose! fixture/app
                        (merge (review-fixture/proposal target id)
                               {:field :source-name :category :name-normalization :before "Éxample" :after "Corrected" :evidence [ref]}))
      (decide! (str "approve-" id) :approve id 0)
      (validate! target (str "validate-" id)))
    (is (= {:refreshed 0} (public/refresh! reviewer)))
    (is (= [] (public/results reader-url)))))
(deftest migration-preserves-pdf-publication-until-explicit-activation
  (let [t (sample)]
    (validate! t "v1") (public/refresh! reviewer)
    (is (= 1 (count (public/results reader-url))))
    (public/migrate! fixture/admin "reviews_owner" "reviews_public")
    (is (= 1 (count (public/results reader-url))))
    (publication/activate-policy! fixture/admin "extraction-publication/2" "Synthetic policy change")
    (is (= [] (public/results reader-url)))
    (is (= {:refreshed 0} (public/refresh! reviewer)))
    (is (thrown? Exception (publication/activate-policy! reader-url "forged" "Denied")))))
(deftest unrelated-review-invalidates-all-caches-conservatively
  (let [t (sample) other (assoc t :ordinal 1)]
    (validate! t "v1") (public/refresh! reviewer)
    (reviews/propose! fixture/app (review-fixture/proposal other "other"))
    (decide! "reject-other" :reject "other" 0)
    (is (= [] (public/results reader-url)))
    (is (= {:refreshed 1} (public/refresh! reviewer)))
    (is (= 1 (count (public/results reader-url))))))
(deftest duplicate-and-concurrent-refresh-cannot-publish-partial-snapshots
  (let [t (sample)]
    (validate! t "v1")
    (is (= (public/refresh! reviewer) (public/refresh! reviewer)))
    (let [gate (promise) workers (mapv (fn [_] (future @gate (try (public/refresh! reviewer)
                                                                  (catch java.sql.SQLException e
                                                                    (if (= "40001" (.getSQLState e)) :retry (throw e)))))) (range 3))]
      (deliver gate true)
      (doseq [r (mapv deref workers)] (is (or (= :retry r) (= {:refreshed 1} r))))
      (is (= {:refreshed 1} (public/refresh! reviewer)))
      (is (= 1 (count (public/results reader-url)))))))

(deftest guarded-html-activation-requires-owner-checksum-and-acknowledgement
  (is (thrown? Exception (public/activate-html-policy! fixture/admin "" "reason")))
  (is (thrown? Exception (public/activate-html-policy! reader-url "hide-existing-public-results" "reason")))
  (is (thrown? Exception (public/activate-html-policy! reviewer "hide-existing-public-results" "reason")))
  (is (= {:policy-version "extraction-publication/2"}
         (public/activate-html-policy! fixture/admin "hide-existing-public-results" "Synthetic activation")))
  (is (thrown? Exception (public/activate-html-policy! fixture/admin "hide-existing-public-results" "already active"))))

(deftest html-source-values-and-heading-are-sanitized-without-invention
  (let [payload {:parsed {:event-name "AIDA generic title" :source-name "Synthetic"}
                 :raw {:html "PRIVATE" :fields {"Diver" "Synthetic" "RP" "90 m" "Card" "WHITE" "Remarks" "NR" "secret" "PRIVATE"}}}
        result (public/html-public-fields payload {:event-name "Synthetic Championship" :event-date "2025-06-28"})]
    (is (= "AIDA generic title" (get-in result [:original :document-title])))
    (is (= "Synthetic Championship" (get-in result [:original :event-name])))
    (is (= {:source-name "Synthetic" :realised-performance "90 m" :card "WHITE" :remarks "NR"} (:raw-values result)))
    (is (not (.contains (pr-str result) "PRIVATE")))
    (is (nil? (get-in (public/html-public-fields payload {}) [:original :event-name])))))

(deftest additive-view-upgrade-retains-existing-pdf-cache-and-detects-checksum-drift
  (let [t (sample)]
    (validate! t "v1") (public/refresh! reviewer)
    ;; Reproduce a deployed v4 view before the additive migration.
    (fixture/sql! fixture/admin "DROP VIEW freediving.public_results")
    (fixture/sql! fixture/admin
                  (subs (slurp (io/resource "migrations/004-public-results.sql"))
                        (.indexOf (slurp (io/resource "migrations/004-public-results.sql")) "CREATE VIEW")))
    (fixture/sql! fixture/admin "DELETE FROM freediving.schema_migrations WHERE version=9")
    (is (= {:schema-version 9} (public/migrate! fixture/admin "reviews_owner" "reviews_public")))
    (is (= 1 (count (public/results reader-url))))
    (is (= {:schema-version 9} (public/migrate! fixture/admin "reviews_owner" "reviews_public")))
    (fixture/sql! fixture/admin "UPDATE freediving.schema_migrations SET sha256=repeat('0',64) WHERE version=9")
    (is (thrown? Exception (public/migrate! fixture/admin "reviews_owner" "reviews_public")))
    (is (thrown? Exception (public/activate-html-policy! fixture/admin "hide-existing-public-results" "checksum drift")))
    (is (= 1 (count (public/results reader-url))))))

(deftest html-and-pdf-validation-coexist-with-exact-public-citation-and-revocation
  (let [dir (archive-fixture/workspace) root (str dir "/archive")
        source (str "<h1>Synthetic Championship</h1>" (html-fixture/document (assoc html-fixture/cells 7 "90 m" 10 "")))
        digest (html-fixture/register-html root (str dir "/source.html") source)
        receipt (html/extract! root digest {:actor "PRIVATE" :config {}})
        t {:job-id (:job-id receipt) :ordinal 0} pdf (sample)]
    (observations/import! fixture/app root (:job-id t))
    (public/activate-html-policy! fixture/admin "hide-existing-public-results" "Synthetic owner checkpoint")
    (doseq [[target id evidence] [[t "html" [{:table 1 :row 2}]] [pdf "pdf" [{:page 1 :line 1}]]]]
      (let [d (publication/diagnose reviewer target)]
        (is (empty? (:blockers d)) (pr-str d))
        (publication/decide! reviewer
                             (merge target {:id id :action :validate :base-revision (:revision d)
                                            :review-revision (:review-revision d) :policy-version "extraction-publication/2"
                                            :observation (:observation d) :evidence evidence :actor "PRIVATE"
                                            :attestations {:source-visual-accuracy true :no-unresolved-substantive-errors true}
                                            :reason "Synthetic explicit source validation"}))))
    (is (= {:refreshed 2} (public/refresh! reviewer)))
    (let [row (first (public/search-source-name reader-url "ÉXAMPLE  & Person"))]
      (is (= {:table 1 :row 2} (:source-position row)))
      (is (= "Synthetic Championship" (get-in row [:effective :event-name])))
      (is (= "Synthetic AIDA event" (get-in row [:original :document-title])))
      (is (= "90 m" (get-in row [:raw-values :realised-performance])))
      (is (= "2025-06-28" (get-in row [:citations 0 :event-date])))
      (is (= digest (get-in row [:citations 0 :source-sha256])))
      (is (= {:status :unresolved} (:identity row)))
      (is (not (re-find #"PRIVATE|<table|<h1>|source.html|archive/" (pr-str row)))))
    (reviews/propose! fixture/app (assoc (review-fixture/proposal t "html-identity") :evidence [{:table 1 :row 2}]))
    (decide! "approve-html-identity" :approve "html-identity" 0)
    (is (= [] (public/results reader-url)))
    (let [d (publication/diagnose reviewer t)]
      (publication/decide! reviewer
                           (merge t {:id "html-with-identity" :action :validate :base-revision (:revision d)
                                     :review-revision (:review-revision d) :policy-version "extraction-publication/2"
                                     :observation (:observation d) :evidence [{:table 1 :row 2}] :actor "PRIVATE"
                                     :attestations {:source-visual-accuracy true :no-unresolved-substantive-errors true}
                                     :reason "Synthetic explicit revalidation"})))
    (public/refresh! reviewer)
    (let [row (first (public/search-source-name reader-url "ÉXAMPLE  & Person")) id (get-in row [:identity :id])]
      (is (= [row] (public/athlete-history reader-url id)))
      (decide! "reverse-html-identity" :reverse "approve-html-identity" 1)
      (is (= [] (public/athlete-history reader-url id))))
    (let [d (publication/diagnose reviewer t)]
      (publication/decide! reviewer
                           (merge t {:id "revoke-html" :action :revoke :base-revision (:revision d)
                                     :review-revision (:review-revision d) :policy-version "extraction-publication/2"
                                     :observation (:observation d) :evidence [{:table 1 :row 2}] :actor "PRIVATE"
                                     :attestations {} :reason "Synthetic revoked"})))
    (is (= [] (public/search-source-name reader-url "ÉXAMPLE  & Person")))
    (is (= {:refreshed 1} (public/refresh! reviewer)))
    (is (= 1 (count (public/results reader-url))))))
