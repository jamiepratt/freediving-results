(ns freediving.private-sporting-proofs-test
  (:require [clojure.string]
            [clojure.data.json :as json]
            [clojure.test :refer [deftest is use-fixtures run-tests]]
            [freediving.observations :as observations]
            [freediving.observations-test :as fixture]
            [freediving.reviews :as reviews]
            [freediving.revisions :as revisions]
            [freediving.event-selections :as selections]
            [freediving.publication :as publication]
            [freediving.public-results :as public]
            [freediving.publication-test :as publication-fixture]
            [freediving.depth-2026-test :as pdf-fixture]
            [freediving.indoor-2026-test :as indoor-fixture]
            [freediving.indoor-time-2026-test :as time-fixture]))

(def proof-url (clojure.string/replace fixture/app "user=observations_app" "user=proof_source"))
(def relationship-url (clojure.string/replace fixture/app "user=observations_app" "user=proof_relationships"))
(def source-tables ["extractions" "observations" "extraction_reviews" "pdf_extraction_reviews" "review_proposals" "review_decisions" "publication_decisions" "publication_policy_events" "revision_proposals" "revision_decisions" "event_selections"])
(defn role! [role tables]
  (fixture/sql! fixture/admin (str "DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='" role "') THEN CREATE ROLE " role " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT; END IF; END $$"))
  (fixture/sql! fixture/admin (str "ALTER ROLE " role " SET default_transaction_read_only=on"))
  (fixture/sql! fixture/admin (str "GRANT USAGE ON SCHEMA freediving TO " role))
  (fixture/sql! fixture/admin (str "GRANT SELECT ON " (clojure.string/join "," (map #(str "freediving." %) tables)) " TO " role)))

(defn with-database [f]
  (fixture/sql! fixture/admin "DROP SCHEMA IF EXISTS freediving CASCADE")
  (observations/migrate! fixture/admin "observations_app")
  (reviews/migrate! fixture/admin "observations_app" "reviews_owner")
  (publication/migrate! fixture/admin "reviews_owner")
  (revisions/migrate! fixture/admin "observations_app" "reviews_owner")
  (public/migrate! fixture/admin "reviews_owner" "reviews_public")
  (selections/migrate! fixture/admin "reviews_owner")
  (role! "proof_source" source-tables)
  (role! "proof_relationships" ["extractions" "observations" "canonical_attempt_evidence" "canonical_attempt_events" "canonical_attempt_state"])
  (f))
(use-fixtures :each with-database)
(defn read-proof [config]
  ((requiring-resolve 'freediving.private-sporting-proofs/read-proofs) config))
(defn sample []
  (let [t (publication-fixture/sample)
        stored (observations/inspect fixture/app (:job-id t))
        artifact (:artifact stored)
        reference (assoc (revisions/reference fixture/app t) :parser-version (:parser-version artifact))]
    {:config {:jdbc_url proof-url :database "observations_test" :mode "source"
              :rows [{:reference reference :coordinates (get-in artifact [:candidates 0 :coordinates])}]}
     :target t :artifact artifact :stored stored}))

(deftest exact-unimported-row-is-an-observable-gap-not-zero-attempts
  (let [sha (apply str (repeat 64 "a"))
        reference {:job-id sha :ordinal 0 :candidate-id sha :source-sha256 sha
                   :artifact-sha256 sha :parser-version "synthetic/1"}
        result (read-proof {:jdbc_url proof-url :database "observations_test" :mode "source"
                            :rows [{:reference reference :coordinates {:table 0 :row 0}}]})]
    (is (= "private-sporting-proofs/v1" (:schema result)))
    (is (= reference (get-in result [:rows 0 :reference])))
    (is (= "not-imported" (get-in result [:rows 0 :diagnostics :mapping :state])))
    (is (empty? (get-in result [:rows 0 :upstream])))))

(deftest imported-exact-row-exposes-current-publication-and-rejects-other-scope
  (let [{:keys [config target]} (sample)
        initial (read-proof config)]
    (is (= "mapped" (get-in initial [:rows 0 :diagnostics :mapping :state])))
    (is (empty? (get-in initial [:rows 0 :upstream])))
    (publication/decide! publication-fixture/reviewer (publication-fixture/request target "validation"))
    (let [validated (read-proof config)]
      (is (= "approved" (get-in validated [:rows 0 :upstream :publication :value])))
      (is (= #{:result-id :observation-id :ordinal :source-sha256 :artifact-sha256}
             (set (keys (get-in validated [:rows 0 :public_reference])))))
      (is (nil? (get-in validated [:rows 0 :upstream :same-attempt])))
      (is (nil? (get-in validated [:rows 0 :upstream :source-conflict])))
      (is (not= (:binding_sha256 initial) (:binding_sha256 validated)))
      (doseq [[path value] [[[:reference :parser-version] "different/1"]
                            [[:reference :source-sha256] (apply str (repeat 64 "a"))]
                            [[:coordinates :line] 2]]]
        (let [wrong (read-proof (assoc-in config (into [:rows 0] path) value))]
          (is (= "scope-mismatch" (get-in wrong [:rows 0 :diagnostics :mapping :state])))
          (is (empty? (get-in wrong [:rows 0 :upstream]))))))))

(defn accept-pdf [{:keys [target artifact config]}]
  (let [ref (merge (get-in config [:rows 0 :reference])
                   {:source-kind :pdf :schema-version (:schema-version artifact)
                    :acquisition-id (get-in artifact [:acquisitions 0 :acquisition-id])
                    :observation-id (str "local-observation:" (:job-id target) ":0") :page 1 :line 1})
        request (merge target {:id "accept" :base-revision 0 :evidence ref
                               :owner-receipt-sha256 (apply str (repeat 64 "b"))
                               :owner-response {:task-id "test-task" :user-message-id "test-message"
                                                :response-annotation-index 1 :selected-text "Accept extraction 0-1"}
                               :actor "PRIVATE-OWNER" :reason "PRIVATE-REASON"})]
    (reviews/accept-pdf-extraction! publication-fixture/reviewer request)
    ref))

(deftest exact-extraction-receipts-currentness-and-history-stay-separate
  (let [{:keys [config target] :as sample} (sample)
        ref (accept-pdf sample)
        accepted (read-proof config)]
    (is (= "verified" (get-in accepted [:rows 0 :upstream :review :value])))
    (is (= "accepted" (get-in accepted [:rows 0 :diagnostics :review :state])))
    (is (nil? (get-in accepted [:rows 0 :upstream :publication])))
    (reviews/revoke-pdf-extraction! publication-fixture/reviewer
                                    (merge target {:id "revoke" :base-revision 1 :event-id "accept"
                                                   :evidence ref :actor "owner" :reason "Changed extraction"}))
    (let [revoked (read-proof config)]
      (is (= "revoked" (get-in revoked [:rows 0 :diagnostics :review :state])))
      (is (nil? (get-in revoked [:rows 0 :upstream :review])))
      (is (= 2 (count (get-in revoked [:rows 0 :diagnostics :review :history_sha256]))))
      (is (not= (:binding_sha256 accepted) (:binding_sha256 revoked))))))

(deftest active-policy-review-revision-and-revocation-withdraw-public-proof
  (let [{:keys [config target]} (sample)
        validate (publication-fixture/request target "v")]
    (publication/decide! publication-fixture/reviewer validate)
    (let [before (read-proof config)]
      (publication/decide! publication-fixture/reviewer (assoc validate :id "r" :action :revoke :base-revision 1 :attestations {}))
      (let [after (read-proof config)]
        (is (nil? (get-in after [:rows 0 :upstream :publication])))
        (is (nil? (get-in after [:rows 0 :public_reference])))
        (is (not= (:binding_sha256 before) (:binding_sha256 after)))))
    (publication/decide! publication-fixture/reviewer (assoc validate :id "v2" :base-revision 2))
    (publication/activate-policy! fixture/admin "extraction-publication/99" "Synthetic policy change")
    (let [changed (read-proof config)]
      (is (nil? (get-in changed [:rows 0 :upstream :publication])))
      (is (some #{:policy-inactive} (get-in changed [:rows 0 :diagnostics :publication :reasons]))))))

(deftest source-and-relationship-capabilities-are-independent-and-never-leak-private-data
  (let [{:keys [config]} (sample)
        url (clojure.string/replace fixture/app "user=observations_app" "user=proof_extra")]
    (fixture/sql! fixture/admin "CREATE ROLE proof_extra LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT")
    (fixture/sql! fixture/admin "ALTER ROLE proof_extra SET default_transaction_read_only=on")
    (fixture/sql! fixture/admin "GRANT USAGE ON SCHEMA freediving TO proof_extra")
    (fixture/sql! fixture/admin "GRANT SELECT ON freediving.extractions,freediving.observations,freediving.extraction_reviews,freediving.pdf_extraction_reviews,freediving.review_proposals,freediving.review_decisions,freediving.publication_decisions,freediving.publication_policy_events,freediving.revision_proposals,freediving.revision_decisions,freediving.event_selections TO proof_extra")
    (let [result (read-proof (assoc config :jdbc_url url))]
      (is (= "mapped" (get-in result [:rows 0 :diagnostics :mapping :state])))
      (is (not-any? #(clojure.string/includes? (pr-str result) %) ["PRIVATE-OWNER" "PRIVATE-REASON" ":artifact_bytes" ":payload_edn" ":body_edn" "Éxample"]))
      (is (= (:binding_sha256 result) (:binding_sha256 (read-proof (assoc config :jdbc_url url :rows []))))))
    (is (thrown? Exception (read-proof (assoc config :jdbc_url url :mode "relationships"))))
    (is (thrown? Exception (read-proof (assoc config :jdbc_url (System/getenv "FREEDIVING_TEST_PUBLIC_URL")))))
    (is (thrown? Exception (fixture/sql! url "INSERT INTO freediving.pdf_extraction_reviews(id,job_id,ordinal,revision,action,body_edn) VALUES('forged','missing',0,1,'accept','{}')")))))

(deftest authority-mutation-between-owned-reads-denies-old-context
  (let [{:keys [config target]} (sample)
        original publication/diagnose-many
        entered (promise) continue (promise)]
    (with-redefs [publication/diagnose-many (fn [url targets]
                                              (deliver entered true)
                                              @continue
                                              (original url targets))]
      (let [reading (future (try (read-proof config) (catch Exception e (.getMessage e))))]
        @entered
        (publication/decide! publication-fixture/reviewer (publication-fixture/request target "concurrent"))
        (deliver continue true)
        (is (= "Canonical sporting authority changed during read" @reading))))))

(deftest capability-widening-after-startup-is-observable-denial
  (let [{:keys [config]} (sample)]
    (is (= "mapped" (get-in (read-proof config) [:rows 0 :diagnostics :mapping :state])))
    (fixture/sql! fixture/admin "GRANT SELECT ON freediving.canonical_attempt_events TO proof_source")
    (is (thrown-with-msg? Exception #"capability" (read-proof config)))
    (fixture/sql! fixture/admin "REVOKE SELECT ON freediving.canonical_attempt_events FROM proof_source")
    (fixture/sql! fixture/admin "GRANT UPDATE(body_edn) ON freediving.pdf_extraction_reviews TO proof_source")
    (is (thrown-with-msg? Exception #"capability" (read-proof config)))
    (fixture/sql! fixture/admin "REVOKE UPDATE(body_edn) ON freediving.pdf_extraction_reviews FROM proof_source")
    (fixture/sql! fixture/admin "GRANT CREATE ON SCHEMA freediving TO proof_source")
    (is (thrown-with-msg? Exception #"capability" (read-proof config)))
    (fixture/sql! fixture/admin "REVOKE CREATE ON SCHEMA freediving FROM proof_source")))

(deftest tampered-extraction-acquisition-cannot-authenticate-current-review
  (let [{:keys [config] :as sample} (sample)]
    (accept-pdf sample)
    (fixture/sql! fixture/admin "ALTER TABLE freediving.pdf_extraction_reviews DISABLE TRIGGER immutable_pdf_extraction_reviews")
    (fixture/sql! fixture/admin "UPDATE freediving.pdf_extraction_reviews SET body_edn=replace(body_edn,':source-kind :pdf',':source-kind :html')")
    (is (empty? (get-in (read-proof config) [:rows 0 :upstream])))))

(deftest html-review-is-only-the-current-explicit-source-visual-validation
  (let [target (publication-fixture/html-sample)
        stored (observations/inspect fixture/app (:job-id target))
        reference (assoc (revisions/reference fixture/app target) :parser-version (get-in stored [:artifact :parser-version]))
        config {:jdbc_url proof-url :database "observations_test" :mode "source"
                :rows [{:reference reference :coordinates {:table 1 :row 2}}]}]
    (publication/activate-policy! fixture/admin publication/html-policy "Explicit synthetic HTML policy")
    (is (nil? (get-in (read-proof config) [:rows 0 :upstream :review])))
    (let [request (publication-fixture/html-request target "html-current")]
      (is (thrown? Exception (publication/decide! publication-fixture/reviewer
                                                  (assoc-in request [:attestations :source-visual-accuracy] false))))
      (publication/decide! publication-fixture/reviewer request)
      (let [current (read-proof config)]
        (is (= "verified" (get-in current [:rows 0 :upstream :review :value])))
        (is (= "verified-by-current-html-source-validation" (get-in current [:rows 0 :diagnostics :review :state]))))
      (publication/decide! publication-fixture/reviewer (assoc request :id "html-revoked" :base-revision 1 :action :revoke :attestations {}))
      (is (nil? (get-in (read-proof config) [:rows 0 :upstream :review]))))))

(deftest receipt-history-with-a-revision-gap-cannot-authenticate-current-facts
  (let [{:keys [config] :as sample} (sample)]
    (accept-pdf sample)
    (fixture/sql! fixture/admin "ALTER TABLE freediving.pdf_extraction_reviews DISABLE TRIGGER immutable_pdf_extraction_reviews")
    (fixture/sql! fixture/admin "UPDATE freediving.pdf_extraction_reviews SET revision=3,body_edn=replace(body_edn,':revision 1',':revision 3')")
    (is (empty? (get-in (read-proof config) [:rows 0 :upstream])))))

(deftest actual-cmas-parser-column-bounds-bind-the-exact-owned-source-row
  (let [{:keys [root result]} (pdf-fixture/extract-stream
                               [(str (indoor-fixture/header "DNF" "SENIORS - WOMEN" "11")
                                     (indoor-fixture/row 640 ["1" "SAMPLE Person" "AIN" "113,5" "113,5" "GOLD MEDAL"]))
                                (str (time-fixture/speed-header "2X50" "SENIORS - WOMEN" "12")
                                     (indoor-fixture/row 640 ["1" "TIME Person" "AIN" "00:40.00" "00:40.00"]))])
        _ (observations/import! fixture/app root (:job-id result))
        target {:job-id (:job-id result) :ordinal 0}
        reference (assoc (revisions/reference fixture/app target) :parser-version (:parser-version result))
        coordinates (get-in result [:candidates 0 :coordinates])
        config {:jdbc_url proof-url :database "observations_test" :mode "source"
                :rows [{:reference reference :coordinates coordinates}]}
        ref (merge reference (select-keys coordinates [:page :line])
                   {:source-kind :pdf :schema-version (:schema-version result)
                    :acquisition-id (get-in result [:acquisitions 0 :acquisition-id])
                    :observation-id (str "local-observation:" (:job-id target) ":0")})]
    (is (= "cmas-2026-indoor-time/2" (:parser-version result)))
    (is (= #{:page :line :column-start :column-end} (set (keys coordinates))))
    (reviews/accept-pdf-extraction! publication-fixture/reviewer
                                    (merge target {:id "real-shaped-pdf-review" :base-revision 0 :evidence ref
                                                   :owner-receipt-sha256 (apply str (repeat 64 "a"))
                                                   :owner-response {:task-id "isolated-owner" :user-message-id "isolated-column-review"
                                                                    :response-annotation-index 0 :selected-text "Accept extraction 0-0"}
                                                   :actor "owner" :reason "Inspected the exact source row and retained column bounds"}))
    (let [request (assoc (publication-fixture/request target "real-shaped-publication")
                         :evidence [(select-keys coordinates [:page :line])])]
      (publication/decide! publication-fixture/reviewer request))
    (let [proof (read-proof config)]
      (is (= coordinates (get-in proof [:rows 0 :coordinates])))
      (is (= "mapped" (get-in proof [:rows 0 :diagnostics :mapping :state])))
      (is (= "verified" (get-in proof [:rows 0 :upstream :review :value])))
      (is (= "approved" (get-in proof [:rows 0 :upstream :publication :value]))))
    (doseq [key [:column-start :column-end]]
      (let [changed (read-proof (update-in config [:rows 0 :coordinates key] inc))]
        (is (= "scope-mismatch" (get-in changed [:rows 0 :diagnostics :mapping :state])))
        (is (empty? (get-in changed [:rows 0 :upstream])))))
    (is (thrown-with-msg? Exception #"Invalid exact sporting row"
                          (read-proof (assoc-in config [:rows 0 :coordinates :unknown-column] 1))))))

(deftest full-inventory-read-replays-current-source-selection-once-and-never-reuses-prior-authority
  (let [{:keys [config target artifact]} (sample)
        second-target (assoc target :ordinal 1)
        second-reference (assoc (revisions/reference fixture/app second-target) :parser-version (:parser-version artifact))
        absent (mapv (fn [n] {:reference {:job-id (fixture/hash-value [:missing-job n]) :ordinal 0
                                          :candidate-id (fixture/hash-value [:missing-candidate n])
                                          :source-sha256 (fixture/hash-value [:missing-source n])
                                          :artifact-sha256 (fixture/hash-value [:missing-artifact n]) :parser-version "unimported/1"}
                              :coordinates {:table 1 :row (inc n)}}) (range 274))
        rows (into (conj (:rows config) {:reference second-reference :coordinates (get-in artifact [:candidates 1 :coordinates])}) absent)
        config (assoc config :rows rows)
        original selections/projection-plan calls (atom 0)
        read-current (fn [] (reset! calls 0)
                       (with-redefs [selections/projection-plan (fn [c validations]
                                                                  (swap! calls inc)
                                                                  (original c validations))]
                         (read-proof config)))
        validation (publication-fixture/request target "current-batched-validation")]
    (publication/decide! publication-fixture/reviewer validation)
    (let [first-read (read-current)]
      (is (= 1 @calls))
      (is (= 276 (count (:rows first-read))))
      (is (= "approved" (get-in first-read [:rows 0 :upstream :publication :value])))
      (is (nil? (get-in first-read [:rows 1 :upstream :publication])))
      (is (= 274 (count (filter #(= "not-imported" (get-in % [:diagnostics :mapping :state])) (:rows first-read)))))
      (publication/decide! publication-fixture/reviewer
                           (assoc validation :id "current-batched-revocation" :base-revision 1 :action :revoke :attestations {}))
      (let [second-read (read-current)]
        (is (= 1 @calls))
        (is (not= (:binding_sha256 first-read) (:binding_sha256 second-read)))
        (is (nil? (get-in second-read [:rows 0 :upstream :publication])))
        (is (nil? (get-in second-read [:rows 0 :public_reference])))))))

(defn connector-fixture!
  "Only isolated disposable PostgreSQL. Emit exact real APIs/readers for HTTP tests."
  [path]
  (with-database
    (fn []
      (let [{:keys [config target] :as sample} (sample)]
        (accept-pdf sample)
        (publication/decide! publication-fixture/reviewer (publication-fixture/request target "connector-validation"))
        (public/refresh! publication-fixture/reviewer)
        (spit path (json/write-str {:source_config config
                                    :relationship_config (assoc config :jdbc_url relationship-url :mode "relationships")
                                    :rows (:rows config) :source_result (read-proof config)
                                    :relationship_result (read-proof (assoc config :jdbc_url relationship-url :mode "relationships"))}))))))

(defn -main [& [command path]]
  (if (= command "fixture-output") (connector-fixture! path)
      (let [r (run-tests 'freediving.private-sporting-proofs-test)]
        (shutdown-agents)
        (when (pos? (+ (:fail r) (:error r))) (System/exit 1)))))
