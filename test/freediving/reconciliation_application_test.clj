(ns freediving.reconciliation-application-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [freediving.athlete-identity :as identity]
            [freediving.reconciliation-application :as application]
            [freediving.reconciliation-flow :as flow]
            [freediving.reconciliation-policy :as policy]
            [freediving.reviews :as reviews]
            [freediving.source-relationships :as relationships]
            [freediving.source-relationships-jev-test :as attempt-fixture]))

(def config {:provider :jev :model "jev-test" :version "app-test/1"})
(defn decision [id family choices candidates]
  {:id id :family family :action (first choices) :choices choices
   :subject {:id id} :candidates candidates :dependencies []
   :evidence [{:evidence-id (str id "-evidence")
               :citation {:source-sha256 "synthetic" :locator "row 1"}
               :fact "Synthetic cited result"}]
   :evidence-adequate? true})

(deftest approved-identity-routes-and-row-semantics-remains-unresolved
  (let [identity-decision (decision "identity" :identity
                                    [:same-person :different-person :unknown] ["a" "b"])
        row-decision (decision "row" :row-semantics
                               [:attempt :aggregate :summary :not-result :unknown] ["row-1"])
        calls (atom [])
        execute! (fn [request]
                   {:model "jev-test" :usage {}
                    :answers (into {}
                                   (for [id (:decision-ids request)]
                                     [id (if (= id "identity")
                                           {:type "choice" :choice "same_person" :confidence 0.99
                                            :probabilities {"same_person" 0.98 "different_person" 0.01
                                                            "unknown" 0.01}}
                                           {:type "choice" :choice "attempt" :confidence 0.99
                                            :probabilities {"attempt" 0.98 "aggregate" 0.005
                                                            "summary" 0.005 "not_result" 0.005
                                                            "unknown" 0.005}})]))})
        opts {:config config :policy policy/default-policy :execute! execute!
              :persist-flow! (fn [_] true)
              :identity-url "synthetic-db" :reviewer-url "synthetic-review"
              :identity-revisions {"identity" 0}}]
    (with-redefs [identity/record-model-event! (fn [& args]
                                                 (swap! calls conj [:approve args])
                                                 {:accepted-group-count 1})
                  identity/sync-model-correction! (fn [& args]
                                                    (swap! calls conj [:reverse args])
                                                    {:accepted-group-count 0})]
      (let [first-run (application/run! (flow/empty-ledger)
                                        [identity-decision row-decision] opts)
            corrected (flow/append-human-event (:flow-ledger first-run)
                                               {:id "owner-no" :decision-id "identity"
                                                :status :rejected :reason "not same person"})
            second-run (application/run! corrected [identity-decision row-decision] opts)]
        (is (= :materialized (get-in first-run [:results "identity" :status])))
        (is (= :unresolved (get-in first-run [:results "row" :status])))
        (is (= :no-canonical-role-ledger (get-in first-run [:results "row" :reason])))
        (is (= :reversed (get-in second-run [:results "identity" :status])))
        (is (= [:approve :reverse] (mapv first @calls)))))))

(deftest attempt-approval-replays-and-human-correction-updates-count
  (let [base (attempt-fixture/partial-ledger)
        d (relationships/attempt-jev-decision base "attempt-1" :same-attempt ["v1" "v3"] {})
        saved (atom [])
        saved-attempts (atom [])
        opts {:config config :policy (assoc-in policy/default-policy
                                               [:thresholds :same-attempt :same-attempt]
                                               {:min-confidence 0.98 :min-probability 0.97 :min-margin 0.3})
              :persist-flow! #(swap! saved conj %)
              :execute! (fn [_]
                          {:model "jev-test" :usage {}
                           :answers {"attempt-1" {:type "choice" :choice "same_attempt"
                                                  :confidence 0.99
                                                  :probabilities {"same_attempt" 0.98
                                                                  "distinct_attempts" 0.01
                                                                  "unknown" 0.01}}}})
              :persist-attempt! #(swap! saved-attempts conj %)
              :attempt-ledger base}
        _ (is (thrown? clojure.lang.ExceptionInfo
                       (application/run! (flow/empty-ledger) [d]
                                         (dissoc opts :persist-attempt!))))
        first-run (application/run! (flow/empty-ledger) [d] opts)
        replay (application/run! (:flow-ledger first-run) [d]
                                 (assoc opts :attempt-ledger (:attempt-ledger first-run)))
        corrected (flow/append-human-event (:flow-ledger replay)
                                           {:id "owner-attempt-no" :decision-id (:id d)
                                            :status :rejected :reason "distinct dives"})
        correction (application/run! corrected [d]
                                     (assoc opts :attempt-ledger (:attempt-ledger replay)))
        strict-policy (assoc-in (assoc (:policy opts) :version "policy/2")
                                [:thresholds :same-attempt :same-attempt :min-confidence] 1.0)
        stale (application/run! (:flow-ledger first-run) [d]
                                (assoc opts :policy strict-policy
                                       :attempt-ledger (:attempt-ledger first-run)))]
    (is (= :applied (get-in first-run [:results "attempt-1" :status])))
    (is (= 1 (get-in (relationships/project-attempts (:attempt-ledger first-run))
                     [:counts :accepted-attempts])))
    (is (= :replayed (get-in replay [:results "attempt-1" :status])))
    (is (= :reversed (get-in correction [:results "attempt-1" :status])))
    (is (= 0 (get-in (relationships/project-attempts (:attempt-ledger correction))
                     [:counts :accepted-attempts])))
    (is (= :reversed (get-in stale [:results "attempt-1" :status])))
    (is (= 0 (get-in (relationships/project-attempts (:attempt-ledger stale))
                     [:counts :accepted-attempts])))
    (is (= 5 (count @saved)))
    (is (= 3 (count @saved-attempts)))))

(deftest field-classification-routes-and-both-meaning-stays-explicit
  (let [d (decision "field" :category-representation
                    [:category :representation :both :neither :unknown] [{:field "category"}])
        calls (atom [])
        target {:source-position-id "position-1" :job-id "job-1" :ordinal 0}
        dictionary {:version "dictionary/1" :federation "CMAS" :event-id "event-1"}
        answer (fn [choice]
                 {:model "jev-test" :usage {}
                  :answers {"field" {:type "choice" :choice choice :confidence 0.99
                                     :probabilities (if (= choice "both")
                                                      {"category" 0.005 "representation" 0.005
                                                       "both" 0.98 "neither" 0.005 "unknown" 0.005}
                                                      {"category" 0.98 "representation" 0.005
                                                       "both" 0.005 "neither" 0.005 "unknown" 0.005})}}})
        opts {:config config :policy policy/default-policy :persist-flow! (fn [_] true)
              :execute! (fn [_] (answer "category"))
              :field-url "synthetic-db" :reviewer-url "synthetic-review"
              :field-targets {"field" {:target target :decision-type :category
                                       :dictionary dictionary :base-revision 0}}}]
    (with-redefs [reviews/approve-jev-dive-field! (fn [& args]
                                                    (swap! calls conj [:approve args])
                                                    {:id "model-field"})
                  reviews/dive-fields (fn [& args]
                                        (swap! calls conj [:read args])
                                        {:revision 1})
                  reviews/sync-human-jev-dive-field! (fn [& args]
                                                       (swap! calls conj [:reverse args])
                                                       {:id "human-reverse"})
                  reviews/invalidate-jev-dive-field! (fn [& args]
                                                       (swap! calls conj [:invalidate args])
                                                       {:id "model-invalidate"})]
      (let [approved (application/run! (flow/empty-ledger) [d] opts)
            human (flow/append-human-event (:flow-ledger approved)
                                           {:id "owner-field-no" :decision-id "field"
                                            :origin :human :status :reversed
                                            :actor "owner" :reason "wrong column"})
            reversed (application/run! human [d] opts)
            both (application/run! (flow/empty-ledger) [d]
                                   (assoc opts :execute! (fn [_] (answer "both"))))
            stricter (assoc-in (assoc (:policy opts) :version "policy/2")
                               [:thresholds :category-representation :category :min-confidence] 1.0)
            stale (application/run! (:flow-ledger approved) [d]
                                    (assoc opts :policy stricter))]
        (is (= :materialized (get-in approved [:results "field" :status])))
        (is (= :reversed (get-in reversed [:results "field" :status])))
        (is (= [:approve :read :reverse :read :invalidate] (mapv first @calls)))
        (is (= :unresolved (get-in both [:results "field" :status])))
        (is (= :both-requires-separate-source-bound-fields
               (get-in both [:results "field" :reason])))
        (is (= :reversed (get-in stale [:results "field" :status])))
        (is (= :unresolved (:current-flow-status (nth (second (last @calls)) 3))))))))

(deftest child-before-parent-materializes-in-order-and-then-invalidates
  (let [parent (decision "parent" :identity
                         [:same-person :different-person :unknown] ["a" "b"])
        child (assoc (decision "child" :identity
                               [:same-person :different-person :unknown] ["c" "d"])
                     :dependencies ["parent"])
        calls (atom [])
        fail-parent? (atom false)
        opts {:config config :policy policy/default-policy
              :identity-url "synthetic-db" :reviewer-url "synthetic-review"
              :identity-revisions {"parent" 0 "child" 0}
              :persist-flow! (fn [_] true)
              :execute! (fn [request]
                          {:model "jev-test" :usage {}
                           :answers (into {}
                                          (for [id (:decision-ids request)]
                                            [id {:type "choice" :choice "same_person" :confidence 0.99
                                                 :probabilities {"same_person" 0.98
                                                                 "different_person" 0.01
                                                                 "unknown" 0.01}}]))})}]
    (with-redefs [identity/record-model-event! (fn [_ _ d & _]
                                                 (swap! calls conj [:approve (:id d)])
                                                 (when (and @fail-parent? (= "parent" (:id d)))
                                                   (throw (ex-info "parent unavailable" {})))
                                                 {:accepted-group-count 1})
                  identity/sync-model-correction! (fn [_ _ d & _]
                                                    (swap! calls conj [:human-reverse (:id d)])
                                                    {:accepted-group-count 0})
                  identity/private-projection (fn [_] {:revision 2})
                  identity/invalidate-model-dependency! (fn [_ _ d & _]
                                                          (swap! calls conj [:dependency-reverse (:id d)])
                                                          {:accepted-group-count 0})]
      (let [approved (application/run! (flow/empty-ledger) [child parent] opts)
            corrected (flow/append-human-event (:flow-ledger approved)
                                               {:id "owner-parent-no" :decision-id "parent"
                                                :status :rejected :reason "bad parent"})
            second-run (application/run! corrected [child parent] opts)
            _ (reset! fail-parent? true)
            canonical-failure (application/run! (:flow-ledger approved) [child parent] opts)]
        (is (= [[:approve "parent"] [:approve "child"]
                [:human-reverse "parent"] [:dependency-reverse "child"]
                [:approve "parent"] [:dependency-reverse "child"]]
               @calls))
        (is (= :reversed (get-in second-run [:results "child" :status])))
        (is (= :reversed (get-in canonical-failure [:results "child" :status])))))))

(deftest dispatch-is-durable-before-provider-call
  (let [d (decision "durable" :identity
                    [:same-person :different-person :unknown] ["a" "b"])
        order (atom [])
        _ (application/run! (flow/empty-ledger) [d]
                            {:config config :policy policy/default-policy
                             :persist-flow! (fn [_] (swap! order conj :durable))
                             :checkpoint! (fn [_] (swap! order conj :extra-checkpoint))
                             :execute! (fn [_]
                                         (swap! order conj :provider)
                                         {:model "jev-test" :usage {}
                                          :answers {"durable" {:type "choice" :choice "unknown"
                                                               :confidence 0.99
                                                               :probabilities {"same_person" 0.01
                                                                               "different_person" 0.01
                                                                               "unknown" 0.98}}}})})]
    (is (= [:durable :extra-checkpoint :provider :durable] @order))))

(defn -main [& _]
  (let [result (run-tests 'freediving.reconciliation-application-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))
