(ns freediving.athlete-identity-db-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [freediving.athlete-identity :as identity]
            [freediving.observations :as observations]
            [freediving.observations-test :as fixture]
            [freediving.reconciliation-flow :as flow]
            [freediving.reviews :as reviews]))

(def admin (System/getenv "FREEDIVING_TEST_ADMIN_URL"))
(def app (System/getenv "FREEDIVING_TEST_URL"))
(def reviewer (System/getenv "FREEDIVING_TEST_REVIEW_URL"))
(use-fixtures :each
  (fn [f]
    (when-not (and admin app reviewer)
      (throw (ex-info "Run scripts/test-postgres.sh test-athlete-identity-db" {})))
    (fixture/sql! admin "DROP SCHEMA IF EXISTS freediving CASCADE")
    (observations/migrate! admin "observations_app")
    (reviews/migrate! admin "observations_app" "reviews_owner")
    (f)))

(deftest private-canonical-view-follows-links-and-recovers-from-new-evidence
  (let [source (fixture/synthetic 1 "canonical-view/1")
        job (get-in source [:artifact :job-id])
        a (str "local-observation:" job ":0")
        b (str "local-observation:" job ":1")]
    (fixture/publish! source)
    (observations/import! app (:root source) job)
    (is (= 2 (:provisional-record-count
              (identity/rebuild-private-canonical-view! reviewer))))
    (is (= 1 (:accepted-group-count
              (identity/record-event! reviewer
                                      {:id "canonical-link" :action :accept :actor-kind :human
                                       :pair [a b] :reason "synthetic cited link"}))))
    (is (= 1 (:accepted-group-count (identity/private-canonical-view app))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (identity/record-event! reviewer
                                         {:id "canonical-link" :action :reject :actor-kind :human
                                          :pair [a b] :reason "conflicting replay"})))
    (is (= 1 (:accepted-group-count (identity/private-canonical-view app))))
    (is (= 0 (:accepted-group-count
              (identity/record-event! reviewer
                                      {:id "canonical-reverse" :action :reverse :actor-kind :human
                                       :event-id "canonical-link" :reason "synthetic split"}))))
    (is (= 0 (:accepted-group-count (identity/private-canonical-view app))))
    (is (= [{:pair [a b] :event-id "canonical-reverse" :actor-kind :human
             :reason "synthetic split"}]
           (:negative-pairs (identity/private-canonical-view app))))
    (is (= (:negative-pairs (identity/private-canonical-view app))
           (:negative-pairs (identity/rebuild-private-canonical-view! reviewer))))
    (is (= #{a b} (set (keys (:athletes (identity/private-canonical-view app))))))
    (is (= #{"Éxample"}
           (set (map :source-name (vals (:athletes (identity/private-canonical-view app)))))))
    (let [later (fixture/synthetic 1 "canonical-view/2")]
      (fixture/publish! later)
      (observations/import! app (:root later) (get-in later [:artifact :job-id]))
      (is (thrown? clojure.lang.ExceptionInfo (identity/private-canonical-view app)))
      (is (= 4 (:provisional-record-count
                (identity/rebuild-private-canonical-view! reviewer))))
      (is (= 0 (:accepted-group-count (identity/private-canonical-view app)))))))

(deftest persisted-links-reverse-with-stable-provisional-records
  (let [{:keys [root artifact] :as source} (fixture/synthetic 1 "identity-db/1")
        job (:job-id artifact)
        a (str "local-observation:" job ":0")
        b (str "local-observation:" job ":1")
        link {:id "identity-link" :action :accept :actor-kind :human :pair [a b] :reason "synthetic evidence"}
        reverse {:id "identity-reverse" :action :reverse :actor-kind :human
                 :event-id "identity-link" :reason "synthetic split"}]
    (fixture/publish! source)
    (observations/import! app root job)
    (is (= 2 (:provisional-record-count (identity/private-projection app))))
    (is (= 1 (:accepted-group-count (identity/record-event! reviewer link))))
    (is (= 1 (:accepted-group-count (identity/record-event! reviewer link))))
    (is (= 0 (:accepted-group-count (identity/record-event! reviewer reverse))))
    (is (= 0 (:accepted-group-count (identity/record-event! reviewer reverse))))
    (is (= 0 (:accepted-group-count (identity/private-projection app))))
    (is (= ["identity-link" "identity-reverse"]
           (mapv :id (identity/private-history app))))
    (is (= "reviews_owner" (:db-role (first (identity/private-history app)))))
    (is (= #{a b} (set (map :observation-id
                            (get-in (first (identity/private-history app)) [:evidence :observations])))))
    (is (= #{(str "athlete:" a) (str "athlete:" b)}
           (set (map :provisional-id (vals (:athletes (identity/private-projection app)))))))
    (is (thrown? Exception
                 (identity/record-event! app {:id "forged-human" :action :accept :actor-kind :human
                                              :pair [a b] :reason "wrong role"})))
    (is (thrown? Exception
                 (fixture/sql! app "DELETE FROM freediving.athlete_identity_events")))))

(deftest automatic-link-sees-competing-retained-observations
  (let [rename (fn [source]
                 (update-in source [:artifact :candidates]
                            (fn [rows] (mapv #(-> %
                                                  (assoc-in [:parsed :source-name] "Distinctive Diver")
                                                  (assoc-in [:raw :fields :source-name] "Distinctive Diver")) rows))))
        source1 (rename (fixture/synthetic 1 "identity-compete/1"))
        source2 (rename (fixture/synthetic 1 "identity-compete/2"))
        first-job (get-in source1 [:artifact :job-id])
        a (str "local-observation:" first-job ":0")
        b (str "local-observation:" first-job ":1")]
    (doseq [source [source1 source2]]
      (fixture/publish! source)
      (observations/import! app (:root source) (get-in source [:artifact :job-id])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (identity/record-event! app {:id "unsafe-auto" :action :accept :actor-kind :automatic
                                              :pair [a b] :rule-version identity/rule-version})))
    (is (= 0 (:revision (identity/private-projection app))))))

(deftest automatic-link-accepts-one-complete-distinctive-candidate
  (let [source (update-in (fixture/synthetic 1 "identity-unique/1") [:artifact :candidates]
                          (fn [rows] (mapv #(-> %
                                                (assoc-in [:parsed :source-name] "Distinctive Diver")
                                                (assoc-in [:raw :fields :source-name] "Distinctive Diver")) rows)))
        job (get-in source [:artifact :job-id])
        a (str "local-observation:" job ":0")
        b (str "local-observation:" job ":1")]
    (fixture/publish! source)
    (observations/import! app (:root source) job)
    (is (= 1 (:accepted-group-count
              (identity/record-event! app {:id "safe-auto" :action :accept :actor-kind :automatic
                                           :pair [a b] :rule-version identity/rule-version}))))
    (is (= [:automatic] (get-in (identity/private-projection app) [:athletes a :decision-origin])))
    (is (= :distinctive-exact-name (get-in (first (identity/private-history app)) [:decision :reason])))
    (is (= 1 (get-in (first (identity/private-history app)) [:evidence :candidate-count])))))

(deftest model-link-records-canonical-group-without-human-attestation
  (let [{:keys [root artifact] :as source} (fixture/synthetic 1 "identity-model/1")
        job (:job-id artifact)
        a (str "local-observation:" job ":0")
        b (str "local-observation:" job ":1")
        config {:provider :jev :model "jev-1.13.0" :version "test-config/1"}
        policy {:version "test-policy/1"
                :thresholds {:identity {:same-person {:min-confidence 0.9
                                                      :min-probability 0.9 :min-margin 0.2}}}}]
    (fixture/publish! source)
    (observations/import! app root job)
    (let [decision (identity/private-jev-decision app a b)
          flow-ledger (flow/run! (flow/empty-ledger) [decision]
                                 {:config config :policy policy
                                  :execute! (fn [_]
                                              {:model "jev-1.13.0" :usage {}
                                               :answers {(:id decision)
                                                         {:type "choice" :choice "same_person" :confidence 0.96
                                                          :probabilities {"same_person" 0.94
                                                                          "different_person" 0.04 "unknown" 0.02}}}})})]
      (is (= 1 (:accepted-group-count
                (identity/record-model-event! app flow-ledger decision config policy 0))))
      (is (= 1 (:accepted-group-count
                (identity/record-model-event! app flow-ledger decision config policy 0))))
      (is (= 1 (:accepted-group-count
                (identity/record-model-event! app flow-ledger decision config policy 1))))
      (is (= [:model] (get-in (identity/private-projection app) [:athletes a :decision-origin])))
      (is (= :model (:actor-kind (first (identity/private-history app)))))
      (is (thrown? Exception
                   (identity/record-model-event! reviewer flow-ledger decision config policy 0)))
      (let [corrected (flow/append-human-event flow-ledger
                                               {:id "owner-correction" :decision-id (:id decision)
                                                :status :rejected :reason "different people"})]
        (is (= 0 (:accepted-group-count
                  (identity/sync-model-correction! reviewer corrected decision config))))
        (is (= 0 (:accepted-group-count
                  (identity/sync-model-correction! reviewer corrected decision config))))
        (is (= 2 (count (identity/private-history app))))))))

(deftest failed-flow-dependency-reverses-persisted-model-identity
  (let [{:keys [root artifact] :as source} (fixture/synthetic 1 "identity-dependency/1")
        job (:job-id artifact)
        a (str "local-observation:" job ":0")
        b (str "local-observation:" job ":1")
        config {:provider :jev :model "jev-1.13.0" :version "test-config/1"}
        policy {:version "test-policy/1"
                :thresholds {:identity {:same-person {:min-confidence 0.9
                                                      :min-probability 0.9 :min-margin 0.2}}}}]
    (fixture/publish! source)
    (observations/import! app root job)
    (let [base (identity/private-jev-decision app a b)
          parent (assoc base :id "parent" :dependencies [])
          child (identity/private-jev-decision app a b {:id "child" :dependencies ["parent"]})
          execute! (fn [_] {:model "jev-1.13.0" :usage {}
                            :answers {"child" {:type "choice" :choice "same_person" :confidence 0.96
                                               :probabilities {"same_person" 0.94
                                                               "different_person" 0.04 "unknown" 0.02}}}})
          approved (flow/run! (flow/empty-ledger) [parent child]
                              {:config config :policy policy :execute! execute!
                               :deterministic-results {"parent" {:status :approve :rule-version "rule/1"}}})
          _ (identity/record-model-event! app approved child config policy 0)
          corrected (flow/append-human-event approved
                                             {:id "parent-no" :decision-id "parent"
                                              :status :rejected :reason "bad parent"})
          failed (flow/run! corrected [parent child]
                            {:config config :policy policy :execute! execute!})]
      (is (= 1 (:accepted-group-count (identity/private-projection app))))
      (is (= 0 (:accepted-group-count
                (identity/invalidate-model-dependency! app failed child 1))))
      (is (= 0 (:accepted-group-count
                (identity/invalidate-model-dependency! app failed child 1)))))))

(defn -main [& _]
  (let [result (clojure.test/run-tests 'freediving.athlete-identity-db-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))
