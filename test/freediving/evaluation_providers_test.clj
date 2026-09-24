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

(defn diagnostic-result
  ([provider body]
   ;; Existing compact diagnostics and adversarial cases must stay identical.
   (let [legacy (diagnostic-result provider body 1)
         corrected (diagnostic-result provider body 2)]
     (is (= legacy corrected))
     corrected))
  ([provider body version]
   (with-server (fn [ex] (reply! ex 200 body))
     (fn [url]
       (p/execute! (p/prepare-request {:provider provider :model "fixture" :endpoint url :diagnostics-version version} {:input {}})
                   {:bearer-token "fixture-secret"})))))

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

(deftest corrected-diagnostics-accept-complete-json-with-legal-whitespace
  (with-server (fn [ex] (reply! ex 200 (str " \t\r\n" (json/write-str llm-response) "\n\r\t ")))
    (fn [url]
      (let [request (p/prepare-request {:provider :llm :model "fixture" :endpoint url :diagnostics-version 2} {:input {}})
            result (p/execute! request {:bearer-token "fixture-secret"})]
        (is (= "shadow-adapters/4" (:adapter-version request)))
        (is (= :match (:outcome result)))
        (is (= [] (:validation-reasons result)))
        (is (= "fixture-v1" (:model-version result)))
        (is (= {:prompt_tokens 7 :completion_tokens 3} (:usage result)))
        (is (nil? (:response-body result)))))))

(deftest corrected-json-whitespace-is-exact-and-preserves-strings
  (let [content "{\"outcome\":\"match\",\"ignored\":\" inside \\t \\r\\n \"}"
        llm-body #(json/write-str (assoc-in llm-response [:choices 0 :message :content] %))
        jev-body (json/write-str {:answers {:identity jev-answer}})]
    (doseq [whitespace ["" " " "\t" "\r" "\n" " \t\r\n"]]
      (doseq [[provider body] [[:llm (str whitespace (llm-body content) whitespace)]
                               [:llm (llm-body (str whitespace content whitespace))]
                               [:jev (str whitespace jev-body whitespace)]]]
        (is (= :match (:outcome (diagnostic-result provider body 2))))))
    (doseq [invalid ["{}" " []" " true" " null" " 1" " junk fixture-secret"
                     "\u000b" "\f" "\u00a0" "\u2003" "\ufeff"]]
      (doseq [[provider body reason] [[:llm (str (llm-body content) invalid) :invalid-outer-json]
                                      [:llm (llm-body (str content invalid)) :invalid-content-json]
                                      [:jev (str jev-body invalid) :invalid-outer-json]]]
        (let [result (diagnostic-result provider body 2)]
          (is (= :invalid-response (:error result)))
          (is (= [reason] (:validation-reasons result)))
          (is (nil? (:response-body result)))
          (is (not (.contains (pr-str result) "fixture-secret"))))))
    (doseq [invalid ["\u000b" "\f" "\u00a0" "\u2003" "\ufeff"]]
      (doseq [[provider body reason] [[:llm (str invalid (llm-body content)) :invalid-outer-json]
                                      [:llm (llm-body (str invalid content)) :invalid-content-json]
                                      [:jev (str invalid jev-body) :invalid-outer-json]]]
        (is (= [reason] (:validation-reasons (diagnostic-result provider body 2))))))
    (doseq [outcome [" match" "match " "mat ch" "match\n" "MATCH"]]
      (is (= [:invalid-outcome]
             (:validation-reasons (diagnostic-result :llm (llm-body (json/write-str {:outcome outcome})) 2))))))
  (doseq [[provider body] [[:llm (str (json/write-str llm-response) "\n")]
                           [:llm (json/write-str (assoc-in llm-response [:choices 0 :message :content] "{\"outcome\":\"match\"}\n"))]
                           [:jev (str (json/write-str {:answers {:identity jev-answer}}) "\n")]]]
    (is (= :error (:outcome (diagnostic-result provider body 1))))
    (is (= :match (:outcome (diagnostic-result provider body 2))))))

(deftest diagnostics-version-is-an-explicit-http-only-choice
  (doseq [provider [:llm :jev] version [0 3 "2" nil]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (p/prepare-request {:provider provider :model "fixture" :endpoint "http://127.0.0.1:1/"
                                     :diagnostics-version version} {:input {}}))))
  (doseq [provider [:rules :stub] version [1 2]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (p/prepare-request {:provider provider :diagnostics-version version} {:input {}})))))

(def output-contract-config
  {:provider :llm :model "gpt-4.1-nano-2025-04-14" :endpoint "http://127.0.0.1:1/"
   :diagnostics-version 2 :output-contract :identity-outcome-v1 :max-completion-tokens 128})

(deftest explicit-output-contract-changes-only-response-format
  (let [case {:case-id "synthetic-contract" :input {:left {:name "SYNTHETIC"} :right {}}}
        legacy (p/prepare-request (dissoc output-contract-config :output-contract) case)
        request (p/prepare-request output-contract-config case)
        seen (atom nil)]
    (is (= "shadow-adapters/5" (:adapter-version request)))
    (is (= :identity-outcome-v1 (get-in request [:config :output-contract])))
    (is (= (dissoc (json/read-str (:body legacy)) "response_format")
           (dissoc (json/read-str (:body request)) "response_format")))
    (with-server (fn [ex]
                   (reset! seen (json/read-str (slurp (.getRequestBody ex))))
                   (reply! ex 200 "{}"))
      (fn [url]
        (p/execute! (p/prepare-request (assoc output-contract-config :endpoint url) case)
                    {:bearer-token "fixture-secret"})))
    (is (= {"type" "json_schema"
            "json_schema" {"name" "identity_outcome_v1" "strict" true
                           "schema" {"type" "object" "properties" {"outcome" {"type" "string" "enum" ["match" "no_match" "abstain"]}}
                                     "required" ["outcome"] "additionalProperties" false}}}
           (get @seen "response_format")))))

(deftest output-contract-rejects-unsupported-configurations
  (doseq [config (concat (map #(assoc output-contract-config :output-contract %) [nil :unknown "identity-outcome-v1" {}])
                         (map #(assoc output-contract-config :provider %) [:rules :stub :jev])
                         (map #(assoc output-contract-config :model %) [nil "gpt-4.1-nano" "fixture"])
                         [(dissoc output-contract-config :diagnostics-version)
                          (assoc output-contract-config :diagnostics-version 1)])]
    (is (thrown? clojure.lang.ExceptionInfo (p/prepare-request config {:input {}})))))

(defn output-contract-result [body]
  (with-server (fn [ex] (reply! ex 200 body))
    (fn [url]
      (p/execute! (p/prepare-request (assoc output-contract-config :endpoint url) {:input {}})
                  {:bearer-token "fixture-secret"}))))

(deftest output-contract-enforces-exact-object-with-sanitized-diagnostics
  (doseq [[content expected reasons]
          [[" \t{\"outcome\":\"match\"}\r\n" :match []]
           ["{\"outcome\":\"no_match\"}" :no-match []]
           ["{\"outcome\":\"abstain\"}" :abstain []]
           ["{\"outcome\":\"match\",\"extra\":\"fixture-secret\"}" :error [:invalid-output-shape]]
           ["{}" :error [:invalid-output-shape :invalid-outcome]]
           ["[]" :error [:invalid-output-shape :invalid-outcome]]
           ["null" :error [:invalid-output-shape :invalid-outcome]]
           ["{\"outcome\":3}" :error [:invalid-output-shape :invalid-outcome]]
           ["{\"outcome\":null}" :error [:invalid-output-shape :invalid-outcome]]
           ["{\"outcome\":\"MATCH\"}" :error [:invalid-outcome]]
           ["{\"outcome\":\"match \"}" :error [:invalid-outcome]]
           ["{\"outcome\":\"match\"} {}" :error [:invalid-content-json]]]]
    (let [r (output-contract-result (str " \n" (json/write-str (assoc-in llm-response [:choices 0 :message :content] content)) "\t\r\n"))]
      (is (= expected (:outcome r)))
      (is (= reasons (:validation-reasons r)))
      (is (= "fixture-v1" (:model-version r)))
      (is (= {:prompt_tokens 7 :completion_tokens 3} (:usage r)))
      (is (= :stop (:finish-reason r)))
      (is (= {:status :unknown} (:cost r)))
      (is (false? (:retryable? r)))
      (is (= :known (:external-outcome r)))
      (is (nil? (:response-body r)))
      (is (not (.contains (pr-str r) "fixture-secret")))
      (when (= :error expected) (is (= :invalid-response (:error r)))))))

(deftest output-contract-preserves-explicit-errors-and-bounds
  (doseq [[body reason] [["{" :invalid-outer-json]
                         [(json/write-str (assoc-in llm-response [:choices 0 :message :refusal] "fixture-secret")) :refusal]
                         [(json/write-str (assoc-in llm-response [:choices 0 :finish_reason] "length")) :non-stop-finish]
                         [(json/write-str (assoc-in llm-response [:choices 0 :message :content] "bad fixture-secret")) :invalid-content-json]
                         [(json/write-str (assoc llm-response :model "fixture-secret")) :invalid-model]
                         [(json/write-str (assoc llm-response :usage {:prompt_tokens -1})) :invalid-usage]]]
    (let [r (output-contract-result body)]
      (is (= :error (:outcome r)))
      (is (= :invalid-response (:error r)))
      (is (= [reason] (:validation-reasons r)))
      (is (not (.contains (pr-str r) "fixture-secret")))
      (is (nil? (:response-body r)))))
  (is (thrown? clojure.lang.ExceptionInfo
               (p/prepare-request (assoc output-contract-config :max-request-bytes 8) {:input {}})))
  (with-server (fn [ex] (reply! ex 200 (json/write-str llm-response)))
    (fn [url]
      (let [request (p/prepare-request (assoc output-contract-config :endpoint url :max-response-bytes 8) {:input {}})]
        (is (= :response-too-large (:error (p/execute! request {:bearer-token "fixture-secret"})))))))
  (doseq [[config version] [[{} "shadow-adapters/1"]
                            [{:max-completion-tokens 128} "shadow-adapters/2"]
                            [{:diagnostics-version 1} "shadow-adapters/3"]
                            [{:diagnostics-version 2} "shadow-adapters/4"]]]
    (with-server (fn [ex] (reply! ex 200 (json/write-str (assoc-in llm-response [:choices 0 :message :content]
                                                                   "{\"outcome\":\"match\",\"extra\":true}"))))
      (fn [url]
        (let [request (p/prepare-request (merge {:provider :llm :model "fixture" :endpoint url} config) {:input {}})]
          (is (= version (:adapter-version request)))
          (is (= {"type" "json_object"} (get (json/read-str (:body request)) "response_format")))
          (is (= :match (:outcome (p/execute! request {:bearer-token "fixture-secret"})))))))))
