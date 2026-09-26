(ns freediving.evaluation-batches-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.data.json :as json]
            [freediving.evaluation-protocol-test :as fixture]
            [freediving.evaluation-protocol :as protocol]
            [freediving.evaluation-providers-test :as http]
            [freediving.evaluation-providers :as p]
            [freediving.evaluation :as evaluation]
            [freediving.evaluation-test :as runner]
            [freediving.evaluation-data-test :as data]))
(defn config [endpoint]
  {:id "native" :provider :jev :model "jev-1.13.0" :endpoint endpoint
   :identity-protocol :freediving-source-v1 :diagnostics-version 2 :native-batch-size 2})
(defn cases []
  [{:case-id "first" :evidence [(dissoc fixture/reference :source-family-id)] :input (fixture/input)}
   {:case-id "second" :evidence [(dissoc fixture/reference :source-family-id)] :input (fixture/input)}])
(defn answer [choice]
  {:type "choice" :choice choice :confidence 0.7
   :probabilities (assoc {"match" 0.1 "no_match" 0.1 "abstain" 0.1} choice 0.8)})
(deftest native-questions-reference-exact-records-and-deduplicate-only-equal-records
  (let [requests (p/prepare-batches (config "https://example.com") (cases))
        request (first requests) body (json/read-str (:body request) :key-fn keyword)
        state (json/read-str (:state body) :key-fn keyword)]
    (is (= 1 (count requests)))
    (is (= ["first" "second"] (:case-ids request)))
    (is (= 2 (count (:records state))))
    (is (= #{:identity_0 :identity_1} (set (keys (:questions body)))))
    (is (.contains (str/replace (get-in body [:questions :identity_1 :instructions]) "\\/" "/") "/records/record_0"))
    (is (not (.contains (:body request) "case-id")))
    (is (thrown? Exception (p/prepare-batches (assoc (config "https://example.com") :native-batch-size 9) (cases))))))

(defn execute-body [body]
  (http/with-server (fn [ex] (http/reply! ex 200 body))
    (fn [url] (p/execute! (first (p/prepare-batches (config url) (cases))) {:bearer-token "fixture-secret"}))))
(deftest answers-map-by-identifier-and-preserve-explicit-partial-outcomes
  (let [result (execute-body (json/write-str {:model "jev-1.13.0" :usage {:input_tokens 27}
                                              :answers (array-map :identity_1 (answer "no_match") :identity_0 (answer "match"))}))]
    (is (= :complete (:outcome result)))
    (is (= {:input_tokens 27} (:usage result)))
    (is (= [:match :no-match] (mapv #(get-in result [:answers % :outcome]) ["identity_0" "identity_1"]))))
  (let [result (execute-body (json/write-str {:model "jev-1.13.0" :answers {:identity_1 (answer "match") :unexpected (answer "match")}}))]
    (is (= :invalid-response (:error result)))
    (is (= :match (get-in result [:answers "identity_1" :outcome])))
    (is (= :missing-answer (get-in result [:answers "identity_0" :error])))))

(deftest strict-distributions-duplicate-keys-and-models-are-rejected
  (doseq [bad [(assoc (answer "match") :choice "no_match")
               (assoc (answer "match") :probabilities {"match" 0.8 "no_match" 0.8 "abstain" 0.1})
               (assoc (answer "match") :confidence 2)]]
    (is (= :invalid-answer (get-in (execute-body (json/write-str {:model "jev-1.13.0" :answers {:identity_0 bad :identity_1 (answer "match")}}))
                                   [:answers "identity_0" :error]))))
  (is (= :invalid-response (:error (execute-body (str "{\"model\":\"jev-1.13.0\",\"answers\":{\"identity_0\":"
                                                      (json/write-str (answer "match")) ",\"identity_0\":"
                                                      (json/write-str (answer "no_match")) "}}")))))
  (is (= :invalid-response (:error (execute-body (json/write-str {:model "another-model" :answers {:identity_0 (answer "match") :identity_1 (answer "match")}}))))))

(defn dataset []
  (data/dataset (mapv (fn [id] (assoc (data/sample-case id :held-out) :input (fixture/input))) ["a" "b" "c"])))
(deftest runner-freezes-durable-batches-and-replay-makes-no-http-calls
  (let [calls (atom []) dir (runner/root)]
    (http/with-server
      (fn [ex]
        (let [body (json/read-str (slurp (.getRequestBody ex)))]
          (swap! calls conj body)
          (http/reply! ex 200 (json/write-str {:model "jev-1.13.0" :usage {:input_tokens 27}
                                               :answers (into {} (map (fn [id] [id (answer "match")]) (keys (get body "questions"))))}))))
      (fn [url]
        (let [cfg [(config url)] runtime {:providers {"native" {:bearer-token "fixture-secret"}}}
              receipt (evaluation/run! dir (dataset) cfg runtime)
              report (get-in (evaluation/inspect-run dir (:run-id receipt)) [:report :providers "native"])]
          (is (= 2 (count @calls)))
          (is (= ["a" "b" "c"] (mapv :case-id (:results report))))
          (is (= [:match :match :match] (mapv :outcome (:results report))))
          (is (= 2 (count (:batches report))))
          (is (= 54 (get-in report [:request-metrics :usage :input_tokens])))
          (is (every? #(nil? (:latency-ms %)) (:results report)))
          (is (= receipt (evaluation/run! dir (dataset) cfg runtime)))
          (is (= 2 (count @calls))))))))

(deftest terminal-batch-errors-and-unknown-interruptions-halt-new-work
  (doseq [status [401 402 422]]
    (let [calls (atom 0) dir (runner/root)]
      (http/with-server (fn [ex] (swap! calls inc) (http/reply! ex status "secret"))
        (fn [url]
          (let [receipt (evaluation/run! dir (dataset) [(config url)] {:providers {"native" {:bearer-token "fixture"}}})
                report (get-in (evaluation/inspect-run dir (:run-id receipt)) [:report :providers "native"])]
            (is (= 1 @calls))
            (is (= 1 (get-in report [:dispatch :undispatched-case-count])))
            (is (= :comparator-halted (:error (last (:results report))))))))))
  (doseq [phase [:attempt-started :attempt-returned]]
    (let [calls (atom 0) dir (runner/root)]
      (http/with-server (fn [ex] (swap! calls inc)
                          (http/reply! ex 200 (json/write-str {:model "jev-1.13.0" :answers {:identity_0 (answer "match") :identity_1 (answer "match")}})))
        (fn [url]
          (let [cfg [(config url)] runtime {:providers {"native" {:bearer-token "fixture"}}}]
            (is (thrown? Exception (evaluation/run! dir (dataset) cfg (assoc runtime :on-progress #(when (= phase (:phase %)) (throw (ex-info "crash" {})))))))
            (let [before @calls receipt (evaluation/run! dir (dataset) cfg runtime)
                  report (get-in (evaluation/inspect-run dir (:run-id receipt)) [:report :providers "native"])]
              (is (= before @calls))
              (is (= [:interrupted-attempt :interrupted-attempt :comparator-halted] (mapv :error (:results report))))
              (is (= 1 (get-in report [:dispatch :undispatched-case-count]))))))))))

(deftest companion-assessments-are-separate-questions-with-own-choice-contract
  (http/with-server
    (fn [ex]
      (http/reply! ex 200 (json/write-str {:model "jev-1.13.0" :answers {:identity_0 (answer "match")
                                                                         :contradiction_0 {:type "choice" :choice "no" :confidence 0.9 :probabilities {:yes 0.05 :no 0.9 :unknown 0.05}}}})))
    (fn [url]
      (let [request (first (p/prepare-batches (assoc (config url) :companion-assessments [:contradiction]) [(first (cases))]))
            question (get-in (json/read-str (:body request)) ["questions" "contradiction_0"])
            result (p/execute! request {:bearer-token "fixture"})]
        (is (= #{"yes" "no" "unknown"} (set (keys (get question "criteria")))))
        (is (not (.contains (get question "instructions") "Choose match")))
        (is (= :match (get-in result [:answers "identity_0" :outcome])))
        (is (= :no (get-in result [:answers "contradiction_0" :outcome])))))))

(deftest conflicting-records-and-unbounded-settings-cannot-dispatch
  (doseq [cfg [(assoc (config "https://example.com") :max-attempts 2)
               (assoc (config "https://example.com") :max-request-bytes 10)
               (assoc (config "https://example.com") :companion-assessments [:invalid])]]
    (is (thrown? Exception (p/prepare-batches cfg (cases)))))
  (is (thrown? Exception (p/prepare-batches (config "https://example.com")
                                            (assoc-in (cases) [1 :input :left :fields :name :value] "Conflicting name"))))
  (let [large (update-in (fixture/input) [:left :uncertainties]
                         (constantly (vec (repeat 8 {:value (apply str (repeat 4000 "x")) :evidence-ids ["source-row-1"]}))))]
    (is (thrown? Exception (p/prepare-batches (config "https://example.com") [{:case-id "large" :input large}])))))

(deftest distinct-pairs-have-distinct-explicit-record-pointers
  (let [members (-> (cases) (assoc-in [1 :input :left :record-id] "third") (assoc-in [1 :input :right :record-id] "fourth"))
        request (first (p/prepare-batches (config "https://example.com") members))
        body (json/read-str (:body request)) state (json/read-str (get body "state"))]
    (doseq [[i left right] [[0 "left-row" "right-row"] [1 "third" "fourth"]]]
      (let [instructions (get-in body ["questions" (str "identity_" i) "instructions"])
            references (mapv #(str "/records/record_" %) [(* 2 i) (inc (* 2 i))])]
        (is (every? #(.contains (str/replace instructions "\\/" "/") %) references))
        (is (= [left right] (mapv #(get-in state ["records" (last (str/split % #"/")) "record-id"]) references)))))))

(deftest invalid-extra-answer-halts-following-batch-but-retains-valid-identities
  (let [calls (atom 0) dir (runner/root)]
    (http/with-server
      (fn [ex] (swap! calls inc)
        (http/reply! ex 200 (json/write-str {:model "jev-1.13.0"
                                             :answers {:identity_0 (answer "match") :identity_1 (answer "no_match") :unexpected (answer "match")}})))
      (fn [url]
        (let [receipt (evaluation/run! dir (dataset) [(config url)] {:providers {"native" {:bearer-token "fixture"}}})
              report (get-in (evaluation/inspect-run dir (:run-id receipt)) [:report :providers "native"])]
          (is (= 1 @calls))
          (is (= [:match :no-match :error] (mapv :outcome (:results report))))
          (is (= 1 (get-in report [:request-metrics :request-error-count])))
          (is (= {:invalid-response 1} (get-in report [:request-metrics :request-errors])))
          (is (= 1 (get-in report [:dispatch :undispatched-case-count]))))))))

(deftest oversized-batch-response-halts-following-work
  (let [calls (atom 0) dir (runner/root)]
    (http/with-server (fn [ex] (swap! calls inc) (http/reply! ex 200 (apply str (repeat 128 "x"))))
      (fn [url]
        (let [receipt (evaluation/run! dir (dataset) [(assoc (config url) :max-response-bytes 10)] {:providers {"native" {:bearer-token "fixture"}}})
              report (get-in (evaluation/inspect-run dir (:run-id receipt)) [:report :providers "native"])]
          (is (= 1 @calls))
          (is (= [:response-too-large :response-too-large :comparator-halted] (mapv :error (:results report)))))))))

(deftest native-diagnostics-require-explicit-immutable-opt-in
  (let [cfg (config "https://example.com")
        legacy (first (p/prepare-batches cfg (cases)))
        diagnostic (first (p/prepare-batches (assoc cfg :native-diagnostics-version 1) (cases)))]
    (is (= "shadow-adapters/7" (:adapter-version legacy)))
    (is (= "shadow-adapters/8" (:adapter-version diagnostic)))
    (is (= legacy (-> diagnostic (assoc :adapter-version "shadow-adapters/7")
                      (update :config dissoc :native-diagnostics-version))))
    (is (= (:body legacy) (:body diagnostic)))
    (doseq [version [nil 0 3 "1"]]
      (is (thrown? Exception (p/prepare-batches (assoc cfg :native-diagnostics-version version) (cases)))))
    (is (thrown? Exception (p/prepare-request (-> cfg (dissoc :native-batch-size)
                                                  (assoc :native-diagnostics-version 1)) (first (cases)))))))

(defn execute-diagnostic-body [body]
  (http/with-server (fn [ex] (http/reply! ex 200 body))
    (fn [url] (p/execute! (first (p/prepare-batches (assoc (config url) :native-diagnostics-version 1) (cases)))
                          {:bearer-token "fixture-secret"}))))
(defn diagnostic-response [bad]
  {:model "jev-1.13.0" :usage {:input_tokens 27 :output_tokens 3}
   :answers {:identity_0 bad :identity_1 (answer "no_match")}})
(deftest native-diagnostics-explain-invalid-confidence-and-keep-valid-siblings
  (let [result (execute-diagnostic-body (json/write-str (diagnostic-response (dissoc (answer "match") :confidence))))]
    (is (= :invalid-response (:error result)))
    (is (= [:missing-confidence] (get-in result [:answers "identity_0" :validation-reasons])))
    (is (= :no-match (get-in result [:answers "identity_1" :outcome])))
    (is (= {:input_tokens 27 :output_tokens 3} (:usage result)))
    (is (= "jev-1.13.0" (:model-version result)))))

(deftest native-answer-failures-have-finite-redacted-reasons
  (let [good (answer "match") secret "fixture-secret-source-name"]
    (doseq [[bad reason] [[nil :invalid-answer-type]
                          [secret :invalid-answer-type]
                          [(assoc good :type secret) :invalid-choice-type]
                          [(assoc good :choice secret) :unsupported-choice]
                          [(dissoc good :confidence) :missing-confidence]
                          [(assoc good :confidence secret) :invalid-confidence]
                          [(assoc good :confidence 2) :invalid-confidence]
                          [(dissoc good :probabilities) :missing-probabilities]
                          [(assoc good :probabilities secret) :invalid-probabilities-type]
                          [(assoc good :probabilities {secret 1}) :invalid-probability-keys]
                          [(assoc-in good [:probabilities "match"] secret) :invalid-probability-type]
                          [(assoc-in good [:probabilities "match"] -1) :invalid-probability-range]
                          [(assoc-in good [:probabilities "match"] 0.2) :invalid-probability-sum]
                          [(assoc good :choice "abstain") :choice-probability-inconsistency]]]
      (let [result (execute-diagnostic-body (json/write-str (diagnostic-response bad)))]
        (is (= :invalid-response (:error result)) (str reason))
        (is (= [reason] (get-in result [:answers "identity_0" :validation-reasons])) (str reason))
        (is (= :no-match (get-in result [:answers "identity_1" :outcome])))
        (is (= {:input_tokens 27 :output_tokens 3} (:usage result)))
        (is (not (str/includes? (pr-str result) secret)))))))

(deftest native-envelope-failures-preserve-only-independent-safe-metadata
  (let [good (diagnostic-response (answer "match"))]
    (doseq [[payload reason model usage]
            [[[] :invalid-envelope nil nil]
             [(dissoc good :model) :missing-model nil {:input_tokens 27 :output_tokens 3}]
             [(assoc good :model "Alice") :model-mismatch nil {:input_tokens 27 :output_tokens 3}]
             [(assoc good :model "fixture-secret") :invalid-model nil {:input_tokens 27 :output_tokens 3}]
             [(dissoc good :usage) :missing-usage "jev-1.13.0" nil]
             [(assoc good :usage "fixture-secret") :invalid-usage "jev-1.13.0" nil]
             [(assoc good :usage {:input_tokens "fixture-secret" :output_tokens 3}) :invalid-usage "jev-1.13.0" {:output_tokens 3}]
             [(dissoc good :answers) :missing-answers "jev-1.13.0" {:input_tokens 27 :output_tokens 3}]
             [(assoc good :answers "fixture-secret") :invalid-answers-type "jev-1.13.0" {:input_tokens 27 :output_tokens 3}]
             [(update good :answers dissoc :identity_0) :missing-answer-identifiers "jev-1.13.0" {:input_tokens 27 :output_tokens 3}]
             [(assoc-in good [:answers :fixture-secret] "Alice") :extra-answer-identifiers "jev-1.13.0" {:input_tokens 27 :output_tokens 3}]]]
      (let [result (execute-diagnostic-body (json/write-str payload))]
        (is (= :invalid-response (:error result)))
        (is (some #{reason} (:validation-reasons result)) (str reason))
        (is (= model (:model-version result)))
        (is (= usage (:usage result)))
        (is (not (re-find #"Alice|fixture-secret" (pr-str result))))))
    (let [result (execute-diagnostic-body (json/write-str (-> good (update :answers dissoc :identity_0)
                                                              (assoc-in [:answers :fixture-secret] "Alice"))))]
      (is (= [:missing-answer-identifiers :extra-answer-identifiers :invalid-answer] (:validation-reasons result)))
      (is (= [:missing-answer] (get-in result [:answers "identity_0" :validation-reasons])))
      (is (= :no-match (get-in result [:answers "identity_1" :outcome]))))
    (doseq [body ["{\"model\":\"jev-1.13.0\",\"model\":\"Alice\"}" "{fixture-secret" "{} trailing"]]
      (let [result (execute-diagnostic-body body)]
        (is (= [:invalid-outer-json] (:validation-reasons result)))
        (is (not (re-find #"Alice|fixture-secret" (pr-str result))))))))

(deftest diagnostic-runs-keep-accounting-stop-and-all-replay-states
  (doseq [mode [:complete :invalid :unknown]]
    (let [calls (atom 0) dir (runner/root)]
      (http/with-server
        (fn [ex]
          (swap! calls inc)
          (let [body (json/read-str (slurp (.getRequestBody ex)))
                payload {:model "jev-1.13.0" :usage {:input_tokens 27 :output_tokens 3}
                         :answers (into {} (map (fn [id] [id (answer "match")]) (keys (get body "questions"))))}]
            (http/reply! ex 200 (json/write-str (if (= mode :invalid)
                                                  (assoc-in payload [:answers "identity_0" :confidence] "malicious-private-name")
                                                  payload)))))
        (fn [url]
          (let [legacy-cfg [(config url)]
                cfg [(assoc (config url) :native-diagnostics-version 1)]
                runtime {:providers {"native" {:bearer-token "fixture-secret"}}}]
            (when (= mode :unknown)
              (is (thrown? Exception (evaluation/run! dir (dataset) cfg
                                                      (assoc runtime :on-progress #(when (= :attempt-returned (:phase %))
                                                                                     (throw (ex-info "synthetic crash" {}))))))))
            (let [before @calls receipt (evaluation/run! dir (dataset) cfg runtime)
                  inspection (evaluation/inspect-run dir (:run-id receipt))
                  report (get-in inspection [:report :providers "native"])
                  after @calls]
              (is (= (if (= mode :complete) 2 1) after))
              (is (= (case mode :complete [:match :match :match] :invalid [:error :match :error] :unknown [:error :error :error])
                     (mapv :outcome (:results report))))
              (is (= (if (= mode :complete) 0 1) (get-in report [:dispatch :undispatched-case-count])))
              (when (= mode :invalid)
                (is (= [:invalid-confidence] (:validation-reasons (first (:results report)))))
                (is (= {:input_tokens 27 :output_tokens 3} (get-in report [:request-metrics :usage])))
                (is (= 1 (get-in report [:request-metrics :request-error-count])))
                (is (not (str/includes? (pr-str inspection) "malicious-private-name"))))
              (when (= mode :unknown) (is (= before after)))
              (is (= receipt (evaluation/run! dir (dataset) cfg runtime)))
              (is (= after @calls))
              (when (= mode :complete)
                (let [legacy (evaluation/run! dir (dataset) legacy-cfg runtime)]
                  (is (not= (:run-id legacy) (:run-id receipt))))))))))))

(deftest native-diagnostics-do-not-equate-confidence-with-choice-probability
  (let [result (execute-diagnostic-body (json/write-str (diagnostic-response (answer "match"))))]
    (is (= :complete (:outcome result)))
    (is (= 0.7 (get-in result [:answers "identity_0" :confidence])))
    (is (= 0.8 (get-in result [:answers "identity_0" :probabilities :match])))))

(deftest native-usage-accounting-requires-a-measured-counter
  (doseq [[usage expected known outcome]
          [[{} nil 0 :match]
           [{:private-provider-key "private-provider-value"} nil 0 :match]
           [{:input_tokens "private-provider-value"} nil 0 :error]
           [{:input_tokens "private-provider-value" :output_tokens 3} {:output_tokens 3} 1 :error]]]
    (let [dir (runner/root)]
      (http/with-server
        (fn [ex] (http/reply! ex 200 (json/write-str (assoc (diagnostic-response (answer "match")) :usage usage))))
        (fn [url]
          (let [receipt (evaluation/run! dir (update (dataset) :cases #(vec (take 2 %))) [(assoc (config url) :native-diagnostics-version 1)]
                                         {:providers {"native" {:bearer-token "fixture-secret"}}})
                report (get-in (evaluation/inspect-run dir (:run-id receipt)) [:report :providers "native"])]
            (is (= outcome (:outcome (first (:results report)))))
            (is (= expected (get-in report [:batches 0 :attempt :result :usage])))
            (is (= (if (= :match outcome) :complete :error) (get-in report [:batches 0 :attempt :result :outcome])))
            (is (= known (get-in report [:request-metrics :usage-known-request-count])))
            (is (not (re-find #"private-provider-key|private-provider-value" (pr-str report))))))))))

(defn execute-numerical-body [body]
  (http/with-server (fn [ex] (http/reply! ex 200 body))
    (fn [url] (p/execute! (first (p/prepare-batches (assoc (config url) :native-diagnostics-version 2) (cases)))
                          {:bearer-token "fixture-secret"}))))

(deftest numerical-diagnostics-explain-sum-without-assuming-rounding
  (let [bad (assoc (answer "match") :probabilities {"match" 0.61 "no_match" 0.08 "abstain" 0.30})
        result (execute-numerical-body (json/write-str (diagnostic-response bad)))
        numerical (get-in result [:answers "identity_0" :probability-diagnostics])]
    (is (= :invalid-response (:error result)))
    (is (= [:invalid-probability-sum] (get-in result [:answers "identity_0" :validation-reasons])))
    (is (= {:match 0.61 :no_match 0.08 :abstain 0.30} (:values numerical)))
    (is (= 3 (:count numerical)))
    (is (= 0.99 (:sum numerical)))
    (is (= (Math/abs (- 1.0 0.99)) (:absolute-deviation numerical)))
    (is (= 0.00001 (:tolerance numerical)))
    (is (= :strict-less-than (:comparison numerical)))
    (is (= :unestablished (:rounding-cause numerical)))
    (is (= :no-match (get-in result [:answers "identity_1" :outcome])))
    (is (= {:input_tokens 27 :output_tokens 3} (:usage result)))))

(deftest numerical-opt-in-preserves-wire-body-and-version-eight
  (let [cfg (config "https://example.com")
        old (first (p/prepare-batches (assoc cfg :native-diagnostics-version 1) (cases)))
        new (first (p/prepare-batches (assoc cfg :native-diagnostics-version 2) (cases)))
        body (json/write-str (diagnostic-response (assoc (answer "match") :probabilities {"match" 0.61 "no_match" 0.08 "abstain" 0.30})))
        legacy (execute-diagnostic-body body)
        numerical (execute-numerical-body body)]
    (is (= "shadow-adapters/9" (:adapter-version new)))
    (is (= old (-> new (assoc :adapter-version "shadow-adapters/8")
                   (assoc-in [:config :native-diagnostics-version] 1))))
    (is (= (:body old) (:body new)))
    (is (= {:outcome :error :error :invalid-answer :validation-reasons [:invalid-probability-sum]}
           (get-in legacy [:answers "identity_0"])))
    (is (= (dissoc legacy :latency-ms) (-> numerical (dissoc :latency-ms)
                                           (update-in [:answers "identity_0"] dissoc :probability-diagnostics))))))

(deftest numerical-diagnostics-retain-the-strict-binary-sum-boundary
  ;; Closest representable sums straddling both mathematical tolerance limits.
  (doseq [[values expected]
          [[[0.1 0.7 0.2] :no-match]
           [[(Math/nextUp (- 1.0 0.00001)) 0.0 0.0] :match]
           [[(Math/nextDown (- 1.0 0.00001)) 0.0 0.0] :error]
           [[0.5 (- (Math/nextDown (+ 1.0 0.00001)) 0.5) 0.0] :no-match]
           [[0.5 (- (Math/nextUp (+ 1.0 0.00001)) 0.5) 0.0] :error]
           [[0.61 0.08 0.30] :error]
           [[0.0 0.0 0.0] :error]
           [[1.0 1.0 1.0] :error]]]
    (let [probs (zipmap ["match" "no_match" "abstain"] values)
          choice (key (apply max-key val probs))
          result (execute-numerical-body (json/write-str (diagnostic-response (assoc (answer choice) :probabilities probs))))
          prediction (get-in result [:answers "identity_0"])]
      (is (= expected (:outcome prediction)) (pr-str values))
      (if (= :error expected)
        (let [d (:probability-diagnostics prediction)]
          (is (= [:invalid-probability-sum] (:validation-reasons prediction)))
          (is (= #{:values :count :sum :absolute-deviation :tolerance :comparison :rounding-cause} (set (keys d))))
          (is (<= 0 (:sum d) 3))
          (is (<= 0 (:absolute-deviation d) 2))
          (is (every? #(and (Double/isFinite (double %)) (<= 0 % 1)) (vals (:values d)))))
        (is (nil? (:probability-diagnostics prediction)))))))

(deftest numerical-diagnostics-never-retain-malformed-or-private-values
  (let [good (answer "match")]
    (doseq [bad [nil "private-source-name"
                 (assoc good :probabilities "private-source-name")
                 (assoc good :probabilities {"private-source-name" 1})
                 (assoc good :probabilities {"match" 0.5 "no_match" 0.2})
                 (assoc-in good [:probabilities "match"] "private-source-name")
                 (assoc-in good [:probabilities "match"] nil)
                 (assoc-in good [:probabilities "match"] true)
                 (assoc-in good [:probabilities "match"] [0.6])
                 (assoc-in good [:probabilities "match"] -0.01)
                 (assoc-in good [:probabilities "match"] 1.01)
                 (assoc good :confidence "private-source-name")]]
      (let [r (execute-numerical-body (json/write-str (diagnostic-response bad)))]
        (is (= :invalid-response (:error r)))
        (is (nil? (get-in r [:answers "identity_0" :probability-diagnostics])))
        (is (= :no-match (get-in r [:answers "identity_1" :outcome])))
        (is (not (str/includes? (pr-str r) "private-source-name"))))))
  (doseq [numeric ["1e400" "-1e400" "NaN" "Infinity" "-Infinity"]]
    (let [body (str/replace (json/write-str (diagnostic-response (assoc-in (answer "match") [:probabilities "match"] "PLACEHOLDER")))
                            "\"PLACEHOLDER\"" numeric)
          r (execute-numerical-body body)]
      (is (= :invalid-response (:error r)))
      (is (nil? (get-in r [:answers "identity_0" :probability-diagnostics])))
      (is (not (re-find #"Infinity|NaN|1e400" (pr-str r))))))
  (let [bad (assoc (answer "match") :probabilities {"match" 0.61 "no_match" 0.08 "abstain" 0.30}
                   :private-source-name "fixture-secret")
        r (execute-numerical-body (json/write-str (diagnostic-response bad)))]
    (is (some? (get-in r [:answers "identity_0" :probability-diagnostics])))
    (is (not (re-find #"fixture-secret|private-source-name" (pr-str r))))))

(deftest numerical-runs-retain-diagnostics-stop-and-replay-native-adapter-generations
  (doseq [mode [:complete :invalid :unknown]]
    (let [calls (atom 0) dir (runner/root) run-ids (atom #{})]
      (http/with-server
        (fn [ex]
          (swap! calls inc)
          (let [body (json/read-str (slurp (.getRequestBody ex)))
                payload {:model "jev-1.13.0" :usage {:input_tokens 27 :output_tokens 3}
                         :answers (into {} (map (fn [id] [id (answer "match")]) (keys (get body "questions"))))}]
            (http/reply! ex 200 (json/write-str (if (= mode :invalid)
                                                  (assoc-in payload [:answers "identity_0" :probabilities]
                                                            {"match" 0.61 "no_match" 0.08 "abstain" 0.30})
                                                  payload)))))
        (fn [url]
          (doseq [version [nil 1 2]]
            (reset! calls 0)
            (let [cfg [(cond-> (config url) version (assoc :native-diagnostics-version version))]
                  runtime {:providers {"native" {:bearer-token "fixture-secret"}}}]
              (when (= mode :unknown)
                (is (thrown? Exception
                             (evaluation/run! dir (dataset) cfg
                                              (assoc runtime :on-progress
                                                     #(when (= :attempt-returned (:phase %))
                                                        (throw (ex-info "synthetic crash" {}))))))))
              (let [receipt (if (= mode :unknown)
                              (with-redefs [p/execute! (fn [& _] (throw (ex-info "Forbidden redispatch" {})))]
                                (evaluation/run! dir (dataset) cfg runtime))
                              (evaluation/run! dir (dataset) cfg runtime))
                    report (get-in (evaluation/inspect-run dir (:run-id receipt)) [:report :providers "native"])
                    diagnostic (get-in report [:results 0 :probability-diagnostics])]
                (swap! run-ids conj (:run-id receipt))
                (is (= (if (= mode :complete) 2 1) @calls))
                (is (= (case mode :complete [:match :match :match] :invalid [:error :match :error] :unknown [:error :error :error])
                       (mapv :outcome (:results report))))
                (is (= (if (= mode :complete) 0 1) (get-in report [:dispatch :undispatched-case-count])))
                (if (and (= version 2) (= mode :invalid))
                  (do (is (= 0.99 (:sum diagnostic)))
                      (is (= diagnostic (get-in report [:batches 0 :attempt :result :answers "identity_0" :probability-diagnostics])))
                      (is (= {:input_tokens 27 :output_tokens 3} (get-in report [:request-metrics :usage]))))
                  (is (nil? diagnostic)))
                (with-redefs [p/execute! (fn [& _] (throw (ex-info "Forbidden redispatch" {})))]
                  (is (= receipt (evaluation/run! dir (dataset) cfg runtime)))))))
          (is (= 3 (count @run-ids))))))))

(deftest numerical-companions-use-only-their-fixed-choice-keys
  (http/with-server
    (fn [ex]
      (http/reply! ex 200 (json/write-str {:model "jev-1.13.0" :usage {:input_tokens 27}
                                           :answers {:identity_0 (answer "match")
                                                     :contradiction_0 {:type "choice" :choice "no" :confidence 0.7
                                                                       :probabilities {:yes 0.08 :no 0.61 :unknown 0.30}
                                                                       :private-source-name "fixture-secret"}}})))
    (fn [url]
      (let [cfg (assoc (config url) :native-diagnostics-version 2 :companion-assessments [:contradiction])
            r (p/execute! (first (p/prepare-batches cfg [(first (cases))])) {:bearer-token "fixture-secret"})
            d (get-in r [:answers "contradiction_0" :probability-diagnostics])]
        (is (= :invalid-response (:error r)))
        (is (= :match (get-in r [:answers "identity_0" :outcome])))
        (is (= {:yes 0.08 :no 0.61 :unknown 0.30} (:values d)))
        (is (= 0.99 (:sum d)))
        (is (not (re-find #"private-source-name|fixture-secret" (pr-str r))))))))

(deftest numerical-diagnostics-preserve-independent-metadata-validation
  (doseq [[changes model usage]
          [[{:model "private-source-name"} nil {:input_tokens 27 :output_tokens 3}]
           [{:usage {:input_tokens "private-source-name" :output_tokens 3}} "jev-1.13.0" {:output_tokens 3}]]]
    (let [bad (assoc (answer "match") :probabilities {"match" 0.61 "no_match" 0.08 "abstain" 0.30})
          r (execute-numerical-body (json/write-str (merge (diagnostic-response bad) changes)))]
      (is (= :invalid-response (:error r)))
      (is (= model (:model-version r)))
      (is (= usage (:usage r)))
      (is (nil? (get-in r [:answers "identity_0" :probability-diagnostics])))
      (is (not (str/includes? (pr-str r) "private-source-name"))))))

(defn local-config [url size]
  (assoc (config url) :identity-protocol :freediving-question-local-v1
         :native-diagnostics-version 2 :native-batch-size size))

(deftest question-local-evidence-is-identical-across-batch-sizes
  (let [members (assoc-in (cases) [1 :input :left]
                          (fixture/record "different-record" "Other Original Name"))
        singles (p/prepare-batches (local-config "https://example.com" 1) members)
        batch (first (p/prepare-batches (local-config "https://example.com" 2) members))
        decode #(json/read-str (:body %) :key-fn keyword)
        body (decode batch)]
    (is (= "shadow-adapters/10" (:adapter-version batch)))
    (is (= :freediving-question-local-v1 (get-in batch [:config :identity-protocol])))
    (is (= :question-local-evidence (:context-policy batch)))
    (is (= (:instruction protocol/descriptor) (:state body)))
    (doseq [i (range 2)]
      (let [single (decode (nth singles i))
            q (get-in body [:questions (keyword (str "identity_" i))])]
        (is (= (:state body) (:state single)))
        (is (= q (get-in single [:questions :identity_0])))
        (is (= (select-keys (:input (nth members i)) [:left :right])
               (select-keys (:instructions q) [:left :right])))
        (is (= (:criteria (protocol/question "/left" "/right")) (:criteria q)))
        (is (= #{:left :right :question} (set (keys (:instructions q)))))))
    (is (not (.contains (:state body) "Other Original Name")))
    (is (not (.contains (:body (first singles)) "Other Original Name")))
    (doseq [bad [(assoc (local-config "https://example.com" 2) :native-diagnostics-version 1)
                 (assoc (local-config "https://example.com" 2) :companion-assessments [:name-variation])
                 (local-config "https://example.com" 3)
                 (assoc (local-config "https://example.com" 2) :model "jev-latest")]]
      (is (thrown? Exception (p/prepare-batches bad members))))))

(deftest question-local-run-retains-strict-validation-and-durable-replay
  (let [calls (atom 0) dir (runner/root)]
    (http/with-server
      (fn [ex]
        (swap! calls inc)
        (let [body (json/read-str (slurp (.getRequestBody ex)))]
          (http/reply! ex 200
                       (json/write-str {:model "jev-1.13.0" :usage {:input_tokens 27}
                                        :answers (into {} (map (fn [id] [id (answer "match")])
                                                               (keys (get body "questions"))))}))))
      (fn [url]
        (let [cfg [(local-config url 2)]
              runtime {:providers {"native" {:bearer-token "fixture-secret"}}}
              receipt (evaluation/run! dir (dataset) cfg runtime)
              report (get-in (evaluation/inspect-run dir (:run-id receipt)) [:report :providers "native"])]
          (is (= [:match :match :match] (mapv :outcome (:results report))))
          (is (= 2 @calls))
          (is (= receipt (evaluation/run! dir (dataset) cfg runtime)))
          (is (= 2 @calls))))))
  (http/with-server
    (fn [ex] (http/reply! ex 200 (json/write-str {:model "jev-1.13.0" :usage {}
                                                  :answers {:identity_0 (assoc (answer "match") :probabilities
                                                                               {:match 0.33 :no_match 0.33 :abstain 0.33})
                                                            :identity_1 (answer "match")}})))
    (fn [url]
      (let [result (p/execute! (first (p/prepare-batches (local-config url 2) (cases)))
                               {:bearer-token "fixture-secret"})]
        (is (= :error (:outcome result)))
        (is (= :match (get-in result [:answers "identity_1" :outcome])))
        (is (= 0.99 (get-in result [:answers "identity_0" :probability-diagnostics :sum])))))))

(deftest question-local-rounded-probabilities-accept-inclusive-two-percent-sums
  (doseq [[values expected]
          [[[0.93 0.01 0.05] :match]
           [[0.93 0.01 0.04] :match]
           [[0.93 0.04 0.05] :match]
           [[0.93 0.01 0.039999999] :error]
           [[0.93 0.04 0.050000001] :error]]]
    (http/with-server
      (fn [ex] (http/reply! ex 200 (json/write-str
                                    (diagnostic-response
                                     (assoc (answer "match") :probabilities
                                            (zipmap ["match" "no_match" "abstain"] values))))))
      (fn [url]
        (let [cfg (assoc (local-config url 2) :probability-sum-tolerance 0.02)
              prepared (first (p/prepare-batches cfg (cases)))
              result (p/execute! prepared {:bearer-token "fixture-secret"})
              prediction (get-in result [:answers "identity_0"])]
          (is (= "shadow-adapters/11" (:adapter-version prepared)))
          (is (= (:body (first (p/prepare-batches (dissoc cfg :probability-sum-tolerance) (cases)))) (:body prepared)))
          (is (= expected (:outcome prediction)) (pr-str values))
          (if (= :match expected)
            (is (= (zipmap [:match :no_match :abstain] values) (:probabilities prediction)))
            (do (is (= [:invalid-probability-sum] (:validation-reasons prediction)))
                (is (= 0.02 (get-in prediction [:probability-diagnostics :tolerance])))
                (is (= :less-than-or-equal (get-in prediction [:probability-diagnostics :comparison]))))))))))

(deftest rounded-sum-contract-preserves-other-validation-and-rejects-unsupported-configs
  (doseq [cfg [(assoc (local-config "https://example.com" 2) :probability-sum-tolerance 0.03)
               (assoc (local-config "https://example.com" 2) :probability-sum-tolerance nil)
               (assoc (config "https://example.com") :probability-sum-tolerance 0.02)]]
    (is (thrown? Exception (p/prepare-batches cfg (cases)))))
  (doseq [[bad reason]
          [[(assoc (answer "match") :probabilities {"match" 1.01 "no_match" 0 "abstain" 0}) :invalid-probability-range]
           [(assoc (answer "match") :probabilities {"match" 0.01 "no_match" 0.93 "abstain" 0.05}) :choice-probability-inconsistency]
           [(assoc (answer "match") :probabilities {"match" 0.99}) :invalid-probability-keys]
           [(assoc (answer "match") :confidence 1.01) :invalid-confidence]]]
    (http/with-server
      (fn [ex] (http/reply! ex 200 (json/write-str (diagnostic-response bad))))
      (fn [url]
        (let [r (p/execute! (first (p/prepare-batches
                                    (assoc (local-config url 2) :probability-sum-tolerance 0.02) (cases)))
                            {:bearer-token "fixture-secret"})]
          (is (= [reason] (get-in r [:answers "identity_0" :validation-reasons])))
          (is (= :no-match (get-in r [:answers "identity_1" :outcome]))))))))

(deftest rounded-sum-runs-have-distinct-identities-and-replay-without-calls
  (let [calls (atom 0) dir (runner/root)]
    (http/with-server
      (fn [ex]
        (swap! calls inc)
        (let [body (json/read-str (slurp (.getRequestBody ex)))]
          (http/reply! ex 200 (json/write-str
                               {:model "jev-1.13.0" :usage {:input_tokens 27}
                                :answers (into {} (map (fn [id] [id (assoc (answer "match") :probabilities
                                                                           {"match" 0.93 "no_match" 0.01 "abstain" 0.05})])
                                                       (keys (get body "questions"))))}))))
      (fn [url]
        (let [old [(local-config url 2)]
              new [(assoc (first old) :probability-sum-tolerance 0.02)]
              runtime {:providers {"native" {:bearer-token "fixture-secret"}}}
              before (evaluation/run! dir (dataset) old runtime)
              after (evaluation/run! dir (dataset) new runtime)]
          (is (not= (:run-id before) (:run-id after)))
          (is (= [:error :error :error]
                 (mapv :outcome (get-in (evaluation/inspect-run dir (:run-id before)) [:report :providers "native" :results]))))
          (is (= [:match :match :match]
                 (mapv :outcome (get-in (evaluation/inspect-run dir (:run-id after)) [:report :providers "native" :results]))))
          (is (= 3 @calls))
          (with-redefs [p/execute! (fn [& _] (throw (ex-info "Unexpected replay dispatch" {})))]
            (is (= before (evaluation/run! dir (dataset) old runtime)))
            (is (= after (evaluation/run! dir (dataset) new runtime)))))))))

(deftest five-question-local-batches-preserve-original-questions-and-order
  (let [members (mapv #(assoc (first (cases)) :case-id (str "larger-" %)) (range 11))
        cfg (assoc (local-config "https://example.com" 5) :probability-sum-tolerance 0.02)
        singles (p/prepare-batches (assoc cfg :native-batch-size 1) members)
        grouped (p/prepare-batches cfg members)
        decode #(json/read-str (:body %))]
    (is (= [5 5 1] (mapv #(count (:case-ids %)) grouped)))
    (is (= (mapv :case-id members) (vec (mapcat :case-ids grouped))))
    (is (every? #(= "shadow-adapters/12" (:adapter-version %)) grouped))
    (is (= (mapv #(get-in (decode %) ["questions" "identity_0"]) singles)
           (vec (mapcat #(vals (into (sorted-map) (get (decode %) "questions"))) grouped))))
    (is (every? #(= (get (decode (first singles)) "state") (get (decode %) "state")) grouped))
    (doseq [size [1 2]]
      (is (every? #(= "shadow-adapters/11" (:adapter-version %))
                  (p/prepare-batches (assoc cfg :native-batch-size size) members))))
    (doseq [size [3 4 6 7 8]]
      (is (every? #(= "shadow-adapters/12" (:adapter-version %))
                  (p/prepare-batches (assoc cfg :native-batch-size size) members))))
    (is (thrown? Exception (p/prepare-batches (dissoc cfg :probability-sum-tolerance) members)))
    (is (thrown? Exception (p/prepare-batches (assoc cfg :max-request-bytes 1000) members)))
    (is (thrown? Exception (p/prepare-batches (assoc cfg :companion-assessments [:contradiction]) members)))))

(deftest five-question-rounded-http-results-replay-without-redispatch
  (let [calls (atom 0) dir (runner/root)
        sample (dataset)
        sample (assoc sample :cases (mapv #(assoc (first (:cases sample)) :case-id (str "larger-" %)) (range 11)))]
    (http/with-server
      (fn [ex]
        (swap! calls inc)
        (let [body (json/read-str (slurp (.getRequestBody ex)))]
          (http/reply! ex 200
                       (json/write-str {:model "jev-1.13.0" :usage {:input_tokens 27}
                                        :answers (into {} (map (fn [id] [id (assoc (answer "match") :probabilities
                                                                                   {"match" 0.93 "no_match" 0.01 "abstain" 0.05})])
                                                               (keys (get body "questions"))))}))))
      (fn [url]
        (let [cfg [(assoc (local-config url 5) :probability-sum-tolerance 0.02)]
              runtime {:providers {"native" {:bearer-token "fixture-secret"}}}
              receipt (evaluation/run! dir sample cfg runtime)
              report (get-in (evaluation/inspect-run dir (:run-id receipt)) [:report :providers "native"])]
          (is (= (vec (repeat 11 :match)) (mapv :outcome (:results report))))
          (is (= 3 @calls))
          (is (= [5 5 1] (get-in report [:request-metrics :batch-sizes])))
          (is (= {:match 0.93 :no_match 0.01 :abstain 0.05} (get-in report [:results 0 :probabilities])))
          (with-redefs [p/execute! (fn [& _] (throw (ex-info "Unexpected replay dispatch" {})))]
            (is (= receipt (evaluation/run! dir sample cfg runtime))))
          (is (= 3 @calls)))))))

(deftest larger-question-local-batches-retain-total-and-per-question-byte-caps
  (let [cfg (assoc (local-config "https://example.com" 5) :probability-sum-tolerance 0.02)
        input (assoc-in (fixture/input) [:left :uncertainties]
                        (vec (repeat 2 {:value (apply str (repeat 4000 "x")) :evidence-ids ["source-row-1"]})))
        members (mapv #(assoc (first (cases)) :case-id (str "large-" %) :input input) (range 5))]
    (is (= 5 (count (p/prepare-batches (assoc cfg :native-batch-size 1) members))))
    (is (thrown? Exception (p/prepare-batches cfg members)))
    (is (thrown? Exception
                 (p/prepare-batches cfg [(assoc (first members) :input
                                                (assoc-in input [:left :uncertainties]
                                                          (vec (repeat 8 {:value (apply str (repeat 4000 "x")) :evidence-ids ["source-row-1"]}))))])))))

(deftest compact-rounded-http-results-replay-without-redispatch
  (let [calls (atom 0) dir (runner/root)
        sample (dataset)
        sample (assoc sample :cases (mapv #(assoc (first (:cases sample)) :case-id (str "larger-" %)) (range 11)))]
    (http/with-server
      (fn [ex]
        (swap! calls inc)
        (let [body (json/read-str (slurp (.getRequestBody ex)))]
          (http/reply! ex 200
                       (json/write-str {:model "jev-1.13.0" :usage {:input_tokens 27}
                                        :answers (into {} (map (fn [id] [id (assoc (answer "match") :probabilities
                                                                                   {"match" 0.93 "no_match" 0.01 "abstain" 0.05})])
                                                               (keys (get body "questions"))))}))))
      (fn [url]
        (let [cfg [(assoc (local-config url 5) :identity-protocol :freediving-compact-v1 :probability-sum-tolerance 0.02)]
              runtime {:providers {"native" {:bearer-token "fixture-secret"}}}
              receipt (evaluation/run! dir sample cfg runtime)
              report (get-in (evaluation/inspect-run dir (:run-id receipt)) [:report :providers "native"])]
          (is (= (vec (repeat 11 :match)) (mapv :outcome (:results report))))
          (is (= 3 @calls))
          (is (= [5 5 1] (get-in report [:request-metrics :batch-sizes])))
          (is (= {:match 0.93 :no_match 0.01 :abstain 0.05} (get-in report [:results 0 :probabilities])))
          (with-redefs [p/execute! (fn [& _] (throw (ex-info "Unexpected replay dispatch" {})))]
            (is (= receipt (evaluation/run! dir sample cfg runtime))))
          (is (= 3 @calls)))))))

(deftest compact-rounded-probabilities-accept-inclusive-two-percent-sums
  (doseq [[values expected]
          [[[0.93 0.01 0.05] :match]
           [[0.93 0.01 0.04] :match]
           [[0.93 0.04 0.05] :match]
           [[0.93 0.01 0.039999999] :error]
           [[0.93 0.04 0.050000001] :error]]]
    (http/with-server
      (fn [ex] (http/reply! ex 200 (json/write-str
                                    (diagnostic-response
                                     (assoc (answer "match") :probabilities
                                            (zipmap ["match" "no_match" "abstain"] values))))))
      (fn [url]
        (let [cfg (assoc (local-config url 2) :identity-protocol :freediving-compact-v1 :probability-sum-tolerance 0.02)
              prepared (first (p/prepare-batches cfg (cases)))
              result (p/execute! prepared {:bearer-token "fixture-secret"})
              prediction (get-in result [:answers "identity_0"])]
          (is (= "shadow-adapters/13" (:adapter-version prepared)))
          (is (= expected (:outcome prediction)) (pr-str values))
          (if (= :match expected)
            (is (= (zipmap [:match :no_match :abstain] values) (:probabilities prediction)))
            (do (is (= [:invalid-probability-sum] (:validation-reasons prediction)))
                (is (= 0.02 (get-in prediction [:probability-diagnostics :tolerance])))
                (is (= :less-than-or-equal (get-in prediction [:probability-diagnostics :comparison]))))))))))

(deftest default-eight-question-run-preserves-each-probability-distribution
  (let [sample (assoc (dataset) :cases
                      (mapv #(assoc (first (:cases (dataset))) :case-id (str "default-" %)) (range 9)))
        calls (atom 0) dir (runner/root)]
    (http/with-server
      (fn [ex]
        (swap! calls inc)
        (let [body (json/read-str (slurp (.getRequestBody ex)))
              ids (keys (get body "questions"))]
          (http/reply! ex 200
                       (json/write-str {:model "jev-1.13.0" :usage {:input_tokens 27}
                                        :answers (into {} (map-indexed
                                                           (fn [i id]
                                                             [id (assoc (answer "match")
                                                                        :probabilities {"match" (- 0.9 (* i 0.01))
                                                                                        "no_match" (+ 0.05 (* i 0.01))
                                                                                        "abstain" 0.05})]) ids))}))))
      (fn [url]
        (let [cfg [(-> (config url)
                       (dissoc :native-batch-size :identity-protocol)
                       (assoc :identity-protocol :freediving-compact-v2))]
              runtime {:providers {"native" {:bearer-token "fixture-secret"}}}
              receipt (evaluation/run! dir sample cfg runtime)
              report (get-in (evaluation/inspect-run dir (:run-id receipt)) [:report :providers "native"])]
          (is (= [8 1] (get-in report [:request-metrics :batch-sizes])))
          (is (= 2 @calls))
          (is (= (vec (repeat 9 :match)) (mapv :outcome (:results report))))
          (is (= (mapv #(- 0.9 (* (mod % 8) 0.01)) (range 9))
                 (mapv #(get-in % [:probabilities :match]) (:results report))))
          (with-redefs [p/execute! (fn [& _] (throw (ex-info "Unexpected replay dispatch" {})))]
            (is (= receipt (evaluation/run! dir sample cfg runtime)))))))))

(deftest spelling-answers-share-batches-and-remain-in-private-results
  (let [cfg (dissoc (config "https://example.com") :identity-protocol :native-batch-size)
        prepared (first (p/prepare-batches cfg (cases)))
        body (json/read-str (:body prepared) :key-fn keyword)
        spelling-answer {:type "choice" :choice "left" :confidence 0.98
                         :probabilities {:left 0.96 :right 0.01 :equally_plausible 0.01
                                         :unknown 0.01 :not_applicable 0.01}}
        identity-answer {:type "choice" :choice "match" :confidence 0.98
                         :probabilities {:match 0.96 :no_match 0.02 :abstain 0.02}}]
    (is (= "shadow-adapters/14" (:adapter-version prepared)))
    (is (= #{:identity_0 :identity_1 :spelling_0 :spelling_1} (set (keys (:questions body)))))
    (is (.contains (:state body) "mistransliterated"))
    (http/with-server
      (fn [ex] (http/reply! ex 200 (json/write-str {:model "jev-1.13.0" :usage {:input_tokens 27}
                                                    :answers {:identity_0 identity-answer :identity_1 identity-answer
                                                              :spelling_0 spelling-answer :spelling_1 spelling-answer}})))
      (fn [url]
        (let [sample (assoc (dataset) :cases (subvec (:cases (dataset)) 0 2))
              config (assoc cfg :endpoint url :identity-protocol :freediving-compact-v3)
              root (runner/root)
              receipt (evaluation/run! root sample [config] {:providers {"native" {:bearer-token "fixture-secret"}}})
              results (get-in (evaluation/inspect-run root (:run-id receipt)) [:report :providers "native" :results])]
          (is (= [:left :left] (mapv #(get-in % [:spelling :outcome]) results))))))))

(deftest partial-v3-response-retains-private-raw-body-and-replays-without-dispatch
  (let [calls (atom 0) root (runner/root)
        raw "{\"model\":\"jev-1.13.0\",\"answers\":{\"identity_0\":{\"type\":\"choice\",\"choice\":\"match\",\"confidence\":0.91,\"probabilities\":{\"match\":0.91,\"no_match\":0.07,\"abstain\":0.02}}}}"]
    (http/with-server
      (fn [ex] (swap! calls inc) (http/reply! ex 200 raw))
      (fn [url]
        (let [sample (assoc (dataset) :cases (subvec (:cases (dataset)) 0 2))
              config [(assoc (config url) :identity-protocol :freediving-compact-v3)]
              runtime {:providers {"native" {:bearer-token "fixture-secret"}}}
              receipt (evaluation/run! root sample config runtime)
              result (get-in (evaluation/inspect-run root (:run-id receipt))
                             [:report :providers "native" :batches 0 :attempt :result])]
          (is (= :error (:outcome result)))
          (is (= :invalid-response (:error result)))
          (is (= raw (:raw-response result)))
          (is (= receipt (evaluation/run! root sample config)))
          (is (= 1 @calls)))))))

(deftest v3-credential-echo-never-enters-private-raw-receipt
  (http/with-server
    (fn [ex] (http/reply! ex 200 "{\"debug\":\"fixture-secret\"}"))
    (fn [url]
      (let [root (runner/root)
            sample (assoc (dataset) :cases (subvec (:cases (dataset)) 0 2))
            config [(assoc (config url) :identity-protocol :freediving-compact-v3)]
            receipt (evaluation/run! root sample config {:providers {"native" {:bearer-token "fixture-secret"}}})
            result (get-in (evaluation/inspect-run root (:run-id receipt))
                           [:report :providers "native" :batches 0 :attempt :result])]
        (is (= :credential-echo (:error result)))
        (is (nil? (:raw-response result)))))))
