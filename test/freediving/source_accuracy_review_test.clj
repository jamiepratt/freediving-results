(ns freediving.source-accuracy-review-test
  (:require [clojure.test :refer [deftest is use-fixtures run-tests]]
            [clojure.string :as str]
            [freediving.private-sporting-proofs-test :as fixture]
            [freediving.observations-test :as db]
            [freediving.publication-test :as publication-fixture]
            [freediving.reviews :as reviews]
            [freediving.aida-html :as html]
            [freediving.aida-html-test :as html-fixture]
            [freediving.html-evidence :as evidence]
            [freediving.archive :as archive]
            [freediving.archive-test :as archive-fixture]
            [freediving.observations :as observations]
            [freediving.revisions :as revisions]
            [freediving.publication :as publication]
            [freediving.private-sporting-proofs :as proofs]))
(def review-url (str/replace db/app "user=observations_app" "user=sporting_source_review"))
(use-fixtures :each
  (fn [f] (fixture/with-database
            (fn []
              (fixture/role! "sporting_source_review" fixture/source-tables)
              (db/sql! db/admin "ALTER ROLE sporting_source_review SET default_transaction_read_only=off")
              (db/sql! db/admin "GRANT INSERT ON freediving.extraction_reviews,freediving.pdf_extraction_reviews TO sporting_source_review")
              (f)))))
(defn execute [config]
  (if-let [f (try (requiring-resolve 'freediving.source-accuracy-review/execute!) (catch Exception _ nil))]
    (f config) (throw (ex-info "Source accuracy API missing" {}))))
(defn sample []
  (let [target (publication-fixture/sample
                #(update % :candidates (fn [rows] (mapv (fn [row] (update row :coordinates assoc :column-start 1 :column-end 16)) rows))))
        stored ((requiring-resolve 'freediving.observations/inspect) db/app (:job-id target))
        artifact (:artifact stored)
        reference (assoc ((requiring-resolve 'freediving.revisions/reference) db/app target) :parser-version (:parser-version artifact))
        config {:jdbc_url fixture/proof-url :database "observations_test" :mode "source"
                :rows [{:reference reference :coordinates (get-in artifact [:candidates 0 :coordinates])}]}]
    {:target target :config config
     :request {:id "human-exact-accept" :action "accept" :reference reference :coordinates (get-in config [:rows 0 :coordinates])
               :expected_source_binding_sha256 (:binding_sha256 (fixture/read-proof config))
               :base_revision 0 :event_id nil :actor "owner" :reason "Synthetic explicit one-version review" :source_visual_accuracy true}}))
(defn command [request] {:jdbc_url review-url :database "observations_test" :request request})
(deftest exact-pdf-review-acceptance-and-revocation-are-genuine-independent-facts
  (let [{:keys [request config target]} (sample)
        accepted (execute (command request))
        proof (fixture/read-proof config)]
    (is (= "verified" (get-in proof [:rows 0 :upstream :review :value])))
    (is (nil? (get-in proof [:rows 0 :upstream :publication])))
    (is (= :accept (:action (first (reviews/pdf-extraction-history review-url target)))))
    (is (= (:receipt accepted) (:receipt (execute (command request)))))
    (execute (command (assoc request :id "human-revoke" :action "revoke" :event_id "human-exact-accept" :base_revision 1
                             :expected_source_binding_sha256 (:binding_sha256 proof))))
    (is (nil? (get-in (fixture/read-proof config) [:rows 0 :upstream :review])))))
(defn html-sample []
  (let [dir (archive-fixture/workspace) root (str dir "/archive")
        source (html-fixture/document html-fixture/cells)
        path (str dir "/source.html")
        _ (spit path source)
        hash (evidence/sha256 (.getBytes source "UTF-8"))
        render-sha (:sha256 (archive/retain-evidence! root (.getBytes "Synthetic rendered selected date" "UTF-8")))
        url "https://example.org/StartList/1"
        _ (archive/register! root path
                             (assoc archive-fixture/manifest :sha256 hash :content-type "text/html" :final-url url
                                    :provenance {:publisher-url url :redirect-chain [url]
                                                 :browser-state {:selected-date "2025-06-28" :filters {:discipline :all :gender :all}
                                                                 :representation :rendered-dom :rendered-sha256 render-sha}}))
        job (:job-id (html/extract! root hash {:actor "synthetic" :config {}}))
        _ (observations/import! db/app root job)
        target {:job-id job :ordinal 0}
        artifact (:artifact (observations/inspect db/app job))
        reference (assoc (revisions/reference db/app target) :parser-version (:parser-version artifact))
        config {:jdbc_url fixture/proof-url :database "observations_test" :mode "source"
                :rows [{:reference reference :coordinates {:table 1 :row 2}}]}
        request {:id "html-accept" :action "accept" :reference reference :coordinates {:table 1 :row 2}
                 :expected_source_binding_sha256 (:binding_sha256 (fixture/read-proof config))
                 :base_revision 0 :event_id nil :actor "owner" :reason "Faithfully extracted anomalous source" :source_visual_accuracy true}]
    {:target target :request request :config config}))
(deftest exact-html-accuracy-is-independent-of-anomalous-source-publication
  (let [{:keys [target request config]} (html-sample)]
    (execute (command request))
    (let [proof (fixture/read-proof config)]
      (is (= "verified" (get-in proof [:rows 0 :upstream :review :value])))
      (is (nil? (get-in proof [:rows 0 :upstream :publication])))
      (is (= :html (get-in (first (reviews/extraction-history review-url target)) [:evidence :source-kind])))
      (is (= "2025-06-28" (get-in (first (reviews/extraction-history review-url target)) [:evidence :selected-date])))
      (execute (command (assoc request :id "html-revoke" :action "revoke" :base_revision 1 :event_id "html-accept"
                               :expected_source_binding_sha256 (:binding_sha256 proof))))
      (is (nil? (get-in (fixture/read-proof config) [:rows 0 :upstream :review]))))))
(defn status-of [f] (try (f) nil (catch Exception e (:status (ex-data e)))))
(deftest stale-exact-scope-and-replay-only-never-add-authority
  (let [{:keys [request target config]} (sample)
        history #(reviews/pdf-extraction-history review-url target)]
    (is (= 409 (status-of #(execute (command (assoc request :expected_source_binding_sha256 (apply str (repeat 64 "0"))))))))
    (is (= 400 (status-of #(execute (command (update-in request [:coordinates :column-end] inc))))))
    (is (= 400 (status-of #(execute (command (assoc request :source_visual_accuracy false))))))
    (is (= 409 (status-of #(execute (assoc (command request) :replay_only true)))))
    (is (empty? (history)))
    (let [original (execute (command request))
          replay (execute (assoc (command request) :replay_only true))]
      (is (false? (:replayed original)))
      (is (true? (:replayed replay)))
      (is (= (:receipt original) (:receipt replay)))
      (is (= 1 (count (history))))
      (is (= "verified" (get-in (fixture/read-proof config) [:rows 0 :upstream :review :value])))
      (is (= 409 (status-of #(execute (assoc (command (assoc request :reason "Changed body")) :replay_only true)))))
      (is (= 1 (count (history)))))))
(defn policy-waiting? []
  (with-open [c (java.sql.DriverManager/getConnection db/admin)
              s (.prepareStatement c "SELECT EXISTS(SELECT 1 FROM pg_locks l JOIN pg_stat_activity a USING(pid) WHERE l.locktype='advisory' AND NOT l.granted AND a.query LIKE 'SELECT pg_advisory_xact_lock(781246935)%')")
              r (.executeQuery s)]
    (.next r) (.getBoolean r 1)))
(deftest policy-writer-waits-for-exact-review-then-invalidates-old-binding
  (let [{:keys [request target config]} (sample)
        acquired (promise) release (promise) first-call (atom true)
        capability proofs/source-review-capability!
        reviewer (with-redefs [proofs/source-review-capability!
                               (fn [c]
                                 (capability c)
                                 (when (compare-and-set! first-call true false)
                                   (deliver acquired true)
                                   (when (= :timeout (deref release 10000 :timeout))
                                     (throw (ex-info "Review latch timed out" {})))))]
                   (let [reviewer (future (execute (command request)))]
                     (try
                       (is (= true (deref acquired 5000 :timeout)))
                       (let [policy (future (publication/activate-policy! db/admin "extraction-publication/race" "Synthetic simultaneous policy update"))
                             deadline (+ (System/nanoTime) 5000000000)
                             waiting (loop [] (cond (policy-waiting?) true
                                                    (> (System/nanoTime) deadline) false
                                                    :else (recur)))]
                         (is waiting "Policy mutation is blocked on the same advisory lock")
                         (is (not (realized? policy)))
                         (deliver release true)
                         (is (map? (deref reviewer 5000 :timeout)))
                         (is (map? (deref policy 5000 :timeout))))
                       reviewer
                       (finally (deliver release true)))))]
    (is (realized? reviewer))
    (is (= 1 (count (reviews/pdf-extraction-history review-url target))))
    (is (= 409 (status-of #(execute (command (assoc request :id "stale-after-policy" :base_revision 1))))))
    (is (= 1 (count (reviews/pdf-extraction-history review-url target))))
    (is (= "verified" (get-in (fixture/read-proof config) [:rows 0 :upstream :review :value])))))
(deftest accuracy-receipt-does-not-deliver-or-transition-publication-policy
  (doseq [sample-fn [sample html-sample]]
    (let [{:keys [request config]} (sample-fn)
          _ (execute (command request))
          row (get-in (fixture/read-proof config) [:rows 0])
          diagnosis (get-in row [:diagnostics :publication])]
      (is (= "verified" (get-in row [:upstream :review :value])))
      (is (false? (:validated diagnosis)))
      (is (false? (:selected diagnosis)))
      (is (nil? (:delivered diagnosis)))
      (is (= "not-verified" (:delivery_state diagnosis)))
      (is (= (:reference request) (:version_binding diagnosis)))
      (is (= 1 (:source_accuracy_revision diagnosis)))
      (is (= 0 (:review_revision diagnosis)))
      (is (= publication/current-policy (:active_policy_version diagnosis)))
      (is (some #{:validation-required} (:validation_reasons diagnosis)))
      (if (contains? (:coordinates request) :table)
        (do (is (false? (:ready_for_validation diagnosis)))
            (is (= [:white-card-with-disqualification-remark] (get-in row [:source_review :anomalies])))
            (is (some #{[:substantive-source-flag :white-card-with-disqualification-remark]} (:substantive_errors diagnosis)))
            (is (= false (get-in diagnosis [:html_policy_transition :enabled]))))
        (is (true? (:ready_for_validation diagnosis)))))))
(defn -main [& _]
  (let [r (run-tests 'freediving.source-accuracy-review-test)] (shutdown-agents)
       (when (pos? (+ (:fail r) (:error r))) (System/exit 1))))
