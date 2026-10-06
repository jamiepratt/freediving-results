(ns freediving.local-reconciliation
  "Synthetic, private coordinator adapter for the existing reconciliation application."
  (:refer-clojure :exclude [run!])
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [freediving.reconciliation-application :as application]
            [freediving.reconciliation-flow :as flow]
            [freediving.reconciliation-jev :as jev]
            [freediving.reconciliation-policy :as policy])
  (:import [java.nio.file Files Path LinkOption]
           [java.nio.file.attribute PosixFilePermissions]))

(defn- remote-revision [ledger]
  (reduce max 0 (keep :remote-store-revision (:events ledger))))

(defn- decision-metrics [decisions views]
  (let [summary (fn [members]
                  {:denominator (count members)
                   :automatic_approved (count (filter (fn [d]
                                                        (let [v (get views (:id d))]
                                                          (and (= :approved (:status v))
                                                               (not= :human (:origin v))))) members))
                   :unknown (count (filter #(#{:unresolved :unknown-external-outcome :timeout}
                                             (:status (get views (:id %)))) members))
                   :error (count (filter #(#{:provider-error :invalid-response :interrupted}
                                           (:status (get views (:id %)))) members))
                   :conflict (count (filter #(or (seq (:conflicts %))
                                                 (= :conflicting-evidence
                                                    (:reason (get views (:id %))))) members))
                   :pending_review (count (filter #(not= :approved
                                                         (:status (get views (:id %)))) members))})]
    (assoc (summary decisions) :decision_denominator (count decisions)
           :by_family (into (sorted-map)
                            (map (fn [[family members]] [(name family) (summary members)])
                                 (group-by :family decisions))))))

(defn owner-sync-options!
  "Read an owner-only EDN config and require the active snapshot and local flow."
  [config-path flow-path snapshot-sha256]
  (let [path (Path/of config-path (make-array String 0))]
    (when-not (and (.isAbsolute path)
                   (Files/isRegularFile path (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
                   (= (PosixFilePermissions/fromString "rw-------")
                      (Files/getPosixFilePermissions path (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))))
      (throw (ex-info "Owner synchronization config must be an owner-only regular file" {})))
    (let [options (edn/read-string (Files/readString path))]
      (when-not (and (map? options)
                     (= snapshot-sha256 (:active-snapshot-sha256 options))
                     (or (nil? (:flow-path options)) (= flow-path (:flow-path options))))
        (throw (ex-info "Owner synchronization snapshot differs from local run or flow" {})))
      options)))

(defn sync-owner! [flow-path decisions config owner-options]
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
          (:store_revision feed))))))

(defn run!
  ([spec-path flow-path snapshot-sha256]
   (run! spec-path flow-path snapshot-sha256 nil))
  ([spec-path flow-path snapshot-sha256 owner-config-path]
   (let [spec (edn/read-string (Files/readString (Path/of spec-path (make-array String 0))))
         {:keys [config decisions synthetic-answers synthetic-errors review-sample]} spec
         deterministic-results (or (:deterministic-results spec) {})
         calls (atom 0)
         _ (when-not (and (map? config) (vector? decisions)
                          (map? synthetic-answers) (map? deterministic-results)
                          (map? (or synthetic-errors {}))
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
                      (when-not (every? #(or (contains? synthetic-answers %)
                                             (contains? synthetic-errors %)) ids)
                        (throw (ex-info "Missing synthetic answer" {:error :missing-synthetic-answer})))
                      (swap! calls inc)
                      (when-let [failure (some #(get synthetic-errors %) ids)]
                        (throw (ex-info "Synthetic provider failure" {:error failure})))
                      {:model (:model config) :usage {}
                       :answers (select-keys synthetic-answers ids)}))
         owner-revision (when owner-options
                          (sync-owner! flow-path decisions config owner-options))
         before-ledger (flow/load-ledger! flow-path)
         result (application/run! before-ledger decisions
                                  (merge owner-options
                                         {:config config :policy policy/default-policy
                                          :persist-flow! persist! :execute! execute!
                                          :deterministic-results deterministic-results}))
         outcomes (frequencies (map :status (vals (:results result))))
         views (flow/inspect (:flow-ledger result) decisions config)
         statuses (frequencies (map :status (vals views)))
         events (get-in result [:flow-ledger :events])
         revision (count events)
         metrics {:schema "local-reconciliation-metrics/v1"
                  :binding {:snapshot_sha256 snapshot-sha256
                            :decision_revision revision
                            :policy_version (:version policy/default-policy)
                            :config_version (:version config)
                            :template_version jev/template-version
                            :model (:model config)}
                  :coverage (decision-metrics decisions views)
                  :provider {:calls_this_execution @calls
                             :cache_hits_this_execution
                             (count (filter #(= :cached-jev (:origin %))
                                            (drop (count (:events before-ledger)) events)))
                             :cache_hits_recorded
                             (count (filter #(= :cached-jev (:origin %)) events))
                             :reported_usage nil :actual_monetary_cost nil}
                  :reversals (count (filter #(and (= :human (:origin %))
                                                  (= :reversed (:status %))) events))
                  :sampled_error (when review-sample
                                   (let [{:keys [frame reviewed errors selection selection-bias]} review-sample]
                                     (when-not (and (string? frame) (seq frame)
                                                    (integer? reviewed) (pos? reviewed)
                                                    (integer? errors) (<= 0 errors reviewed)
                                                    (string? selection) (seq selection)
                                                    (string? selection-bias) (seq selection-bias))
                                       (throw (ex-info "Invalid synthetic review sample" {})))
                                     {:sampling_frame frame :numerator errors
                                      :denominator reviewed :selection selection
                                      :selection_bias selection-bias
                                      :rate (/ (double errors) reviewed)}))
                  :latency_ms nil
                  :accepted_athletes nil :distinct_attempts nil
                  :observation_versions nil}]
     {:schema "local-reconciliation-result/v1"
      :snapshot_sha256 snapshot-sha256
      :remote_store_revision owner-revision
      :decision_revision revision
      :metrics metrics
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
