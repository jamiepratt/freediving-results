(ns freediving.microplus-owner-delivery-path-test
  (:require [clojure.data.json :as json]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is run-tests use-fixtures]]
            [freediving.canonical-attempt-store :as attempt-store]
            [freediving.observations :as observations]
            [freediving.observations-test :as fixture]
            [freediving.reconciliation-flow :as flow]
            [freediving.reconciliation-policy :as policy]
            [freediving.reviews :as reviews]
            [freediving.source-relationships :as relationships])
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter]
           [java.nio.file Files]
           [java.nio.file.attribute PosixFilePermissions]))

(def admin (System/getenv "FREEDIVING_TEST_ADMIN_URL"))
(def app (System/getenv "FREEDIVING_TEST_URL"))

(use-fixtures :each
  (fn [f]
    (when-not (and admin app)
      (throw (ex-info "Disposable PostgreSQL test URLs required" {})))
    (fixture/sql! admin "DROP SCHEMA IF EXISTS freediving CASCADE")
    (observations/migrate! admin "observations_app")
    (reviews/migrate! admin "observations_app" "reviews_owner")
    (f)))

(defn- command! [process reader command]
  (let [writer (OutputStreamWriter. (.getOutputStream process) "UTF-8")]
    (.write writer (str command "\n"))
    (.flush writer))
  (json/read-str (.readLine reader) :key-fn keyword))

(defn- delivery! [database config]
  (let [result (shell/sh "python3" "scripts/owner_decision_export_adapter.py"
                         "deliver" "--decision-db" (str database)
                         "--config" (str config))]
    (is (#{0 2} (:exit result)) (str (:out result) "\n" (:err result)))
    (json/read-str (:out result) :key-fn keyword)))

(defn- route! [config target event-id]
  (let [result (shell/sh "clojure" "-M" "-m" "freediving.owner-event-delivery"
                         "--config" (str config) "--target" target
                         "--event-id" event-id)]
    (is (zero? (:exit result)) (:err result))
    (when (zero? (:exit result))
      (json/read-str (:out result) :key-fn keyword))))

(deftest generated-microplus-proposal-delivers-signed-owner-reversal-to-canonical-store
  (let [temp (Files/createTempDirectory "microplus-owner-delivery"
                                        (make-array java.nio.file.attribute.FileAttribute 0))
        snapshot (.resolve temp "snapshot")
        prepared (shell/sh "python3" "tests/microplus_owner_delivery_service.py"
                           "prepare" (str snapshot))
        _ (is (zero? (:exit prepared)) (:err prepared))
        source (json/read-str (:out prepared) :key-fn keyword)
        evidence (update (:evidence source) :observation-versions
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
        attempt-ledger (relationships/empty-attempt-ledger evidence)
        pair (mapv :id (:observation-versions evidence))
        decision (relationships/attempt-jev-decision attempt-ledger "microplus-attempt"
                                                     :same-attempt pair {})
        config {:provider :jev :model "synthetic" :version "synthetic/1"}
        provider-calls (atom 0)
        execute! (fn [_] (swap! provider-calls inc)
                   (throw (ex-info "Provider must not be called" {})))
        opts {:config config :policy policy/default-policy :execute! execute!
              :deterministic-results {(:id decision)
                                      {:status :approve :rule-version "synthetic/1"}}}
        original (flow/run! (flow/empty-ledger) [decision] opts)
        run-revision (count (:events original))
        original-event (last (:events original))
        flow-path (.resolve temp "flow.edn")
        metadata-path (.resolve temp "metadata.json")
        config-path (.resolve temp "delivery.edn")
        failed-config-path (.resolve temp "failed-delivery.edn")
        database (.resolve temp "decisions.sqlite")]
    (try
      (is (= 1 run-revision))
      (is (= (:id decision) (:decision-id original-event)))
      (is (= (:evidence decision) (:evidence original-event)))
      (is (= 0 @provider-calls))
      (flow/save-ledger! flow-path original)
      (attempt-store/persist! app attempt-ledger)
      (Files/writeString metadata-path
                         (json/write-str (assoc (select-keys source
                                                             [:source_name :record_id :snapshot_sha256])
                                                :decision_id (:id decision)
                                                :reconciliation_run_revision run-revision
                                                :reconciliation_event_id (:id original-event)
                                                :reconciliation_flow_path (str flow-path)))
                         (make-array java.nio.file.OpenOption 0))
      (let [process (.start (doto (ProcessBuilder.
                                   ["python3" "tests/microplus_owner_delivery_service.py"
                                    "serve" (str metadata-path)])
                              (.directory (java.io.File. (System/getProperty "user.dir")))))
            reader (BufferedReader. (InputStreamReader. (.getInputStream process) "UTF-8"))]
        (try
          (let [service (json/read-str (.readLine reader) :key-fn keyword)
                binding (:canonical_binding service)
                _ (is (= (:id original-event) (:reconciliation_event_id binding)))
                owner-config {:flow-path (str flow-path) :decisions [decision]
                              :config config :policy policy/default-policy
                              :base-url (str "http://127.0.0.1:" (:import_port service))
                              :allow-loopback-http? true
                              :access-client-id "synthetic.access"
                              :access-client-secret "synthetic-service-secret"
                              :import-token "synthetic-separate-machine-import-token"
                              :current-bindings {(:id decision) binding}
                              :active-snapshot-sha256 (:snapshot_sha256 source)
                              :active-binding-revision (:binding_revision service)
                              :attempt-url app}]
            (Files/writeString config-path (pr-str owner-config)
                               (make-array java.nio.file.OpenOption 0))
            (Files/setPosixFilePermissions config-path
                                           (PosixFilePermissions/fromString "rw-------"))
            (Files/writeString failed-config-path (pr-str (dissoc owner-config :attempt-url))
                               (make-array java.nio.file.OpenOption 0))
            (Files/setPosixFilePermissions failed-config-path
                                           (PosixFilePermissions/fromString "rw-------"))
            (is (= {:stale_event true :stale_run true :forged_export true}
                   (command! process reader "bad-bindings")))
            (let [approval (command! process reader "approve")
                  event-id (get-in approval [:event :id])
                  flow-receipt (route! config-path "flow-ledger" event-id)
                  _ (is (= (str "flow-ledger:" event-id) (:receipt flow-receipt)))
                  _ (is (= {:flow-ledger 0 :postgresql 0}
                           (:checkpoints (command! process reader "checkpoints"))))
                  failed (delivery! database failed-config-path)
                  _ (is (= "retry_required" (:status failed)))
                  _ (is (= {:flow-ledger (get-in approval [:event :store_revision])
                            :postgresql 0} (:checkpoints failed)))
                  canonical-receipt (route! config-path "postgresql" event-id)
                  _ (is (= (str "postgresql:" event-id) (:receipt canonical-receipt)))
                  _ (is (= {:flow-ledger (get-in approval [:event :store_revision])
                            :postgresql 0}
                           (:checkpoints (command! process reader "checkpoints"))))
                  delivered (delivery! database config-path)]
              (is (= (:id (:event approval))
                     (str "owner-store:" (:store_revision (:event approval)))))
              (is (= "complete" (:status delivered)))
              (is (= 1 (count (:events (attempt-store/private-ledger app)))))
              (is (= 1 (get-in (attempt-store/private-projection app)
                               [:counts :accepted-attempts]))))
            (let [reversal (command! process reader "reverse")
                  delivered (delivery! database config-path)
                  revision (get-in reversal [:event :store_revision])]
              (is (= "complete" (:status delivered)))
              (is (= {:flow-ledger revision :postgresql revision}
                     (:checkpoints delivered)))
              (is (= 2 (get-in (attempt-store/private-projection app)
                               [:counts :accepted-attempts])))
              (is (= 2 (count (:events (attempt-store/private-ledger app)))))
              (is (= 3 (count (:events (flow/load-ledger! flow-path)))))
              (is (= (:events (flow/load-ledger! flow-path))
                     (:events (flow/run! (flow/load-ledger! flow-path) [decision] opts))))
              (is (= 0 @provider-calls))
              (is (= "complete" (:status (delivery! database config-path))))
              (is (= {:flow-ledger revision :postgresql revision}
                     (:checkpoints (command! process reader "checkpoints"))))))
          (finally
            (when (.isAlive process)
              (with-open [writer (OutputStreamWriter. (.getOutputStream process) "UTF-8")]
                (.write writer "stop\n") (.flush writer)))
            (.waitFor process)
            (.close reader))))
      (finally
        (with-open [paths (Files/walk temp (make-array java.nio.file.FileVisitOption 0))]
          (doseq [path (reverse (iterator-seq (.iterator paths)))]
            (Files/deleteIfExists path)))))))

(defn -main [& _]
  (let [result (run-tests 'freediving.microplus-owner-delivery-path-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
