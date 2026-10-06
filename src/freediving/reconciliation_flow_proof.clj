(ns freediving.reconciliation-flow-proof
  "Verify a Microplus owner export against the persisted local reconciliation event."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [freediving.reconciliation-flow :as flow]))

(defn- fail! []
  (throw (ex-info "Microplus reconciliation event is stale or unbound" {})))

(defn- evidence-bindings [event]
  (mapv (fn [item]
          {:evidence_id (:evidence-id item)
           :snapshot_record_id (get-in item [:canonical-subject :version :snapshot-record-id])
           :observation_revision (get-in item [:canonical-subject :version :observation-revision])})
        (:evidence event)))

(defn- cited? [event]
  (every? (fn [item]
            (let [subject (:canonical-subject item)
                  sha (get-in subject [:source :sha256])
                  position (:position subject)
                  citation (:citation item)]
              (and (string? sha) (re-matches #"[0-9a-f]{64}" sha)
                   (string? (get-in subject [:version :snapshot-record-id]))
                   (string? (get-in subject [:version :observation-revision :observation_version]))
                   (= sha (:source-sha256 citation)
                      (get-in subject [:version :observation-revision :source_sha256]))
                   (some? (:id position)) (some? (:locator position))
                   (= (:id position) (:position-id citation))
                   (= (:locator position) (:locator citation)))))
          (:evidence event)))

(defn- original-event [events decision-id]
  (last (filter #(and (= decision-id (:decision-id %)) (not= :human (:origin %))) events)))

(defn- bound-human? [prefix human run-revision]
  (let [remote (:remote-event human)
        binding (get-in remote [:proposal :canonical_binding])
        original (original-event prefix (:decision-id human))
        status ({"approve" :approved "correct" :approved "reject" :rejected "reverse" :reversed}
                (:action remote))
        action (case (:action remote)
                 "approve" (some-> (get-in remote [:proposal :selected_option])
                                   (str/replace "_" "-") keyword)
                 "correct" (some-> (get-in remote [:correction :action])
                                   (str/replace "_" "-") keyword)
                 "reject" :distinct-attempts
                 "reverse" (:action original)
                 nil)]
    (and (= :human (:origin human)) original (= :same-attempt (:family original))
         (= (:id human) (:id remote) (str "owner-store:" (:store_revision remote)))
         (pos-int? (:store_revision remote)) (pos-int? (:binding_revision remote))
         (= (:decision-id human) (:decision_id remote) (:decision_id binding))
         (= (:remote-store-revision human) (:store_revision remote))
         (= (:remote-binding-revision human) (:binding_revision remote))
         (= (:remote-snapshot-sha256 human) (:snapshot_sha256 remote))
         (every? #(= (:snapshot_sha256 remote)
                     (get-in % [:observation_revision :snapshot_sha256]))
                 (evidence-bindings original))
         (= (:actor human) (:actor remote)) (string? (:actor human)) (seq (:actor human))
         (= (:reason human) (:reason remote)) (string? (:reason human))
         (= (:correction human) (:correction remote))
         (= (:status human) status) status (= (:action human) action) action
         (= run-revision (:reconciliation_run_revision binding))
         (= (:id original) (:reconciliation_event_id binding))
         (= (evidence-bindings original) (:evidence_bindings binding))
         (= (mapv :observation_revision (evidence-bindings original))
            (:observation_revisions binding))
         (cited? original))))

(defn verify! [{:keys [flow_path decision_id reconciliation_run_revision
                       reconciliation_event_id evidence_bindings]}]
  (let [ledger (flow/load-ledger! flow_path)
        events (:events ledger)]
    (when-not (and (= flow/ledger-version (:version ledger))
                   (pos-int? reconciliation_run_revision)
                   (<= reconciliation_run_revision (count events)))
      (fail!))
    (let [prefix (subvec events 0 reconciliation_run_revision)
          suffix (subvec events reconciliation_run_revision)
          event (original-event prefix decision_id)
          current (into {} (map (juxt :decision-id identity) events))
          remote-revisions (mapv :remote-store-revision (filter #(= :human (:origin %)) events))]
      (when-not (and (not= :human (:origin (peek prefix)))
                     (= reconciliation_event_id (:id event))
                     (= :same-attempt (:family event))
                     (= 2 (count evidence_bindings) (count (:evidence event)))
                     (= 2 (count (set (:candidates event))))
                     (= (set (map :evidence_id evidence_bindings)) (set (:candidates event)))
                     (= (evidence-bindings event) evidence_bindings) (cited? event)
                     (= (count events) (count (set (map :id events))))
                     (every? #(bound-human? prefix % reconciliation_run_revision) suffix)
                     (or (empty? suffix)
                         (and (every? pos-int? remote-revisions)
                              (apply < (cons 0 remote-revisions))))
                     (every? #(= :approved (:status (get current %))) (:dependencies event)))
        (fail!))
      {:verified true :event_id (:id event) :run_revision reconciliation_run_revision})))

(defn -main [& _]
  (try
    (println (json/write-str (verify! (json/read-str (slurp *in*) :key-fn keyword))))
    (catch Exception _
      (binding [*out* *err*]
        (println "Microplus reconciliation event verification failed"))
      (System/exit 1))))
