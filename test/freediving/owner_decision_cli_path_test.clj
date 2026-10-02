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
           [java.io BufferedReader InputStreamReader OutputStreamWriter]
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

(defn- browser! [port phase decision-id]
  (let [result (shell/sh "node" "tests/owner_decision_synthetic_browser.mjs"
                         (str port) phase decision-id)]
    (is (zero? (:exit result)) (:err result))
    (when (zero? (:exit result)) (json/read-str (:out result) :key-fn keyword))))

(defn- deliver! [decision-db config-path]
  (let [result (shell/sh "python3" "scripts/owner_decision_export_adapter.py" "deliver"
                         "--decision-db" (str decision-db) "--config" (str config-path))]
    (is (zero? (:exit result)) (str (:out result) "\n" (:err result)))
    (when (seq (:out result)) (json/read-str (:out result) :key-fn keyword))))

(deftest browser-owner-action-delivers-exact-synthetic-decision-to-postgresql
  (let [source (fixture/synthetic 1 "owner-cli-path/1")
        job (get-in source [:artifact :job-id])
        a (str "local-observation:" job ":0")
        b (str "local-observation:" job ":1")
        _ (fixture/publish! source)
        _ (observations/import! app (:root source) job)
        decision (identity/private-jev-decision app a b)
        revisions (export/load-observation-revisions! app [[job 0] [job 1]])
        versions (mapv revisions [[job 0] [job 1]])
        temp (Files/createTempDirectory "owner-browser-pg" (make-array java.nio.file.attribute.FileAttribute 0))
        metadata-path (.resolve temp "metadata.json")
        flow-path (.resolve temp "flow.edn")
        config-path (.resolve temp "config.edn")]
    (Files/writeString metadata-path
                       (json/write-str {:decision_id (:id decision)
                                        :observation_revisions versions :app_url app})
                       (make-array java.nio.file.OpenOption 0))
    (let [process (.start (doto (ProcessBuilder.
                                 ["python3" "tests/test_owner_decision_synthetic_path.py"
                                  "--serve-integrated" (str metadata-path)])
                            (.directory (java.io.File. (System/getProperty "user.dir")))
                            (.redirectErrorStream true)))
          reader (BufferedReader. (InputStreamReader. (.getInputStream process) "UTF-8"))]
      (try
        (let [line (.readLine reader)
              service (try (json/read-str line :key-fn keyword)
                           (catch Exception _ (throw (ex-info "Synthetic service failed"
                                                              {:line line
                                                               :tail (slurp reader)}))))
              binding (:canonical_binding service)
              config {:flow-path (str flow-path) :decisions [decision]
                      :config {:version "synthetic/1"}
                      :base-url (str "http://127.0.0.1:" (:import_port service))
                      :allow-loopback-http? true :access-client-id "synthetic.access"
                      :access-client-secret "synthetic-service-secret"
                      :import-token "synthetic-separate-machine-import-token"
                      :current-bindings {(:id decision) binding}
                      :active-snapshot-sha256 (:snapshot_sha256 service)
                      :active-binding-revision (:binding_revision service)
                      :reviewer-url reviewer}
              db-path (.resolve temp "decisions.sqlite")]
          (flow/save-ledger! flow-path (update (flow/empty-ledger) :events conj
                                               {:id "original-flow" :decision-id (:id decision)}))
          (Files/writeString config-path (pr-str config) (make-array java.nio.file.OpenOption 0))
          (Files/setPosixFilePermissions config-path (PosixFilePermissions/fromString "rw-------"))
          (let [action (browser! (:origin_port service) "approve" (:id decision))]
            (is (= (:decision_id action) (:id decision)))
            (is (= {:expired_access 403 :foreign_origin 403 :stale_revision 409
                    :browser_event_feed 403} (:rejected action)))
            (is (= 200 (:retry action)))
            (is (= (:id decision)
                   (get-in (json/read-str (get-in action [:feed :payload_json]) :key-fn keyword)
                           [:events 0 :decision_id]))))
          (is (= "complete" (:status (deliver! db-path config-path))))
          (is (= 1 (:accepted-group-count (identity/private-canonical-view app))))
          (is (= (:id decision)
                 (get-in (last (identity/private-history app))
                         [:owner-canonical-decision :id])))
          (let [projection (browser! (:origin_port service) "projection" (:id decision))]
            (is (= 1 (:accepted-group-count projection))
                (when (Files/exists (.resolve temp "canonical-error.txt")
                                    (make-array java.nio.file.LinkOption 0))
                  (Files/readString (.resolve temp "canonical-error.txt")))))
          (let [writer (OutputStreamWriter. (.getOutputStream process) "UTF-8")]
            (.write writer "register-dependent\n")
            (.flush writer)
            (is (= "synthetic-dependent"
                   (:registered (json/read-str (.readLine reader) :key-fn keyword)))))
          (is (= "automatic_approved"
                 (:effective_status (browser! (:origin_port service) "inspect"
                                              "synthetic-dependent"))))
          (let [action (browser! (:origin_port service) "reverse" (:id decision))]
            (is (= (:id decision)
                   (get-in (json/read-str (get-in action [:feed :payload_json]) :key-fn keyword)
                           [:events 1 :decision_id]))))
          (is (= "complete" (:status (deliver! db-path config-path))))
          (is (= 0 (:accepted-group-count (identity/private-canonical-view app))))
          (is (= (:id decision)
                 (get-in (last (identity/private-history app))
                         [:owner-canonical-decision :id])))
          (let [projection (browser! (:origin_port service) "projection" (:id decision))]
            (is (= 0 (:accepted-group-count projection)))
            (is (= 1 (count (:negative-pairs projection)))))
          (is (= "invalidated"
                 (:effective_status (browser! (:origin_port service) "inspect"
                                              "synthetic-dependent"))))
          (is (= "complete" (:status (deliver! db-path config-path)))))
        (finally
          (when (.isAlive process)
            (with-open [writer (OutputStreamWriter. (.getOutputStream process) "UTF-8")]
              (.write writer "stop\n") (.flush writer)))
          (when-not (.waitFor process 10 java.util.concurrent.TimeUnit/SECONDS)
            (.destroyForcibly process)
            (.waitFor process 5 java.util.concurrent.TimeUnit/SECONDS))
          (.close reader)
          (with-open [paths (Files/walk temp (make-array java.nio.file.FileVisitOption 0))]
            (doseq [path (reverse (iterator-seq (.iterator paths)))]
              (Files/deleteIfExists path))))))))

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

(defn -main [& args]
  (if (= ["projection"] (vec args))
    (println (json/write-str (identity/private-canonical-view app)))
    (let [result (run-tests 'freediving.owner-decision-cli-path-test)]
      (shutdown-agents)
      (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))))
