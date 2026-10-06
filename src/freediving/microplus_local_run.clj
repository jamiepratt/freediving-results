(ns freediving.microplus-local-run
  "Persist verified two-view Microplus decisions without provider dispatch."
  (:require [clojure.data.json :as json]
            [freediving.reconciliation-flow :as flow]
            [freediving.reconciliation-policy :as policy]
            [freediving.source-relationships :as relationships]))

(defn- keywordize-evidence [evidence]
  (update evidence :observation-versions
          (fn [versions]
            (mapv (fn [version]
                    (-> version
                        (update :role keyword)
                        (update-in [:scope-evidence :bindings]
                                   (fn [bindings]
                                     (into {} (map (fn [[field binding]]
                                                     [field (update binding :path
                                                                    #(mapv keyword %))])
                                                   bindings))))))
                  versions))))

(defn- inspect-local [{:keys [decision_ids flow_path]}]
  (let [ledger (flow/load-ledger! flow_path)
        ids (set decision_ids)
        events (filterv #(ids (:decision-id %)) (:events ledger))
        current (into {} (map (fn [[id history]]
                                [id (or (last (filter #(= :human (:origin %)) history))
                                        (last history))])
                              (group-by :decision-id events)))
        versions (fn [field] (vec (sort (set (keep field events)))))]
    (when-not (and (vector? decision_ids) (seq decision_ids)
                   (= (count decision_ids) (count ids))
                   (= ids (set (keys current))))
      (throw (ex-info "Microplus metrics decision history incomplete" {})))
    {:decision_denominator (count ids)
     :statuses (frequencies (map :status (vals current)))
     :automatic_decision_ids (mapv :decision-id
                                   (filter #(and (= :approved (:status %))
                                                 (not= :human (:origin %)))
                                           (vals current)))
     :deterministic_approved (count (set (map :decision-id
                                              (filter #(and (= :approved (:status %))
                                                            (= :deterministic (:origin %)))
                                                      events))))
     :human_reversals (count (filter #(and (= :human (:origin %))
                                           (= :reversed (:status %))) events))
     :cache_hits_recorded (count (filter #(= :cached-jev (:origin %)) events))
     :history_versions {:ledger (:version ledger) :config (versions :config-version)
                        :policy (versions :policy-version) :rule (versions :rule-version)
                        :template (versions :template-version)
                        :model (versions :model-version)}}))

(defn- run-local! [{:keys [evidence decision_ids flow_path]}]
  (let [attempt (relationships/empty-attempt-ledger (keywordize-evidence evidence))
        grouped (sort-by first (group-by :snapshot-record-id
                                         (get evidence :observation-versions)))
        _ (when-not (and (vector? decision_ids)
                         (= (count decision_ids) (count grouped))
                         (= (count decision_ids) (count (set decision_ids)))
                         (every? #(= 2 (count (second %))) grouped))
            (throw (ex-info "One decision per two-view result required" {})))
        decisions (mapv (fn [id [_ views]]
                          (relationships/attempt-jev-decision
                           attempt id :same-attempt (mapv :id views) {}))
                        decision_ids grouped)
        _ (when-not (every? :evidence-adequate? decisions)
            (throw (ex-info "Microplus source-bound attempt evidence inadequate" {})))
        ledger (flow/run! (flow/load-ledger! flow_path) decisions
                          {:config {:provider :jev :model "none" :version "microplus-local/1"}
                           :policy policy/default-policy
                           :execute! (fn [_] (throw (ex-info "Provider call forbidden" {})))
                           :deterministic-results
                           (into {} (map (fn [id]
                                           [id {:status :approve
                                                :rule-version "cmas-microplus-attempt-evidence/1"}])
                                         decision_ids))})
        decisions-set (set decision_ids)
        current (into {} (map (juxt :decision-id identity)
                              (filter #(and (decisions-set (:decision-id %))
                                            (not= :human (:origin %)))
                                      (:events ledger))))
        _ (when-not (every? (fn [id]
                              (let [event (get current id)]
                                (and (= :approved (:status event))
                                     (= :deterministic (:origin event)))))
                            decision_ids)
            (throw (ex-info "Microplus deterministic flow event not approved" {})))]
    (flow/save-ledger! flow_path ledger)
    {:run_revision (count (:events ledger))
     :events (into {} (map (fn [[id event]] [id (:id event)]) current))
     :provider_calls 0}))

(defn -main [& _]
  (try
    (let [input (json/read-str (slurp *in*) :key-fn keyword)]
      (println (json/write-str (if (= "inspect" (:operation input))
                                 (inspect-local input)
                                 (run-local! input)))))
    (catch Exception error
      (binding [*out* *err*] (println (.getMessage error)))
      (System/exit 1))))
