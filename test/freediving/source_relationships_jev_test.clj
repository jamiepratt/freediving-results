(ns freediving.source-relationships-jev-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [freediving.reconciliation-flow :as flow]
            [freediving.source-relationships :as relationships]
            [freediving.source-relationships-test :as fixture]))

(def config {:provider :jev :model "jev-test" :version "test-config/1"})
(def policy {:version "test-policy/1"
             :thresholds {:same-attempt {:same-attempt {:min-confidence 0.98
                                                        :min-probability 0.97
                                                        :min-margin 0.3}}}})

(defn partial-ledger []
  (let [base (update (fixture/attempt-fixture) :observation-versions dissoc "v2")]
    (reduce (fn [ledger id]
              (-> ledger
                  (update-in [:observation-versions id :scope] dissoc :attempt)
                  (update-in [:observation-versions id :scope-evidence :fields] dissoc :attempt)
                  (update-in [:observation-versions id :scope-evidence :bindings] dissoc :attempt)))
            base ["v1" "v3"])))

(defn subject [ledger id]
  (let [version (get-in ledger [:observation-versions id])
        position (get-in ledger [:positions (:position-id version)])]
    {:version version :position position
     :source (get-in ledger [:sources (:source-id position)])}))

(defn decision [ledger]
  (relationships/attempt-jev-decision
   ledger "jev-attempt" :same-attempt ["v1" "v3"] {}))

(defn approved-flow [decision]
  (flow/run! (flow/empty-ledger) [decision]
             {:config config :policy policy
              :execute! (fn [_] {:model "jev-test" :usage {}
                                 :answers {"jev-attempt" {:type "choice" :choice "same_attempt"
                                                          :confidence 0.99
                                                          :probabilities {"same_attempt" 0.98
                                                                          "distinct_attempts" 0.01
                                                                          "unknown" 0.01}}}})}))

(deftest approved-model-answer-groups-cited-partial-rows-and-reverses
  (let [base (partial-ledger)
        d (decision base)
        result (relationships/apply-approved-jev-attempt-decision
                base (approved-flow d) d config policy 0)
        accepted (relationships/project-attempts (:ledger result))
        reversed (relationships/append-attempt-event (:ledger result)
                                                     {:id "undo" :action :reverse
                                                      :event-id (-> result :event :id)})]
    (is (= 0 (get-in (relationships/project-attempts base) [:counts :accepted-attempts])))
    (is (= :applied (:status result)))
    (is (= 1 (get-in accepted [:counts :accepted-attempts])))
    (is (= :model-supported (-> accepted :attempts first :origin)))
    (is (= 0 (get-in (relationships/project-attempts reversed) [:counts :accepted-attempts])))))

(deftest model-approval-replays-but-changed-evidence-and-human-correction-block
  (let [base (partial-ledger)
        d (decision base)
        run (approved-flow d)
        first-pass (relationships/apply-approved-jev-attempt-decision base run d config policy 0)
        replay (relationships/apply-approved-jev-attempt-decision
                (:ledger first-pass) run d config policy 1)
        changed (assoc-in d [:evidence 0 :fact] "Changed source reading")
        human (flow/append-human-event run {:id "owner-reject" :decision-id (:id d)
                                            :status :rejected})]
    (is (= :replayed (:status replay)))
    (is (= (:ledger first-pass) (:ledger replay)))
    (is (= :unresolved (:status (relationships/apply-approved-jev-attempt-decision
                                 base run changed config policy 0))))
    (is (= :unresolved (:status (relationships/apply-approved-jev-attempt-decision
                                 base human d config policy 0))))))

(deftest changed-source-invalidates-model-count
  (let [base (partial-ledger)
        d (decision base)
        accepted (:ledger (relationships/apply-approved-jev-attempt-decision
                           base (approved-flow d) d config policy 0))
        replacement {:sources (mapv #(if (= "mirror" (:id %)) (assoc % :sha256 "changed") %)
                                    (vals (:sources base)))
                     :positions (vec (vals (:positions base)))
                     :observation-versions (mapv #(if (= "v3" (:id %))
                                                    (assoc-in % [:scope-evidence :source-sha256] "changed") %)
                                                 (vals (:observation-versions base)))}
        rebased (relationships/rebase-attempt-ledger accepted replacement)]
    (is (= 1 (get-in (relationships/project-attempts accepted) [:counts :accepted-attempts])))
    (is (= 0 (get-in (relationships/project-attempts rebased) [:counts :accepted-attempts])))))

(deftest high-score-cannot-turn-aggregate-or-conflicting-row-into-a-dive
  (let [base (partial-ledger)
        ranking (assoc-in base [:observation-versions "v3" :role] :ranking)
        different-day (-> base
                          (assoc-in [:observation-versions "v3" :scope :day] "2026-06-02")
                          (assoc-in [:observation-versions "v3" :scope-evidence :fields :day]
                                    "2026-06-02")
                          (assoc-in [:observation-versions "v3" :scope-evidence :bindings :day :value]
                                    "2026-06-02")
                          (assoc-in [:observation-versions "v3" :values :raw :day]
                                    "2026-06-02"))]
    (doseq [ledger [ranking different-day]]
      (let [d (decision ledger)
            result (relationships/apply-approved-jev-attempt-decision
                    ledger (approved-flow d) d config policy 0)]
        (is (= :unresolved (:status result)))
        (is (= 0 (get-in (relationships/project-attempts (:ledger result))
                         [:counts :accepted-attempts])))))))

(deftest public-append-rejects-forged-model-approval
  (let [base (partial-ledger)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (relationships/append-attempt-event
                  base {:id "forged" :action :accept :type :same-attempt
                        :pair ["v1" "v3"]
                        :evidence {:kind :jev-source-bound :decision-id "fake"
                                   :result-hash "fake" :receipt {}}})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (relationships/append-attempt-event
                  base {:id "forged-revision" :action :accept :type :source-revision
                        :pair ["official" "mirror"]
                        :evidence {:kind :publisher-correction
                                   :predecessor "official" :successor "mirror"
                                   :citation {:source-id "mirror" :locator "header"
                                              :text "Publisher correction notice"}
                                   :jev {:decision-id "fake"}}})))))

(deftest approved-model-event-replays-from-retained-evidence
  (let [base (partial-ledger)
        d (decision base)
        flow-ledger (flow/append-human-event
                     (approved-flow d)
                     {:id "unrelated-human" :decision-id "other" :status :rejected})
        accepted (:ledger (relationships/apply-approved-jev-attempt-decision
                           base flow-ledger d config policy 0))
        fresh (relationships/empty-attempt-ledger
               {:sources (vals (:sources base))
                :positions (vals (:positions base))
                :observation-versions (vals (:observation-versions base))})
        replayed (reduce relationships/append-attempt-event fresh (:events accepted))]
    (is (= (relationships/project-attempts accepted)
           (relationships/project-attempts replayed)))
    (is (= 1 (count (get-in accepted [:events 0 :approval-proof :flow-ledger :events]))))
    (is (= 1 (get-in (relationships/project-attempts replayed)
                     [:counts :accepted-attempts])))))

(deftest dependency-proof-retains-current-owner-event
  (let [base (partial-ledger)
        parent (relationships/attempt-jev-decision
                base "parent" :same-attempt ["v1" "v3"] {})
        child (assoc (decision base) :dependencies ["parent"])
        initial (flow/append-human-event
                 (flow/empty-ledger)
                 {:id "owner-parent" :decision-id "parent"
                  :status :approved :action :same-attempt})
        run (flow/run! initial [parent child]
                       {:config config :policy policy
                        :execute! (fn [_] {:model "jev-test" :usage {}
                                           :answers {"jev-attempt"
                                                     {:type "choice" :choice "same_attempt"
                                                      :confidence 0.99
                                                      :probabilities {"same_attempt" 0.98
                                                                      "distinct_attempts" 0.01
                                                                      "unknown" 0.01}}}})})
        accepted (relationships/apply-approved-jev-attempt-decision
                  base run child config policy 0 [parent child])
        corrected (flow/append-human-event run {:id "owner-parent-no"
                                                :decision-id "parent" :status :rejected})]
    (is (= :applied (:status accepted)))
    (is (= "owner-parent"
           (get-in accepted [:event :approval-proof :event-ids "parent"])))
    (is (= 2 (count (get-in accepted [:event :approval-proof :flow-ledger :events]))))
    (is (= :unresolved (:status (relationships/apply-approved-jev-attempt-decision
                                 base corrected child config policy 0 [parent child]))))))

(deftest publisher-cited-model-revision-records-direction-without-changing-count
  (let [base (fixture/attempt-fixture)
        publisher {:kind :publisher-correction :predecessor "official" :successor "mirror"
                   :citation {:source-id "mirror" :locator "header"
                              :text "Publisher correction notice"}}
        d (relationships/attempt-jev-decision
           base "source-revision" :source-revision ["official" "mirror"]
           {:publisher-evidence publisher})
        p {:version "source-policy/1"
           :thresholds {:source-revision
                        {:right-revises-left {:min-confidence 0.98
                                              :min-probability 0.97 :min-margin 0.3}}}}
        run (flow/run! (flow/empty-ledger) [d]
                       {:config config :policy p
                        :execute! (fn [_] {:model "jev-test" :usage {}
                                           :answers {"source-revision"
                                                     {:type "choice" :choice "right_revises_left"
                                                      :confidence 0.99
                                                      :probabilities {"left_revises_right" 0.005
                                                                      "right_revises_left" 0.98
                                                                      "same_source" 0.005
                                                                      "unrelated" 0.005
                                                                      "unknown" 0.005}}}})})
        result (relationships/apply-approved-jev-attempt-decision base run d config p 0)]
    (is (= :applied (:status result)))
    (is (= 1 (count (get (relationships/project-attempts (:ledger result))
                         :source-relationships))))
    (is (= (get-in (relationships/project-attempts base) [:counts :accepted-attempts])
           (get-in (relationships/project-attempts (:ledger result)) [:counts :accepted-attempts])))))

(defn -main []
  (let [result (run-tests 'freediving.source-relationships-jev-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
