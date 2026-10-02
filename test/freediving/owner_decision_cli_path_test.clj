(ns freediving.owner-decision-cli-path-test
  (:require [clojure.data.json :as json]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is use-fixtures run-tests]]
            [freediving.athlete-identity :as identity]
            [freediving.observations :as observations]
            [freediving.observations-test :as fixture]
            [freediving.owner-decision-export :as export]
            [freediving.reconciliation-flow :as flow]
            [freediving.reviews :as reviews])
  (:import [com.sun.net.httpserver HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.nio.file Files]
           [java.nio.file.attribute PosixFilePermissions]
           [java.util HexFormat]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(def admin (System/getenv "FREEDIVING_TEST_ADMIN_URL"))
(def app (System/getenv "FREEDIVING_TEST_URL"))
(def reviewer (System/getenv "FREEDIVING_TEST_REVIEW_URL"))

(use-fixtures :each
  (fn [f]
    (when-not (and admin app reviewer)
      (throw (ex-info "Disposable PostgreSQL test URLs required" {})))
    (fixture/sql! admin "DROP SCHEMA IF EXISTS freediving CASCADE")
    (observations/migrate! admin "observations_app")
    (reviews/migrate! admin "observations_app" "reviews_owner")
    (f)))

(defn- signed [secret payload]
  (let [value (json/write-str payload)
        mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. (.getBytes secret "UTF-8") "HmacSHA256"))
    {:payload_json value
     :signature (.formatHex (HexFormat/of) (.doFinal mac (.getBytes value "UTF-8")))}))

(defn- command! [config target event-id]
  (let [result (shell/sh "clojure" "-M" "-m" "freediving.owner-event-delivery"
                         "--config" config "--target" target "--event-id" event-id)]
    (is (zero? (:exit result)) (:err result))
    (when (zero? (:exit result)) (json/read-str (:out result) :key-fn keyword))))

(deftest signed-cli-checkpoints-before-canonical-identity-and-retries
  (let [source (fixture/synthetic 1 "owner-cli-path/1")
        job (get-in source [:artifact :job-id])
        a (str "local-observation:" job ":0")
        b (str "local-observation:" job ":1")
        _ (fixture/publish! source)
        _ (observations/import! app (:root source) job)
        decision (identity/private-jev-decision app a b)
        revisions (export/load-observation-revisions! app [[job 0] [job 1]])
        versions (mapv revisions [[job 0] [job 1]])
        binding {:decision_id (:id decision) :reconciliation_run_revision 1
                 :reconciliation_event_id "original-flow"
                 :observation_revisions versions
                 :evidence_bindings
                 (mapv (fn [revision]
                         {:evidence_id (str (:job_id revision) ":" (:ordinal revision))
                          :snapshot_record_id (apply str (repeat 64 "a"))
                          :observation_revision revision}) versions)}
        snapshot-sha (apply str (repeat 64 "b"))
        secret "synthetic-owner-import-token-12345"
        owner-base {:binding_revision 2 :snapshot_sha256 snapshot-sha
                    :decision_id (:id decision) :actor "owner@example.com"
                    :reason "synthetic source review"
                    :proposal {:selected_option "same_person" :canonical_binding binding}}
        approval (assoc owner-base :id "owner-store:3" :store_revision 3 :action "approve")
        reversal (assoc owner-base :id "owner-store:4" :store_revision 4 :action "reverse")
        served (atom (signed secret {:events [approval] :store_revision 3 :next_revision 3}))
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        temp (Files/createTempDirectory "owner-cli-path" (make-array java.nio.file.attribute.FileAttribute 0))
        flow-path (.resolve temp "flow.edn")
        config-path (.resolve temp "config.edn")]
    (.createContext server "/owner-evidence/api/decision-events"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (let [body (.getBytes (json/write-str @served) "UTF-8")]
                          (.getFirst (.getRequestHeaders exchange) "X-Freediving-Import-Token")
                          (.sendResponseHeaders exchange 200 (alength body))
                          (with-open [out (.getResponseBody exchange)] (.write out body))))))
    (.start server)
    (try
      (flow/save-ledger! flow-path (update (flow/empty-ledger) :events conj
                                           {:id "original-flow" :decision-id (:id decision)}))
      (let [config {:flow-path (str flow-path) :decisions [decision]
                    :config {:version "synthetic/1"}
                    :base-url (str "http://127.0.0.1:" (.getPort (.getAddress server)))
                    :allow-loopback-http? true :access-client-id "synthetic.access"
                    :access-client-secret "synthetic-service-secret"
                    :import-token secret :current-bindings {(:id decision) binding}
                    :active-snapshot-sha256 snapshot-sha :active-binding-revision 2
                    :reviewer-url reviewer}
            _ (Files/writeString config-path (pr-str config) (make-array java.nio.file.OpenOption 0))
            _ (Files/setPosixFilePermissions config-path (PosixFilePermissions/fromString "rw-------"))]
        (is (= "flow-ledger:owner-store:3"
               (:receipt (command! (str config-path) "flow-ledger" "owner-store:3"))))
        (is (= 2 (count (:events (flow/load-ledger! flow-path)))))
        (is (= "postgresql:owner-store:3"
               (:receipt (command! (str config-path) "postgresql" "owner-store:3"))))
        (is (= 1 (:accepted-group-count (identity/private-projection app))))
        (is (= "postgresql:owner-store:3"
               (:receipt (command! (str config-path) "postgresql" "owner-store:3"))))
        (is (= 1 (:accepted-group-count (identity/private-projection app))))
        (reset! served (signed secret {:events [reversal] :store_revision 4 :next_revision 4}))
        (is (= "flow-ledger:owner-store:4"
               (:receipt (command! (str config-path) "flow-ledger" "owner-store:4"))))
        (is (= "postgresql:owner-store:4"
               (:receipt (command! (str config-path) "postgresql" "owner-store:4"))))
        (is (= 0 (:accepted-group-count (identity/private-projection app))))
        (is (= 1 (count (:negative-pairs (identity/private-projection app))))))
      (finally
        (.stop server 0)
        (Files/deleteIfExists config-path)
        (Files/deleteIfExists flow-path)
        (Files/deleteIfExists (.resolve temp "flow.edn.lock"))
        (Files/deleteIfExists temp)))))

(defn -main [& _]
  (let [result (run-tests 'freediving.owner-decision-cli-path-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
