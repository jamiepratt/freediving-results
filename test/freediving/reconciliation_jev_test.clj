(ns freediving.reconciliation-jev-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.data.json :as json]
            [freediving.reconciliation-jev :as jev]))

(def config {:model "jev-1.13.0" :endpoint "https://example.test/jev"})
(defn decision [id family]
  {:decision-id id :family family :subject {:record-id id}
   :candidates [{:id "a" :name "Ágnes"} {:id "b" :name "Agnes"}]
   :evidence [{:evidence-id "source-1" :source-label "private source"
               :citation "page 1, row 2" :exact-excerpt "Ágnes; 52m"}]
   :uncertainties ["OCR uncertain"] :contradictions [] :dependencies []})

(deftest finite-family-questions-retain-local-evidence
  (let [families [:identity :same-attempt :source-revision :category-representation :row-semantics]
        decisions (mapv #(decision (str "decision-" %2) %1) families (range))
        requests (jev/prepare-batches config decisions)
        request (first requests)
        body (json/read-str (:body request))]
    (is (= 1 (count requests)))
    (is (= (mapv :decision-id decisions) (:decision-ids request)))
    (is (= (set (map :decision-id decisions)) (set (keys (get body "questions")))))
    (is (every? #(contains? (set (keys (get % "criteria"))) "unknown")
                (vals (get body "questions"))))
    (is (not (.contains (get body "state") "Ágnes")))
    (is (= "Ágnes" (get-in body ["questions" "decision-0" "instructions" "candidates" 0 "name"])))
    (is (= "private source" (get-in request [:source-labels "source-1"])))
    (is (not (.contains (:body request) "private source")))))

(deftest independent-questions-batch-and-dependencies-wait
  (let [nine (mapv #(decision (str "d" %) :identity) (range 9))]
    (is (= [8 1] (mapv #(count (:decision-ids %)) (jev/prepare-batches config nine))))
    (is (= [["d0" "d1"] ["d2"]]
           (mapv :decision-ids
                 (jev/prepare-batches config (assoc-in (subvec nine 0 3) [2 :dependencies] ["d0"])))))
    (is (= [1 1] (mapv #(count (:decision-ids %))
                       (jev/prepare-batches (assoc config :native-batch-size 1) (subvec nine 0 2)))))))

(deftest response-preserves-raw-distribution-and-rejects-malformed
  (let [request (first (jev/prepare-batches config [(decision "d0" :identity)]))
        probabilities {:same_person 0.94 :different_person 0.03 :unknown 0.03}
        response {:model "jev-1.13.0" :usage {:input_tokens 9}
                  :answers {:d0 {:type "choice" :choice "same_person" :confidence 0.97
                                 :probabilities probabilities}}}
        answer (jev/parse-answer request response "d0")]
    (is (= :same-person (:outcome answer)))
    (is (= {:same-person 0.94 :different-person 0.03 :unknown 0.03} (:probabilities answer)))
    (is (= 0.97 (:confidence answer)))
    (is (= "jev-1.13.0" (:model-version answer)))
    (is (= (:request-hash request) (:request-hash answer)))
    (is (= answer (get-in (jev/parse-batch request response) [:answers "d0"])))
    (is (= :invalid-probability-sum
           (:error (jev/parse-answer request (assoc-in response [:answers :d0 :probabilities :same_person] 0.4) "d0"))))
    (is (= :model-mismatch
           (:error (jev/parse-answer request (assoc response :model "other") "d0"))))
    (is (= :unknown
           (:outcome (jev/parse-answer request
                                       (assoc-in (assoc-in response [:answers :d0 :choice] "unknown")
                                                 [:answers :d0 :probabilities]
                                                 {:same_person 0.03 :different_person 0.03 :unknown 0.94}) "d0"))))))

(deftest oversize-single-question-rejected
  (is (thrown? clojure.lang.ExceptionInfo
               (jev/prepare-batches config
                                    [(assoc (decision "large" :identity)
                                            :evidence [{:evidence-id "source-1"
                                                        :exact-excerpt (apply str (repeat 25000 "x"))}])]))))

(deftest credentials-and-provider-errors-stay-out-of-approval-input
  (let [decision (decision "d0" :identity)
        request (first (jev/prepare-batches config [decision]))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (jev/prepare-batches (assoc config :bearer-token "secret") [decision])))
    (is (= :timeout (:error (jev/parse-answer request {:outcome :error :error :timeout} "d0"))))
    (is (= :error (:outcome (get-in (jev/parse-batch request {:outcome :error :error :timeout})
                                    [:answers "d0"]))))))

(deftest cited-evidence-is-projected-and-source-labels-must-agree
  (let [base (decision "d0" :identity)
        extra (assoc-in base [:evidence 0 :audit-only] "private archive payload")
        request (first (jev/prepare-batches config [extra]))]
    (is (not (.contains (:body request) "private archive payload")))
    (is (= 5000 (get-in request [:config :timeout-ms])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (jev/prepare-batches config [(assoc base :evidence [{:evidence-id "source-1"
                                                                      :exact-excerpt "x"}])])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (jev/prepare-batches config
                                      [base (assoc-in (decision "d1" :identity)
                                                      [:evidence 0 :source-label] "another label")])))))

(defn -main [& _]
  (let [result (run-tests 'freediving.reconciliation-jev-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
