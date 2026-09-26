(ns freediving.evaluation-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [freediving.evaluation :as evaluation]
            [freediving.evaluation-data-test :as fixtures]
            [freediving.evaluation-providers :as providers]
            [freediving.evaluation-providers-test :as http])
  (:import [java.nio.file Files] [java.nio.file.attribute FileAttribute]))
(defn root [] (.getCanonicalPath (.toFile (Files/createTempDirectory "shadow-eval-" (make-array FileAttribute 0)))))
(def dataset (fixtures/dataset [(fixtures/sample-case "a" :development) (fixtures/sample-case "b" :held-out)]))
(def configs [{:id "rules" :provider :rules}])
(deftest v3-requires-credential-before-storage
  (let [dir (str (root) "/fresh") cfg [{:id "jev" :provider :jev :identity-protocol :freediving-compact-v3}]
        calls (atom 0)]
    (with-redefs [providers/prepare-batches (fn [_ _]
                                              [{:adapter-version "shadow-adapters/14" :provider :jev
                                                :case-ids ["b"] :question-ids ["identity_0"]
                                                :config {:max-response-bytes 10000
                                                         :identity-protocol :freediving-compact-v3}}])
                  providers/execute! (fn [& _]
                                       (swap! calls inc)
                                       {:outcome :complete :model-version "jev-1.13.0"
                                        :http-status 200 :answers {"identity_0" {:outcome :match}}
                                        :cost {:status :unknown}})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"credential"
                            (evaluation/run! dir dataset cfg {:providers {"jev" {:bearer-token "  "}}})))
      (is (not (.exists (io/file dir))))
      (let [first-run (evaluation/run! dir dataset cfg {:providers {"jev" {:bearer-token "secret"}}})]
        (is (= 1 @calls))
        (is (= [(:run-id first-run)] (evaluation/list-runs dir)))
        (is (re-matches #"[0-9a-f]{64}"
                        (get-in (evaluation/inspect-run dir (:run-id first-run))
                                [:report :providers "jev" :batches 0 :result-hash])))
        (is (= first-run (evaluation/run! dir dataset cfg)))
        (is (= 1 @calls))))))
(deftest strict-output-contract-is-durable-and-halts-without-masking-errors
  (let [dir (root) calls (atom 0)
        ds (fixtures/dataset (mapv #(fixtures/sample-case % :held-out) ["a" "b" "c"]))
        response (fn [outcome]
                   (str (json/write-str
                         {:model "gpt-4.1-nano-2025-04-14" :usage {:prompt_tokens 9}
                          :choices [{:finish_reason "stop" :message {:content outcome}}]}) "\n"))]
    (http/with-server
      (fn [ex]
        (http/reply! ex 200 (response (if (= 1 (swap! calls inc))
                                        " \n{\"outcome\":\"match\"}\t"
                                        "{\"outcome\":\"match\",\"extra\":\"fixture-secret\"}"))))
      (fn [url]
        (let [cfg [{:id "remote" :provider :llm :endpoint url :model "gpt-4.1-nano-2025-04-14"
                    :diagnostics-version 2 :output-contract :identity-outcome-v1
                    :max-completion-tokens 128 :stop-on-terminal-error? true :max-attempts 3}
                   {:id "rules" :provider :rules}]
              runtime {:providers {"remote" {:bearer-token "fixture-secret"}}}
              receipt (evaluation/run! dir ds cfg runtime)
              view (evaluation/inspect-run dir (:run-id receipt))
              results (get-in view [:report :providers "remote" :results])]
          (is (= 2 @calls))
          (is (= "shadow-adapters/5" (get-in view [:input :requests 0 0 :adapter-version])))
          (is (= [:match :error :error] (mapv :outcome results)))
          (is (= [[] [:invalid-output-shape] nil] (mapv :validation-reasons results)))
          (is (= [{:prompt_tokens 9} {:prompt_tokens 9} nil] (mapv :usage results)))
          (is (= ["gpt-4.1-nano-2025-04-14" "gpt-4.1-nano-2025-04-14" nil]
                 (mapv :model-version results)))
          (is (= [1 1 0] (mapv #(count (:attempts %)) results)))
          (is (= :comparator-halted (:error (last results))))
          (is (= :not-dispatched (:dispatch-status (last results))))
          (is (= {:status :not-incurred} (:cost (last results))))
          (is (= [:abstain :abstain :abstain]
                 (mapv :outcome (get-in view [:report :providers "rules" :results]))))
          (with-redefs [providers/execute! (fn [& _] (throw (AssertionError. "Replay dispatched provider")))]
            (is (= receipt (evaluation/run! dir ds cfg runtime)))
            (is (= view (evaluation/inspect-run dir (:run-id receipt)))))
          (let [legacy (evaluation/run! dir ds (update cfg 0 dissoc :output-contract))]
            (is (not= (:run-id receipt) (:run-id legacy)))
            (is (= "shadow-adapters/4"
                   (get-in (evaluation/inspect-run dir (:run-id legacy)) [:input :requests 0 0 :adapter-version]))))
          (is (= 2 @calls))
          (doseq [file (filter #(.isFile %) (file-seq (io/file dir)))]
            (is (not (.contains (slurp file) "fixture-secret")))))))))
(deftest replay-is-idempotent-and-evaluates-only-held-out-cases
  (let [dir (root) first-run (evaluation/run! dir dataset configs)
        again (evaluation/run! dir dataset configs)
        report (:report (evaluation/inspect-run dir (:run-id first-run)))]
    (is (= first-run again))
    (is (= ["b"] (mapv :case-id (get-in report [:providers "rules" :results]))))
    (is (= :abstain (get-in report [:providers "rules" :results 0 :outcome])))
    (is (= (:run-id first-run) (:run-id again)))))
(deftest retry-attempts-are-bounded-and-metered-separately
  (let [calls (atom 0) dir (root)
        result {:outcome :error :retryable? true :external-outcome :known :model-version "test-v1" :cost {:status :metered :amount 0.2 :currency "USD"}}]
    (with-redefs [providers/execute! (fn [_ _] (swap! calls inc) result)]
      (let [receipt (evaluation/run! dir dataset [{:id "retry" :provider :stub :max-attempts 3 :retry-delay-ms 0}])
            r (get-in (evaluation/inspect-run dir (:run-id receipt)) [:report :providers "retry" :results 0])]
        (is (= 3 @calls))
        (is (= 3 (count (:attempts r))))
        (is (< 0.59 (get-in r [:cost :amount]) 0.61))
        (is (= :metered (get-in r [:cost :status])))
        (is (number? (:latency-ms r)))
        (evaluation/run! dir dataset [{:id "retry" :provider :stub :max-attempts 3 :retry-delay-ms 0}])
        (is (= 3 @calls))))))

(deftest interrupted-external-outcome-is-not-redispatched
  (doseq [phase [:attempt-started :attempt-returned]]
    (let [dir (root) calls (atom 0) cfg [{:id "interrupted" :provider :stub :max-attempts 3}]
          runtime {:on-progress #(when (= phase (:phase %)) (throw (ex-info "interrupted" {})))}]
      (with-redefs [providers/execute! (fn [_ _] (swap! calls inc) {:outcome :match :retryable? false :external-outcome :known :model-version "fake" :cost {:status :unknown}})]
        (is (thrown? clojure.lang.ExceptionInfo (evaluation/run! dir dataset cfg runtime)))
        (let [before @calls receipt (evaluation/run! dir dataset cfg)
              result (get-in (evaluation/inspect-run dir (:run-id receipt)) [:report :providers "interrupted" :results 0])]
          (is (= before @calls))
          (is (= :error (:outcome result)))
          (is (= :unknown (:external-outcome result)))
          (is (= :unknown (get-in result [:cost :status])))
          (is (nil? (:latency-ms result)))
          (is (= receipt (evaluation/run! dir dataset cfg))))))))

(deftest process-death-releases-os-lock-and-replay-preserves-uncertainty
  (let [dir (root)
        code (str "(require '[freediving.evaluation :as e] '[freediving.evaluation-test :as t]) "
                  "(e/run! " (pr-str dir) " t/dataset t/configs {:on-progress (fn [event] (when (= :attempt-returned (:phase event)) (.halt (Runtime/getRuntime) 23)))})")
        child (.start (ProcessBuilder. ^java.util.List [(str (System/getProperty "java.home") "/bin/java") "-cp" (System/getProperty "java.class.path") "clojure.main" "-e" code]))]
    (try
      (is (.waitFor child 20 java.util.concurrent.TimeUnit/SECONDS))
      (is (= 23 (.exitValue child)))
      (let [receipt (evaluation/run! dir dataset configs)
            r (get-in (evaluation/inspect-run dir (:run-id receipt)) [:report :providers "rules" :results 0])]
        (is (= :interrupted-attempt (:error r)))
        (is (= :unknown (:external-outcome r))))
      (finally (.destroyForcibly child)))))

(deftest private-content-is-verified-on-inspection-and-replay
  (let [dir (root) receipt (evaluation/run! dir dataset configs)
        files (filter #(.isFile %) (file-seq (io/file dir)))]
    (doseq [file files]
      (is (not-any? #(re-find #"GROUP|OTHERS" (str %)) (Files/getPosixFilePermissions (.toPath file) (make-array java.nio.file.LinkOption 0)))))
    (spit (io/file dir "objects" (:report-hash receipt)) "{}")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"hash mismatch" (evaluation/inspect-run dir (:run-id receipt))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"hash mismatch" (evaluation/run! dir dataset configs)))))

(deftest rejects-datasets-without-evaluation-cases-before-writing
  (let [dir (root) empty-held-out (fixtures/dataset [(fixtures/sample-case "a" :development)])]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"held-out" (evaluation/run! dir empty-held-out configs)))
    (is (empty? (.listFiles (io/file dir))))))

(deftest replay-verifies-original-request-and-start-traces
  (doseq [hash-key [:request-hash :start-hash :trace-hash]]
    (let [dir (root) receipt (evaluation/run! dir dataset configs)
          inspected (evaluation/inspect-run dir (:run-id receipt))
          hash (get-in inspected [:report :providers "rules" :results 0 :attempts 0 hash-key])]
      (spit (io/file dir "objects" hash) "{}")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"hash mismatch" (evaluation/inspect-run dir (:run-id receipt))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"hash mismatch" (evaluation/run! dir dataset configs))))))

(deftest excessive-planned-response-budget-rejected-before-storage-or-dispatch
  (let [dir (root) calls (atom 0)
        cases (mapv #(assoc (fixtures/sample-case "b" :held-out) :case-id (str "case-" %)) (range 12))]
    (with-redefs [providers/execute! (fn [_ _] (swap! calls inc) {:outcome :abstain :retryable? false :cost {:status :unknown}})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"budget"
                            (evaluation/run! dir (fixtures/dataset cases)
                                             [{:id "oversized" :provider :stub :max-attempts 3 :max-response-bytes 1048576}])))
      (is (zero? @calls))
      (is (empty? (.listFiles (io/file dir)))))))

(deftest successful-retry-retains-unknown-external-attempt-warning
  (let [dir (root) calls (atom 0)]
    (with-redefs [providers/execute! (fn [_ _]
                                       (if (= 1 (swap! calls inc))
                                         {:outcome :error :retryable? true :external-outcome :unknown :cost {:status :unknown}}
                                         {:outcome :match :retryable? false :external-outcome :known :cost {:status :metered :amount 0.1 :currency "USD"}}))]
      (let [receipt (evaluation/run! dir dataset [{:id "retry" :provider :stub :max-attempts 2 :retry-delay-ms 0}])
            result (get-in (evaluation/inspect-run dir (:run-id receipt)) [:report :providers "retry" :results 0])]
        (is (= :match (:outcome result)))
        (is (= 1 (:unknown-external-attempt-count result)))
        (is (= [:possible-duplicate-external-work] (:warnings result)))
        (is (= :unknown (get-in result [:cost :status])))))))

(deftest provider-credentials-are-scoped-by-configuration-id
  (let [dir (root) received (atom {})
        server (com.sun.net.httpserver.HttpServer/create (java.net.InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/" (reify com.sun.net.httpserver.HttpHandler
                                 (handle [_ exchange]
                                   (let [route (.getPath (.getRequestURI exchange))
                                         token (.getFirst (.getRequestHeaders exchange) "Authorization")
                                         body (.getBytes "{\"choices\":[{\"message\":{\"content\":\"{\\\"outcome\\\":\\\"abstain\\\"}\"}}],\"model\":\"fixture-v1\"}" "UTF-8")]
                                     (swap! received assoc route token)
                                     (.sendResponseHeaders exchange 200 (alength body))
                                     (with-open [out (.getResponseBody exchange)] (.write out body))))))
    (.start server)
    (try
      (let [endpoint (str "http://127.0.0.1:" (.getPort (.getAddress server)))
            configs (mapv (fn [id] {:id id :provider :llm :model "fixture-v1" :endpoint (str endpoint "/" id)}) ["alpha" "beta" "unscoped"])
            runtime {:bearer-token "root-token-must-not-be-used"
                     :providers {"alpha" {:bearer-token "alpha-private-token"}
                                 "beta" {:bearer-token "beta-private-token"}}}]
        (evaluation/run! dir dataset configs runtime)
        (is (= "Bearer alpha-private-token" (get @received "/alpha")))
        (is (= "Bearer beta-private-token" (get @received "/beta")))
        (is (nil? (get @received "/unscoped")))
        (doseq [file (filter #(.isFile %) (file-seq (io/file dir)))]
          (is (not (re-find #"alpha-private-token|beta-private-token|root-token-must-not-be-used" (slurp file))))))
      (finally (.stop server 0)))))

(defn -main [& _] (let [r (run-tests 'freediving.evaluation-test)] (System/exit (if (pos? (+ (:fail r) (:error r))) 1 0))))

(deftest file-verification-flags-cannot-promote-owner-labels
  (let [c (assoc (fixtures/sample-case "b" :held-out) :label
                 {:outcome :match :provenance :owner :reviewer "file assertion"
                  :review-id "claimed" :evidence-ids ["b"]
                  :reviewed-at "2026-09-23T00:00:00Z"
                  :review-artifact-sha256 (apply str (repeat 64 "b"))})
        forged (assoc (fixtures/dataset [c]) :verified? true :label-source :verified-owner-review)
        dir (root)
        receipt (evaluation/run! dir forged configs {:verified? true :label-source :verified-owner-review})
        metrics (get-in (evaluation/inspect-run dir (:run-id receipt)) [:report :providers "rules" :metrics])]
    (is (= 0 (get-in metrics [:owner :case-count])))
    (is (= 1 (get-in metrics [:asserted :case-count])))))

(deftest terminal-provider-errors-stop-only-that-comparator-and-replay
  (doseq [[status body token] [[400 "{}" "fixture"] [401 "{}" "fixture"]
                               [402 "{}" "fixture"] [403 "{}" "fixture"]
                               [404 "{}" "fixture"] [422 "{}" "fixture"]
                               [429 "{}" "fixture"] [200 "{}" "fixture"]
                               [200 "{}" nil]]]
    (let [calls (atom 0) dir (root)
          ds (fixtures/dataset (mapv #(fixtures/sample-case % :held-out) ["a" "b" "c"]))]
      (http/with-server (fn [ex] (swap! calls inc) (http/reply! ex status body))
        (fn [url]
          (let [cfg [{:id "remote" :provider :llm :endpoint url :model "fixture"
                      :stop-on-terminal-error? true :max-attempts 3 :retry-delay-ms 0}
                     {:id "rules" :provider :rules}]
                runtime {:providers {"remote" {:bearer-token token}}}
                receipt (evaluation/run! dir ds cfg runtime)
                report (:report (evaluation/inspect-run dir (:run-id receipt)))
                results (get-in report [:providers "remote" :results])]
            (is (= (if token 1 0) @calls))
            (is (= [1 0 0] (mapv #(count (:attempts %)) results)))
            (is (= [:comparator-halted :comparator-halted] (mapv :error (rest results))))
            (is (every? #(and (= :not-dispatched (:dispatch-status %))
                              (nil? (:latency-ms %)) (= {:status :not-incurred} (:cost %))) (rest results)))
            (is (= {:evaluated-case-count 1 :undispatched-case-count 2 :attempt-count 1}
                   (get-in report [:providers "remote" :dispatch])))
            (is (= {:metered-count 0 :unknown-count 1 :not-incurred-count 2 :totals-by-currency {}}
                   (get-in report [:providers "remote" :metrics :cost])))
            (is (= 3 (get-in report [:providers "remote" :metrics :overall :errors])))
            (is (= 2 (get-in report [:providers "remote" :metrics :latency :not-dispatched-count])))
            (is (= [:abstain :abstain :abstain] (mapv :outcome (get-in report [:providers "rules" :results]))))
            (is (= receipt (evaluation/run! dir ds cfg runtime)))
            (is (= (if token 1 0) @calls))))))))

(deftest diagnostics-have-distinct-identities-and-survive-durable-replay
  (let [dir (root) calls (atom 0)
        body "{\"model\":\"fixture-v1\",\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":-1,\"unknown\":\"fixture-secret\"},\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"invalid fixture-secret\"}}]}"
        ds (fixtures/dataset (mapv #(fixtures/sample-case % :held-out) ["a" "b"]))]
    (http/with-server (fn [ex] (swap! calls inc) (http/reply! ex 200 body))
      (fn [url]
        (let [legacy [{:id "remote" :provider :llm :model "fixture" :endpoint url :stop-on-terminal-error? true}]
              cfg (mapv #(assoc % :diagnostics-version 1) legacy)
              runtime {:providers {"remote" {:bearer-token "fixture-secret"}}}
              old (evaluation/run! dir ds legacy runtime)
              old-view (evaluation/inspect-run dir (:run-id old))
              fresh (evaluation/run! dir ds cfg runtime)
              fresh-view (evaluation/inspect-run dir (:run-id fresh))
              result (get-in fresh-view [:report :providers "remote" :results 0])
              request (get-in fresh-view [:input :requests 0 0])]
          (is (= 2 @calls))
          (is (not= (:run-id old) (:run-id fresh)))
          (is (= "shadow-adapters/3" (:adapter-version request)))
          (is (= :invalid-response (:error result)))
          (is (= [:non-stop-finish :invalid-content-json :invalid-usage] (:validation-reasons result)))
          (is (= :length (:finish-reason result)))
          (is (= "fixture-v1" (:model-version result)))
          (is (= {:prompt_tokens 9} (:usage result)))
          (is (= :comparator-halted (get-in fresh-view [:report :providers "remote" :results 1 :error])))
          (is (= {:status :unknown} (:cost result)))
          (is (nil? (get-in old-view [:report :providers "remote" :results 0 :validation-reasons])))
          (is (= old (evaluation/run! dir ds legacy runtime)))
          (is (= fresh (evaluation/run! dir ds cfg runtime)))
          (is (= fresh-view (evaluation/inspect-run dir (:run-id fresh))))
          (is (= old-view (evaluation/inspect-run dir (:run-id old))))
          (is (= 2 @calls))
          (doseq [file (filter #(.isFile %) (file-seq (io/file dir)))]
            (is (not (.contains (slurp file) "fixture-secret")))))))))

(deftest legacy-run-identity-remains-anchored-to-pre-diagnostics-code
  ;; /1 and /2 captured on 4e75100; /3 on b55861b; /4 on 51b07ab, via public run! without credentials.
  (doseq [[cap diagnostics version expected] [[nil nil "shadow-adapters/1" "4e9b85ffca3a3d97cde02dfbc1b9b373ffd11f75cca065b15ef9dddb4c0b7fb3"]
                                              [128 nil "shadow-adapters/2" "472e342314cac02f762fba3025beda369435f720220b0905bdabb72ebb40f712"]
                                              [nil 1 "shadow-adapters/3" "e8cf87fbe372cc63abebcfa525587a3e3c7b3f0a0de53923f34247b8fc77a964"]
                                              [nil 2 "shadow-adapters/4" "b9729f53c826b21d25dc852f0e9afc993a75943eefa6012a55526ceec97b4fb4"]]]
    (let [dir (root)
          cfg [(cond-> {:id "legacy" :provider :llm :model "fixture" :endpoint "http://127.0.0.1:1/"}
                 cap (assoc :max-completion-tokens cap)
                 diagnostics (assoc :diagnostics-version diagnostics))]
          receipt (evaluation/run! dir dataset cfg)
          before (evaluation/inspect-run dir (:run-id receipt))]
      (is (= expected (:run-id receipt)))
      (is (= version (get-in before [:input :requests 0 0 :adapter-version])))
      (is (= :missing-credential (get-in before [:report :providers "legacy" :results 0 :error])))
      (with-redefs [providers/execute! (fn [& _] (throw (AssertionError. "Replay dispatched provider")))]
        (is (= receipt (evaluation/run! dir dataset cfg)))
        (is (= before (evaluation/inspect-run dir (:run-id receipt))))))))

(deftest corrected-whitespace-results-and-terminal-diagnostics-replay-without-http
  (let [dir (root) calls (atom 0)
        valid " \t\r\n{\"model\":\"fixture-v1\",\"usage\":{\"prompt_tokens\":9},\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\" \\n{\\\"outcome\\\":\\\"match\\\"}\\t \"}}]}\n"
        invalid "{\"model\":\"fixture-v1\",\"usage\":{\"prompt_tokens\":9},\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"invalid fixture-secret\"}}]}\n"
        ds (fixtures/dataset (mapv #(fixtures/sample-case % :held-out) ["a" "b" "c"]))]
    (http/with-server (fn [ex] (http/reply! ex 200 (if (= 3 (swap! calls inc)) invalid valid)))
      (fn [url]
        (let [legacy [{:id "remote" :provider :llm :model "fixture" :endpoint url
                       :diagnostics-version 1 :stop-on-terminal-error? true :max-attempts 3 :retry-delay-ms 0}
                      {:id "rules" :provider :rules}]
              corrected (assoc-in legacy [0 :diagnostics-version] 2)
              runtime {:providers {"remote" {:bearer-token "fixture-secret"}}}
              old (evaluation/run! dir ds legacy runtime)
              old-view (evaluation/inspect-run dir (:run-id old))
              fresh (evaluation/run! dir ds corrected runtime)
              fresh-view (evaluation/inspect-run dir (:run-id fresh))
              results (get-in fresh-view [:report :providers "remote" :results])]
          (is (= 3 @calls))
          (is (not= (:run-id old) (:run-id fresh)))
          (is (= [:invalid-outer-json] (get-in old-view [:report :providers "remote" :results 0 :validation-reasons])))
          (is (= "shadow-adapters/4" (get-in fresh-view [:input :requests 0 0 :adapter-version])))
          (is (= [:match :error :error] (mapv :outcome results)))
          (is (= [[] [:invalid-content-json] nil] (mapv :validation-reasons results)))
          (is (= ["fixture-v1" "fixture-v1" nil] (mapv :model-version results)))
          (is (= [{:prompt_tokens 9} {:prompt_tokens 9} nil] (mapv :usage results)))
          (is (= [1 1 0] (mapv #(count (:attempts %)) results)))
          (is (= :comparator-halted (:error (last results))))
          (is (= :not-dispatched (:dispatch-status (last results))))
          (is (= {:status :not-incurred} (:cost (last results))))
          (is (= [:abstain :abstain :abstain] (mapv :outcome (get-in fresh-view [:report :providers "rules" :results]))))
          (is (= old (evaluation/run! dir ds legacy runtime)))
          (is (= fresh (evaluation/run! dir ds corrected runtime)))
          (is (= old-view (evaluation/inspect-run dir (:run-id old))))
          (is (= fresh-view (evaluation/inspect-run dir (:run-id fresh))))
          (is (= 3 @calls))
          (doseq [file (filter #(.isFile %) (file-seq (io/file dir)))]
            (is (not (.contains (slurp file) "fixture-secret")))))))))
