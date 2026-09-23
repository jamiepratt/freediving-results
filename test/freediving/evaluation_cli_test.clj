(ns freediving.evaluation-cli-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.data.json :as json]
            [freediving.evaluation :as evaluation]
            [freediving.evaluation-providers-test :as http]
            [freediving.evaluation-cli :as cli]))

(deftest reads-one-bounded-data-value
  (let [file (java.io.File/createTempFile "shadow-input" ".edn")]
    (try
      (spit file "{:schema-version 1}")
      (is (= {:schema-version 1} (cli/read-input (str file))))
      (doseq [bad ["" "{} {}" "#=(System/exit 1)" "#unknown [1]"]]
        (spit file bad)
        (is (thrown? Exception (cli/read-input (str file)))))
      (spit file (apply str (repeat (inc (* 4 1024 1024)) "x")))
      (is (thrown? Exception (cli/read-input (str file))))
      (finally (.delete file)))))

(deftest local-command-refuses-remote-provider-configuration
  (is (= [{:provider :rules}] (cli/local-configs! [{:provider :rules}])))
  (is (= [{:provider :jev :endpoint "http://127.0.0.1:1234/v1/systemone"}]
         (cli/local-configs! [{:provider :jev :endpoint "http://127.0.0.1:1234/v1/systemone"}])))
  (doseq [endpoint ["https://api.typesafe.ai/v1/systemone"
                    "http://localhost:1234/" "http://127.0.0.1.evil.test/"
                    "http://user:secret@127.0.0.1/" "http://127.0.0.1/?key=secret"
                    "http://127.0.0.1/#secret"]]
    (is (thrown? Exception (cli/local-configs! [{:provider :jev :endpoint endpoint}])))))

(deftest local-command-returns-only-private-artifact-references
  (let [root (.toRealPath (java.nio.file.Files/createTempDirectory "shadow-cli" (make-array java.nio.file.attribute.FileAttribute 0))
                          (make-array java.nio.file.LinkOption 0))
        config (.resolve root "config.edn")]
    (spit (str config) "[{:id \"rules\" :provider :rules :identifier-policy :unique-person-id-v1}]")
    (let [args ["run" (str (.resolve root "store")) "test/fixtures/shadow-evaluation.edn" (str config)]
          receipt (cli/command args)]
      (is (= #{:run-id :input-hash :report-hash} (set (keys receipt))))
      (is (every? #(re-matches #"[a-f0-9]{64}" %) (vals receipt)))
      (is (= receipt (cli/command args))))
    (is (thrown? Exception (cli/command [])))))

(deftest local-command-runs-all-three-comparators-over-identical-held-out-ids
  (let [root (.toRealPath (java.nio.file.Files/createTempDirectory "shadow-http-cli" (make-array java.nio.file.attribute.FileAttribute 0))
                          (make-array java.nio.file.LinkOption 0))
        server (com.sun.net.httpserver.HttpServer/create (java.net.InetSocketAddress. "127.0.0.1" 0) 0)
        calls (atom [])
        handler (reify com.sun.net.httpserver.HttpHandler
                  (handle [_ exchange]
                    (let [request (json/read-str (slurp (.getRequestBody exchange)) :key-fn keyword)
                          jev? (contains? request :questions)
                          response (if jev?
                                     {:model "jev-local-fixture-v1"
                                      :answers {:identity {:type "choice" :choice "abstain" :confidence 0.7
                                                           :probabilities {:match 0.1 :no_match 0.1 :abstain 0.8}}}}
                                     {:model "llm-local-fixture-v1"
                                      :choices [{:finish_reason "stop" :message {:content "{\"outcome\":\"abstain\"}"}}]})
                          bytes (.getBytes (json/write-str response) "UTF-8")]
                      (swap! calls conj request)
                      (.sendResponseHeaders exchange 200 (alength bytes))
                      (with-open [out (.getResponseBody exchange)] (.write out bytes))
                      (.close exchange))))]
    (.createContext server "/" handler)
    (.start server)
    (try
      (let [endpoint (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/")
            file (.resolve root "configs.edn")
            configs [{:id "rules" :provider :rules :identifier-policy :unique-person-id-v1}
                     {:id "jev" :provider :jev :endpoint endpoint :model "fixture-jev"}
                     {:id "llm" :provider :llm :endpoint endpoint :model "fixture-llm"}]
            _ (spit (str file) (pr-str configs))
            args ["run" (str (.resolve root "store")) "test/fixtures/shadow-evaluation.edn" (str file)]
            receipt (cli/command args)]
        (is (= 6 (count @calls)))
        (is (= receipt (cli/command args)))
        (is (= 6 (count @calls)))
        (is (= (set (map :state (filter :questions @calls)))
               (set (map #(get-in % [:messages 1 :content]) (remove :questions @calls)))))
        (is (not-any? #(re-find #"reviewer|fixture-id|provenance|held-out" (pr-str %)) @calls)))
      (finally (.stop server 0)))))

(deftest transient-http-errors-use-bounded-persisted-retries
  (let [root (.toRealPath (java.nio.file.Files/createTempDirectory "shadow-retry-cli" (make-array java.nio.file.attribute.FileAttribute 0))
                          (make-array java.nio.file.LinkOption 0))
        calls (atom 0)]
    (http/with-server
      (fn [exchange]
        (if (= 1 (swap! calls inc))
          (http/reply! exchange 429 "private-error-text")
          (http/reply! exchange 200 "{\"model\":\"fixture\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"outcome\\\":\\\"abstain\\\"}\"}}]}")))
      (fn [endpoint]
        (let [file (.resolve root "configs.edn")
              _ (spit (str file) (pr-str [{:id "llm" :provider :llm :endpoint endpoint :model "fixture"
                                           :max-attempts 2 :retry-delay-ms 1}]))
              store (str (.resolve root "store"))
              args ["run" store "test/fixtures/shadow-evaluation.edn" (str file)]
              receipt (cli/command args)
              result (get-in (evaluation/inspect-run store (:run-id receipt)) [:report :providers "llm" :results 0])]
          (is (= 4 @calls))
          (is (= :abstain (:outcome result)))
          (is (= [429 200] (mapv #(get-in % [:result :http-status]) (:attempts result))))
          (is (= :unknown (get-in result [:cost :status])))
          (is (not (.contains (pr-str result) "private-error-text")))
          (is (= receipt (cli/command args)))
          (is (= 4 @calls)))))))

(defn -main [& _]
  (let [r (run-tests 'freediving.evaluation-cli-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail r) (:error r))) (System/exit 1))))
