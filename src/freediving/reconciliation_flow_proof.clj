(ns freediving.reconciliation-flow-proof
  "Verify a Microplus owner export against the persisted local reconciliation event."
  (:require [clojure.data.json :as json]
            [freediving.reconciliation-flow :as flow]))

(defn- fail! []
  (throw (ex-info "Microplus reconciliation event is stale or unbound" {})))

(defn verify! [{:keys [flow_path decision_id reconciliation_run_revision
                       reconciliation_event_id evidence_bindings]}]
  (let [ledger (flow/load-ledger! flow_path)
        events (:events ledger)
        event (last (filter #(= decision_id (:decision-id %)) events))
        bindings (mapv (fn [entry]
                         {:evidence-id (:evidence_id entry)
                          :snapshot-record-id (:snapshot_record_id entry)
                          :observation-revision (:observation_revision entry)})
                       evidence_bindings)
        cited (mapv (fn [item]
                      (let [subject (:canonical-subject item)
                            version (:version subject)
                            position (:position subject)
                            source (:source subject)]
                        {:evidence-id (:evidence-id item)
                         :snapshot-record-id (:snapshot-record-id version)
                         :observation-revision (:observation-revision version)
                         :source-sha256 (:sha256 source)
                         :citation-source-sha256 (get-in item [:citation :source-sha256])
                         :citation-position-id (get-in item [:citation :position-id])
                         :position-id (:id position)
                         :citation-locator (get-in item [:citation :locator])
                         :position-locator (:locator position)}))
                    (:evidence event))]
    (when-not (and (= flow/ledger-version (:version ledger))
                   (pos-int? reconciliation_run_revision)
                   (= reconciliation_run_revision (count events))
                   (= reconciliation_event_id (:id event))
                   (= decision_id (:decision-id event))
                   (= :same-attempt (:family event))
                   (not= :human (:origin event))
                   (= 2 (count bindings) (count cited))
                   (= (set (map :evidence-id bindings)) (set (:candidates event)))
                   (= (mapv #(select-keys % [:evidence-id :snapshot-record-id
                                             :observation-revision]) cited)
                      bindings)
                   (every? #(and (= (:source-sha256 %) (:citation-source-sha256 %))
                                 (= (:source-sha256 %)
                                    (get-in % [:observation-revision :source_sha256]))
                                 (= (:citation-position-id %) (:position-id %))
                                 (= (:citation-locator %) (:position-locator %)))
                           cited))
      (fail!))
    {:verified true :event_id (:id event) :run_revision (count events)}))

(defn -main [& _]
  (try
    (println (json/write-str (verify! (json/read-str (slurp *in*) :key-fn keyword))))
    (catch Exception _
      (binding [*out* *err*]
        (println "Microplus reconciliation event verification failed"))
      (System/exit 1))))
