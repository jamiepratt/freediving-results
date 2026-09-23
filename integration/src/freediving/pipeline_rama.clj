(ns freediving.pipeline-rama
  "Local orchestration. Only opaque job references and stage receipts enter Rama."
  (:require [com.rpl.agent-o-rama :as aor]))

(defn- reference? [value]
  (and (string? value) (boolean (re-matches #"[0-9a-f]{64}" value))))

(defn invoke!
  "Validate before Rama records input. Trusted callers must use this boundary."
  [client job-id]
  (when-not (reference? job-id)
    (throw (ex-info "Invalid job reference" {})))
  (aor/agent-invoke client job-id))

(def ^:private reasons
  #{:invalid-job-reference :invalid-stage :invalid-private-reference :stale-job-version
    :missing-provenance :source-unavailable :invalid-source-reference :source-hash-mismatch
    :missing-or-corrupt-prerequisite :prerequisite-not-ready :stale-stage-receipt
    :stale-extraction-receipt :unsupported-needs-parser :needs-ocr :parser-unresolved
    :invalid-provenance :invalid-archive-reference :extraction-failed :database-error
    :ingestion-integrity-error :readiness-unavailable :worker-failure :invalid-stage-receipt
    :stale-execution :pdf-tool-unavailable :partial-unsupported-needs-parser
    :invalid-extraction-reference :missing-ingestion-reference})

(defn- worker [loader]
  (try
    (let [configuration ((requiring-resolve loader))
          stage! (requiring-resolve 'freediving.pipeline/stage!)]
      (fn [stage job-id execution-id]
        (try
          (let [result (stage! configuration stage job-id execution-id)]
            (when-not (and (#{:ready :blocked :failed :unresolved :unsupported} (:status result))
                           (or (nil? (:reason result)) (reasons (:reason result)))
                           (or (not= :ready (:status result))
                               (and (= job-id (:job-id result))
                                    (every? #(reference? (get result %)) [:execution-id :receipt-id]))))
              (throw (ex-info "Invalid worker outcome" {})))
            (merge {:stage stage :status (:status result)}
                   (when (:reason result) {:reason (:reason result)})
                   (when (= :unreviewed (:review-status result)) {:review-status :unreviewed})
                   (when (= :blocked (:publication-status result)) {:publication-status :blocked})
                   (into {} (for [key [:job-id :execution-id :receipt-id :source-sha256
                                       :extraction-id :artifact-sha256 :corpus-id]
                                  :let [value (get result key)] :when (reference? value)]
                              [key value]))))
          (catch Throwable _
            {:stage stage :status :failed :reason :worker-unavailable}))))
    (catch Throwable _
      (throw (ex-info "Pipeline worker initialization failed" {})))))

(defn- run-stage [stage next-node]
  (fn [node input]
    (let [job-id (if (= stage :archive) input (:job-id input))
          execution-id (when (map? input) (:execution-id input))
          result (if (and (reference? job-id)
                          (or (= stage :archive) (reference? execution-id)))
                   ((aor/get-agent-object node "private-pipeline") stage job-id execution-id)
                   {:stage stage :status :blocked :reason :invalid-job-reference})]
      ;; Framework acknowledgement is outside the worker catch. A retry repeats
      ;; the stage against its own durable, verified receipt.
      (if (and (= :ready (:status result)) next-node)
        (aor/emit! node next-node result)
        (aor/result! node result)))))

(defn pipeline-module
  "Trusted qualified loader returns private same-host worker configuration.
   Neither secrets nor source bytes are serialized in module or node input."
  [loader]
  (when-not (qualified-symbol? loader)
    (throw (ex-info "Expected a qualified worker configuration function" {})))
  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (aor/agentmodule {:module-name "LocalImportPipeline"} [topology]
                   (aor/declare-agent-object-builder topology "private-pipeline"
                                                     (fn [_] (worker loader))
                                                     {:thread-safe? true :auto-tracing? false})
                   (-> (aor/new-agent topology "import")
                       (aor/node "archive" "extraction" (run-stage :archive "extraction"))
                       (aor/node "extraction" "ingestion" (run-stage :extraction "ingestion"))
                       (aor/node "ingestion" "readiness" (run-stage :ingestion "readiness"))
                       (aor/node "readiness" nil (run-stage :readiness nil)))))
