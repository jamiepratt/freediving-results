(ns freediving.evaluation-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.java.io :as io]
            [freediving.evaluation :as evaluation]
            [freediving.evaluation-data-test :as fixtures]
            [freediving.evaluation-providers :as providers])
  (:import [java.nio.file Files] [java.nio.file.attribute FileAttribute]))
(defn root [] (.getCanonicalPath (.toFile (Files/createTempDirectory "shadow-eval-" (make-array FileAttribute 0)))))
(def dataset (fixtures/dataset [(fixtures/sample-case "a" :development) (fixtures/sample-case "b" :held-out)]))
(def configs [{:id "rules" :provider :rules}])
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
