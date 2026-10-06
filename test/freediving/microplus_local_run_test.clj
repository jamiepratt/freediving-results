(ns freediving.microplus-local-run-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.data.json :as json]
            [clojure.java.shell :as shell]
            [freediving.microplus-local-run :as microplus]
            [freediving.reconciliation-flow :as flow]
            [freediving.reconciliation-application :as application]
            [freediving.canonical-attempt-store :as attempt-store]))

(deftest normal-microplus-cli-rejects-owner-snapshot-mismatch
  (let [root (java.nio.file.Files/createTempDirectory "microplus-sync"
                                                      (make-array java.nio.file.attribute.FileAttribute 0))
        source (shell/sh "python3" "tests/microplus_owner_delivery_service.py" "prepare"
                         (str (.resolve root "snapshot")))
        input (json/read-str (:out source) :key-fn keyword)
        config (.resolve root "owner.edn")]
    (is (= 0 (:exit source)) (:err source))
    (spit (str config) (pr-str {:active-snapshot-sha256 (apply str (repeat 64 "a"))}))
    (java.nio.file.Files/setPosixFilePermissions
     config (java.nio.file.attribute.PosixFilePermissions/fromString "rw-------"))
    (let [result (shell/sh "clojure" "-M" "-m" "freediving.microplus-local-run"
                           :in (json/write-str {:evidence (:evidence input)
                                                :snapshot_sha256 (:snapshot_sha256 input)
                                                :decision_ids ["attempt"]
                                                :flow_path (str (.resolve root "flow.edn"))
                                                :owner_sync_config (str config)}))]
      (is (= 1 (:exit result)))
      (is (re-find #"snapshot differs" (:err result))))))

(defn- signed [payload]
  (let [body (json/write-str payload)
        mac (javax.crypto.Mac/getInstance "HmacSHA256")]
    (.init mac (javax.crypto.spec.SecretKeySpec.
                (.getBytes "synthetic-private-import-token" "UTF-8") "HmacSHA256"))
    {:payload_json body :signature (.formatHex (java.util.HexFormat/of)
                                               (.doFinal mac (.getBytes body "UTF-8")))}))

(deftest normal-microplus-sync-preserves-newer-signed-human-correction
  (let [root (java.nio.file.Files/createTempDirectory "microplus-signed-sync"
                                                      (make-array java.nio.file.attribute.FileAttribute 0))
        prepared (shell/sh "python3" "tests/microplus_owner_delivery_service.py" "prepare"
                           (str (.resolve root "snapshot")))
        source (json/read-str (:out prepared) :key-fn keyword)
        path (str (.resolve root "flow.edn"))
        input {:evidence (:evidence source) :decision_ids ["attempt"]
               :flow_path path :snapshot_sha256 (:snapshot_sha256 source)}
        original (microplus/run! input)
        event (first (:events (flow/load-ledger! path)))
        bindings (mapv (fn [item]
                         {:evidence_id (:evidence-id item)
                          :snapshot_record_id (get-in item [:canonical-subject :version :snapshot-record-id])
                          :observation_revision (get-in item [:canonical-subject :version :observation-revision])})
                       (:evidence event))
        binding {:decision_id "attempt" :reconciliation_run_revision 1
                 :reconciliation_event_id (:id event) :evidence_bindings bindings
                 :observation_revisions (mapv :observation_revision bindings)}
        correction {:id "owner-store:3" :decision_id "attempt" :store_revision 3
                    :binding_revision 1 :snapshot_sha256 (:snapshot_sha256 source)
                    :action "correct" :actor "owner" :reason "different attempts"
                    :correction {:action "distinct_attempts"}
                    :proposal {:selected_option "same_attempt" :canonical_binding binding}}
        config (.resolve root "owner.edn")
        requests (atom [])
        acks (atom [])
        tampered? (atom false)]
    (is (= 1 (:run_revision original)))
    (spit (str config) (pr-str {:active-snapshot-sha256 (:snapshot_sha256 source)
                                :active-binding-revision 1 :flow-path path
                                :base-url "http://127.0.0.1:1234" :attempt-url "disposable-boundary"
                                :allow-loopback-http? true :current-bindings {"attempt" binding}
                                :import-token "synthetic-private-import-token"}))
    (java.nio.file.Files/setPosixFilePermissions
     config (java.nio.file.attribute.PosixFilePermissions/fromString "rw-------"))
    (with-redefs [application/fetch-owner-review-events
                  (fn [_ after _]
                    (swap! requests conj after)
                    (cond-> (signed {:events (if (< after 3) [correction] [])
                                     :store_revision 7 :next_revision (if (< after 3) 3 after)})
                      @tampered? (assoc :signature (apply str (repeat 64 "0")))))
                  application/ack-owner-review-event!
                  (fn [target remote receipt _] (swap! acks conj [target (:id remote) receipt]))
                  attempt-store/private-ledger (fn [_] {:events []})]
      (let [result (microplus/run! (assoc input :owner_sync_config (str config)))
            ledger (flow/load-ledger! path)
            human (last (:events ledger))
            before (slurp path)
            replay (microplus/run! (assoc input :owner_sync_config (str config)))]
        (is (= 7 (:owner_store_revision_synchronized result)))
        (is (= 2 (:run_revision result)))
        (is (= 1 (:proposal_run_revision result)))
        (is (= :distinct-attempts (:action human)))
        (is (= :human (:origin human)))
        (is (= event (first (:events ledger))))
        (is (= [:flow-ledger :postgresql] (mapv first @acks)))
        (is (= 0 (:provider_calls replay)))
        (is (= 7 (:owner_store_revision_synchronized replay)))
        (is (= before (slurp path)))
        (reset! tampered? true)
        (is (thrown? clojure.lang.ExceptionInfo
                     (microplus/run! (assoc input :owner_sync_config (str config)))))
        (is (= before (slurp path)))))))

(defn -main [& _]
  (let [r (run-tests 'freediving.microplus-local-run-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail r) (:error r))) (System/exit 1))))
