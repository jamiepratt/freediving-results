(ns freediving.evaluation-providers-test
  (:import [com.sun.net.httpserver HttpServer HttpHandler] [java.net InetSocketAddress])
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is run-tests]]
            [freediving.evaluation-providers :as p]))
(deftest rules-use-only-shared-authority-evidence
  (let [case {:case-id "c1" :label :no-match :input {:left {:authority "registry" :subject-id "1"} :right {:authority "registry" :subject-id "1"}}}
        result (p/execute! (p/prepare-request {:provider :rules :identifier-policy :unique-person-id-v1} case) {})]
    (is (= :match (:outcome result)))
    (is (= {:status :unknown} (:cost result)))
    (is (= :abstain (:outcome (p/execute! (p/prepare-request {:provider :rules :identifier-policy :unique-person-id-v1} {:case-id "c2" :input {}}) {}))))))
(defn with-server [handler f]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/" (reify HttpHandler (handle [_ exchange] (handler exchange))))
    (.start server)
    (try (f (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/"))
         (finally (.stop server 0)))))
(defn reply! [exchange status body]
  (let [bs (.getBytes body "UTF-8")]
    (.sendResponseHeaders exchange status (alength bs))
    (with-open [out (.getResponseBody exchange)] (.write out bs))))
(deftest jev-real-http-contract
  (let [seen (atom nil)]
    (with-server
      (fn [ex] (reset! seen {:auth (.getFirst (.getRequestHeaders ex) "Authorization")
                             :body (json/read-str (slurp (.getRequestBody ex)) :key-fn keyword)})
        (reply! ex 200 "{\"model\":\"jev-1.13.0\",\"answers\":{\"identity\":{\"type\":\"choice\",\"choice\":\"match\",\"confidence\":0.8,\"probabilities\":{\"match\":0.9,\"no_match\":0.05,\"abstain\":0.05}}},\"usage\":{\"input_tokens\":4,\"output_tokens\":2}}"))
      (fn [url]
        (let [request (p/prepare-request {:provider :jev :model "jev-1.13.0" :endpoint url} {:case-id "s1" :label :no-match :input {:name "SYNTHETIC"}})
              result (p/execute! request {:bearer-token "fixture-secret"})]
          (is (= :match (:outcome result)))
          (is (= "jev-1.13.0" (:model-version result)))
          (is (= "Bearer fixture-secret" (:auth @seen)))
          (is (= "choice" (get-in @seen [:body :questions :identity :type])))
          (is (= {:name "SYNTHETIC"} (json/read-str (get-in @seen [:body :state]) :key-fn keyword)))
          (is (not (.contains (pr-str request) "fixture-secret")))
          (is (string? (:response-body result))))))))
(deftest adapters-report-errors-without-leaking-response-text
  (doseq [[status body expected retry?]
          [[429 "provider-secret" :rate-limited true]
           [503 "provider-secret" :provider-unavailable true]
           [400 "provider-secret" :http-error false]
           [302 "provider-secret" :redirect-refused false]
           [200 "not-json provider-secret" :invalid-response false]
           [200 "{}" :invalid-response false]]]
    (let [calls (atom 0)]
      (with-server (fn [ex] (swap! calls inc) (reply! ex status body))
        (fn [url]
          (let [result (p/execute! (p/prepare-request {:provider :llm :model "fixture" :endpoint url} {:input {}}) {:bearer-token "test"})]
            (is (= expected (:error result)))
            (is (= retry? (:retryable? result)))
            (is (= 1 @calls))
            (is (not (.contains (pr-str result) "provider-secret")))))))))
(deftest llm-contract-and-response-bounds
  (with-server (fn [ex] (reply! ex 200 "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"outcome\\\":\\\"no_match\\\"}\"}}]}"))
    (fn [url]
      (let [config {:provider :llm :model "fixture" :endpoint url}
            result (p/execute! (p/prepare-request config {:input {}}) {:bearer-token "test"})]
        (is (= :no-match (:outcome result)))
        (is (nil? (:model-version result)))
        (is (= :response-too-large (:error (p/execute! (p/prepare-request (assoc config :max-response-bytes 8) {:input {}}) {:bearer-token "test"}))))))))
(deftest timeout-covers-body-after-headers
  (with-server (fn [ex]
                 (.sendResponseHeaders ex 200 0)
                 (try (with-open [out (.getResponseBody ex)]
                        (.write out (.getBytes "{" "UTF-8")) (.flush out)
                        (Thread/sleep 250)
                        (.write out (.getBytes "}" "UTF-8")))
                      (catch Exception _ nil)))
    (fn [url]
      (let [result (p/execute! (p/prepare-request {:provider :llm :model "fixture" :endpoint url :timeout-ms 100} {:input {}}) {:bearer-token "test"})]
        (is (= :error (:outcome result)))
        (is (= :unknown (:external-outcome result)))
        (is (:retryable? result))))))
(deftest durable-config-rejects-credentials-and-unsupported-input
  (doseq [config [{:provider :rules :api-key "secret"}
                  {:provider :rules :endpoint "https://user:secret@example.com"}
                  {:provider :llm :model "fixture" :endpoint "https://example.com/?key=secret"}
                  {:provider :llm :model "fixture" :endpoint "http://external.example.com"}
                  {:provider :llm :model "fixture" :endpoint "https://example.com" :timeout-ms 0}]]
    (is (thrown? clojure.lang.ExceptionInfo (p/prepare-request config {:input {}}))))
  (is (= "shadow-adapters/1" (:adapter-version (p/prepare-request {:provider :stub :scope-id "suite-1" :retry-delay-ms 0} {:input {}})))))
(deftest rules-and-stubs-are-explicit-and-conservative
  (let [case {:input {:left {:authority "a" :subject-id "1"} :right {:authority "a" :subject-id "2"}}}]
    (is (= :abstain (:outcome (p/execute! (p/prepare-request {:provider :rules} case) {}))))
    (is (= :no-match (:outcome (p/execute! (p/prepare-request {:provider :rules :identifier-policy :unique-person-id-v1} case) {}))))
    (let [result (p/execute! (p/prepare-request {:provider :stub :stub-outcome :error} case) {})]
      (is (= :synthetic-error (:error result)))
      (is (true? (:synthetic? result))))))
(deftest invalid-success-metadata-is-an-error
  (doseq [body [{:choices [{:finish_reason "length" :message {:content "{\"outcome\":\"match\"}"}}]}
                {:choices [{:finish_reason "stop" :message {:content "{\"outcome\":\"match\"}"}}] :usage {:prompt_tokens -1}}
                {:choices [{:finish_reason "stop" :message {:content "{\"outcome\":\"MATCH\"}"}}]}]]
    (with-server (fn [ex] (reply! ex 200 (json/write-str body)))
      (fn [url]
        (is (= :invalid-response (:error (p/execute! (p/prepare-request {:provider :llm :model "fixture" :endpoint url} {:input {}}) {:bearer-token "test"}))))))))
(deftest every-provider-rejects-nonportable-model-config
  (doseq [provider [:rules :stub] model [(Object.) ##NaN 42 "" (apply str (repeat 201 "x"))]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (p/prepare-request {:provider provider :model model} {:input {}})))))
(defn -main [& _] (let [r (run-tests 'freediving.evaluation-providers-test)] (System/exit (if (pos? (+ (:fail r) (:error r))) 1 0))))

(deftest llm-output-bound-is-validated-and-sent
  (let [seen (atom nil)]
    (with-server (fn [ex]
                   (reset! seen (json/read-str (slurp (.getRequestBody ex)) :key-fn keyword))
                   (reply! ex 200 "{}"))
      (fn [url]
        (let [config {:provider :llm :model "fixture" :endpoint url :max-completion-tokens 128}
              request (p/prepare-request config {:input {}})]
          (p/execute! request {:bearer-token "fixture"})
          (is (= 128 (:max_completion_tokens @seen)))
          (is (= 128 (get-in request [:config :max-completion-tokens])))
          (doseq [invalid [0 -1 16385 1.5 "128"]]
            (is (thrown? clojure.lang.ExceptionInfo
                         (p/prepare-request (assoc config :max-completion-tokens invalid) {:input {}})))))))))

(defn diagnostic-result [provider body]
  (with-server (fn [ex] (reply! ex 200 body))
    (fn [url]
      (p/execute! (p/prepare-request {:provider provider :model "fixture" :endpoint url :diagnostics-version 1} {:input {}})
                  {:bearer-token "fixture-secret"}))))

(deftest invalid-content-retains-independent-metadata
  (let [result (diagnostic-result :llm (json/write-str {:model "gpt-4.1-nano-2025-04-14"
                                                        :usage {:prompt_tokens 7 :completion_tokens 3}
                                                        :choices [{:finish_reason "stop" :message {:content "bad fixture-secret"}}]}))]
    (is (= :invalid-response (:error result)))
    (is (= [:invalid-content-json] (:validation-reasons result)))
    (is (= "gpt-4.1-nano-2025-04-14" (:model-version result)))
    (is (= {:prompt_tokens 7 :completion_tokens 3} (:usage result)))
    (is (= {:status :unknown} (:cost result)))
    (is (false? (:retryable? result)))
    (is (not (.contains (pr-str result) "fixture-secret")))
    (is (nil? (:response-body result)))))

(def jev-answer {:type "choice" :choice "match" :confidence 0.8
                 :probabilities {:match 0.9 :no_match 0.05 :abstain 0.05}})
(deftest jev-diagnostics-distinguish-invalid-prediction-fields
  (doseq [[answer reasons] [[jev-answer []]
                            [(assoc jev-answer :choice "unknown") [:invalid-choice]]
                            [(assoc jev-answer :confidence -1) [:invalid-confidence]]
                            [(assoc jev-answer :probabilities {:match 1}) [:invalid-probabilities]]
                            [(assoc jev-answer :type "text") [:invalid-choice-type]]]]
    (let [r (diagnostic-result :jev (json/write-str {:model "jev-1.13.0" :usage {:input_tokens 8}
                                                     :answers {:identity answer}}))]
      (is (= reasons (:validation-reasons r)))
      (is (= (if (seq reasons) :error :match) (:outcome r)))
      (is (= {:input_tokens 8} (:usage r)))
      (is (= "jev-1.13.0" (:model-version r)))
      (when (seq reasons) (is (not (contains? r :confidence)))))))

(def llm-response {:model "fixture-v1" :usage {:prompt_tokens 7 :completion_tokens 3}
                   :choices [{:finish_reason "stop" :message {:content "{\"outcome\":\"match\"}"}}]})
(deftest diagnostic-output-is-bounded-and-secret-safe
  (doseq [[body reason] [["{" :invalid-outer-json]
                         ["{} {}" :invalid-outer-json]
                         ["[]" :invalid-envelope]
                         [(json/write-str (assoc-in llm-response [:choices 0 :message :content] "{} {}")) :invalid-content-json]
                         [(json/write-str (assoc-in llm-response [:choices 0 :message :content] "{}")) :invalid-outcome]
                         [(json/write-str (assoc-in llm-response [:choices 0 :message :content] nil)) :missing-content]
                         [(json/write-str (assoc-in llm-response [:choices 0 :message :content] 3)) :invalid-content-type]
                         [(json/write-str (assoc-in llm-response [:choices 0 :message :refusal] "fixture-secret")) :refusal]]]
    (let [r (diagnostic-result :llm body)]
      (is (= :invalid-response (:error r)))
      (is (some #{reason} (:validation-reasons r)))
      (is (nil? (:response-body r)))
      (is (not (.contains (pr-str r) "fixture-secret")))))
  (doseq [[finish expected] [["stop" :stop] ["length" :length] ["content_filter" :content-filter]
                             ["tool_calls" :tool-calls] ["function_call" :function-call]
                             [nil :missing] ["fixture-secret" :unknown] [{:secret "fixture-secret"} :unknown]]]
    (let [r (diagnostic-result :llm (json/write-str (assoc-in llm-response [:choices 0 :finish_reason] finish)))]
      (is (= expected (:finish-reason r)))
      (is (= (if (= finish "stop") :match :error) (:outcome r)))
      (is (not (.contains (pr-str r) "fixture-secret")))))
  (doseq [model [42 "" (apply str (repeat 201 "x")) "Bearer fixture-secret" "prefix-fixture-secret-suffix"
                 "sk-abcdef" "api_key-abcdef" "token-abcdef" "unsafe\nmodel"]]
    (let [r (diagnostic-result :llm (json/write-str (assoc llm-response :model model :usage {:prompt_tokens -1 :completion_tokens 3 :arbitrary "fixture-secret"})))]
      (is (= :error (:outcome r)))
      (is (= [:invalid-model :invalid-usage] (:validation-reasons r)))
      (is (nil? (:model-version r)))
      (is (= {:completion_tokens 3} (:usage r)))
      (is (not (.contains (pr-str r) "fixture-secret")))))
  (doseq [usage [3 [] {:prompt_tokens 1000000001 :completion_tokens 3}
                 {:prompt_tokens 1.5 :completion_tokens 3} {:prompt_tokens "fixture-secret" :completion_tokens 3}]]
    (let [r (diagnostic-result :llm (json/write-str (assoc llm-response :usage usage)))]
      (is (= [:invalid-usage] (:validation-reasons r)))
      (is (= "fixture-v1" (:model-version r)))
      (is (= (when (map? usage) {:completion_tokens 3}) (:usage r))))))

(deftest deeply-nested-untrusted-json-remains-a-sanitized-error
  (let [nested (str (apply str (repeat 10000 "[")) "0" (apply str (repeat 10000 "]")))]
    (doseq [[body reason] [[nested :invalid-outer-json]
                           [(json/write-str (assoc-in llm-response [:choices 0 :message :content] nested)) :invalid-content-json]]]
      (let [r (diagnostic-result :llm body)]
        (is (= :invalid-response (:error r)))
        (is (some #{reason} (:validation-reasons r)))))))
