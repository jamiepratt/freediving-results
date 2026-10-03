(ns freediving.local-reconciliation-sync-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.data.json :as json]
            [freediving.local-reconciliation :as local]
            [freediving.reconciliation-application :as application]
            [freediving.athlete-identity :as identity]
            [freediving.owner-identity-route :as owner-identity]
            [freediving.reconciliation-flow :as flow]))

(defn- signed [secret payload]
  (let [body (json/write-str payload)
        mac (javax.crypto.Mac/getInstance "HmacSHA256")]
    (.init mac (javax.crypto.spec.SecretKeySpec. (.getBytes secret "UTF-8") "HmacSHA256"))
    {:payload_json body :signature (.formatHex (java.util.HexFormat/of)
                                               (.doFinal mac (.getBytes body "UTF-8")))}))

(deftest newer-owner-reversal-is-imported-before-synthetic-rerun
  (let [root (java.nio.file.Files/createTempDirectory "local-owner-sync"
                                                      (make-array java.nio.file.attribute.FileAttribute 0))
        spec (.resolve root "spec.edn")
        ledger (.resolve root "flow.edn")
        config (.resolve root "owner.edn")
        sha (apply str (repeat 64 "a"))
        binding {:decision_id "identity" :evidence_bindings []}
        decision {:id "identity" :family :identity :action :same-person
                  :choices [:same-person :different-person :unknown]
                  :subject {:id "pair"} :candidates ["a" "b"]
                  :dependencies [] :evidence-adequate? true
                  :evidence [{:evidence-id "e" :citation {:source-sha256 sha :locator "row 1"}
                              :fact "Synthetic names"}]}
        event {:id "owner-store:2" :decision_id "identity" :store_revision 2
               :binding_revision 1 :snapshot_sha256 sha :action "reverse"
               :actor "owner" :reason "synthetic reversal"
               :proposal {:selected_option "same_person" :canonical_binding binding}}
        feed (atom {:events [] :store_revision 1 :next_revision 1})
        project (atom 0)
        acks (atom [])
        calls (atom [])]
    (spit (str spec) (pr-str {:config {:provider :jev :model "synthetic" :version "sync/1"}
                              :decisions [decision]
                              :synthetic-answers {"identity" {:type "choice" :choice "same_person"
                                                              :confidence 0.99 :probabilities {"same_person" 0.99
                                                                                               "different_person" 0.005
                                                                                               "unknown" 0.005}}}}))
    (spit (str config) (pr-str {:base-url "http://127.0.0.1:1234"
                                :access-client-id "synthetic" :access-client-secret "synthetic"
                                :allow-loopback-http? true :import-token "private-import-token-for-test"
                                :current-bindings {"identity" binding}
                                :active-snapshot-sha256 sha :active-binding-revision 1
                                :reviewer-url "synthetic-review" :identity-revisions {"identity" 0}}))
    (with-redefs [application/fetch-owner-review-events
                  (fn [_ after _]
                    (swap! calls conj after)
                    (signed "private-import-token-for-test"
                            (if (> after 1)
                              {:events [] :store_revision (:store_revision @feed)
                               :next_revision after}
                              @feed)))
                  application/ack-owner-review-event!
                  (fn [target event receipt _]
                    (swap! acks conj [target (:id event) receipt]))
                  identity/private-projection (fn [_] {:revision 0})
                  identity/private-history (fn [_] [])
                  owner-identity/record-imported-decision!
                  (fn [& _]
                    (if (= 1 (swap! project inc))
                      (throw (ex-info "synthetic canonical outage" {}))
                      {:accepted-group-count 0}))]
      (local/run! (str spec) (str ledger) sha (str config))
      (reset! feed {:events [event] :store_revision 2 :next_revision 2})
      (is (thrown? clojure.lang.ExceptionInfo
                   (local/run! (str spec) (str ledger) sha (str config))))
      (is (= [:flow-ledger] (mapv first @acks)))
      (is (= 1 (count (filter #(= :human (:origin %))
                              (:events (flow/load-ledger! ledger))))))
      (let [result (local/run! (str spec) (str ledger) sha (str config))]
        (is (= 2 (:remote_store_revision result)))
        (is (= 1 (get-in result [:metrics :reversals])))
        (is (= 1 (get-in result [:metrics :coverage :pending_review])))
        (is (= :reversed (:status (get (flow/inspect (flow/load-ledger! ledger)
                                                     [decision]) "identity"))))
        (is (= [0 0 1 0 2] @calls))
        (is (= 1 (count (filter #(= :human (:origin %))
                                (:events (flow/load-ledger! ledger))))))
        (let [before (count (:events (flow/load-ledger! ledger)))
              replay (local/run! (str spec) (str ledger) sha (str config))]
          (is (= 0 (:provider_calls replay)))
          (is (= before (count (:events (flow/load-ledger! ledger))))))))))

(defn -main [& _]
  (let [result (run-tests 'freediving.local-reconciliation-sync-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
