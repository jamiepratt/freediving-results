(ns freediving.reconciliation-jev-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.data.json :as json]
            [clojure.string :as str]
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

(deftest same-attempt-question-uses-rebuttable-performance-prior
  (let [families [:same-attempt :identity :source-revision :row-semantics]
        decisions (mapv #(decision (str "d" %2) %1) families (range))
        request (first (jev/prepare-batches config decisions))
        body (json/read-str (:body request))
        scope (get-in body ["questions" "d0" "instructions" "scope"])
        other-scopes (map #(get-in body ["questions" (str "d" %) "instructions" "scope"])
                          (range 1 4))]
    (is (= "reconciliation-jev/3" (:template-version request)))
    (is (= 1 (count (jev/prepare-batches config decisions))))
    (is (<= (count (.getBytes ^String (:body request) "UTF-8")) 49152))
    (is (every? #(str/includes? scope %)
                ["same competition, athlete and discipline" "identical performance"
                 "strong, rebuttable prior" "same depth" "static apnea duration"
                 "pool distance" "rare" "session" "day" "round" "attempt ID"
                 "status" "penalty" "source semantics" "PDF date range" "specific unit day"
                 "missing PDF row day" "shared upstream timing" "not independent corroboration"
                 "unknown" "Invent no facts"]))
    (is (every? #(not (str/includes? % "strong, rebuttable prior")) other-scopes))
    (is (contains? (set (keys (get-in body ["questions" "d0" "criteria"]))) "unknown"))))

(deftest source-revision-question-separates-source-version-from-row-change
  (let [request (first (jev/prepare-batches config [(decision "revision" :source-revision)]))
        scope (get-in (json/read-str (:body request))
                      ["questions" "revision" "instructions" "scope"])]
    (is (= "reconciliation-jev/3" (:template-version request)))
    (is (every? #(str/includes? scope %)
                ["correction" "republication" "same sporting attempt"
                 "publication" "acquisition" "retrieval order"
                 "each row changed" "unknown"]))))

(deftest row-semantics-question-distinguishes-alternate-views-from-attempts
  (let [request (first (jev/prepare-batches config [(decision "role" :row-semantics)]))
        scope (get-in (json/read-str (:body request))
                      ["questions" "role" "instructions" "scope"])]
    (is (every? #(str/includes? scope %)
                ["start list" "ranking" "aggregate" "repeated export"
                 "status" "penalty" "another representation"
                 "explicit identifiers" "unknown"]))))

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

(deftest all-family-actions-parse-with-unknown-available
  (doseq [[family choice probabilities]
          [[:identity "same_person" {"same_person" 0.94 "different_person" 0.03 "unknown" 0.03}]
           [:same-attempt "same_attempt" {"same_attempt" 0.94 "distinct_attempts" 0.03 "unknown" 0.03}]
           [:source-revision "left_revises_right" {"left_revises_right" 0.92 "right_revises_left" 0.02 "same_source" 0.02 "unrelated" 0.02 "unknown" 0.02}]
           [:category-representation "representation" {"category" 0.02 "representation" 0.92 "both" 0.02 "neither" 0.02 "unknown" 0.02}]
           [:row-semantics "summary" {"attempt" 0.02 "aggregate" 0.02 "summary" 0.92 "not_result" 0.02 "unknown" 0.02}]]]
    (let [request (first (jev/prepare-batches config [(decision "d0" family)]))
          response (json/write-str {:model "jev-1.13.0" :usage {:input_tokens 9}
                                    :answers {:d0 {:type "choice" :choice choice :confidence 0.95
                                                   :probabilities probabilities}}})
          answer (jev/parse-answer request response "d0")]
      (is (= (keyword (str/replace choice "_" "-")) (:outcome answer)) (name family))
      (is (= (into {} (map (fn [[k v]] [k (bigdec (str v))]) probabilities))
             (:raw-probabilities answer)) (name family))
      (is (= (:request-hash request) (get-in answer [:receipt :request-hash]))))))

(deftest response-identifiers-usage-and-distribution-fail-closed
  (let [request (first (jev/prepare-batches config [(decision "d0" :identity)]))
        answer {:type "choice" :choice "same_person" :confidence 0.95
                :probabilities {:same_person 0.94 :different_person 0.03 :unknown 0.03}}
        response {:model "jev-1.13.0" :usage {:input_tokens 9} :answers {:d0 answer}}]
    (doseq [[bad reason]
            [[(assoc response :answers {}) :invalid-answer-identifiers]
             [(assoc-in response [:answers :extra] answer) :invalid-answer-identifiers]
             [(assoc-in response [:usage :input_tokens] -1) :invalid-usage]
             [(assoc-in response [:usage :input_tokens] "nine") :invalid-usage]
             [(assoc-in response [:answers :d0 :confidence] 1.1) :invalid-confidence]
             [(assoc-in response [:answers :d0 :probabilities :same_person] 1.1) :invalid-probability-values]]]
      (is (= reason (:error (jev/parse-answer request bad "d0"))) (pr-str bad)))))

(deftest request-rejects-unbounded-or-credential-shaped-evidence
  (let [base (decision "d0" :identity)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (jev/prepare-batches config [(assoc-in base [:candidates 0 :name]
                                                        (apply str (repeat 25000 "x")))])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (jev/prepare-batches config [(assoc-in base [:evidence 0 :api-key] "secret")])))))

(deftest raw-json-probability-precision-is-retained
  (let [request (first (jev/prepare-batches config [(decision "d0" :identity)]))
        raw "{\"model\":\"jev-1.13.0\",\"usage\":{},\"answers\":{\"d0\":{\"type\":\"choice\",\"choice\":\"different_person\",\"confidence\":0.9,\"probabilities\":{\"same_person\":0.123456789012345678901,\"different_person\":0.8,\"unknown\":0.076543210987654321099}}}}"
        parsed (jev/parse-answer request raw "d0")]
    (is (= :different-person (:outcome parsed)))
    (is (= 0.123456789012345678901M (get-in parsed [:raw-probabilities "same_person"])))
    (is (= (format "%064x" (java.math.BigInteger. 1
                                                  (.digest (java.security.MessageDigest/getInstance "SHA-256")
                                                           (.getBytes raw "UTF-8"))))
           (:result-hash parsed)))))

(defn -main [& _]
  (let [result (run-tests 'freediving.reconciliation-jev-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
