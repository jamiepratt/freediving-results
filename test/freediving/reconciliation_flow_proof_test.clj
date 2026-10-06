(ns freediving.reconciliation-flow-proof-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [freediving.reconciliation-flow :as flow]
            [freediving.reconciliation-flow-proof :as proof]))

(def sha (apply str (repeat 64 "a")))
(def bindings
  (mapv (fn [id] {:evidence_id id :snapshot_record_id "record"
                  :observation_revision {:source_sha256 sha :snapshot_sha256 sha :observation_version id}}) ["a" "b"]))
(def proposal
  {:id "proposal" :decision-id "attempt" :origin :deterministic :family :same-attempt :action :same-attempt
   :candidates ["a" "b"] :dependencies []
   :evidence (mapv (fn [{:keys [evidence_id observation_revision]}]
                     {:evidence-id evidence_id
                      :canonical-subject {:version {:snapshot-record-id "record"
                                                    :observation-revision observation_revision}
                                          :source {:sha256 sha} :position {:id evidence_id :locator "/1"}}
                      :citation {:source-sha256 sha :position-id evidence_id :locator "/1"}}) bindings)})
(def canonical-binding {:decision_id "attempt" :reconciliation_event_id "proposal"
                        :reconciliation_run_revision 1 :evidence_bindings bindings
                        :observation_revisions (mapv :observation_revision bindings)})
(def human
  {:id "owner-store:2" :decision-id "attempt" :origin :human :status :approved
   :action :same-attempt :actor "owner" :reason "checked"
   :remote-store-revision 2 :remote-binding-revision 1 :remote-snapshot-sha256 sha
   :remote-event {:id "owner-store:2" :decision_id "attempt" :store_revision 2
                  :binding_revision 1 :snapshot_sha256 sha :action "approve"
                  :actor "owner" :reason "checked"
                  :proposal {:selected_option "same_attempt" :canonical_binding canonical-binding}}})

(defn verify
  ([events] (verify events 1))
  ([events revision]
   (let [dir (java.nio.file.Files/createTempDirectory "immutable-proof"
                                                      (make-array java.nio.file.attribute.FileAttribute 0))
         path (.resolve dir "flow.edn")]
     (flow/save-ledger! path {:version flow/ledger-version :events events})
     (proof/verify! {:flow_path (str path) :decision_id "attempt"
                     :reconciliation_run_revision revision :reconciliation_event_id "proposal"
                     :evidence_bindings bindings}))))

(deftest original-proposal-survives-bound-human-suffix
  (is (= {:verified true :event_id "proposal" :run_revision 1}
         (verify [proposal human]))))

(deftest incompatible-history-cannot-reuse-original-proof
  (doseq [events [[proposal (assoc human :origin :deterministic)]
                  [proposal (assoc human :remote-store-revision 3)]
                  [proposal (assoc-in human [:remote-event :proposal :canonical_binding
                                             :evidence_bindings 0 :observation_revision
                                             :observation_version] "changed")]
                  [(update-in proposal [:evidence 0 :citation] dissoc :locator) human]
                  [(assoc-in proposal [:evidence 0 :canonical-subject :source :sha256]
                             (apply str (repeat 64 "b"))) human]
                  [(update-in proposal [:evidence 0 :canonical-subject :version]
                              dissoc :observation-revision) human]
                  [proposal human (assoc proposal :id "replacement")]]]
    (is (thrown? clojure.lang.ExceptionInfo (verify events)))))

(deftest newest-human-correction-retains-original-proposal-binding
  (let [reverse-event (-> human
                          (assoc :id "owner-store:3" :status :reversed :remote-store-revision 3)
                          (assoc-in [:remote-event :id] "owner-store:3")
                          (assoc-in [:remote-event :store_revision] 3)
                          (assoc-in [:remote-event :action] "reverse"))
        corrected (-> human
                      (assoc :id "owner-store:4" :remote-store-revision 4
                             :action :distinct-attempts :correction {:action "distinct_attempts"})
                      (assoc-in [:remote-event :id] "owner-store:4")
                      (assoc-in [:remote-event :store_revision] 4)
                      (assoc-in [:remote-event :action] "correct")
                      (assoc-in [:remote-event :correction] {:action "distinct_attempts"}))]
    (is (= {:verified true :event_id "proposal" :run_revision 1}
           (verify [proposal human reverse-event corrected])))))

(deftest proof-does-not-relabel-human-revision-or-accept-missing-dependencies
  (is (= {:verified true :event_id "proposal" :run_revision 1} (verify [proposal])))
  (is (thrown? clojure.lang.ExceptionInfo (verify [proposal human] 2)))
  (is (thrown? clojure.lang.ExceptionInfo
               (verify [(assoc proposal :dependencies ["missing-decision"])]))))

(defn -main [& _]
  (let [r (run-tests 'freediving.reconciliation-flow-proof-test)]
    (when (pos? (+ (:fail r) (:error r))) (System/exit 1))))
