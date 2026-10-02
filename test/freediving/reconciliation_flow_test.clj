(ns freediving.reconciliation-flow-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [freediving.reconciliation-flow :as flow]))

(def policy {:version "test/1"
             :thresholds {:identity {:same-person {:min-confidence 0.9 :min-probability 0.9 :min-margin 0.2}}}})
(def config {:provider :jev :model "jev-1.13.0" :max-request-bytes 49152
             :native-batch-size 8})
(defn decision [id]
  {:id id :family :identity :action :same-person :choices [:same-person :different-person :unknown]
   :evidence [{:evidence-id (str id "-source") :citation {:source-sha256 "sha-test" :locator "row 1"} :exact-excerpt "Named result row"}]
   :candidates ["athlete:a" "athlete:b"] :dependencies [] :evidence-adequate? true})
(def answer {:outcome :same-person :confidence 0.96
             :probabilities {:same-person 0.94 :different-person 0.04 :unknown 0.02}
             :model-version "jev-1.13.0"})

(deftest retained-deterministic-and-jev-decisions-replay-without-http
  (let [calls (atom 0)
        execute! (fn [request] (swap! calls inc)
                   {:model "jev-1.13.0" :usage {} :answers (zipmap (:decision-ids request) (repeat {:type "choice" :choice "same_person" :confidence 0.96 :probabilities {"same_person" 0.94 "different_person" 0.04 "unknown" 0.02}}))})
        opts {:config config :policy policy :execute! execute!
              :deterministic-results {"rule" {:status :approve :rule-version "rule/1"}}}
        decisions [(decision "rule") (decision "model")]
        first-run (flow/run! (flow/empty-ledger) decisions opts)
        rerun (flow/run! first-run decisions opts)]
    (is (= 1 @calls))
    (is (= (:events first-run) (:events rerun)))
    (is (= :approved (get-in (flow/inspect rerun decisions) ["model" :status])))
    (is (= :deterministic (get-in (flow/inspect rerun decisions) ["rule" :origin])))))

(deftest changed-evidence-invalidates-cached-approval
  (let [calls (atom 0)
        execute! (fn [request] (swap! calls inc)
                   {:model "jev-1.13.0" :usage {} :answers (zipmap (:decision-ids request) (repeat {:type "choice" :choice "same_person" :confidence 0.96 :probabilities {"same_person" 0.94 "different_person" 0.04 "unknown" 0.02}}))})
        opts {:config config :policy policy :execute! execute!}
        initial [(decision "model")]
        changed [(assoc-in (decision "model") [:evidence 0 :exact-excerpt] "Changed row")]
        ledger (flow/run! (flow/empty-ledger) initial opts)
        rerun (flow/run! ledger changed opts)]
    (is (= 2 @calls))
    (is (= 2 (count (:events rerun))))
    (is (= :approved (get-in (flow/inspect rerun changed) ["model" :status])))))

(deftest dependency-and-human-override-control-dispatch
  (let [calls (atom [])
        execute! (fn [request]
                   (swap! calls conj (:decision-ids request))
                   {:model "jev-1.13.0" :usage {} :answers (zipmap (:decision-ids request) (repeat {:type "choice" :choice "same_person" :confidence 0.96 :probabilities {"same_person" 0.94 "different_person" 0.04 "unknown" 0.02}}))})
        decisions [(decision "first")
                   (assoc (decision "second") :dependencies ["first"])]
        blocked (flow/append-human-event (flow/empty-ledger)
                                         {:id "human-1" :decision-id "first"
                                          :status :rejected :reason "source conflict"})
        result (flow/run! blocked decisions {:config config :policy policy :execute! execute!})]
    (is (empty? @calls))
    (is (= :human (get-in (flow/inspect result decisions) ["first" :origin])))
    (is (= :dependency-blocked (get-in (flow/inspect result decisions) ["second" :status])))))

(deftest timeout-is-explicit-and-retryable
  (let [calls (atom 0)
        execute! (fn [_] (swap! calls inc) (throw (ex-info "timeout" {:error :timeout})))
        opts {:config config :policy policy :execute! execute!}
        decisions [(decision "model")]
        first-run (flow/run! (flow/empty-ledger) decisions opts)
        rerun (flow/run! first-run decisions opts)]
    (is (= 2 @calls))
    (is (= :timeout (get-in (flow/inspect rerun decisions) ["model" :status])))))

(deftest independent-decisions-batch-and-dependent-questions-follow
  (let [requests (atom [])
        execute! (fn [request]
                   (swap! requests conj (:decision-ids request))
                   {:model "jev-1.13.0" :usage {}
                    :answers (zipmap (:decision-ids request)
                                     (repeat {:type "choice" :choice "same_person" :confidence 0.96
                                              :probabilities {"same_person" 0.94 "different_person" 0.04 "unknown" 0.02}}))})
        decisions [(decision "a") (decision "b")
                   (assoc (decision "c") :dependencies ["a"])]
        result (flow/run! (flow/empty-ledger) decisions
                          {:config config :policy policy :execute! execute!})]
    (is (= [["a" "b"] ["c"]] @requests))
    (is (every? #(= :approved (:status %))
                (vals (flow/inspect result decisions))))))

(deftest policy-reassessment-reuses-retained-answer
  (let [calls (atom 0)
        execute! (fn [request]
                   (swap! calls inc)
                   {:model "jev-1.13.0" :usage {}
                    :answers (zipmap (:decision-ids request)
                                     (repeat {:type "choice" :choice "same_person" :confidence 0.96
                                              :probabilities {"same_person" 0.94 "different_person" 0.04 "unknown" 0.02}}))})
        decisions [(decision "a")]
        initial (flow/run! (flow/empty-ledger) decisions
                           {:config config :policy policy :execute! execute!})
        stricter (assoc-in (assoc policy :version "test/2")
                           [:thresholds :identity :same-person :min-confidence] 0.99)
        reassessed (flow/run! initial decisions
                              {:config config :policy stricter :execute! execute!})]
    (is (= 1 @calls))
    (is (= :unresolved (get-in (flow/inspect reassessed decisions) ["a" :status])))
    (is (= "test/2" (get-in (flow/inspect reassessed decisions) ["a" :policy-version])))))

(deftest stale-retained-answer-cannot-bypass-dispatch
  (let [calls (atom 0)
        execute! (fn [request]
                   (swap! calls inc)
                   {:model "jev-1.13.0" :usage {}
                    :answers (zipmap (:decision-ids request)
                                     (repeat {:type "choice" :choice "same_person" :confidence 0.96
                                              :probabilities {"same_person" 0.94 "different_person" 0.04 "unknown" 0.02}}))})
        decisions [(decision "a")]
        stale (assoc answer :decision-key "old-evidence" :template-version "reconciliation-jev/1")
        result (flow/run! (flow/empty-ledger) decisions
                          {:config config :policy policy :execute! execute!
                           :retained-answers {"a" stale}})]
    (is (= 1 @calls))
    (is (= :jev (get-in (flow/inspect result decisions) ["a" :origin])))))

(defn -main [& _]
  (let [result (run-tests 'freediving.reconciliation-flow-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))
