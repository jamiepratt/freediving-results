(ns freediving.reviews-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [freediving.cmas-2025-indoor-json :as indoor-json]
            [freediving.cmas-2025-indoor-json-test :as indoor-fixture]
            [clojure.java.shell :as shell]
            [freediving.observations :as observations]
            [freediving.aida-html :as html]
            [freediving.html-evidence :as html-evidence]
            [freediving.aida-html-test :as html-fixture]
            [freediving.archive :as archive]
            [freediving.archive-test :as archive-fixture]
            [freediving.extraction-test :as extraction-fixture]
            [freediving.observations-test :as fixture]
            [freediving.reviews :as reviews]
            [freediving.candidates :as candidates]
            [freediving.evaluation :as evaluation]
            [freediving.spelling-normalization :as spelling]
            [freediving.jev-candidates :as jev-candidates]
            [freediving.evaluation-test :as evaluation-fixture]
            [freediving.evaluation-providers-test :as http-fixture]
            [clojure.data.json :as json]))
(def reviewer (System/getenv "FREEDIVING_TEST_REVIEW_URL"))
(use-fixtures :each (fn [f]
                      (fixture/sql! fixture/admin "DROP SCHEMA IF EXISTS freediving CASCADE")
                      (observations/migrate! fixture/admin "observations_app")
                      (reviews/migrate! fixture/admin "observations_app" "reviews_owner") (f)))
(defn sample []
  (let [{:keys [root artifact] :as s} (fixture/synthetic 1 "review-synthetic/1")]
    (fixture/publish! s) (observations/import! fixture/app root (:job-id artifact))
    {:job-id (:job-id artifact) :ordinal 0}))
(defn proposal [target id]
  (merge target {:id id :base-revision 0 :category :identity-matching :field :identity
                 :before {:outcome :unknown} :after {:outcome :matched :identity-id "synthetic-person-1"}
                 :evidence [{:page 1 :line 1}] :reason "Synthetic evidence" :actor "test-proposer"}))

(defn- scored-fixture []
  (let [target (sample)
        candidate (assoc target :ordinal 1)
        rows (candidates/load-corpus fixture/app {})
        record (fn [row]
                 (let [ref (str "local-observation:" (:job-id row) ":" (:ordinal row))
                       source {:evidence-id ref :source-sha256 (:source-sha256 row)
                               :artifact-sha256 (:artifact-sha256 row)
                               :observation-id (:candidate-id row)
                               :source-family-id (:source-sha256 row)
                               :exact-lines [(get-in row [:payload :raw :line])]}]
                   {:record-id ref :source-version (select-keys row [:parser-version :schema-version :source-format])
                    :fields {:name {:value (get-in row [:payload :parsed :source-name])
                                    :evidence-ids [ref]}}
                    :sources [source] :uncertainties []}))
        left (record (first rows)) right (record (second rows))
        case {:case-id "synthetic-pair" :split :held-out :input {:left left :right right}}
        result {:case-id "synthetic-pair" :batch-index 0 :request-hash "request"
                :trace-hash "trace" :model-version "jev-test" :outcome :match
                :confidence 0.96 :probabilities {:match 0.96 :no_match 0.02 :abstain 0.02}}
        run {:input {:configurations [{:id "jev"}]
                     :requests [[{:provider :jev :adapter-version "shadow-adapters/14"
                                  :config {:identity-protocol :freediving-compact-v3}}]]
                     :dataset {:cases [case]}}
             :report {:providers {"jev" {:results [result]
                                         :batches [{:dispatch-status :dispatched :trace-hash "trace"
                                                    :result-hash "result"
                                                    :attempt {:request-hash "request"
                                                              :completed-at "2026-09-26T00:00:00Z"
                                                              :result {:outcome :complete :http-status 200
                                                                       :model-version "jev-test"}}}]}}}}
        reference (fn [row] (merge (select-keys row [:job-id :ordinal :candidate-id :source-sha256 :artifact-sha256])
                                   (select-keys (first (:source-lines row)) [:page :line])))
        refs (mapv reference rows)
        selector {:run-id "run" :provider-id "jev" :case-id "synthetic-pair" :result-hash "result"}
        request (-> (proposal target "scored-p")
                    (assoc :after {:outcome :matched :identity-id (str "local-observation:" (:job-id candidate) ":1")}
                           :evidence refs :identity-target (second refs) :jev-score selector
                           :inspection {:both-versions-reviewed true :contrary-evidence-reviewed true
                                        :source-dependence-reviewed true}))]
    {:target target :candidate candidate :rows rows :run run :refs refs :request request}))

(deftest scored-identity-is-owner-approved-source-bound-and-reversible
  (let [{:keys [target candidate rows run refs request]} (scored-fixture)
        decision {:id "scored-a" :proposal-id "scored-p" :action :approve :base-revision 0
                  :actor "owner" :reason "Inspected both original source rows and uncertainty"}
        overrides {#'evaluation/inspect-run (fn [& _] run)
                   #'candidates/load-corpus (fn [& _] rows)
                   (requiring-resolve 'freediving.jev-candidates/candidate-case)
                   (fn [& _] {:case-id "synthetic-pair"})}]
    (with-redefs-fn overrides
      #(do
         (is (thrown? Exception (reviews/propose! fixture/app request)))
         (is (thrown? Exception (reviews/propose-scored-identity! "root" fixture/app
                                                                  (assoc request :evidence [(first refs)]))))
         (let [p (reviews/propose-scored-identity! "root" fixture/app request)]
           (is (= :complete (get-in p [:score-binding :score-status])))
           (is (= (:jev-score request) (get-in p [:score-binding :selector])))
           (is (= p (reviews/propose-scored-identity! "root" fixture/app request)))
           (is (= {:outcome :unknown} (:identity (reviews/effective fixture/app target))))
           (is (thrown-with-msg? Exception #"capability" (reviews/decide! fixture/app decision)))
           (let [approved (reviews/decide! reviewer decision)]
             (is (= approved (reviews/decide! reviewer decision)))
             (is (= (:after request) (:identity (reviews/effective fixture/app target))))
             (is (= {:outcome :unknown} (:identity (reviews/effective fixture/app candidate))))
             (is (thrown-with-msg? Exception #"Conflicting identity merge"
                                   (reviews/propose-scored-identity!
                                    "root" fixture/app
                                    (-> request
                                        (assoc :id "conflicting-anchor" :job-id (:job-id candidate)
                                               :ordinal (:ordinal candidate)
                                               :after {:outcome :no-match})
                                        (dissoc :identity-target)))))
             (is (= 1 (:revision approved)))
             (is (= "reviews_owner" (:db-role approved)))
             (is (= {:outcome :unknown} (:before approved)))
             (is (= (:after request) (:after approved)))
             (is (= refs (:evidence approved)))
             (is (string? (:recorded-at approved)))
             (is (thrown-with-msg? Exception #"Stale" (reviews/decide! reviewer (assoc decision :id "late"))))
             (let [reversed (reviews/decide! reviewer {:id "scored-r" :event-id "scored-a" :action :reverse
                                                       :base-revision 1 :actor "owner" :reason "Reconsidered evidence"})]
               (is (= (:after request) (:before reversed)))
               (is (= {:outcome :unknown} (:after reversed)))
               (is (= refs (:evidence reversed))))
             (is (= {:outcome :unknown} (:identity (reviews/effective fixture/app target))))
             (is (= 3 (count (reviews/history fixture/app target))))
             (is (thrown? java.sql.SQLException
                          (fixture/sql! reviewer "UPDATE freediving.review_decisions SET id=id")))))))))

(deftest scored-no-match-unknown-rejection-and-stale-score-stay-distinct
  (let [{:keys [target rows run request]} (scored-fixture)
        stored (atom run)
        overrides {#'evaluation/inspect-run (fn [& _] @stored)
                   #'candidates/load-corpus (fn [& _] rows)
                   (requiring-resolve 'freediving.jev-candidates/candidate-case)
                   (fn [& _] {:case-id "synthetic-pair"})}
        decide (fn [id proposal base action]
                 (reviews/decide! reviewer {:id id :proposal-id proposal :base-revision base
                                            :action action :actor "owner" :reason "Inspected exact source pair"}))]
    (with-redefs-fn overrides
      #(do
         (is (thrown? Exception
                      (reviews/propose-scored-identity! "root" fixture/app
                                                        (assoc request :inspection {:both-versions-reviewed true}))))
         (is (thrown? Exception
                      (reviews/propose-scored-identity! "root" fixture/app
                                                        (assoc-in request [:jev-score :result-hash] "forged"))))
         (let [unknown (-> request (assoc :id "unknown" :after {:outcome :unknown})
                           (dissoc :identity-target))]
           (reviews/propose-scored-identity! "root" fixture/app unknown)
           (is (= :approve (:action (decide "unknown-a" "unknown" 0 :approve))))
           (is (= {:outcome :unknown} (:identity (reviews/effective fixture/app target))))
           (is (= 1 (:revision (reviews/effective fixture/app target)))))
         (let [no-match (-> request (assoc :id "no-match" :base-revision 1
                                           :after {:outcome :no-match})
                            (dissoc :identity-target))]
           (reviews/propose-scored-identity! "root" fixture/app no-match)
           (swap! stored assoc-in [:report :providers "jev" :batches 0 :result-hash] "changed")
           (is (thrown-with-msg? Exception #"Stale|mismatched"
                                 (decide "stale-score" "no-match" 1 :approve)))
           (reset! stored run)
           (is (= :reject (:action (decide "reject-no-match" "no-match" 1 :reject))))
           (is (= {:outcome :unknown} (:identity (reviews/effective fixture/app target)))))
         (let [no-match (-> request (assoc :id "no-match-2" :base-revision 2
                                           :after {:outcome :no-match})
                            (dissoc :identity-target))]
           (reviews/propose-scored-identity! "root" fixture/app no-match)
           (decide "no-match-a" "no-match-2" 2 :approve)
           (is (= {:outcome :no-match} (:identity (reviews/effective fixture/app target))))
           (is (= 3 (:revision (reviews/effective fixture/app target))))
           (is (thrown-with-msg? Exception #"Stale"
                                 (decide "late" "no-match-2" 2 :approve))))))))

(deftest archived-jev-score-requires-explicit-owner-decision
  (let [{:keys [root artifact] :as first-source} (fixture/synthetic 1 "review-synthetic/1")
        bytes (.getBytes (extraction-fixture/synthetic-pdf
                          "BT /F1 12 Tf 40 750 Td (Second synthetic result page) Tj ET") "UTF-8")
        sha (html-evidence/sha256 bytes)
        path (str root "/second-source.pdf")]
    (java.nio.file.Files/write (java.nio.file.Paths/get path (make-array String 0)) bytes
                               (make-array java.nio.file.OpenOption 0))
    (archive/register! root path (assoc archive-fixture/manifest :sha256 sha))
    (let [second-identity (assoc artifact :source-sha256 sha
                                 :acquisitions (:acquisitions (archive/inspect root sha)))
          second-source (assoc second-identity :job-id
                               (fixture/hash-value (select-keys second-identity observations/identity-keys)))
          target {:job-id (:job-id artifact) :ordinal 0}
          candidate {:job-id (:job-id second-source) :ordinal 0}]
      (fixture/publish! first-source)
      (fixture/publish! {:root root :artifact second-source})
      (observations/import! fixture/app root (:job-id artifact))
      (observations/import! fixture/app root (:job-id second-source))
      (let [corpus (candidates/load-corpus reviewer {})
            pair (jev-candidates/candidate-case reviewer corpus target candidate)]
        (is (= :held-out (:split pair)))
        (http-fixture/with-server
          (fn [exchange]
            (http-fixture/reply! exchange 200
                                 (json/write-str
                                  {:model "jev-1.13.0" :usage {:input_tokens 27}
                                   :answers {:identity_0 {:type "choice" :choice "match" :confidence 0.96
                                                          :probabilities {:match 0.96 :no_match 0.02 :abstain 0.02}}
                                             :spelling_0 {:type "choice" :choice "unknown" :confidence 0.96
                                                          :probabilities {:left 0.01 :right 0.01
                                                                          :equally_plausible 0.01 :unknown 0.96
                                                                          :not_applicable 0.01}}}})))
          (fn [url]
            (let [run-root (evaluation-fixture/root)
                  receipt (jev-candidates/score-and-normalize!
                           run-root reviewer target candidate
                           {:id "jev" :provider :jev :model "jev-1.13.0" :endpoint url
                            :identity-protocol :freediving-compact-v3 :max-attempts 1}
                           {:jev-dispatch-gate :credential-checks-passed-v1
                            :providers {"jev" {:bearer-token "fixture-secret"}}})
                  score (first (spelling/score-view run-root (:run-id receipt) "jev" target
                                                    (candidates/load-corpus reviewer {})))
                  refs [(:target-reference score) (:candidate-reference score)]
                  p (-> (proposal target "real-score")
                        (assoc :after {:outcome :matched
                                       :identity-id (str "local-observation:" (:job-id candidate) ":0")}
                               :identity-target (second refs) :evidence refs
                               :jev-score {:run-id (:run-id receipt) :provider-id "jev"
                                           :case-id (:case-id pair) :result-hash (:result-hash score)}
                               :inspection {:both-versions-reviewed true :contrary-evidence-reviewed true
                                            :source-dependence-reviewed true}))]
              (is (= :complete (:score-status score)))
              (is (= {:outcome :unknown} (:identity (reviews/effective reviewer target))))
              (reviews/propose-scored-identity! run-root fixture/app p)
              (is (= {:outcome :unknown} (:identity (reviews/effective reviewer target))))
              (reviews/decide! reviewer {:id "real-owner-a" :proposal-id "real-score" :action :approve
                                         :base-revision 0 :actor "owner" :reason "Inspected original source versions"})
              (is (= (:after p) (:identity (reviews/effective reviewer target)))))))))))

(defn json-observation []
  (let [dir (archive-fixture/workspace) root (str dir "/archive") path (str dir "/source.json")
        bytes (indoor-fixture/source [indoor-fixture/row (assoc indoor-fixture/row "PlaLane" "2")])
        hash (html-evidence/sha256 bytes)
        manifest (assoc archive-fixture/manifest :sha256 hash
                        :discovery-url indoor-fixture/json-url :final-url indoor-fixture/json-url
                        :content-type "application/json"
                        :provenance {:publisher-url indoor-fixture/json-url
                                     :redirect-chain [indoor-fixture/json-url]
                                     :source-page-url indoor-fixture/view-url})]
    (java.nio.file.Files/write (java.nio.file.Paths/get path (make-array String 0)) bytes
                               (make-array java.nio.file.OpenOption 0))
    (archive/register! root path manifest)
    (let [job (:job-id (indoor-json/extract! root hash {:actor "synthetic" :config {}}))]
      (observations/import! fixture/app root job)
      (let [inspection (observations/inspect fixture/app job)]
        {:job-id job :ordinal 0 :candidate-id (:candidate_id (first (:observations inspection)))
         :source-sha256 hash :artifact-sha256 (html-evidence/sha256 (:artifact-bytes inspection))
         :parser-version (:parser-version (:artifact inspection))
         :source-page-url indoor-fixture/view-url :row-index-zero-based 0}))))

(deftest scored-identity-accepts-registered-json-citation-and-rejects-forged-row
  (let [{:keys [target run request]} (scored-fixture)
        json-ref (json-observation)
        rows (candidates/load-corpus fixture/app {})
        json-row (some #(when (= (:job-id json-ref) (:job-id %)) %) rows)
        source {:evidence-id "json-source" :source-sha256 (:source-sha256 json-row)
                :artifact-sha256 (:artifact-sha256 json-row)
                :observation-id (:candidate-id json-row)
                :source-family-id (:source-sha256 json-row)}
        record {:record-id (str "local-observation:" (:job-id json-ref) ":0")
                :source-version (select-keys json-row [:parser-version :schema-version :source-format])
                :fields {:name {:value (get-in json-row [:payload :parsed :source-name])
                                :evidence-ids ["json-source"]}}
                :sources [source] :uncertainties []}
        run (assoc-in run [:input :dataset :cases 0 :input :right] record)
        request (assoc request :evidence [(first (:evidence request)) json-ref]
                       :identity-target json-ref
                       :after {:outcome :matched
                               :identity-id (str "local-observation:" (:job-id json-ref) ":0")})
        overrides {#'evaluation/inspect-run (fn [& _] run)
                   #'candidates/load-corpus (fn [& _] rows)
                   (requiring-resolve 'freediving.jev-candidates/candidate-case)
                   (fn [& _] {:case-id "synthetic-pair"})}]
    (with-redefs-fn overrides
      #(do
         (is (thrown? Exception
                      (reviews/propose-scored-identity! "root" fixture/app
                                                        (assoc request :evidence [(first (:evidence request))
                                                                                  (assoc json-ref :row-index-zero-based 1)]))))
         (reviews/propose-scored-identity! "root" fixture/app request)
         (reviews/decide! reviewer {:id "json-a" :proposal-id "scored-p" :action :approve
                                    :base-revision 0 :actor "owner" :reason "Inspected JSON row and PDF line"})
         (is (= (:after request) (:identity (reviews/effective fixture/app target))))))))

(defn extraction-request [ref id]
  {:id id :job-id (:job-id ref) :ordinal (:ordinal ref) :base-revision 0
   :evidence ref :owner-receipt-sha256 (apply str (repeat 64 "a"))
   :owner-response {:task-id "test-task" :user-message-id "test-message"
                    :response-annotation-index 1 :selected-text "Accept 0-1"}
   :actor "owner-label" :reason "Synthetic exact-source extraction review"})

(deftest json-extraction-acceptance-is-separate-from-identity-and-publication
  (let [ref (json-observation) target (select-keys ref [:job-id :ordinal])
        request (extraction-request ref "accept-zero")
        accepted (reviews/accept-extraction! reviewer request)]
    (is (= accepted (reviews/accept-extraction! reviewer request)))
    (is (= :accept (:action accepted)))
    (is (= 1 (:revision accepted)))
    (is (= 1 (:revision (reviews/extraction-effective reviewer target))))
    (is (= :accepted (:status (reviews/extraction-effective reviewer target))))
    (is (= {:outcome :unknown} (:identity (reviews/effective reviewer target))))
    (is (= 0 (:revision (reviews/effective reviewer target))))
    (is (thrown? Exception (reviews/accept-extraction! reviewer
                                                       (assoc request :identity-id "forged-identity"))))
    (is (thrown? Exception (reviews/accept-extraction! reviewer
                                                       (assoc request :selection-status :selected))))
    (is (= :blocked (get-in (:artifact (observations/inspect fixture/app (:job-id ref))) [:publication :status])))))

(deftest json-extraction-acceptance-rejects-forged-provenance-and-role
  (let [ref (json-observation) request (extraction-request ref "accept-zero")]
    (doseq [bad [(assoc ref :row-index-zero-based 1)
                 (assoc ref :row-index-zero-based 2)
                 (assoc ref :source-sha256 (apply str (repeat 64 "0")))
                 (assoc ref :artifact-sha256 (apply str (repeat 64 "0")))
                 (assoc ref :parser-version "cmas-2025-indoor-json/3")
                 (assoc ref :candidate-id (apply str (repeat 64 "0")))
                 (assoc ref :source-page-url "https://example.org/forged")]]
      (is (thrown? Exception (reviews/accept-extraction! reviewer
                                                         (assoc request :evidence bad)))))
    (is (thrown-with-msg? Exception #"capability"
                          (reviews/accept-extraction! fixture/app request)))
    (is (= [] (reviews/extraction-history reviewer (select-keys ref [:job-id :ordinal]))))))

(deftest json-extraction-acceptance-has-idempotent-append-only-revocation
  (let [ref (json-observation) target (select-keys ref [:job-id :ordinal])
        request (extraction-request ref "accept-zero")
        accepted (reviews/accept-extraction! reviewer request)
        revoke (merge target {:id "revoke-zero" :base-revision 1 :event-id "accept-zero"
                              :actor "owner-label" :reason "Synthetic reconsideration"})]
    (is (= "reviews_owner" (:db-role accepted)))
    (is (string? (:recorded-at accepted)))
    (is (= (:owner-receipt-sha256 request) (:owner-receipt-sha256 accepted)))
    (is (= (:owner-response request) (:owner-response accepted)))
    (is (thrown-with-msg? Exception #"idempotency"
                          (reviews/accept-extraction! reviewer (assoc request :reason "changed"))))
    (is (thrown-with-msg? Exception #"Stale"
                          (reviews/accept-extraction! reviewer (assoc request :id "stale"))))
    (is (thrown-with-msg? Exception #"active"
                          (reviews/revoke-extraction! reviewer (assoc revoke :event-id "forged"))))
    (is (= :revoke (:action (reviews/revoke-extraction! reviewer revoke))))
    (is (= :unreviewed (:status (reviews/extraction-effective reviewer target))))
    (is (= 2 (:revision (reviews/extraction-effective reviewer target))))
    (is (= 2 (count (reviews/extraction-history reviewer target))))
    (is (= (reviews/revoke-extraction! reviewer revoke)
           (last (reviews/extraction-history reviewer target))))
    (is (thrown? java.sql.SQLException
                 (fixture/sql! reviewer "UPDATE freediving.extraction_reviews SET id=id")))
    (is (= {:outcome :unknown} (:identity (reviews/effective reviewer target))))))
(deftest pending-approval-and-reversal-retain-original
  (let [target (sample) before (observations/inspect fixture/app (:job-id target))
        p (reviews/propose! fixture/app (proposal target "p1"))]
    (is (= 0 (:revision (reviews/effective fixture/app target))))
    (is (= {:outcome :unknown} (:identity (reviews/effective fixture/app target))))
    (is (= p (reviews/propose! fixture/app (proposal target "p1"))))
    (reviews/decide! reviewer {:id "a1" :proposal-id "p1" :action :approve :base-revision 0 :actor "owner" :reason "Checked synthetic evidence"})
    (is (= {:outcome :matched :identity-id "synthetic-person-1"} (:identity (reviews/effective fixture/app target))))
    (reviews/decide! reviewer {:id "r1" :event-id "a1" :action :reverse :base-revision 1 :actor "owner" :reason "Undo synthetic decision"})
    (is (= {:outcome :unknown} (:identity (reviews/effective fixture/app target))))
    (is (= 3 (count (reviews/history fixture/app target))))
    (is (= (:observations before) (:observations (observations/inspect fixture/app (:job-id target)))))))

(deftest jev-spelling-normalization-uses-reversible-review-audit
  (let [t (sample)
        target (first (filter #(= t (select-keys % [:job-id :ordinal]))
                              (candidates/load-corpus reviewer {})))
        left {:job-id "other" :ordinal 0 :kind "result-row" :candidate-id "other-candidate"
              :source-sha256 "other-source" :artifact-sha256 "other-artifact"
              :payload {:parsed {:source-name "Example"}}}
        record (fn [row]
                 {:record-id (str "local-observation:" (:job-id row) ":" (:ordinal row))
                  :fields {:name {:value (get-in row [:payload :parsed :source-name])
                                  :evidence-ids ["name-source"]}}
                  :sources [{:evidence-id "name-source" :source-sha256 (:source-sha256 row)
                             :artifact-sha256 (:artifact-sha256 row)
                             :observation-id (:candidate-id row)}]})
        case {:case-id "pair" :split :held-out
              :input {:left (record left) :right (record target)}}
        result {:case-id "pair" :batch-index 0 :request-hash "request" :trace-hash "trace"
                :model-version "jev-1.13.0" :outcome :match :confidence 0.98
                :probabilities {:match 0.96 :no_match 0.02 :abstain 0.02}
                :spelling {:outcome :left :confidence 0.98
                           :probabilities {:left 0.96 :right 0.01 :equally_plausible 0.01
                                           :unknown 0.01 :not_applicable 0.01}}}
        run {:input {:configurations [{:id "jev"}]
                     :requests [[{:provider :jev :adapter-version "shadow-adapters/14"
                                  :config {:identity-protocol :freediving-compact-v3}}]]
                     :dataset {:cases [case]}}
             :report {:providers {"jev" {:results [result]
                                         :batches [{:dispatch-status :dispatched :trace-hash "trace"
                                                    :attempt {:request-hash "request"
                                                              :result {:outcome :complete :http-status 200
                                                                       :model-version "jev-1.13.0"}}}]}}}}
        applied (with-redefs [evaluation/inspect-run (fn [& _] run)
                              candidates/load-corpus (fn [& _] [left target])]
                  (first (spelling/apply-run! "private-root" "run" "jev" reviewer)))
        state (reviews/effective reviewer t)]
    (is (= :applied (:status applied)))
    (is (= "Example" (get-in state [:fields :source-name])))
    (is (= 2 (count (reviews/history reviewer t))))
    (is (= "Éxample" (get-in target [:payload :parsed :source-name])))
    (reviews/decide! reviewer {:id "reverse-jev" :event-id (:approval-id applied)
                               :action :reverse :base-revision (:revision state)
                               :actor "owner" :reason "Restore original spelling"})
    (is (= "Éxample" (get-in (reviews/effective reviewer t) [:fields :source-name])))))
(defn -main [& _]
  (let [r (clojure.test/run-tests 'freediving.reviews-test)]
    (shutdown-agents) (when (pos? (+ (:fail r) (:error r))) (System/exit 1))))
(deftest rejection-corrections-and-stacked-reversals
  (let [t (sample) p (proposal t "p1")]
    (reviews/propose! fixture/app p)
    (reviews/decide! reviewer {:id "reject" :proposal-id "p1" :action :reject :base-revision 0 :actor "owner" :reason "No support"})
    (is (= {:outcome :unknown} (:identity (reviews/effective fixture/app t))))
    (doseq [[id base before after] [["p2" 1 "Éxample" "Example"] ["p3" 2 "Example" "Example A"]]]
      (reviews/propose! fixture/app (merge p {:id id :base-revision base :category :name-normalization :field :source-name :before before :after after}))
      (reviews/decide! reviewer {:id (str "a" id) :proposal-id id :action :approve :base-revision base :actor "owner" :reason "Synthetic normalization"}))
    (is (= "Example A" (get-in (reviews/effective fixture/app t) [:fields :source-name])))
    (is (thrown-with-msg? Exception #"active" (reviews/decide! reviewer {:id "bad-reverse" :event-id "ap2" :action :reverse :base-revision 3 :actor "owner" :reason "Not top"})))
    (doseq [[id event base expected] [["r3" "ap3" 3 "Example"] ["r2" "ap2" 4 "Éxample"]]]
      (reviews/decide! reviewer {:id id :event-id event :action :reverse :base-revision base :actor "owner" :reason "Undo"})
      (is (= expected (get-in (reviews/effective fixture/app t) [:fields :source-name]))))
    (is (thrown-with-msg? Exception #"active" (reviews/decide! reviewer {:id "repeat" :event-id "ap2" :action :reverse :base-revision 5 :actor "owner" :reason "Again"})))))
(deftest conflicting-concurrent-approvals-and-idempotency
  (let [t (sample) p (proposal t "p1")]
    (reviews/propose! fixture/app p)
    (reviews/propose! fixture/app (assoc p :id "p2" :after {:outcome :no-match}))
    (let [gate (promise) requests (mapv #(hash-map :id (str "a" %) :proposal-id % :action :approve :base-revision 0 :actor "owner" :reason "Check") ["p1" "p2"])
          workers (mapv (fn [r] (future @gate (try (reviews/decide! reviewer r) (catch Exception _ :conflict)))) requests)]
      (deliver gate true)
      (let [results (mapv deref workers) winner (first (filter map? results)) request (:request winner)]
        (is (= 1 (count (filter #{:conflict} results))))
        (is (= 1 (:revision (reviews/effective fixture/app t))))
        (is (= winner (reviews/decide! reviewer request)))
        (is (thrown-with-msg? Exception #"idempotency" (reviews/decide! reviewer (assoc request :reason "changed"))))))
    (is (thrown-with-msg? Exception #"idempotency" (reviews/propose! fixture/app (assoc p :reason "changed"))))))
(deftest invalid-proposals-and-privileges
  (let [t (sample) p (proposal t "p1")]
    (doseq [[change message] [[#(assoc % :ordinal 99) #"Unknown"]
                              [#(assoc % :evidence []) #"evidence"]
                              [#(assoc % :evidence [{:page 99 :line 1}]) #"evidence"]
                              [#(assoc % :before nil) #"Before"]
                              [#(assoc % :base-revision 1) #"Stale"]
                              [#(assoc % :category :automatic) #"category"]
                              [#(assoc % :after {:outcome :matched}) #"identity"]]]
      (is (thrown-with-msg? Exception message (reviews/propose! fixture/app (change p)))))
    (reviews/propose! fixture/app p)
    (is (thrown-with-msg? Exception #"capability" (reviews/decide! fixture/app {:id "a1" :proposal-id "p1" :action :approve :base-revision 0 :actor "owner" :reason "forged actor"})))
    (doseq [url [fixture/app reviewer] table ["review_proposals" "review_decisions"] op ["UPDATE %s SET id=id" "DELETE FROM %s" "TRUNCATE %s"]]
      (is (thrown? java.sql.SQLException (fixture/sql! url (format op (str "freediving." table))))))
    (is (thrown? java.sql.SQLException (fixture/sql! fixture/app "INSERT INTO freediving.review_decisions(id,job_id,ordinal,revision,action,body_edn) VALUES('fake','x',0,1,'approve','{}')")))
    (is (= 0 (:revision (reviews/effective fixture/app t))))))
(deftest forged-ingest-proposal-cannot-cross-observation-envelope
  (let [t (sample) forged (assoc (proposal t "body-id") :ordinal 1 :action :propose)]
    (with-open [c (java.sql.DriverManager/getConnection fixture/app)
                s (.prepareStatement c "INSERT INTO freediving.review_proposals(id,job_id,ordinal,body_edn) VALUES('sql-id',?,0,?)")]
      (.setString s 1 (:job-id t)) (.setString s 2 (pr-str forged)) (.executeUpdate s))
    (is (thrown-with-msg? Exception #"envelope" (reviews/decide! reviewer {:id "forged-approval" :proposal-id "sql-id" :action :approve :base-revision 0 :actor "owner" :reason "Must reject forged body"})))
    (is (= {:outcome :unknown} (:identity (reviews/effective fixture/app t))))))
(deftest substantive-and-repair-fields-preserve-artifact-bytes
  (let [t (sample) original (observations/inspect fixture/app (:job-id t)) p (proposal t "correction")]
    (doseq [[id base category field before after] [["correction" 0 :substantive-correction :performance 1 2]
                                                   ["repair" 1 :extraction-repair :unit nil "m"]]]
      (reviews/propose! fixture/app (merge p {:id id :base-revision base :category category :field field :before before :after after}))
      (reviews/decide! reviewer {:id (str "approve-" id) :proposal-id id :action :approve :base-revision base :actor "owner" :reason "Synthetic source check"}))
    (is (= 2 (get-in (reviews/effective fixture/app t) [:fields :performance])))
    (is (= "m" (get-in (reviews/effective fixture/app t) [:fields :unit])))
    (is (= (seq (:artifact-bytes original)) (seq (:artifact-bytes (observations/inspect fixture/app (:job-id t))))))
    (let [p (first (reviews/history fixture/app t))]
      (is (= "observations_app" (:db-role p)))
      (is (= (:job-id t) (get-in p [:observation :job-id])))
      (is (= 64 (count (get-in p [:observation :artifact-sha256])))))))
(deftest migration-checksum-and-role-boundaries
  (is (= {:schema-version 2} (reviews/migrate! fixture/admin "observations_app" "reviews_owner")))
  (is (thrown-with-msg? Exception #"Separate" (reviews/migrate! fixture/admin "observations_app" "observations_app")))
  (fixture/sql! fixture/admin "UPDATE freediving.schema_migrations SET sha256='tampered' WHERE version=2")
  (is (thrown-with-msg? Exception #"checksum" (reviews/migrate! fixture/admin "observations_app" "reviews_owner"))))
(deftest fragments-and-unclassified-cannot-be-reviewed
  (let [t (sample)]
    ;; Synthetic fixture only: manufacture immutable non-result observations under separate ordinals.
    (doseq [[ordinal kind] [[2 "fragment"] [3 "unclassified"]]]
      (fixture/sql! fixture/admin (str "INSERT INTO freediving.observations(job_id,ordinal,candidate_id,kind,classification_reason,payload_edn) VALUES('" (:job-id t) "'," ordinal ",'synthetic-" ordinal "','" kind "','test','{}')"))
      (is (thrown-with-msg? Exception #"result-row" (reviews/propose! fixture/app (proposal (assoc t :ordinal ordinal) (str "p" ordinal))))))))
(deftest public-role-and-reapplied-role-grants
  (let [t (sample) p (proposal t "p1") public (System/getenv "FREEDIVING_TEST_PUBLIC_URL")]
    (reviews/propose! fixture/app p)
    (fixture/sql! fixture/admin "GRANT USAGE ON SCHEMA freediving TO reviews_public")
    (fixture/sql! fixture/admin "GRANT SELECT ON ALL TABLES IN SCHEMA freediving TO reviews_public")
    (is (= 0 (:revision (reviews/effective public t))))
    (is (thrown-with-msg? Exception #"capability" (reviews/decide! public {:id "a" :proposal-id "p1" :action :approve :base-revision 0 :actor "owner" :reason "No authority"})))
    (fixture/sql! fixture/admin "GRANT INSERT ON freediving.observations TO reviews_owner")
    (fixture/sql! fixture/admin "GRANT CREATE ON SCHEMA freediving TO reviews_owner,observations_app")
    (reviews/migrate! fixture/admin "observations_app" "reviews_owner")
    (is (thrown? java.sql.SQLException (fixture/sql! reviewer "INSERT INTO freediving.observations SELECT * FROM freediving.observations LIMIT 0")))
    (is (thrown? java.sql.SQLException (fixture/sql! reviewer "CREATE TABLE freediving.forbidden(x int)")))
    (is (thrown? java.sql.SQLException (fixture/sql! fixture/app "CREATE TABLE freediving.forbidden(x int)")))))
(deftest forged-provenance-is-not-approved
  (let [t (sample) p (reviews/propose! fixture/app (proposal t "valid"))
        forged (assoc p :id "forged" :observation (assoc (:observation p) :source-sha256 "fake"))]
    (with-open [c (java.sql.DriverManager/getConnection fixture/app)
                s (.prepareStatement c "INSERT INTO freediving.review_proposals(id,job_id,ordinal,body_edn) VALUES('forged',?,0,?)")]
      (.setString s 1 (:job-id t)) (.setString s 2 (pr-str forged)) (.executeUpdate s))
    (is (thrown-with-msg? Exception #"provenance" (reviews/decide! reviewer {:id "a" :proposal-id "forged" :action :approve :base-revision 0 :actor "owner" :reason "Forged provenance"})))
    (is (= 0 (:revision (reviews/effective fixture/app t))))))
(deftest request-metadata-cannot-fabricate-history
  (let [t (sample) p (proposal t "p")]
    (is (thrown-with-msg? Exception #"Unexpected" (reviews/propose! fixture/app (assoc p :db-role "owner"))))
    (reviews/propose! fixture/app p)
    (is (thrown-with-msg? Exception #"Unexpected" (reviews/decide! reviewer {:id "a" :proposal-id "p" :event-id "unrelated" :action :approve :base-revision 0 :actor "owner" :reason "Check"})))))
(deftest concurrent-retries-and-explicit-no-match
  (let [t (sample) p (assoc (proposal t "p") :after {:outcome :no-match})
        request {:id "a" :proposal-id "p" :action :approve :base-revision 0 :actor "owner" :reason "No matching identity"}]
    (reviews/propose! fixture/app p)
    (let [gate (promise) workers (mapv (fn [_] (future @gate (reviews/decide! reviewer request))) (range 4))]
      (deliver gate true)
      (is (apply = (mapv deref workers))))
    (is (= {:outcome :no-match} (:identity (reviews/effective fixture/app t))))
    (let [r {:id "r" :event-id "a" :action :reverse :base-revision 1 :actor "owner" :reason "Reconsider"}
          result (reviews/decide! reviewer r)]
      (is (= result (reviews/decide! reviewer r)))
      (is (thrown-with-msg? Exception #"Stale" (reviews/decide! reviewer (assoc r :id "stale")))))
    (is (= {:outcome :unknown} (:identity (reviews/effective fixture/app t))))
    (reviews/propose! fixture/app (assoc (proposal t "p2") :base-revision 2))
    (let [r {:id "reject" :proposal-id "p2" :action :reject :base-revision 2 :actor "owner" :reason "Unresolved"}
          result (reviews/decide! reviewer r)]
      (is (= result (reviews/decide! reviewer r))))
    (is (= 3 (:revision (reviews/effective fixture/app t))))))
(deftest cli-requires-exactly-one-edn-request
  (let [file (java.io.File/createTempFile "review-request-" ".edn")]
    (try
      (spit file "{:job-id \"synthetic\" :ordinal 0} {:ignored \"second request\"}")
      (let [r (shell/sh "java" "-cp" (System/getProperty "java.class.path") "clojure.main" "-m" "freediving.reviews" "effective" (.getPath file))]
        (is (= 1 (:exit r)))
        (is (re-find #"Expected one EDN request" (:err r))))
      (finally (.delete file)))))

(defn cross-reference
  ([] (cross-reference identity))
  ([transform]
   (let [{:keys [root artifact]} (fixture/synthetic 1 "review-cross-source/1")
         source (str (.getParent (java.io.File. root)) "/cross-source")
         pdf (extraction-fixture/synthetic-pdf "BT /F1 12 Tf 40 750 Td (Distinct synthetic source) Tj ET")
         _ (spit source pdf :encoding "UTF-8")
         hash (.formatHex (java.util.HexFormat/of)
                          (.digest (java.security.MessageDigest/getInstance "SHA-256")
                                   (.getBytes pdf "UTF-8")))
         _ (archive/register! root source (assoc archive-fixture/manifest :sha256 hash))
         artifact (assoc artifact :source-sha256 hash
                         :acquisitions (:acquisitions (archive/inspect root hash)))
         artifact (transform (assoc artifact :job-id (fixture/hash-value (select-keys artifact observations/identity-keys))))]
     (fixture/publish! {:root root :artifact artifact})
     (observations/import! fixture/app root (:job-id artifact))
     (let [inspection (observations/inspect fixture/app (:job-id artifact))]
       {:job-id (:job-id artifact) :ordinal 0
        :candidate-id (:candidate_id (first (:observations inspection)))
        :source-sha256 (:source-sha256 artifact)
        :artifact-sha256 (.formatHex (java.util.HexFormat/of)
                                     (.digest (java.security.MessageDigest/getInstance "SHA-256") ^bytes (:artifact-bytes inspection)))
        :page 1 :line 1}))))
(deftest registered-cross-source-evidence-survives-approval-and-reversal
  (let [t (sample) ref (cross-reference)
        p (assoc (proposal t "cross") :evidence [{:page 1 :line 1} ref])]
    (is (= (:evidence p) (:evidence (reviews/propose! fixture/app p))))
    (reviews/decide! reviewer {:id "cross-approve" :proposal-id "cross" :action :approve :base-revision 0 :actor "owner" :reason "Synthetic cross-source evidence"})
    (is (= (:after p) (:identity (reviews/effective fixture/app t))))
    (reviews/decide! reviewer {:id "cross-reverse" :event-id "cross-approve" :action :reverse :base-revision 1 :actor "owner" :reason "Undo"})
    (is (= {:outcome :unknown} (:identity (reviews/effective fixture/app t))))
    (is (= (:evidence p) (:evidence (first (reviews/history fixture/app t)))))))

(deftest matched-anchor-is-retained-after-rejection
  (let [t (sample) ref (cross-reference)
        id (str "local-observation:" (:job-id ref) ":" (:ordinal ref))
        p (assoc (proposal t "anchor") :identity-target ref :after {:outcome :matched :identity-id id}
                 :evidence [ref])]
    (is (= ref (:identity-target (reviews/propose! fixture/app p))))
    (reviews/decide! reviewer {:id "reject-anchor" :proposal-id "anchor" :action :reject :base-revision 0 :actor "owner" :reason "Candidate unsupported"})
    (is (= ref (:identity-target (first (reviews/history fixture/app t)))))
    (is (= {:outcome :unknown} (:identity (reviews/effective fixture/app t))))))

(deftest registered-evidence-rejects-forged-and-incomplete-references
  (let [t (sample) ref (cross-reference) p (assoc (proposal t "bad") :evidence [ref])]
    (doseq [bad [(assoc ref :job-id "unregistered") (assoc ref :ordinal 99)
                 (assoc ref :candidate-id "forged") (assoc ref :source-sha256 "forged")
                 (assoc ref :artifact-sha256 "forged") (assoc ref :page 2)
                 (assoc ref :line 999) (assoc ref :ordinal -1)
                 (dissoc ref :candidate-id) (assoc ref :extra "forged")]]
      (is (thrown? clojure.lang.ExceptionInfo (reviews/propose! fixture/app (assoc p :evidence [bad])))))
    (is (= [] (reviews/history fixture/app t)))))
(deftest identity-target-cannot-be-unrelated-to-matched-anchor
  (let [t (sample) ref (cross-reference)
        p (assoc (proposal t "anchor") :identity-target ref :evidence [ref]
                 :after {:outcome :matched :identity-id (str "local-observation:" (:job-id ref) ":0")})]
    (doseq [bad [(assoc-in p [:after :identity-id] "unrelated")
                 (assoc p :after {:outcome :no-match})
                 (assoc p :evidence [{:page 1 :line 1}])
                 (assoc p :identity-target (dissoc ref :artifact-sha256))]]
      (is (thrown? clojure.lang.ExceptionInfo (reviews/propose! fixture/app bad))))
    (is (= [] (reviews/history fixture/app t)))))
(deftest forged-cross-reference-is-revalidated-at-owner-approval
  (let [t (sample) ref (cross-reference)
        p (reviews/propose! fixture/app (assoc (proposal t "valid") :evidence [ref]))
        forged (assoc p :id "forged-cross" :evidence [(assoc ref :artifact-sha256 "forged")])]
    (with-open [c (java.sql.DriverManager/getConnection fixture/app)
                s (.prepareStatement c "INSERT INTO freediving.review_proposals(id,job_id,ordinal,body_edn) VALUES('forged-cross',?,0,?)")]
      (.setString s 1 (:job-id t)) (.setString s 2 (pr-str forged)) (.executeUpdate s))
    (is (thrown-with-msg? Exception #"evidence" (reviews/decide! reviewer {:id "bad-approval" :proposal-id "forged-cross" :action :approve :base-revision 0 :actor "owner" :reason "Must revalidate"})))
    (is (= 0 (:revision (reviews/effective fixture/app t))))))

(deftest registered-context-line-must-share-observation-page
  (let [t (sample)
        ref (cross-reference #(-> % (assoc :pdf-page-count 2)
                                  (update :pages conj {:page 2 :text "Other page" :lines [{:line 1 :text "Other page"}]})
                                  (update :raw-text str "\fOther page")))]
    ;; Context from another line on the observation's page is permitted.
    (is (map? (reviews/propose! fixture/app (assoc (proposal t "context") :evidence [(assoc ref :line 2)]))))
    (is (thrown-with-msg? Exception #"coordinates" (reviews/propose! fixture/app (assoc (proposal t "other-page") :evidence [(assoc ref :page 2)]))))))
(deftest missing-parsed-name-cannot-anchor-an-identity
  (let [t (sample) ref (cross-reference #(assoc-in % [:candidates 0 :parsed :source-name] nil))
        p (assoc (proposal t "nameless") :identity-target ref :evidence [ref]
                 :after {:outcome :matched :identity-id (str "local-observation:" (:job-id ref) ":0")})]
    (is (thrown-with-msg? Exception #"anchor" (reviews/propose! fixture/app p)))
    (is (= [] (reviews/history fixture/app t)))))

(deftest local-anchor-id-requires-registered-identity-target
  (let [t (sample) ref (cross-reference)
        p (assoc (proposal t "missing-target") :evidence [ref]
                 :after {:outcome :matched :identity-id (str "local-observation:" (:job-id ref) ":0")})]
    (is (thrown-with-msg? Exception #"target" (reviews/propose! fixture/app p)))))

(deftest database-candidate-anchor-supports-owner-approval-and-reversal
  (let [t (sample) expected-ref (cross-reference)
        corpus (candidates/load-corpus fixture/app {})
        packet (candidates/packet corpus t {})
        candidate (first (:candidates packet))
        {:keys [identity-id reference]} (:local-identity-anchor candidate)
        row (first (:observations candidate))
        artifact (:artifact (observations/inspect fixture/app (:job-id reference)))
        p (assoc (proposal t "retrieved-anchor") :identity-target reference :evidence [reference]
                 :after {:outcome :matched :identity-id identity-id})]
    (is (= 1 (:candidate-group-count packet)))
    (is (= expected-ref reference))
    (is (= [{:page 1 :line 1 :text "  Éxample  001  "}] (:source-lines row)))
    (is (= "Éxample" (get-in row [:payload :parsed :source-name])))
    (is (= (:acquisitions artifact) (:acquisitions row)))
    (is (= (select-keys artifact [:config :actor :tool :processed-at :pdfinfo-version])
           (:extraction-provenance row)))
    (is (= reference (:identity-target (reviews/propose! fixture/app p))))
    (reviews/decide! reviewer {:id "retrieved-approve" :proposal-id "retrieved-anchor" :action :approve
                               :base-revision 0 :actor "owner" :reason "Synthetic generated anchor check"})
    (is (= (:after p) (:identity (reviews/effective fixture/app t))))
    (reviews/decide! reviewer {:id "retrieved-reverse" :event-id "retrieved-approve" :action :reverse
                               :base-revision 1 :actor "owner" :reason "Undo synthetic anchor check"})
    (is (= {:outcome :unknown} (:identity (reviews/effective fixture/app t))))
    (is (= [reference] (:evidence (first (reviews/history fixture/app t)))))
    (is (= corpus (candidates/load-corpus fixture/app {})))))

(deftest html-review-references-bind-exact-retained-row
  (let [dir (archive-fixture/workspace) root (str dir "/archive")
        hash (html-fixture/register-html root (str dir "/source.html") (html-fixture/document html-fixture/cells))
        job (:job-id (html/extract! root hash {:actor "synthetic" :config {}}))
        target {:job-id job :ordinal 0}]
    (observations/import! fixture/app root job)
    (let [p (assoc (proposal target "html") :evidence [{:table 1 :row 2}])]
      (is (= :propose (:action (reviews/propose! fixture/app p))))
      (is (= {:outcome :unknown} (:identity (reviews/effective reviewer target))))
      (is (thrown? Exception (reviews/propose! fixture/app (assoc p :id "wrong-row" :evidence [{:table 1 :row 1}]))))
      (is (thrown? Exception (reviews/propose! fixture/app (assoc p :id "pdf" :evidence [{:page 1 :line 1}]))))
      (reviews/decide! reviewer {:id "approve-html" :proposal-id "html" :action :approve :base-revision 0 :actor "owner" :reason "Explicit review"})
      (is (= :matched (get-in (reviews/effective reviewer target) [:identity :outcome])))
      (reviews/decide! reviewer {:id "reverse-html" :event-id "approve-html" :action :reverse :base-revision 1 :actor "owner" :reason "Undo"})
      (is (= {:outcome :unknown} (:identity (reviews/effective reviewer target)))))))
(deftest html-registered-identity-target-rejects-wrong-source-row-and-envelope
  (let [dir (archive-fixture/workspace) root (str dir "/archive")
        hash (html-fixture/register-html root (str dir "/source.html") (html-fixture/document html-fixture/cells))
        job (:job-id (html/extract! root hash {:actor "synthetic" :config {}}))
        _ (observations/import! fixture/app root job)
        inspection (observations/inspect fixture/app job)
        ref {:job-id job :ordinal 0 :candidate-id (:candidate_id (first (:observations inspection)))
             :source-sha256 hash :artifact-sha256 (html-evidence/sha256 (:artifact-bytes inspection))
             :table 1 :row 2}
        t (sample) p (assoc (proposal t "html-anchor") :identity-target ref :evidence [ref]
                            :after {:outcome :matched :identity-id (str "local-observation:" job ":0")})]
    (doseq [bad [(assoc ref :row 1) (assoc ref :table 2) (assoc ref :ordinal 1)
                 (assoc ref :candidate-id "forged") (assoc ref :source-sha256 "forged")
                 (assoc ref :artifact-sha256 "forged")]]
      (is (thrown? Exception (reviews/propose! fixture/app (assoc p :evidence [bad] :identity-target bad)))))
    (reviews/propose! fixture/app p)
    (is (thrown? Exception (reviews/decide! fixture/app {:id "forbidden" :proposal-id "html-anchor" :action :approve :base-revision 0 :actor "owner" :reason "Not authorized"})))
    (reviews/decide! reviewer {:id "approved-html-anchor" :proposal-id "html-anchor" :action :approve :base-revision 0 :actor "owner" :reason "Explicit review"})
    (is (= (:after p) (:identity (reviews/effective reviewer t))))))
