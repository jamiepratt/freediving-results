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
        decision {:id "decision-1" :family :identity :action :same-person
                  :subject {:id "person-a"} :evidence [evidence] :dependencies []
                  :owner-proposal {:rule_version "rule/1" :subject_id "person-a" :source_name "Synthetic source"
                                   :original {:athlete "unknown"} :proposed {:athlete "person-a"}
                                   :competing_options ["different-person"]
                                   :supporting_evidence ["source row"] :conflicting_evidence []
                                   :groups ["event:test"]}}
        event {:id "event-1" :decision-id "decision-1" :status :approved :origin :jev
               :action :same-person :evidence [evidence] :policy-version "policy/1"
               :answer {:confidence 0.96 :probabilities {:same-person 0.94}
                        :model-version "model/1"}}
        ledger {:version flow/ledger-version :events [event]}
        observation {:job_id job :ordinal 0 :candidate_id "candidate-1"
                     :artifact_sha256 artifact :source_sha256 sha :parser_version "parser/1"}]
    {:ledger ledger :decision decision :observation observation
     :mapping {:evidence-id "source-row-1" :snapshot-record-id record
               :job-id job :ordinal 0}}))

(deftest exports-exact-immutable-revisions-and-citations
  (let [{:keys [ledger decision observation mapping]} (fixture)
        result (export/export-proposals
                ledger [decision]
                {:snapshot-sha256 sha :binding-revision 7
                 :evidence-bindings {"source-row-1" mapping}
                 :observation-revisions {[job 0] observation}
                 :verified-snapshot-record-ids #{record}})
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

(deftest refuses-unverified-or-changed-observation-bindings
  (let [{:keys [ledger decision observation mapping]} (fixture)
        base {:snapshot-sha256 sha :binding-revision 7
              :evidence-bindings {"source-row-1" mapping}
              :observation-revisions {[job 0] observation}
              :verified-snapshot-record-ids #{record}}]
    (doseq [opts [(assoc base :verified-snapshot-record-ids #{})
                  (assoc-in base [:observation-revisions [job 0] :source_sha256] artifact)
                  (assoc base :evidence-bindings {})]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (export/export-proposals ledger [decision] opts))))))

(defn -main [& _]
  (let [result (run-tests 'freediving.owner-decision-export-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
