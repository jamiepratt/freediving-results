(ns freediving.owner-decision-export-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [freediving.owner-decision-export :as export]
            [freediving.reconciliation-flow :as flow]))

(def sha (apply str (repeat 64 "a")))
(def artifact (apply str (repeat 64 "b")))
(def job (apply str (repeat 64 "c")))
(def record (apply str (repeat 64 "d")))
(defn fixture []
  (let [evidence {:evidence-id "source-row-1" :citation {:source-sha256 sha :locator "row 1"}
                  :exact-excerpt "Synthetic row"}
        decision {:id "decision-1" :family :row-semantics :action :same-person
                  :choices [:same-person :different-person :unknown]
                  :subject {:id "person-a"} :evidence [evidence] :dependencies []
                  :owner-proposal {:rule_version "rule/1" :subject_id "person-a" :source_name "Synthetic source"
                                   :original {:athlete "unknown"} :proposed {:athlete "person-a"}
                                   :competing_options ["different-person"]
                                   :supporting_evidence ["source row"] :conflicting_evidence []
                                   :groups ["event:test"]}}
        event {:id "event-1" :decision-id "decision-1" :status :approved :origin :jev
               :action :same-person :evidence [evidence] :policy-version "policy/1"
               :template-version "reconciliation-jev/1"
               :answer {:confidence 0.96 :probabilities {:same-person 0.94}
                        :model-version "model/1"}}
        ledger {:version flow/ledger-version :events [event]}
        observation {:job_id job :ordinal 0 :candidate_id "candidate-1"
                     :artifact_sha256 artifact :source_sha256 sha :parser_version "parser/1"}]
    {:ledger ledger :decision decision :observation observation
     :mapping {:evidence-id "source-row-1" :snapshot-record-id record
               :job-id job :ordinal 0}
     :verified-record {record {:record-id record :job-id job :ordinal 0
                               :candidate-id "candidate-1" :source-sha256 sha
                               :artifact-sha256 artifact :parser-version "parser/1"
                               :source-name "Synthetic source" :athlete-name "A"
                               :source-value "Synthetic row"}}}))

(deftest exports-exact-immutable-revisions-and-citations
  (let [{:keys [ledger decision observation mapping verified-record]} (fixture)
        result (export/export-proposals
                ledger [decision]
                {:snapshot-sha256 sha :binding-revision 7
                 :evidence-bindings {"source-row-1" mapping}
                 :observation-revisions {[job 0] observation}
                 :verified-snapshot-records verified-record})
        proposal (first (:proposals result))]
    (is (= 1 (:reconciliation_run_revision result)))
    (is (= record (get-in proposal [:evidence 0 :id])))
    (is (= "source-row-1" (get-in proposal [:evidence 0 :citation :evidence_id])))
    (is (= (:citation (first (:evidence decision)))
           (get-in proposal [:evidence 0 :citation :source_citation])))
    (is (= observation (get-in proposal [:canonical_binding :observation_revisions 0])))
    (is (= {:evidence_id "source-row-1" :snapshot_record_id record
            :observation_revision observation}
           (get-in proposal [:canonical_binding :evidence_bindings 0])))
    (is (= "event-1" (get-in proposal [:canonical_binding :reconciliation_event_id])))))

(deftest derives-only-evidenced-proposal-metadata
  (let [{:keys [ledger decision observation mapping verified-record]} (fixture)
        decision (-> decision
                     (assoc :choices [:same-person :different-person :unknown])
                     (assoc-in [:evidence 0 :source-name] "Synthetic source")
                     (update :owner-proposal dissoc :subject_id :source_name :competing_options
                             :supporting_evidence :conflicting_evidence))
        ledger (assoc-in ledger [:events 0 :evidence] (:evidence decision))
        opts {:snapshot-sha256 sha :binding-revision 7
              :evidence-bindings {"source-row-1" mapping}
              :observation-revisions {[job 0] observation}
              :verified-snapshot-records verified-record}
        proposal (first (:proposals (export/export-proposals ledger [decision] opts)))]
    (is (= "person-a" (:subject_id proposal)))
    (is (= "Synthetic source" (:source_name proposal)))
    (is (= ["different_person" "unknown"] (:competing_options proposal)))
    (is (= [] (:supporting_evidence proposal)))
    (is (= [] (:conflicting_evidence proposal)))))

(deftest refuses-unverified-or-changed-observation-bindings
  (let [{:keys [ledger decision observation mapping verified-record]} (fixture)
        base {:snapshot-sha256 sha :binding-revision 7
              :evidence-bindings {"source-row-1" mapping}
              :observation-revisions {[job 0] observation}
              :verified-snapshot-records verified-record}]
    (doseq [opts [(assoc base :verified-snapshot-records {})
                  (assoc-in base [:observation-revisions [job 0] :source_sha256] artifact)
                  (assoc base :evidence-bindings {})]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (export/export-proposals ledger [decision] opts))))))

(deftest refuses-snapshot-record-for-a-different-observation
  (let [{:keys [ledger decision observation mapping]} (fixture)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (export/export-proposals
                  ledger [decision]
                  {:snapshot-sha256 sha :binding-revision 7
                   :evidence-bindings {"source-row-1" mapping}
                   :observation-revisions {[job 0] observation}
                   :verified-snapshot-records
                   {record {:record-id record :job-id job :ordinal 1
                            :candidate-id "candidate-1" :source-sha256 sha
                            :artifact-sha256 artifact :parser-version "parser/1"
                            :source-name "Synthetic source" :athlete-name "A"
                            :source-value "Synthetic row"}}})))))

(deftest ignores-untrusted-owner-proposal-values
  (let [{:keys [ledger decision observation mapping]} (fixture)
        decision (assoc decision :owner-proposal
                        {:source_name "Forged" :original {:athlete "Forged"}
                         :proposed {:athlete "Forged"} :rule_version "forged"})
        proposal (first (:proposals
                         (export/export-proposals
                          ledger [decision]
                          {:snapshot-sha256 sha :binding-revision 7
                           :evidence-bindings {"source-row-1" mapping}
                           :observation-revisions {[job 0] observation}
                           :verified-snapshot-records
                           {record {:record-id record :job-id job :ordinal 0
                                    :candidate-id "candidate-1" :source-sha256 sha
                                    :artifact-sha256 artifact :parser-version "parser/1"
                                    :source-name "Synthetic source" :athlete-name "A"
                                    :source-value "Synthetic row"}}})))]
    (is (= "Synthetic source" (:source_name proposal)))
    (is (= {:source_values ["Synthetic row"]} (:original proposal)))
    (is (= "reconciliation-jev/1" (:rule_version proposal)))))

(deftest exports-row-meaning-from-retained-source-value
  (let [{:keys [ledger decision observation mapping verified-record]} (fixture)
        decision (assoc decision :family :row-semantics :action :summary
                        :choices [:attempt :aggregate :summary :not-result :unknown]
                        :owner-proposal {:original "forged" :proposed "forged"})
        ledger (assoc-in ledger [:events 0 :action] :summary)
        record-value (assoc-in verified-record [record :source-value] "Overall ranking")
        proposal (first (:proposals
                         (export/export-proposals
                          ledger [decision]
                          {:snapshot-sha256 sha :binding-revision 7
                           :evidence-bindings {"source-row-1" mapping}
                           :observation-revisions {[job 0] observation}
                           :verified-snapshot-records record-value})))]
    (is (= {:source_values ["Overall ranking"]} (:original proposal)))
    (is (= "summary" (get-in proposal [:proposed :action])))))

(deftest refuses-identity-pair-that-is-not-the-bound-observation
  (let [{:keys [ledger decision observation mapping verified-record]} (fixture)
        id (str "local-observation:" job ":1")
        decision (assoc decision :family :identity
                        :subject {:id "person-a" :pair [id id]
                                  :observation-versions {id {:source-sha256 sha
                                                             :artifact-sha256 artifact}}})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (export/export-proposals
                  ledger [decision]
                  {:snapshot-sha256 sha :binding-revision 7
                   :evidence-bindings {"source-row-1" mapping}
                   :observation-revisions {[job 0] observation}
                   :verified-snapshot-records verified-record})))))

(deftest refuses-identity-without-exact-pair
  (let [{:keys [ledger decision observation mapping verified-record]} (fixture)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (export/export-proposals
                  ledger [(assoc decision :family :identity)]
                  {:snapshot-sha256 sha :binding-revision 7
                   :evidence-bindings {"source-row-1" mapping}
                   :observation-revisions {[job 0] observation}
                   :verified-snapshot-records verified-record})))))

(deftest names-all-cross-source-evidence
  (let [{:keys [ledger decision observation mapping verified-record]} (fixture)
        other-job (apply str (repeat 64 "e"))
        other-record (apply str (repeat 64 "f"))
        second-evidence {:evidence-id "source-row-2"
                         :citation {:source-sha256 sha :locator "row 2"}
                         :exact-excerpt "Synthetic second row"}
        decision (update decision :evidence conj second-evidence)
        ledger (assoc-in ledger [:events 0 :evidence] (:evidence decision))
        other-observation (assoc observation :job_id other-job :ordinal 1)
        opts {:snapshot-sha256 sha :binding-revision 7
              :evidence-bindings {"source-row-1" mapping
                                  "source-row-2" {:evidence-id "source-row-2"
                                                  :snapshot-record-id other-record
                                                  :job-id other-job :ordinal 1}}
              :observation-revisions {[job 0] observation
                                      [other-job 1] other-observation}
              :verified-snapshot-records
              (assoc verified-record other-record
                     {:record-id other-record :job-id other-job :ordinal 1
                      :candidate-id "candidate-1" :source-sha256 sha
                      :artifact-sha256 artifact :parser-version "parser/1"
                      :source-name "Another synthetic source"
                      :source-value "Synthetic second row"})}
        proposal (first (:proposals (export/export-proposals ledger [decision] opts)))]
    (is (= ["Synthetic source" "Another synthetic source"] (:source_names proposal)))
    (is (= "Synthetic source + Another synthetic source" (:source_name proposal)))))

(defn -main [& _]
  (let [result (run-tests 'freediving.owner-decision-export-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
