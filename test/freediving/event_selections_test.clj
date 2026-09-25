(ns freediving.event-selections-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [freediving.observations :as observations]
            [freediving.observations-test :as fixture]
            [freediving.revisions :as revisions]
            [freediving.revisions-test :as revision-fixture]
            [freediving.reviews :as reviews]
            [freediving.publication :as publication]
            [freediving.public-results :as public]
            [freediving.public-results-test :as public-fixture]
            [freediving.public-server-test :as http]
            [freediving.event-selections :as selections]))
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
    (selections/migrate! fixture/admin "reviews_owner") (f)))
(defn request [id members selected]
  {:id id :actor "PRIVATE-OWNER" :reason "PRIVATE-REVIEW"
   :base (selections/snapshot reviewer)
   :event-scope (select-keys revision-fixture/scope revisions/event-fields)
   :members members :selected selected
   :coverage {:completeness :partial :gaps ["Final session results incomplete"]}})
(defn selected [descriptor validation]
  {:reference (:reference descriptor) :validation-id validation})
(defn sample
  ([version scope] (sample version scope nil))
  ([version scope source]
   (let [original fixture/synthetic]
     (with-redefs [fixture/synthetic (fn [n v] (update-in (original n v) [:artifact :candidates]
                                                          #(mapv (fn [c] (assoc-in c [:parsed :discipline] "CWT")) %)))]
       (if source (revision-fixture/sample version scope source) (revision-fixture/sample version scope))))))
(deftest explicit-selection-publishes-only-reviewed-versions-atomically
  (let [a (sample "old/1" revision-fixture/scope)
        b (sample "new/1" revision-fixture/scope)]
    (public-fixture/validate! (select-keys (:reference a) [:job-id :ordinal]) "v-a")
    (public-fixture/validate! (select-keys (:reference b) [:job-id :ordinal]) "v-b")
    (public/refresh! reviewer)
    (is (= 2 (count (public/results reader-url))))
    (let [r (request "first" [a b] [(selected a "v-a")])]
      (selections/select! reviewer r)
      (is (= 1 (count (public/results reader-url))))
      (is (= :unresolved (get-in (first (public/results reader-url)) [:identity :status])))
      (is (= :partial (get-in (first (public/results reader-url)) [:coverage :completeness])))
      (is (= 1 (count (selections/history reviewer (:event-scope r))))))))
(defn -main [& _]
  (let [r (clojure.test/run-tests 'freediving.event-selections-test)]
    (shutdown-agents) (when (pos? (+ (:fail r) (:error r))) (System/exit 1))))
(deftest public-coverage-survives-empty-selection-without-private-access
  (let [a (sample "empty/1" revision-fixture/scope)]
    (selections/select! reviewer (request "gap" [a] []))
    (is (= [] (public/results reader-url)))
    (is (= ["Final session results incomplete"] (get-in (public/coverage reader-url) [:events 0 :gaps])))
    (is (thrown? java.sql.SQLException (fixture/sql! reader-url "SELECT * FROM freediving.event_selections")))))
(defn validate! [d id & [action]]
  (public-fixture/validate! (select-keys (:reference d) [:job-id :ordinal]) id action))
(defn link! [a b id]
  (revisions/propose! fixture/app (assoc (revision-fixture/proposal a b id) :base-revision (:revision (revisions/diagnostics fixture/app)))))
(defn decide-link! [id proposal action & [event-id]]
  (revisions/decide! reviewer (cond-> {:id id :proposal-id proposal :action action :base-revision (:revision (revisions/diagnostics fixture/app)) :actor "PRIVATE-OWNER" :reason "PRIVATE-REVIEW"} event-id (assoc :event-id event-id))))
(deftest rollback-cannot-change-historical-selection
  (let [a (sample "a/1" revision-fixture/scope) b (sample "b/1" revision-fixture/scope)]
    (validate! a "va") (validate! b "vb")
    (selections/select! reviewer (request "a" [a b] [(selected a "va")]))
    (let [old-id (:result-id (first (public/results reader-url)))]
      (selections/select! reviewer (request "b" [a b] [(selected b "vb")]))
      (is (not= old-id (:result-id (first (public/results reader-url)))))
      (let [rollback {:id "rollback" :actor "owner" :reason "restore" :selection-id "a" :base (selections/snapshot reviewer)}]
        (is (thrown-with-msg? Exception #"Unexpected" (selections/rollback! reviewer (assoc rollback :selected [(selected b "vb")]))))
        (selections/rollback! reviewer rollback)
        (is (= [old-id] (mapv :result-id (public/results reader-url))))))))
(deftest scoped-athlete-identifiers-cannot-hide-duplicate-attempts
  (let [a (sample "a/1" (assoc revision-fixture/scope :source-athlete-id "athlete-1"))
        b (sample "b/1" (-> revision-fixture/scope (dissoc :bib) (assoc :source-athlete-id "athlete-1")))]
    (validate! a "va") (validate! b "vb")
    (is (thrown-with-msg? Exception #"attempt" (selections/select! reviewer (request "bad" [a b] [(selected a "va") (selected b "vb")]))))))
(deftest complete-coverage-cannot-omit-a-known-attempt
  (let [a (sample "a/1" revision-fixture/scope)]
    (is (thrown-with-msg? Exception #"Complete coverage" (selections/select! reviewer (assoc (request "empty" [a] []) :coverage {:completeness :complete :gaps []}))))))

(deftest reviewed-replacement-reversal-and-revocation
  (let [a (sample "old/1" revision-fixture/scope (revision-fixture/changed-source "Old synthetic report" "2026-09-24T12:00:00Z"))
        b (sample "new/1" revision-fixture/scope (revision-fixture/changed-source "Revised synthetic report" "2026-09-23T12:00:00Z"))]
    (validate! a "va") (validate! b "vb") (link! a b "ab")
    (selections/select! reviewer (request "initial" [a b] [(selected a "va")]))
    (let [old-row (first (public/results reader-url))]
      (is (thrown-with-msg? Exception #"Unreviewed" (selections/select! reviewer (request "uncertain" [a b] [(selected b "vb")]))))
      (is (= [old-row] (public/results reader-url)))
      (decide-link! "confirm" "ab" :confirm)
      (is (= [] (public/results reader-url)))
      (selections/select! reviewer (request "replacement" [a b] [(assoc (selected b "vb") :relationship-id "ab")]))
      (let [rows (public/results reader-url)]
        (is (= 1 (count rows)))
        (is (not= (:result-id old-row) (:result-id (first rows))))
        (is (= (:original old-row) (:original (first rows))))
        (is (= {:status :confirmed-correction :previous-values :unknown} (:revision-history (first rows))))
        (is (= :unresolved (get-in (first rows) [:identity :status])))
        (is (not (re-find #"PRIVATE|mapping-rationale|proposal-id|body_edn" (pr-str rows)))))
      (decide-link! "reverse" "ab" :reverse "confirm")
      (is (= [] (public/results reader-url)))
      (public/refresh! reviewer)
      (is (= [] (public/results reader-url)))
      (is (= [] (:events (public/coverage reader-url))))
      (is (thrown? Exception (selections/rollback! reviewer {:id "revoked-link" :actor "owner" :reason "restore" :selection-id "replacement" :base (selections/snapshot reviewer)})))
      (selections/rollback! reviewer {:id "restore" :actor "owner" :reason "restore" :selection-id "initial" :base (selections/snapshot reviewer)})
      (is (= [(:result-id old-row)] (mapv :result-id (public/results reader-url))))
      (validate! a "revoke-a" :revoke)
      (is (= [] (public/results reader-url)))
      (is (thrown-with-msg? Exception #"current extraction validation" (selections/rollback! reviewer {:id "bad" :actor "owner" :reason "restore" :selection-id "initial" :base (selections/snapshot reviewer)})))
      (is (= 3 (count (selections/history reviewer (select-keys revision-fixture/scope revisions/event-fields))))))))
(deftest ranking-records-are-not-sporting-attempts
  (let [original fixture/synthetic
        d (with-redefs [fixture/synthetic (fn [n v] (update-in (original n v) [:artifact :candidates] #(mapv (fn [c] (assoc c :source-family :ranking)) %)))]
            (sample "ranking/1" revision-fixture/scope))]
    (validate! d "ranking-validation")
    (is (thrown-with-msg? Exception #"Ranking" (selections/select! reviewer (request "ranking" [d] [(selected d "ranking-validation")]))))))
(deftest missing-history-and-unrelated-event-retention
  (let [a (sample "missing/1" revision-fixture/scope)
        other-scope (assoc revision-fixture/scope :session "2" :round "qualifying")
        other (sample "other/1" other-scope)]
    (validate! a "va") (validate! other "vo") (link! nil a "missing")
    (decide-link! "ack" "missing" :acknowledge-missing)
    (is (thrown-with-msg? Exception #"every eligible" (selections/select! reviewer (request "omit" [a] [(assoc (selected a "va") :relationship-id "missing")]))))
    (let [r (assoc (request "baseline" [a] [(assoc (selected a "va") :relationship-id "missing")]) :retained [{:descriptor other :validation-id "vo"}])]
      (selections/select! reviewer r)
      (let [rows (public/results reader-url)]
        (is (= 2 (count rows)))
        (is (= [{:status :history-unavailable :previous-values :unknown}] (vec (keep :revision-history rows)))))
      (selections/select! reviewer (request "gap" [a] []))
      (is (= 1 (count (public/results reader-url))))
      (is (nil? (:revision-history (first (public/results reader-url)))))
      (selections/rollback! reviewer {:id "restore" :actor "owner" :reason "restore" :selection-id "baseline" :base (selections/snapshot reviewer)})
      (is (= 2 (count (public/results reader-url)))))))
(deftest reviewer-authority-staleness-idempotency-and-append-only-history
  (let [a (sample "a/1" revision-fixture/scope)]
    (validate! a "va")
    (let [r (request "one" [a] [(selected a "va")])]
      (is (thrown? Exception (selections/select! fixture/app r)))
      (is (thrown? Exception (selections/select! reader-url r)))
      (is (= (selections/select! reviewer r) (selections/select! reviewer r)))
      (is (thrown-with-msg? Exception #"idempotency" (selections/select! reviewer (assoc r :reason "changed"))))
      (is (thrown-with-msg? Exception #"Stale" (selections/select! reviewer (assoc r :id "stale"))))
      (doseq [op ["UPDATE freediving.event_selections SET id=id" "DELETE FROM freediving.event_selections" "TRUNCATE freediving.event_selections"]]
        (is (thrown? java.sql.SQLException (fixture/sql! reviewer op))))
      (is (= 1 (count (selections/history reviewer (:event-scope r))))))))
(deftest revoked-sibling-cannot-leave-a-complete-event-visible
  (let [a (sample "a/1" revision-fixture/scope) b (sample "b/1" (assoc revision-fixture/scope :bib "8"))]
    (validate! a "va") (validate! b "vb")
    (selections/select! reviewer (assoc (request "complete" [a b] [(selected a "va") (selected b "vb")]) :coverage {:completeness :complete :gaps []}))
    (is (= 2 (count (public/results reader-url))))
    (validate! b "revoke-b" :revoke)
    (public/refresh! reviewer)
    (is (= [] (public/results reader-url)))
    (is (= [] (:events (public/coverage reader-url))))))
(deftest idempotent-replay-and-history-recheck-source-integrity
  (let [a (sample "a/1" revision-fixture/scope)]
    (validate! a "va")
    (let [r (request "one" [a] [(selected a "va")])]
      (selections/select! reviewer r)
      (fixture/sql! fixture/admin "ALTER TABLE freediving.observations DISABLE TRIGGER immutable_observations")
      (fixture/sql! fixture/admin "UPDATE freediving.observations SET candidate_id='forged' WHERE ordinal=0")
      (is (thrown-with-msg? Exception #"provenance" (selections/select! reviewer r)))
      (is (thrown-with-msg? Exception #"provenance" (selections/history reviewer (:event-scope r)))))))
(deftest unknown-attempt-discriminator-cannot-create-an-extra-attempt
  (let [a (sample "a/1" revision-fixture/scope) b (sample "b/1" (assoc revision-fixture/scope :attempt "1"))]
    (validate! a "va") (validate! b "vb")
    (is (thrown-with-msg? Exception #"attempt" (selections/select! reviewer (request "ambiguous" [a b] [(selected a "va") (selected b "vb")]))))))

(deftest public-http-reflects-cutover-rollback-and-revocation
  (let [a (sample "http-a/1" revision-fixture/scope) b (sample "http-b/1" revision-fixture/scope)]
    (validate! a "va") (validate! b "vb")
    (selections/select! reviewer (request "first" [a b] [(selected a "va")]))
    (http/with-server
      (fn [url]
        (let [before (http/request url "/api/results") old-id (get-in before [:body :results 0 :result-id])]
          (is (= 200 (:status before)))
          (is (= 1 (get-in before [:body :total])))
          (is (= ["Final session results incomplete"] (get-in before [:body :coverage :events 0 :gaps])))
          (selections/select! reviewer (request "second" [a b] [(selected b "vb")]))
          (is (= 404 (:status (http/request url (str "/api/results/" old-id)))))
          (is (not= old-id (get-in (http/request url "/api/results") [:body :results 0 :result-id])))
          (selections/rollback! reviewer {:id "restore" :actor "owner" :reason "restore" :selection-id "first" :base (selections/snapshot reviewer)})
          (is (= old-id (get-in (http/request url "/api/results") [:body :results 0 :result-id])))
          (validate! a "revoke" :revoke)
          (is (= 0 (get-in (http/request url "/api/results") [:body :total])))
          (is (= [] (get-in (http/request url "/api/results") [:body :coverage :events]))))))))
(deftest exact-observation-cannot-belong-to-two-event-scopes
  (let [a (sample "a/1" revision-fixture/scope)]
    (validate! a "va")
    (selections/select! reviewer (request "initial" [a] [(selected a "va")]))
    (let [other (assoc-in a [:scope :session] (get-in a [:scope :bib]))
          r (assoc (request "other" [other] [(selected other "va")]) :event-scope (assoc (select-keys revision-fixture/scope revisions/event-fields) :session "7"))]
      (is (thrown-with-msg? Exception #"another event" (selections/select! reviewer r))))))
(deftest unvalidated-and-rejected-successors-cannot-replace-public-data
  (let [a (sample "a/1" revision-fixture/scope) b (sample "b/1" revision-fixture/scope)]
    (validate! a "va") (link! a b "ab")
    (selections/select! reviewer (request "old" [a b] [(selected a "va")]))
    (let [before (public/results reader-url)]
      (is (thrown-with-msg? Exception #"current extraction validation" (selections/select! reviewer (request "unvalidated" [a b] [(assoc (selected b "va") :relationship-id "ab")]))))
      (is (= before (public/results reader-url)))
      (validate! b "vb") (decide-link! "reject" "ab" :reject)
      (is (thrown-with-msg? Exception #"rejected" (selections/select! reviewer (request "rejected" [a b] [(assoc (selected b "vb") :relationship-id "ab")]))))
      (public/refresh! reviewer)
      (is (= before (public/results reader-url)))
      (is (= 1 (count (selections/history reviewer (select-keys revision-fixture/scope revisions/event-fields))))))))
(deftest policy-activation-cannot-be-undone-by-rollback
  (let [a (sample "a/1" revision-fixture/scope)]
    (validate! a "va")
    (selections/select! reviewer (request "one" [a] [(selected a "va")]))
    (public/activate-html-policy! fixture/admin "hide-existing-public-results" "Synthetic activation checkpoint")
    (is (= [] (public/results reader-url)))
    (is (= [] (:events (public/coverage reader-url))))
    (is (thrown-with-msg? Exception #"current extraction validation" (selections/rollback! reviewer {:id "bad" :actor "owner" :reason "restore" :selection-id "one" :base (selections/snapshot reviewer)})))))
(deftest new-unbound-versions-never-autoappear-after-cutover
  (let [a (sample "a/1" revision-fixture/scope)]
    (validate! a "va")
    (selections/select! reviewer (request "one" [a] [(selected a "va")]))
    (let [before (public/results reader-url) b (sample "new-acquisition/2" revision-fixture/scope)]
      (validate! b "vb")
      (public/refresh! reviewer)
      (is (= before (public/results reader-url))))))
(deftest racing-selections-have-one-winner
  (let [a (sample "a/1" revision-fixture/scope)]
    (validate! a "va")
    (let [r (request "a" [a] [(selected a "va")]) gate (promise)
          tasks (mapv (fn [r] (future @gate (try (selections/select! reviewer r) (catch Exception _ :conflict)))) [r (assoc r :id "b")])]
      (deliver gate true)
      (is (= 1 (count (filter #{:conflict} (mapv deref tasks)))))
      (is (= 1 (:selections (selections/snapshot reviewer))))
      (is (= 1 (count (public/results reader-url)))))))
(deftest initial-adoption-cannot-silently-drop-ineligible-retained-history
  (let [a (sample "a/1" revision-fixture/scope)
        other-scope (assoc revision-fixture/scope :session "2")
        old (sample "other-old/1" other-scope) new (sample "other-new/1" other-scope)]
    (validate! a "va") (validate! old "vo") (validate! new "vn") (link! old new "other")
    (public/refresh! reviewer)
    (let [before (public/results reader-url)
          r (assoc (request "initial" [a] [(selected a "va")]) :retained [{:descriptor old :validation-id "vo"} {:descriptor new :validation-id "vn"}])]
      (is (thrown-with-msg? Exception #"Retained" (selections/select! reviewer r)))
      (is (= before (public/results reader-url)))
      (is (= 0 (:selections (selections/snapshot reviewer)))))))
(deftest enrolling-retained-event-must-inventory-its-original
  (let [a (sample "a/1" revision-fixture/scope) other-scope (assoc revision-fixture/scope :session "2")
        b (sample "b/1" other-scope)]
    (validate! a "va") (validate! b "vb")
    (selections/select! reviewer (assoc (request "initial" [a] [(selected a "va")]) :retained [{:descriptor b :validation-id "vb"}]))
    (let [new (sample "new-b/1" other-scope)]
      (validate! new "vn")
      (is (thrown-with-msg? Exception #"retained event inventory" (selections/select! reviewer (assoc (request "other" [new] [(selected new "vn")]) :event-scope (select-keys other-scope revisions/event-fields)))))
      (selections/select! reviewer (assoc (request "other" [b new] [(selected new "vn")]) :event-scope (select-keys other-scope revisions/event-fields)))
      (is (= 2 (count (public/results reader-url)))))))
(deftest repeated-migration-repairs-public-function-execution
  (fixture/sql! fixture/admin "GRANT EXECUTE ON FUNCTION freediving.lock_selection_authority(),freediving.lock_revision_projection() TO PUBLIC")
  (selections/migrate! fixture/admin "reviews_owner")
  (is (thrown? java.sql.SQLException (fixture/sql! reader-url "SELECT freediving.lock_selection_authority()"))))

(deftest html-and-pdf-projection-remains-compatible-after-migration-ten
  (public-fixture/html-and-pdf-validation-coexist-with-exact-public-citation-and-revocation))

(def daily-gap "Daily view only; venue, round and session are not established.")
(defn daily-request [id members selected]
  (assoc (request id members selected)
         :event-scope (assoc (select-keys revision-fixture/daily-values [:federation :event-id :date :discipline :category]) :scope-contract :aida-date-view/v1)
         :coverage {:completeness :partial :gaps [daily-gap]}))
(deftest daily-view-selection-preserves-explicit-partial-scope-through-rollback
  (let [a (revision-fixture/daily-descriptor
           (revision-fixture/html-sample (revision-fixture/daily-document) "https://www.aidainternational.org/StartList/4349"))
        first-request (daily-request "daily-first" [a] [])]
    (selections/select! reviewer first-request)
    (is (= :aida-date-view/v1 (get-in (public/coverage reader-url) [:events 0 :event :scope-contract])))
    (is (= [daily-gap] (get-in (public/coverage reader-url) [:events 0 :gaps])))
    (selections/select! reviewer (update-in (daily-request "daily-second" [a] []) [:coverage :gaps] conj "Additional unreviewed source versions"))
    (selections/rollback! reviewer {:id "daily-restore" :actor "owner" :reason "restore" :selection-id "daily-first" :base (selections/snapshot reviewer)})
    (is (= [daily-gap] (get-in (public/coverage reader-url) [:events 0 :gaps])))
    (is (= 3 (count (selections/history reviewer (:event-scope first-request)))))))

(deftest daily-view-cannot-claim-complete-or-hide-missing-scope
  (let [a (revision-fixture/daily-descriptor
           (revision-fixture/html-sample (revision-fixture/daily-document) "https://www.aidainternational.org/StartList/4349"))]
    (doseq [coverage [{:completeness :complete :gaps []}
                      {:completeness :partial :gaps ["Something missing"]}]]
      (is (thrown-with-msg? Exception #"Daily view requires" (selections/select! reviewer (assoc (daily-request "unsupported" [a] []) :coverage coverage)))))))

(defn validate-daily! [d id]
  (let [target (select-keys (:reference d) [:job-id :ordinal])
        diagnosis (publication/diagnose reviewer target)]
    (publication/decide! reviewer
                         (merge target {:id id :action :validate :base-revision (:revision diagnosis)
                                        :review-revision (:review-revision diagnosis) :policy-version "extraction-publication/2"
                                        :observation (:observation diagnosis) :evidence [{:table 1 :row 2}]
                                        :actor "synthetic" :reason "Synthetic source review"
                                        :attestations {:source-visual-accuracy true :no-unresolved-substantive-errors true}}))))
(deftest daily-view-publication-still-requires-exact-validation-and-reviewed-replacement
  (let [source (str/replace (revision-fixture/daily-document) "Dqsp" "")
        a (revision-fixture/daily-descriptor (revision-fixture/html-sample source "https://www.aidainternational.org/StartList/4349"))
        b (revision-fixture/daily-descriptor (revision-fixture/html-sample (str/replace source "<td></td>" "<td>Revised synthetic report</td>") "https://www.aidainternational.org/StartList/4349"))]
    (public/activate-html-policy! fixture/admin "hide-existing-public-results" "Synthetic daily view verification")
    (is (thrown-with-msg? Exception #"current extraction validation" (selections/select! reviewer (daily-request "unvalidated" [a] [(selected a "absent")]))))
    (validate-daily! a "daily-va") (validate-daily! b "daily-vb")
    (is (thrown-with-msg? Exception #"Daily view requires" (selections/select! reviewer (assoc (daily-request "complete" [a] [(selected a "daily-va")]) :coverage {:completeness :complete :gaps []}))))
    (revisions/propose! fixture/app
                        (assoc (revision-fixture/proposal a b "daily-link")
                               :revision-evidence [{:kind :correction-note :binding {:reference (:reference b) :path [:candidates 0 :raw :fields "Remarks"] :value "Revised synthetic report"}}]))
    (selections/select! reviewer (daily-request "daily-old" [a b] [(selected a "daily-va")]))
    (let [old-id (:result-id (first (public/results reader-url)))]
      (is (= 1 (count (public/results reader-url))))
      (is (thrown-with-msg? Exception #"Unreviewed" (selections/select! reviewer (daily-request "daily-unreviewed" [a b] [(selected b "daily-vb")]))))
      (decide-link! "daily-confirm" "daily-link" :confirm)
      (selections/select! reviewer (daily-request "daily-new" [a b] [(assoc (selected b "daily-vb") :relationship-id "daily-link")]))
      (is (= 1 (count (public/results reader-url))))
      (is (not= old-id (:result-id (first (public/results reader-url)))))
      (is (= [daily-gap] (get-in (first (public/results reader-url)) [:coverage :gaps])))
      (selections/rollback! reviewer {:id "daily-old-again" :actor "synthetic" :reason "restore" :selection-id "daily-old" :base (selections/snapshot reviewer)})
      (is (= [old-id] (mapv :result-id (public/results reader-url)))))))

(deftest daily-scope-aliases-cannot-enroll-the-same-participant-as-two-events
  (let [a (revision-fixture/daily-descriptor (revision-fixture/html-sample (revision-fixture/daily-document) "https://www.aidainternational.org/StartList/4349"))
        b (assoc-in (revision-fixture/daily-descriptor (revision-fixture/html-sample (str/replace (revision-fixture/daily-document) "Female" "F") "https://www.aidainternational.org/StartList/4349")) [:scope :category :value] "F")]
    (selections/select! reviewer (daily-request "daily-female" [a] []))
    (is (thrown-with-msg? Exception #"Overlapping daily" (selections/select! reviewer (assoc-in (daily-request "daily-f" [b] []) [:event-scope :category] "F"))))))

(deftest daily-views-must-enroll-explicitly-instead-of-bypassing-coverage-in-retained
  (let [pdf (sample "strict/1" revision-fixture/scope)
        daily (revision-fixture/daily-descriptor (revision-fixture/html-sample (str/replace (revision-fixture/daily-document) "Dqsp" "") "https://www.aidainternational.org/StartList/4349"))]
    (public/activate-html-policy! fixture/admin "hide-existing-public-results" "Synthetic retained check")
    (validate-daily! daily "daily-retained-validation")
    (is (thrown-with-msg? Exception #"Daily views require explicit enrollment" (selections/select! reviewer (assoc (request "strict" [pdf] []) :retained [{:descriptor daily :validation-id "daily-retained-validation"}]))))))

(deftest daily-profile-uuid-casing-cannot-create-a-second-attempt
  (let [profile "https://www.aidainternational.org/Profile-abcdefab-abcd-abcd-abcd-abcdefabcdef"
        upper "https://www.aidainternational.org/Profile-ABCDEFAB-ABCD-ABCD-ABCD-ABCDEFABCDEF"
        source (-> (revision-fixture/daily-document) (str/replace "Dqsp" "") (str/replace revision-fixture/daily-profile profile))
        make-descriptor (fn [source href]
                          (assoc-in (revision-fixture/daily-descriptor (revision-fixture/html-sample source "https://www.aidainternational.org/StartList/4349")) [:scope :source-athlete-id :value] href))
        a (make-descriptor source profile) b (make-descriptor (str/replace source profile upper) upper)]
    (public/activate-html-policy! fixture/admin "hide-existing-public-results" "Synthetic UUID casing check")
    (validate-daily! a "lower") (validate-daily! b "upper")
    (is (thrown-with-msg? Exception #"attempt|identifiers" (selections/select! reviewer (daily-request "both" [a b] [(selected a "lower") (selected b "upper")]))))))
