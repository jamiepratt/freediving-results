(ns freediving.microplus-flow-fixture
  (:require [clojure.data.json :as json]
            [freediving.reconciliation-flow :as flow]
            [freediving.reconciliation-policy :as policy]
            [freediving.source-relationships :as relationships]))

(defn -main [& _]
  (let [{:keys [evidence decision_id decision_ids flow_path]}
        (json/read-str (slurp *in*) :key-fn keyword)
        evidence (update evidence :observation-versions
                         (fn [versions]
                           (mapv (fn [version]
                                   (-> version
                                       (update :role keyword)
                                       (update-in [:scope-evidence :bindings]
                                                  (fn [bindings]
                                                    (into {}
                                                          (map (fn [[field binding]]
                                                                 [field (update binding :path
                                                                                #(mapv keyword %))])
                                                               bindings))))))
                                 versions)))
        attempt (relationships/empty-attempt-ledger evidence)
        ids (or decision_ids [decision_id])
        grouped (group-by :snapshot-record-id (:observation-versions evidence))
        _ (when-not (and (= (count ids) (count grouped))
                         (= (count ids) (count (set ids)))
                         (every? #(= 2 (count %)) (vals grouped)))
            (throw (ex-info "One decision per two-view source result required" {})))
        decisions (mapv (fn [id [_ views]]
                          (relationships/attempt-jev-decision
                           attempt id :same-attempt (mapv :id views) {}))
                        ids (sort-by first grouped))
        ledger (flow/run! (flow/empty-ledger) decisions
                          {:config {:provider :jev :model "synthetic" :version "synthetic/1"}
                           :policy policy/default-policy
                           :execute! (fn [_] (throw (ex-info "Unexpected provider call" {})))
                           :deterministic-results
                           (into {} (map (fn [id]
                                           [id {:status :approve
                                                :rule-version "synthetic/1"}]) ids))})]
    (flow/save-ledger! flow_path ledger)
    (println (json/write-str
              {:event_id (:id (last (:events ledger)))
               :run_revision (count (:events ledger))
               :events (into {} (map (juxt :decision-id :id) (:events ledger)))}))))
