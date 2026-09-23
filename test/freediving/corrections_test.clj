(ns freediving.corrections-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [freediving.corrections :as corrections]
            [freediving.observations-test :as fixture]
            [freediving.public-results-test :as public-fixture]
            [freediving.public-results :as public]
            [freediving.observations :as observations]
            [freediving.reviews :as reviews]
            [freediving.publication :as publication]))
(def submit-url (System/getenv "FREEDIVING_TEST_SUBMIT_URL"))
(def reviewer (System/getenv "FREEDIVING_TEST_REVIEW_URL"))
(def reader-url (System/getenv "FREEDIVING_TEST_PUBLIC_URL"))
(def client (apply str (repeat 64 "a")))
(use-fixtures :each
  (fn [f]
    (fixture/sql! fixture/admin "DROP SCHEMA IF EXISTS freediving CASCADE")
    (observations/migrate! fixture/admin "observations_app")
    (reviews/migrate! fixture/admin "observations_app" "reviews_owner")
    (publication/migrate! fixture/admin "reviews_owner")
    (public/migrate! fixture/admin "reviews_owner" "reviews_public")
    (corrections/migrate! fixture/admin "reviews_owner" "corrections_submit") (f)))
(defn request []
  (let [t (public-fixture/sample)]
    (public-fixture/validate! t "v1") (public/refresh! reviewer)
    (let [id (:result-id (first (public/results reader-url)))]
      {:id (str (random-uuid)) :result-id id :version (corrections/target-version submit-url id)
       :suggestion "Name should be Éxample 修正" :reason "Printed row differs" :evidence "https://example.org/results.pdf#page=1"})))
(deftest private-pending-request-with-stable-receipt
  (let [r (request) before (public/results reader-url) receipt (corrections/submit! submit-url r client)]
    (is (= {:id (:id r) :status :pending :duplicate false} receipt))
    (is (= (assoc receipt :duplicate true) (corrections/submit! submit-url r client)))
    (is (= (:suggestion r) (:suggestion (first (:requests (corrections/list-requests reviewer {}))))))
    (is (= before (public/results reader-url)))))
(defn -main [& _]
  (let [r (clojure.test/run-tests 'freediving.corrections-test)]
    (shutdown-agents) (when (pos? (+ (:fail r) (:error r))) (System/exit 1))))
(defn status-of [f] (try (f) :ok (catch clojure.lang.ExceptionInfo e (:status (ex-data e)))))
(deftest public-detail-binds-token-and-value-in-one-snapshot
  (let [r (request) d (corrections/public-detail reader-url (:result-id r))]
    (is (= (:version r) (get-in d [:correction :version])))
    (is (= (public/result reader-url (:result-id r)) (:result d)))))
(deftest restricted-submitter-and-immutable-private-records
  (let [r (request)]
    (is (true? (corrections/assert-submitter! submit-url)))
    (corrections/submit! submit-url r client)
    (doseq [url [submit-url reader-url] table ["correction_requests" "correction_triage" "observations" "review_proposals" "public_projection_cache"]]
      (is (thrown? java.sql.SQLException (fixture/sql! url (str "SELECT * FROM freediving." table)))))
    (doseq [table ["correction_requests" "correction_triage"] op ["DELETE FROM " "TRUNCATE "]]
      (is (thrown? java.sql.SQLException (fixture/sql! fixture/admin (str op "freediving." table)))))
    (fixture/sql! fixture/admin "GRANT SELECT(suggestion) ON freediving.correction_requests TO corrections_submit")
    (is (= :unsafe-role (status-of #(corrections/assert-submitter! submit-url))))))
(deftest retries-duplicates-and-concurrency-do-not-create-new-requests
  (let [r (request) gate (promise) workers (mapv (fn [_] (future @gate (corrections/submit! submit-url r client))) (range 6))]
    (deliver gate true)
    (is (= #{(:id r)} (set (map :id (mapv deref workers)))))
    (is (= {:id (:id r) :status :pending :duplicate true} (corrections/submit! submit-url (assoc r :id (str (random-uuid))) client)))
    (is (= :conflict (status-of #(corrections/submit! submit-url (assoc r :reason "Different") client))))
    (is (= 1 (count (:requests (corrections/list-requests reviewer {})))))))
(deftest stale-hidden-and-absent-targets-are-indistinguishable
  (let [r (request) t (select-keys (first (:requests (do (corrections/submit! submit-url r client) (corrections/list-requests reviewer {})))) [:job-id :ordinal])]
    (public-fixture/validate! t "revoke" :revoke)
    (is (nil? (corrections/target-version submit-url (:result-id r))))
    (is (nil? (corrections/public-detail reader-url (:result-id r))))
    (is (= :unavailable (status-of #(corrections/submit! submit-url r client))))
    (is (= :unavailable (status-of #(corrections/submit! submit-url (assoc r :result-id (apply str (repeat 64 "0"))) client))))
    (public-fixture/validate! t "v2") (public/refresh! reviewer)
    (is (= :unavailable (status-of #(corrections/submit! submit-url r client))))))
(deftest persistent-client-and-global-limits
  (let [r (request)]
    (doseq [i (range 5)] (is (= :pending (:status (corrections/submit! submit-url (assoc r :id (str (random-uuid)) :reason (str "Reason " i)) client)))))
    (is (= :rate-limited (status-of #(corrections/submit! submit-url (assoc r :id (str (random-uuid)) :reason "Sixth") client))))
    (fixture/sql! fixture/admin "UPDATE freediving.correction_rate_buckets SET attempts=100 WHERE client_key='__global'")
    (is (= :rate-limited (status-of #(corrections/submit! submit-url (assoc r :id (str (random-uuid))) (apply str (repeat 64 "b"))))))))
(deftest inert-bounded-evidence-and-unicode
  (let [r (request)]
    (doseq [e ["javascript:alert(1)" "file:///etc/passwd" "https://user:secret@example.org/" "http://" "\u0000" "  "]]
      (is (= :invalid (status-of #(corrections/submit! submit-url (assoc r :evidence e) client)))))
    (is (= :invalid (status-of #(corrections/submit! submit-url (assoc r :suggestion (apply str (repeat 1001 "é"))) client))))
    (is (= :pending (:status (corrections/submit! submit-url (assoc r :evidence "Official printed results, page 2, row 4" :reason "<script>untrusted</script>") client))))))
(deftest triage-is-append-only-and-never-publishes
  (let [r (request) before (public/results reader-url)
        _ (corrections/submit! submit-url r client)
        event {:id (str (random-uuid)) :request-id (:id r) :base-revision 0 :action :dismiss :proposal-id nil :actor "Owner" :reason "Source agrees"}]
    (is (= 1 (:revision (corrections/triage! reviewer event))))
    (is (= 1 (:revision (corrections/triage! reviewer event))))
    (is (= :conflict (status-of #(corrections/triage! reviewer (assoc event :id (str (random-uuid)))))))
    (is (= :invalid (status-of #(corrections/triage! reviewer (assoc event :id (str (random-uuid)) :base-revision 1 :action :link-proposal :proposal-id "absent")))))
    (is (= before (public/results reader-url)))
    (is (= 1 (:revision (first (:requests (corrections/list-requests reviewer {}))))))))

(deftest evidence-token-validation-and-submitter-startup-boundaries
  (let [r (request)]
    (doseq [e ["See javascript:alert(1)" "See https://u:p@example.org/file" "https://example.org/?token=secret" "See file:///tmp/a"]]
      (is (= :invalid (status-of #(corrections/submit! submit-url (assoc r :evidence e) client)))))
    (is (= :pending (:status (corrections/submit! submit-url (assoc r :evidence "https://example.org/a.pdf page 1") client))))
    (fixture/sql! fixture/admin "CREATE SCHEMA extra; CREATE TABLE extra.secret(x text); GRANT SELECT(x) ON extra.secret TO corrections_submit")
    (try (is (= :unsafe-role (status-of #(corrections/assert-submitter! submit-url))))
         (finally (fixture/sql! fixture/admin "DROP SCHEMA extra CASCADE")))))
(deftest database-submission-and-triage-boundaries-cannot-bypass-validation
  (let [r (request)]
    (with-open [c (java.sql.DriverManager/getConnection submit-url)
                s (.prepareStatement c "SELECT status FROM freediving.submit_correction(?,?,?,?,?,?,?)")]
      (doseq [[i v] (map-indexed vector [(random-uuid) (:result-id r) (:version r) "Change" "Reason" "See javascript:alert(1)" client])]
        (.setObject s (inc i) v))
      (with-open [rs (.executeQuery s)] (.next rs) (is (= "invalid" (.getString rs 1)))))
    (corrections/submit! submit-url r client)
    (is (thrown? java.sql.SQLException
                 (fixture/sql! reviewer (str "INSERT INTO freediving.correction_triage(id,request_id,revision,action,actor,reason) VALUES('" (random-uuid) "','" (:id r) "',10,'dismiss','Owner','Reason')"))))))
(deftest committed-revocation-wins-over-a-waiting-submission
  (let [r (request)]
    (with-open [c (java.sql.DriverManager/getConnection fixture/admin) s (.createStatement c)]
      (.setAutoCommit c false)
      (.executeUpdate s "INSERT INTO freediving.publication_decisions(id,job_id,ordinal,revision,review_revision,policy_version,action,candidate_id,artifact_sha256,source_sha256,body_edn) SELECT 'race-revoke',job_id,ordinal,revision+1,review_revision,policy_version,'revoke',candidate_id,artifact_sha256,source_sha256,body_edn FROM freediving.publication_decisions WHERE id='v1'")
      (let [started (promise) pending (future (deliver started true) (status-of #(corrections/submit! submit-url r client)))]
        @started
        (is (= :waiting (deref pending 100 :waiting)))
        (.commit c)
        (is (= :unavailable (deref pending 5000 :timeout)))))
    (is (= [] (:requests (corrections/list-requests reviewer {}))))))
(deftest plain-citation-labels-remain-valid
  (let [r (request)]
    (is (= :pending (:status (corrections/submit! submit-url (assoc r :evidence "Source: printed official results; page: 1, row: 2") client))))))
(deftest additive-checksummed-migration-and-storage-ceiling
  (let [r (request)]
    (is (= {:schema-version 5} (corrections/migrate! fixture/admin "reviews_owner" "corrections_submit")))
    (corrections/submit! submit-url r client)
    (fixture/sql! fixture/admin "INSERT INTO freediving.correction_requests(id,result_id,version,job_id,ordinal,suggestion,reason,evidence,client_key) SELECT md5('synthetic-capacity-'||n::text)::uuid,result_id,version,job_id,ordinal,suggestion,reason,evidence,client_key FROM freediving.correction_requests CROSS JOIN generate_series(1,9999) n")
    (is (= :capacity (status-of #(corrections/submit! submit-url (assoc r :id (str (random-uuid)) :reason "New capacity test") client))))
    (fixture/sql! fixture/admin "UPDATE freediving.schema_migrations SET sha256=repeat('0',64) WHERE version=5")
    (is (= :checksum-conflict (status-of #(corrections/migrate! fixture/admin "reviews_owner" "corrections_submit"))))))
(deftest supplementary-unicode-is-preserved-and-isolated-surrogates-rejected
  (let [r (request)]
    (doseq [suggestion [(str (char 0xd800)) (str (char 0xdc00))]]
      (is (= :invalid (status-of #(corrections/submit! submit-url (assoc r :suggestion suggestion) client)))))
    (let [suggestion "Correct swimmer name 🐬 修正"]
      (corrections/submit! submit-url (assoc r :suggestion suggestion) client)
      (is (= suggestion (:suggestion (first (:requests (corrections/list-requests reviewer {})))))))))
