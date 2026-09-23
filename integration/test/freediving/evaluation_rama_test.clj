(ns freediving.evaluation-rama-test
  (:require [clojure.test :refer [deftest is run-tests use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [com.rpl.rama.test :as rtest]
            [com.rpl.rama :as rama]
            [com.rpl.agent-o-rama :as aor]
            [freediving.evaluation-rama :as boundary]
            [freediving.evaluation-rama-inspect :as inspect]
            [freediving.observations-test :as db]
            [freediving.observations :as observations]
            [freediving.reviews :as reviews]
            [freediving.evaluation-labels-test :as fixture]
            [freediving.evaluation-labels :as labels]
            [freediving.evaluation :as evaluation]
            [freediving.evaluation-providers-test :as http]))

;; Test process state is resolved on worker initialization, never captured by module.
(def configuration (atom {}))
(def temporary-roots (atom []))
(defn worker-config [] @configuration)

(use-fixtures :each
  (fn [test]
    (db/sql! db/admin "DROP SCHEMA IF EXISTS freediving CASCADE")
    (observations/migrate! db/admin "observations_app")
    (reviews/migrate! db/admin "observations_app" "reviews_owner")
    (labels/migrate! db/admin "reviews_owner" :synthetic)
    (reset! configuration {})
    (try (test)
         (finally
           (doseq [root @temporary-roots file (reverse (file-seq (io/file root)))]
             (io/delete-file file true))
           (reset! temporary-roots [])))))

(defn private-root []
  (let [root (str (.toRealPath (java.nio.file.Files/createTempDirectory
                                "rama-reviewed" (make-array java.nio.file.attribute.FileAttribute 0))
                               (make-array java.nio.file.LinkOption 0)))]
    (swap! temporary-roots conj root)
    root))

(defn assert-private [trace secrets]
  (doseq [secret secrets]
    (is (not (str/includes? (pr-str trace) secret)))))

(defn with-module
  ([f] (with-module 'freediving.evaluation-rama-test/worker-config f))
  ([loader f]
   (with-open [ipc (rtest/create-ipc)]
     (let [module (boundary/evaluation-module loader)
           _ (rtest/launch-module! ipc module {:tasks 1 :threads 1})
           name (rama/get-module-name module)
           client (aor/agent-client (aor/agent-manager ipc name) "evaluate")]
       (f {:ipc ipc :name name :client client})))))

(defn snapshot [root]
  (into {} (for [file (file-seq (io/file root)) :when (.isFile file)]
             [(.getCanonicalPath file) [(.lastModified file) (slurp file)]])))

(defn stored-export [directory]
  (let [receipt (labels/export fixture/owner {:rubric-version "pair-v1"})]
    (labels/write-receipt! directory receipt)
    receipt))

(defn observe [{:keys [ipc name client]} receipt-id]
  (let [invoke (aor/agent-initiate client receipt-id)
        result (.get (aor/agent-result-async client invoke) 60 java.util.concurrent.TimeUnit/SECONDS)]
    {:result result :trace (inspect/trace ipc name client invoke)}))

(deftest opaque-reference-required
  (is (thrown-with-msg? Exception #"Invalid receipt reference"
                        (boundary/invoke! nil {:private "must-not-enter-framework"})))
  (is (thrown-with-msg? Exception #"qualified" (boundary/evaluation-module {:db-url "private"})))
  (with-module
    (fn [{:keys [client]}]
      (is (= {:status :blocked :reason :invalid-receipt-reference}
             (aor/agent-invoke client "invalid-reference"))))))

(deftest real-graph-verifies-and-replays-private-evaluation
  (let [root (private-root)
        store (str root "/store") exports (str root "/exports")
        pair (fixture/sample)
        before (observations/inspect db/app (:job-id (first pair)))
        _ (labels/decide! fixture/owner (fixture/request pair))
        receipt (stored-export exports)
        requests (atom [])
        token "rama-private-synthetic-token"
        secrets [token fixture/owner root "synthetic-reviewer" "pair-label-synthetic/1"
                 (get-in receipt [:dataset :cases 0 :input :left :source-name])
                 (get-in receipt [:dataset :cases 0 :input :right :source-name])]]
    (http/with-server
      (fn [exchange]
        (let [request (json/read-str (slurp (.getRequestBody exchange)) :key-fn keyword)]
          (swap! requests conj request)
          (http/reply! exchange 200
                       (if (:questions request)
                         "{\"model\":\"synthetic-jev\",\"answers\":{\"identity\":{\"type\":\"choice\",\"choice\":\"abstain\",\"confidence\":0.8,\"probabilities\":{\"match\":0.1,\"no_match\":0.1,\"abstain\":0.8}}}}"
                         "{\"model\":\"synthetic-llm\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"outcome\\\":\\\"abstain\\\"}\"}}]}"))))
      (fn [endpoint]
        (reset! configuration {:db-url fixture/owner :receipt-directory exports :private-root store
                               :configs [{:id "rules" :provider :rules}
                                         {:id "jev" :provider :jev :endpoint endpoint :model "synthetic-jev"}
                                         {:id "llm" :provider :llm :endpoint endpoint :model "synthetic-llm"}]
                               :runtime {:providers {"jev" {:bearer-token token} "llm" {:bearer-token token}}}})
        (with-module
          (fn [{:keys [client] :as context}]
            (let [{:keys [result trace]} (observe context (:receipt-id receipt))
                  report (:report (evaluation/inspect-verified-run store fixture/owner (:verified-id result)))
                  files (snapshot root)]
              (is (= :evaluated (:status result)))
              (is (= #{:status :run-id :verified-id :report-hash :export-hash} (set (keys result))))
              (is (every? #(re-matches #"[0-9a-f]{64}" %) (vals (dissoc result :status))))
              (is (= :synthetic-fixture (:label-source report)))
              (is (= 3 (count (:providers report))))
              (is (apply = (map #(mapv :case-id (:results %)) (vals (:providers report)))))
              (doseq [provider (vals (:providers report))]
                (is (= 1 (get-in provider [:metrics :synthetic :case-count])))
                (is (= 0 (get-in provider [:metrics :owner :case-count])))
                (is (= :abstain (get-in provider [:results 0 :outcome])))
                (is (= :unknown (get-in provider [:results 0 :cost :status]))))
              (is (= 2 (count @requests)))
              (is (= result (boundary/invoke! client (:receipt-id receipt))))
              (is (= files (snapshot root)))
              ;; Force a real framework retry after the evaluator has completed.
              ;; No application code or framework source changes are needed.
              (let [real-result! aor/result! attempts (atom 0)]
                (with-redefs [aor/result! (fn [node value]
                                            (when (= 1 (swap! attempts inc))
                                              (throw (ex-info "Synthetic post-evaluation retry" {})))
                                            (real-result! node value))]
                  (let [retried (observe context (:receipt-id receipt))]
                    (is (= result (:result retried)))
                    (assert-private (:trace retried) secrets)
                    (is (= 2 @attempts))
                    (is (str/includes? (pr-str (:trace retried)) "Synthetic post-evaluation retry")))))
              (is (= 2 (count @requests)))
              (is (= files (snapshot root)))
              (assert-private trace secrets)
              (is (not-any? #(re-find #"rubric-version|reviewer|provenance|label-1" (pr-str %)) @requests))
              (is (= (:observations before)
                     (:observations (observations/inspect db/app (:job-id (first pair))))))
              (labels/decide! fixture/owner (assoc (fixture/request pair) :id "revoke" :base-revision 1 :outcome :revoke))
              (let [rejected (observe context (:receipt-id receipt))]
                (is (= {:status :blocked :reason :evaluation-unavailable} (:result rejected)))
                (assert-private (:trace rejected) secrets))
              (is (= files (snapshot root)))
              (let [empty-receipt (stored-export exports)]
                (is (= :blocked (:status (boundary/invoke! client (:receipt-id empty-receipt)))))
                (is (= :no-eligible-reviewed-labels
                       (:reason (boundary/invoke! client (:receipt-id empty-receipt))))))
              (is (= 2 (count @requests))))))))))

(deftest database-change-during-http-cannot-publish-success
  (let [root (private-root) store (str root "/store") exports (str root "/exports")
        pair (fixture/sample)
        _ (labels/decide! fixture/owner (fixture/request pair))
        receipt (stored-export exports) requests (atom 0)]
    (http/with-server
      (fn [exchange]
        (swap! requests inc)
        (labels/decide! fixture/owner (assoc (fixture/request pair)
                                             :id "during-http-revoke" :base-revision 1 :outcome :revoke))
        (http/reply! exchange 200
                     "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"outcome\\\":\\\"abstain\\\"}\"}}]}"))
      (fn [endpoint]
        (reset! configuration {:db-url fixture/owner :receipt-directory exports :private-root store
                               :configs [{:id "llm" :provider :llm :endpoint endpoint :model "synthetic"}]
                               :runtime {:providers {"llm" {:bearer-token "during-http-secret"}}}})
        (with-module
          (fn [context]
            (let [rejected (observe context (:receipt-id receipt))]
              (is (= {:status :blocked :reason :evaluation-unavailable} (:result rejected)))
              (assert-private (:trace rejected)
                              [root fixture/owner "during-http-secret" "synthetic-reviewer"
                               (get-in receipt [:dataset :cases 0 :input :left :source-name])])
              (is (= 1 @requests))
              (is (not-any? #(str/ends-with? (.getName %) "-verified-manifest")
                            (file-seq (io/file store)))))))))))

(defn failing-worker-config []
  (throw (ex-info "SYNTHETIC-PRIVATE-CONFIGURATION" {:credential "SYNTHETIC-PRIVATE-CREDENTIAL"}
                  (Exception. "SYNTHETIC-PRIVATE-CAUSE"))))

(deftest worker-initialization-failure-is-sanitized
  (let [failure (try
                  (with-module 'freediving.evaluation-rama-test/failing-worker-config
                    (fn [{:keys [client]}]
                      (let [invoke (aor/agent-initiate client (apply str (repeat 64 "a")))]
                        (.get (aor/agent-result-async client invoke) 30 java.util.concurrent.TimeUnit/SECONDS))))
                  nil
                  (catch Throwable error error))
        messages (loop [error failure result []]
                   (if error
                     (recur (.getCause error) (conj result [(.getMessage error) (ex-data error)]))
                     (pr-str result)))]
    (is (some? failure))
    (is (str/includes? messages "Evaluation worker initialization failed"))
    (is (not (str/includes? messages "SYNTHETIC-PRIVATE")))))

(defn -main [& _]
  (let [r (run-tests 'freediving.evaluation-rama-test)]
    (shutdown-agents)
    (System/exit (if (pos? (+ (:fail r) (:error r))) 1 0))))
