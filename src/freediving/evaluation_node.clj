(ns freediving.evaluation-node
  "Dependency-free function matching the inspected Agent-o-rama Clojure node contract."
  (:require [freediving.evaluation :as evaluation]))

(defn node-function
  "Inject result! and a trusted resolver for private content-addressed datasets.
   The resolver must verify the requested hash before returning data. Credentials
   stay in the worker closure. Invocation input and output contain only hashes.
   Actual Rama deployment is a separate, unverified integration checkpoint."
  [result! resolve-dataset private-root configs runtime]
  (when-not (and (ifn? result!) (ifn? resolve-dataset))
    (throw (ex-info "Result callback and private dataset resolver are required" {})))
  (fn [node dataset-ref]
    (when-not (and (string? dataset-ref) (re-matches #"[a-f0-9]{64}" dataset-ref))
      (throw (ex-info "Expected an opaque dataset content reference" {})))
    (result! node (select-keys (evaluation/run! private-root (resolve-dataset dataset-ref) configs runtime)
                               [:run-id :input-hash :report-hash]))))
