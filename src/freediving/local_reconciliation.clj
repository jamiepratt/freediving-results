(ns freediving.local-reconciliation
  "Synthetic, private coordinator adapter for the existing reconciliation application."
  (:refer-clojure :exclude [run!])
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [freediving.reconciliation-application :as application]
            [freediving.reconciliation-flow :as flow]
            [freediving.reconciliation-policy :as policy])
  (:import [java.nio.file Files Path]))

(defn- remote-revision [ledger]
  (reduce max 0 (keep :remote-store-revision (:events ledger))))

(defn- sync-owner! [flow-path decisions config owner-options]
  (let [opts (assoc owner-options :config config :policy policy/default-policy
                    :flow-path flow-path)]
    ;; The private ledger cursor advances only after both remote ACK responses.
    ;; A lost response therefore retries the same idempotent event.
    (loop [after (let [ledger (flow/load-ledger! flow-path)
                       acked (or (:owner-acked-revision ledger) 0)]
                   (when-not (and (nat-int? acked) (<= acked (remote-revision ledger)))
                     (throw (ex-info "Invalid owner ACK checkpoint" {})))
                   acked)]
      (let [envelope (application/fetch-owner-review-events (:base-url opts) after opts)
            feed (json/read-str (:payload_json envelope) :key-fn keyword)
            events (:events feed)]
        (application/import-remote-review-events
         (flow/load-ledger! flow-path) decisions envelope opts)
        (doseq [event events]
          (let [event-id (:id event)
                flow-receipt (application/deliver-owner-event!
                              :flow-ledger event-id decisions
                              (assoc opts :expected-event event))]
            (application/ack-owner-review-event! :flow-ledger event flow-receipt opts)
            (let [canonical-receipt (application/deliver-owner-event!
                                     :postgresql event-id decisions
                                     (assoc opts :expected-event event))]
              (application/ack-owner-review-event! :postgresql event canonical-receipt opts)
              (flow/save-ledger! flow-path
                                 (assoc (flow/load-ledger! flow-path)
                                        :owner-acked-revision (:store_revision event))))))
        (if (seq events)
          (let [next-revision (:next_revision feed)]
            (when-not (> next-revision after)
              (throw (ex-info "Owner event feed made no progress" {})))
            (recur next-revision))
          (remote-revision (flow/load-ledger! flow-path)))))))

(defn run!
  ([spec-path flow-path snapshot-sha256]
   (run! spec-path flow-path snapshot-sha256 nil))
  ([spec-path flow-path snapshot-sha256 owner-config-path]
   (let [spec (edn/read-string (Files/readString (Path/of spec-path (make-array String 0))))
         {:keys [config decisions synthetic-answers]} spec
         deterministic-results (or (:deterministic-results spec) {})
         calls (atom 0)
         _ (when-not (and (map? config) (vector? decisions)
                          (map? synthetic-answers) (map? deterministic-results)
                          (every? string? (keys synthetic-answers))
                          (re-matches #"[0-9a-f]{64}" snapshot-sha256))
             (throw (ex-info "Invalid synthetic reconciliation specification" {})))
         persist! #(flow/save-ledger! flow-path %)
         owner-options (when owner-config-path
                         (edn/read-string (Files/readString
                                           (Path/of owner-config-path (make-array String 0)))))
         _ (when (and owner-options
                      (not= snapshot-sha256 (:active-snapshot-sha256 owner-options)))
             (throw (ex-info "Owner synchronization snapshot differs from local run" {})))
         execute! (fn [request]
                    (let [ids (:decision-ids request)]
                      (when-not (every? #(contains? synthetic-answers %) ids)
                        (throw (ex-info "Missing synthetic answer" {:error :missing-synthetic-answer})))
                      (swap! calls inc)
                      {:model (:model config) :usage {}
                       :answers (select-keys synthetic-answers ids)}))
         owner-revision (when owner-options
                          (sync-owner! flow-path decisions config owner-options))
         result (application/run! (flow/load-ledger! flow-path) decisions
                                  (merge owner-options
                                         {:config config :policy policy/default-policy
                                          :persist-flow! persist! :execute! execute!
                                          :deterministic-results deterministic-results}))
         outcomes (frequencies (map :status (vals (:results result))))
         views (flow/inspect (:flow-ledger result) decisions config)
         statuses (frequencies (map :status (vals views)))]
     {:schema "local-reconciliation-result/v1"
      :snapshot_sha256 snapshot-sha256
      :remote_store_revision owner-revision
      :decision_revision (count (get-in result [:flow-ledger :events]))
      :provider_calls @calls
      :accepted_athletes nil
      :distinct_attempts nil
      :no_link (get outcomes :no-link 0)
      :materialized (get outcomes :materialized 0)
      :unresolved (get outcomes :unresolved 0)
      :reversed (get outcomes :reversed 0)
      :flow_statuses (into {} (map (fn [[status count]] [(name status) count]) statuses))})))

(defn -main [& args]
  (when-not (#{3 4} (count args))
    (throw (ex-info "spec, flow, snapshot SHA-256 and optional owner config required" {})))
  (println (json/write-str (apply run! args))))
