(ns freediving.local-reconciliation
  "Synthetic, private coordinator adapter for the existing reconciliation application."
  (:refer-clojure :exclude [run!])
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [freediving.reconciliation-application :as application]
            [freediving.reconciliation-flow :as flow]
            [freediving.reconciliation-policy :as policy])
  (:import [java.nio.file Files Path]))

(defn run! [spec-path flow-path snapshot-sha256]
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
        execute! (fn [request]
                   (let [ids (:decision-ids request)]
                     (when-not (every? #(contains? synthetic-answers %) ids)
                       (throw (ex-info "Missing synthetic answer" {:error :missing-synthetic-answer})))
                     (swap! calls inc)
                     {:model (:model config) :usage {}
                      :answers (select-keys synthetic-answers ids)}))
        result (application/run! (flow/load-ledger! flow-path) decisions
                                 {:config config :policy policy/default-policy
                                  :persist-flow! persist! :execute! execute!
                                  :deterministic-results deterministic-results})
        outcomes (frequencies (map :status (vals (:results result))))
        views (flow/inspect (:flow-ledger result) decisions config)
        statuses (frequencies (map :status (vals views)))]
    {:schema "local-reconciliation-result/v1"
     :snapshot_sha256 snapshot-sha256
     :decision_revision (count (get-in result [:flow-ledger :events]))
     :provider_calls @calls
     :accepted_athletes nil
     :distinct_attempts nil
     :no_link (get outcomes :no-link 0)
     :materialized (get outcomes :materialized 0)
     :unresolved (get outcomes :unresolved 0)
     :reversed (get outcomes :reversed 0)
     :flow_statuses (into {} (map (fn [[status count]] [(name status) count]) statuses))}))

(defn -main [& args]
  (when-not (= 3 (count args))
    (throw (ex-info "spec, flow and snapshot SHA-256 required" {})))
  (println (json/write-str (apply run! args))))
