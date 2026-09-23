(ns freediving.evaluation-rama
  "Optional local Agent-o-rama boundary. Only opaque receipt references cross it."
  (:require [com.rpl.agent-o-rama :as aor]
            [freediving.evaluation :as evaluation]
            [freediving.evaluation-labels :as labels]))

(defn- receipt-reference? [value]
  (and (string? value) (boolean (re-matches #"[0-9a-f]{64}" value))))

(defn invoke!
  "Validate before Rama records the input. Trusted callers must use this boundary."
  [client receipt-id]
  (when-not (receipt-reference? receipt-id)
    (throw (ex-info "Invalid receipt reference" {})))
  (aor/agent-invoke client receipt-id))

(defn- worker [loader]
  (try
    (let [{:keys [db-url receipt-directory private-root configs runtime]} ((requiring-resolve loader))]
      (fn [receipt-id]
        (try
          (let [receipt (labels/resolve-receipt! db-url receipt-directory receipt-id)]
            (select-keys (evaluation/run-verified! private-root db-url receipt configs (or runtime {}))
                         [:status :reason :run-id :verified-id :report-hash :export-hash]))
          (catch Throwable _
            {:status :blocked :reason :evaluation-unavailable}))))
    (catch Throwable _
      (throw (ex-info "Evaluation worker initialization failed" {})))))

(defn evaluation-module
  "Loader is a trusted qualified symbol for a zero-argument worker configuration
   function. Resolve secrets there, never capture them in the serialized module.
   Configuration: :db-url, :receipt-directory, :private-root, :configs, :runtime.
   Private store must survive retries on this host. This is not distributed storage."
  [loader]
  (when-not (qualified-symbol? loader)
    (throw (ex-info "Expected a qualified worker configuration function" {})))
  ;; agentmodule binds topology, like Rama module; no clj-kondo hook is shipped.
  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (aor/agentmodule {:module-name "ReviewedEvaluation"} [topology]
                   (aor/declare-agent-object-builder topology "private-evaluator"
                                                     (fn [_] (worker loader))
                                                     {:thread-safe? true :auto-tracing? false})
                   (-> (aor/new-agent topology "evaluate")
                       (aor/node "evaluate" nil
                                 (fn [node receipt-id]
                    ;; result! is deliberately outside the private-operation catch:
                    ;; framework failures retry the node against immutable receipts.
                                   (aor/result! node
                                                (if (receipt-reference? receipt-id)
                                                  ((aor/get-agent-object node "private-evaluator") receipt-id)
                                                  {:status :blocked :reason :invalid-receipt-reference})))))))
