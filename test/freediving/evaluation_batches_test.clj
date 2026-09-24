(ns freediving.evaluation-batches-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.data.json :as json]
            [freediving.evaluation-protocol-test :as fixture]
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
