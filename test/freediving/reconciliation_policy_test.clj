(ns freediving.reconciliation-policy-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [freediving.reconciliation-policy :as policy]))

(def choices
  {:identity [:same-person :different-person :unknown]
   :same-attempt [:same-attempt :distinct-attempts :unknown]
   :source-revision [:left-revises-right :right-revises-left :same-source :unrelated :unknown]
   :category-representation [:category :representation :both :neither :unknown]
   :row-semantics [:attempt :aggregate :summary :not-result :unknown]})

(defn decision [family action]
  {:family family :action action :evidence-adequate? true :conflicts []})

(defn answer [family action]
  (let [other (remove #{action} (choices family))
        small (/ 0.01 (count other))]
    {:outcome action :confidence 0.98
     :probabilities (assoc (zipmap other (repeat small)) action 0.99)}))

(deftest qualifying-answers-can-approve-from-first-run
  (doseq [[family options] choices
          action (remove #{:unknown} options)]
    (is (= :approve (:status (policy/assess policy/default-policy
                                            (decision family action)
                                            (answer family action)))))))

(deftest policy-is-versioned-configurable-and-action-specific
  (let [strict (assoc-in policy/default-policy
                         [:thresholds :identity :same-person :min-confidence] 0.99)
        positive (decision :identity :same-person)
        negative (decision :identity :different-person)]
    (is (= :low-confidence (:reason (policy/assess strict positive
                                                   (answer :identity :same-person)))))
    (is (= :approve (:status (policy/assess strict negative
                                            (answer :identity :different-person)))))
    (is (= "reconciliation-approval-v1"
           (:policy-version (policy/assess strict negative
                                           (answer :identity :different-person)))))
    (is (= :unsupported-action
           (:reason (policy/assess strict (assoc negative :action :invented)
                                   (answer :identity :different-person)))))))

(deftest malformed-or-uncertain-answers-remain-unresolved
  (let [base (decision :same-attempt :same-attempt)
        good (answer :same-attempt :same-attempt)
        failures [[(assoc good :confidence 0.97) :low-confidence]
                  [(assoc-in good [:probabilities :same-attempt] 0.90) :invalid-answer]
                  [(assoc good :probabilities {:same-attempt 0.70
                                               :distinct-attempts 0.29 :unknown 0.01}) :low-probability]
                  [(assoc good :probabilities {:same-attempt 0.99
                                               :distinct-attempts 0.01}) :invalid-answer]
                  [(assoc-in good [:probabilities :same-attempt] ##NaN) :invalid-answer]
                  [(assoc good :outcome :unknown) :unknown-answer]
                  [(assoc good :outcome :distinct-attempts) :unexpected-answer]
                  [(assoc good :error :timeout) :provider-error]
                  [(assoc good :stale? true) :stale-evidence]]]
    (doseq [[candidate expected] failures]
      (is (= expected (:reason (policy/assess policy/default-policy base candidate)))))))

(deftest alternative-margin-and-evidence-are-independent-gates
  (let [thresholds {:min-confidence 0.8 :min-probability 0.5 :min-margin 0.2}
        custom (assoc-in policy/default-policy [:thresholds :row-semantics :attempt] thresholds)
        close {:outcome :attempt :confidence 0.9
               :probabilities {:attempt 0.55 :aggregate 0.44 :summary 0.005
                               :not-result 0.004 :unknown 0.001}}
        base (decision :row-semantics :attempt)
        good (answer :row-semantics :attempt)]
    (is (= :close-alternative (:reason (policy/assess custom base close))))
    (is (= :inadequate-evidence (:reason (policy/assess custom
                                                        (assoc base :evidence-adequate? false) good))))
    (is (= :conflicting-evidence (:reason (policy/assess custom
                                                         (assoc base :conflicts [:source-disagrees]) good))))
    (is (= :stale-evidence (:reason (policy/assess custom
                                                   (assoc base :stale? true) good))))
    (is (= :invalid-policy (:reason (policy/assess (assoc custom :version nil) base good))))))

(deftest pending-review-orders-low-scores-and-keeps-errors-visible
  (let [entries [{:id :high :confidence 0.9}
                 {:id :error :error :timeout}
                 {:id :low :answer {:confidence 0.2}}
                 {:id :low-tie :confidence 0.2}
                 {:id :gap :reason :missing-evidence}]
        queue (policy/review-queue entries)]
    (is (= [:low :low-tie :high :error :gap] (mapv :id queue)))
    (is (= [:timeout :missing-evidence]
           (mapv :scoreless-reason (take-last 2 queue))))))

(defn -main [& _]
  (let [result (run-tests 'freediving.reconciliation-policy-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))
