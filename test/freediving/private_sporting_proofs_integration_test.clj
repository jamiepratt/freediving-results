(ns freediving.private-sporting-proofs-integration-test
  (:require [clojure.test :refer [deftest is use-fixtures run-tests]]
            [freediving.observations-test :as fixture]
            [freediving.reviews :as reviews]
            [freediving.publication :as publication]
            [freediving.revisions :as revisions]
            [freediving.public-sporting-test :as sporting-fixture]
            [freediving.private-sporting-proofs-test :as proof-fixture]
            [freediving.public-server-test :as http]))

(use-fixtures :each (fn [f]
                      (sporting-fixture/setup!)
                      (proof-fixture/role! "proof_source" proof-fixture/source-tables)
                      (proof-fixture/role! "proof_relationships" ["extractions" "observations" "canonical_attempt_evidence" "canonical_attempt_events" "canonical_attempt_state"])
                      (sporting-fixture/with-private-authority! f)))

(defn actual-source-context! [{:keys [payload input-roots]}]
  (let [rows (mapv (fn [public-row]
                     (let [{:keys [artifact]} (get input-roots (get-in public-row [:source :federation]))
                           target {:job-id (:job-id artifact) :ordinal (get-in public-row [:reference :ordinal])}
                           reference (assoc (revisions/reference fixture/app target) :parser-version (:parser-version artifact))
                           coord {:page 1 :line (inc (:ordinal target))}
                           evidence (merge reference coord {:source-kind :pdf :schema-version (:schema-version artifact)
                                                            :acquisition-id (get-in artifact [:acquisitions 0 :acquisition-id])
                                                            :observation-id (str "local-observation:" (:job-id target) ":" (:ordinal target))})]
                       (reviews/accept-pdf-extraction! (System/getenv "FREEDIVING_TEST_REVIEW_URL")
                                                       (merge target {:id (str "owner-extraction-" (get public-row :result-id))
                                                                      :base-revision 0 :evidence evidence
                                                                      :owner-receipt-sha256 (apply str (repeat 64 "a"))
                                                                      :owner-response {:task-id "isolated-owner" :user-message-id "isolated-explicit-review"
                                                                                       :response-annotation-index 0 :selected-text "Accept extraction 0-1"}
                                                                      :actor "PRIVATE-OWNER" :reason "Isolated explicit exact row source review"}))
                       {:reference reference :coordinates coord :year "2026" :environment "pool" :discipline "dnf" :gender "women"}))
                   (:rows payload))
        result (sporting-fixture/private-command! {:op "canonical-context"
                                                   :config {:jdbc_url proof-fixture/proof-url :database "observations_test"
                                                            :canonical_jdbc_url proof-fixture/relationship-url :canonical_database "observations_test"}
                                                   :rows rows})]
    (is (= 4 (count (get-in result [:result :rows]))))
    (doseq [row (get-in result [:result :rows])]
      (is (= "verified" (get-in row [:upstream :review :value])))
      (is (= "approved" (get-in row [:upstream :publication :value])))
      (is (nil? (get-in row [:upstream :same-attempt]))))
    (sporting-fixture/private-command! {:op "canonical-relationships"})
    rows))

(deftest genuine-exact-source-proofs-owned-relationship-reviews-and-signed-public-ranks
  (binding [sporting-fixture/*prepare-canonical-context* actual-source-context!]
    (sporting-fixture/synthetic-cohort!)
    (http/with-server
      (fn [url]
        (let [body (:body (http/request url "/api/comparison")) rows (:rows body)
              first-row (first rows)
              detail (:detail-api-url first-row) peer (get-in first-row [:ranks 2 :api-url])]
          (is (= 3 (get-in body [:coverage :eligible-comparison-peers])))
          (is (= [1 1 3] (sort (keep #(get-in % [:ranks 2 :rank]) rows))))
          (is (= 200 (:status (http/request url detail))))
          (is (= 200 (:status (http/request url peer))))
          (is (not (re-find #"PRIVATE|job-id|candidate-id|receipt|body_edn|canonical_upstream" (pr-str body))))
          (sporting-fixture/private-command! {:op "reverse-relationship"})
          (is (= "withheld" (get-in (http/request url "/api/comparison") [:body :status])))
          (is (= 404 (:status (http/request url detail))))
          (is (= 404 (:status (http/request url peer)))))))))

(defn refusal-after-mutation! [change]
  (binding [sporting-fixture/*prepare-canonical-context* actual-source-context!]
    (let [cohort (sporting-fixture/synthetic-cohort!)]
      (http/with-server
        (fn [url]
          (let [before (http/request url "/api/comparison") row (first (get-in before [:body :rows]))
                paths [(:detail-api-url row) (get-in row [:ranks 2 :api-url])]]
            (is (= 3 (get-in before [:body :coverage :eligible-comparison-peers])))
            (change cohort)
            (let [after (http/request url "/api/comparison")]
              (is (or (= "withheld" (get-in after [:body :status])) (= 503 (:status after)))))
            (doseq [path paths] (is (#{404 503} (:status (http/request url path)))))))))))

(deftest genuine-extraction-review-reversal-invalidates-signed-old-links
  (refusal-after-mutation!
   (fn [{:keys [targets]}]
     (let [target (first targets)
           receipt (first (reviews/pdf-extraction-history (System/getenv "FREEDIVING_TEST_REVIEW_URL") target))]
       (reviews/revoke-pdf-extraction! (System/getenv "FREEDIVING_TEST_REVIEW_URL")
                                       (merge target {:id "real-source-review-reversed" :base-revision 1 :event-id (:id receipt)
                                                      :evidence (:evidence receipt) :actor "owner" :reason "Isolated genuine review reversed"}))))))

(deftest genuine-active-publication-policy-change-invalidates-signed-old-links
  (refusal-after-mutation! (fn [_] (publication/activate-policy! fixture/admin "extraction-publication/99" "Isolated new policy"))))

(deftest live-source-reader-grant-widening-is-a-public-denial
  (refusal-after-mutation! (fn [_] (fixture/sql! fixture/admin "GRANT UPDATE(body_edn) ON freediving.pdf_extraction_reviews TO proof_source"))))

(deftest genuine-signed-connector-expiry-refuses-old-links
  (refusal-after-mutation! (fn [_] (sporting-fixture/private-command! {:op "expire"}))))

(defn -main [& _]
  (let [r (run-tests 'freediving.private-sporting-proofs-integration-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail r) (:error r))) (System/exit 1))))
