(ns freediving.athlete-identity-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [freediving.athlete-identity :as identity]
            [freediving.reconciliation-flow :as flow]))

(defn athlete [id name & {:as opts}]
  (merge {:observation-id id :source-name name :parse-status :parsed
          :context {:event-id (str "event-" id) :sex :women}
          :citation {:job-id id :ordinal 0}}
         opts))

(deftest indexed-routes-and-complete-accounting
  (let [rows [(athlete "a" "Li Wei" :publisher-scope "CMAS" :publisher-athlete-id "person-1"
                       :publisher-id-kind :person
                       :aliases [{:name "李伟" :origin :publisher :citation {:page 1}}])
              (athlete "b" "李伟")
              (athlete "c" "Other Person" :publisher-scope "CMAS" :publisher-athlete-id "person-1"
                       :publisher-id-kind :person)
              (athlete "d" "Li Wei" :publisher-scope "AIDA" :publisher-athlete-id "person-1"
                       :publisher-id-kind :person)]
        index (identity/build-index rows)
        result (identity/retrieve index (athlete "target" "李伟" :publisher-scope "CMAS"
                                                 :publisher-athlete-id "person-1" :publisher-id-kind :person))]
    (is (= #{"a" "b" "c" "d"} (set (map :observation-id (:candidates result)))))
    (is (= 4 (:candidate-count result)))
    (is (empty? (:omitted result)))
    (is (every? (set (mapcat :routes (:candidates result))) [:publisher-id :native-name :romanization]))
    (is (some #(= :publisher (:origin %)) (get-in result [:candidates 0 :aliases])))))

(deftest exact-name-policy-abstains-on-collisions-and-damage
  (let [a (athlete "a" "Zsófia Törőcsik")
        b (athlete "b" "Törőcsik Zsofia")
        index (identity/build-index [a b])]
    (is (= :approve (:status (identity/decide (identity/build-index [a]) (athlete "target" "Zsofia Torocsik") {}))))
    (is (= :unresolved (:status (identity/decide (identity/build-index [a b (athlete "c" "Zsófia Törőcsik")])
                                                 (athlete "target" "Zsofia Torocsik") {}))))
    (is (= :unresolved (:status (identity/decide index (athlete "target" "Amy Smith")
                                                 {:ambiguous-names #{"amy smith"}}))))
    (is (= :unresolved (:status (identity/decide index (athlete "target" "Zsofia Torocsik" :parse-status :damaged) {}))))))

(deftest reversible-groups-and-human-suppression
  (let [rows [(athlete "a" "A Person") (athlete "b" "B Person") (athlete "c" "C Person")]
        e1 {:id "one" :action :accept :actor-kind :automatic :pair ["a" "b"] :rule-version identity/rule-version}
        e2 {:id "two" :action :accept :actor-kind :automatic :pair ["b" "c"] :rule-version identity/rule-version}
        ledger (-> (identity/empty-ledger rows) (identity/append-event e1) (identity/append-event e2))
        split (identity/append-event ledger {:id "split" :action :reverse :actor-kind :human :event-id "two" :reason "different people"})]
    (is (= 1 (:accepted-group-count (identity/project ledger))))
    (is (= 1 (:accepted-group-count (identity/project split))))
    (is (= 2 (count (:groups (identity/project split)))))
    (is (= "athlete:a" (get-in (identity/project split) [:athletes "a" :group-id])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (identity/append-event split (assoc e2 :id "retry"))))
    (is (= (identity/project split) (identity/project (identity/replay rows (:events split)))))))

(deftest human-negative-identity-survives-rebuild-and-human-supersession
  (let [rows [(athlete "a" "A Person") (athlete "b" "B Person")]
        rejected (identity/append-event (identity/empty-ledger rows)
                                        {:id "owner-reject" :action :reject :actor-kind :human
                                         :pair ["a" "b"] :reason "distinct people"})
        expected [{:pair ["a" "b"] :event-id "owner-reject" :actor-kind :human
                   :reason "distinct people"}]]
    (is (= expected (:negative-pairs (identity/project rejected))))
    (is (= expected (:negative-pairs (identity/project
                                      (identity/replay rows (:events rejected))))))
    (let [corrected (identity/append-event rejected
                                           {:id "owner-correct" :action :accept :actor-kind :human
                                            :pair ["a" "b"] :supersedes "owner-reject"
                                            :reason "new evidence"})]
      (is (= [] (:negative-pairs (identity/project corrected))))
      (is (= 1 (:accepted-group-count (identity/project corrected)))))))

(deftest human-rejection-cannot-contradict-an-active-identity-group
  (let [rows [(athlete "a" "A Person") (athlete "b" "B Person")]
        linked (identity/append-event (identity/empty-ledger rows)
                                      {:id "owner-link" :action :accept :actor-kind :human
                                       :pair ["a" "b"] :reason "initial evidence"})
        reject {:id "owner-reject" :action :reject :actor-kind :human
                :pair ["a" "b"] :reason "new evidence"}]
    (is (thrown? clojure.lang.ExceptionInfo (identity/append-event linked reject)))
    (let [reversed (identity/append-event linked
                                          {:id "owner-reverse" :action :reverse
                                           :actor-kind :human :event-id "owner-link"
                                           :reason "split"})]
      (is (= 0 (:accepted-group-count
                (identity/project (identity/append-event reversed reject))))))))

(deftest transitive-publisher-conflict-and-context-changes
  (let [rows [(athlete "a" "A Person" :publisher-scope "CMAS" :publisher-athlete-id "1" :publisher-id-kind :person)
              (athlete "b" "B Person")
              (athlete "c" "C Person" :publisher-scope "CMAS" :publisher-athlete-id "2" :publisher-id-kind :person
                       :context {:event-id "other" :sex :women :country "FRA"})]
        linked (-> (identity/empty-ledger rows)
                   (identity/append-event {:id "ab" :action :accept :actor-kind :human :pair ["a" "b"] :reason "evidence"}))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (identity/append-event linked {:id "bc" :action :accept :actor-kind :automatic :pair ["b" "c"] :rule-version identity/rule-version})))
    (is (= :approve (:status (identity/decide (identity/build-index [(athlete "x" "Unique Person" :context {:sex :women :country "FRA"})])
                                              (athlete "y" "Unique Person" :context {:sex :women :country "ITA"}) {}))))))

(deftest publisher-id-is-scoped-and-must-denote-a-person
  (let [target (athlete "target" "Changed Name" :publisher-scope "CMAS"
                        :publisher-athlete-id "42" :publisher-id-kind :person)
        verified (athlete "a" "Original Name" :publisher-scope "CMAS"
                          :publisher-athlete-id "42" :publisher-id-kind :person)
        bib (athlete "b" "Original Name" :publisher-scope "CMAS"
                     :publisher-athlete-id "42" :publisher-id-kind :bib)
        other (athlete "c" "Original Name" :publisher-scope "AIDA"
                       :publisher-athlete-id "42" :publisher-id-kind :person)]
    (is (= ["a"] (mapv :observation-id (:candidates (identity/retrieve (identity/build-index [verified bib other]) target)))))
    (is (= :approve (:status (identity/decide (identity/build-index [verified]) target {}))))
    (is (= :unresolved (:status (identity/decide (identity/build-index [bib other]) target {}))))))

(deftest unsupported-rows-are-accounted-and-human-rejection-blocks-a-chain
  (let [rows [(athlete "a" "A Person") (athlete "b" "B Person")
              (athlete "c" "C Person") (athlete "bad" "Damaged" :parse-status :damaged)]
        index (identity/build-index rows)
        ledger (-> (identity/empty-ledger rows)
                   (identity/append-event {:id "reject" :action :reject :actor-kind :human
                                           :pair ["a" "c"] :reason "different people"})
                   (identity/append-event {:id "ab" :action :accept :actor-kind :automatic
                                           :pair ["a" "b"] :rule-version identity/rule-version}))]
    (is (empty? (:omitted (identity/retrieve index (athlete "target" "A Person")))))
    (is (= 1 (:unsupported-count (identity/retrieve index (athlete "target" "A Person")))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (identity/append-event ledger {:id "bc" :action :accept :actor-kind :automatic
                                                :pair ["b" "c"] :rule-version identity/rule-version})))))

(deftest target-alias-and-native-name-retrieve-both-directions
  (let [native (athlete "native" "李伟")
        roman (athlete "roman" "Li Wei" :aliases [{:name "李伟" :origin :publisher
                                                   :citation {:source "synthetic"}}])
        index (identity/build-index [native roman])]
    (is (= #{"native" "roman"}
           (set (map :observation-id (:candidates
                                      (identity/retrieve index
                                                         (athlete "target" "Unrelated Name"
                                                                  :aliases [{:name "李伟" :origin :accepted
                                                                             :citation {:source "synthetic"}}])))))))
    (is (= ["roman"] (mapv :observation-id (:candidates (identity/retrieve (identity/build-index [roman]) native)))))))

(deftest verified-publisher-id-can-resolve-a-common-name
  (let [known (athlete "known" "Amy Smith" :publisher-scope "CMAS"
                       :publisher-athlete-id "42" :publisher-id-kind :person)
        target (athlete "target" "Amy Smith" :publisher-scope "CMAS"
                        :publisher-athlete-id "42" :publisher-id-kind :person)]
    (is (= :verified-publisher-id (:reason (identity/decide (identity/build-index [known]) target {}))))))

(deftest generated-transliteration-remains-a-candidate-signal
  (let [target (athlete "target" "李伟")
        generated (athlete "generated" "Li Wei"
                           :aliases [{:name "李伟" :origin :generated :generator-version "test/1"}])
        index (identity/build-index [generated])]
    (is (= [:generated-transliteration]
           (get-in (identity/retrieve index target) [:candidates 0 :routes])))
    (is (= :generated (get-in (identity/retrieve index target) [:candidates 0 :aliases 0 :origin])))
    (is (= :unresolved (:status (identity/decide index target {}))))))

(deftest human-can-explicitly-supersede-a-rejection
  (let [rows [(athlete "a" "A Person") (athlete "b" "B Person")]
        rejected (identity/append-event (identity/empty-ledger rows)
                                        {:id "reject" :action :reject :actor-kind :human
                                         :pair ["a" "b"] :reason "initial review"})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (identity/append-event rejected {:id "unauthorized" :action :accept :actor-kind :human
                                                  :pair ["a" "b"] :reason "later review"})))
    (is (= 1 (:accepted-group-count
              (identity/project (identity/append-event rejected
                                                       {:id "supersede" :action :accept :actor-kind :human
                                                        :pair ["a" "b"] :supersedes "reject"
                                                        :reason "new evidence"})))))))

(deftest damaged-name-omissions-are-scoped-and-visible
  (let [rows [(athlete "good" "Unique Diver")
              (athlete "unrelated" "Other Athlete" :parse-status :damaged)
              (athlete "possible" "Unique Diver" :parse-status :damaged)
              (athlete "unknown" nil :parse-status :damaged)]
        result (identity/retrieve (identity/build-index rows) (athlete "target" "Unique Diver"))]
    (is (= 3 (:unsupported-count result)))
    (is (= #{"possible" "unknown"} (set (map :observation-id (:omitted result)))))
    (is (= ["good"] (mapv :observation-id (:candidates result))))
    (is (= :unresolved (:status (identity/decide (identity/build-index rows)
                                                 (athlete "target" "Unique Diver") {}))))))

(deftest approved-model-identity-updates-canonical-groups
  (let [a (athlete "a" "Native Name" :citation {:job-id "a" :ordinal 0 :source-sha256 "sha-a"})
        b (athlete "b" "Romanized Name" :citation {:job-id "b" :ordinal 0 :source-sha256 "sha-b"}
                   :aliases [{:name "Native Name" :origin :publisher :citation {:source "synthetic"}}])
        rows [a b]
        decision (identity/jev-decision (identity/empty-ledger rows) "a" "b")
        config {:provider :jev :model "jev-1.13.0" :version "test-config/1"}
        policy {:version "test-policy/1"
                :thresholds {:identity {:same-person {:min-confidence 0.9 :min-probability 0.9 :min-margin 0.2}}}}
        execute! (fn [_]
                   {:raw-response
                    (json/write-str
                     {:model "jev-1.13.0" :usage {}
                      :answers {(:id decision) {:type "choice" :choice "same_person" :confidence 0.96
                                                :probabilities {"same_person" 0.94 "different_person" 0.04 "unknown" 0.02}}}})})
        flow-ledger (flow/run! (flow/empty-ledger) [decision]
                               {:config config :policy policy :execute! execute!})
        ledger (identity/empty-ledger rows)
        event (identity/model-event ledger flow-ledger decision config policy 0)
        linked (identity/append-event ledger event)]
    (is (= :model (:actor-kind event)))
    (is (= 1 (:accepted-group-count (identity/project linked))))
    (is (= [:model] (get-in (identity/project linked) [:athletes "a" :decision-origin])))
    (is (= linked (identity/append-event linked event)))
    (is (= 0 (:accepted-group-count
              (identity/project
               (identity/replay (conj rows (athlete "c" "Native Name"
                                                    :citation {:job-id "c" :ordinal 0 :source-sha256 "sha-c"}))
                                (:events linked))))))
    (is (= 0 (:accepted-group-count
              (identity/project (identity/append-event linked
                                                       {:id "human-split" :action :reverse :actor-kind :human
                                                        :event-id (:id event) :reason "correction"})))))))

(deftest model-identity-rejects-stale-evidence-and-human-blocks
  (let [a (athlete "a" "A Name" :citation {:job-id "a" :ordinal 0 :source-sha256 "sha-a"})
        b (athlete "b" "B Name" :citation {:job-id "b" :ordinal 0 :source-sha256 "sha-b"}
                   :aliases [{:name "A Name" :origin :publisher :citation {:source "synthetic"}}])
        decision (identity/jev-decision (identity/empty-ledger [a b]) "a" "b")
        config {:provider :jev :model "jev-1.13.0" :version "config/1"}
        policy {:version "policy/1" :thresholds {:identity {:same-person {:min-confidence 0.9
                                                                          :min-probability 0.9 :min-margin 0.2}}}}
        flow-ledger (flow/run! (flow/empty-ledger) [decision]
                               {:config config :policy policy
                                :execute! (fn [_] {:model "jev-1.13.0" :usage {}
                                                   :answers {(:id decision) {:type "choice" :choice "same_person"
                                                                             :confidence 0.96
                                                                             :probabilities {"same_person" 0.94
                                                                                             "different_person" 0.04
                                                                                             "unknown" 0.02}}}})})
        ledger (identity/empty-ledger [a b])
        event (identity/model-event ledger flow-ledger decision config policy 0)
        rejected (identity/append-event ledger {:id "human-no" :action :reject :actor-kind :human
                                                :pair ["a" "b"] :reason "correction"})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (identity/model-event ledger flow-ledger
                                       (assoc-in decision [:subject :observation-versions "a" :source-sha256] "changed")
                                       config policy 0)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (identity/model-event ledger flow-ledger
                                       (assoc-in decision [:evidence 0 :fact] "Invented supporting fact")
                                       config policy 0)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (identity/model-event ledger flow-ledger decision config policy 1)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (identity/model-event ledger
                                       (update flow-ledger :events
                                               #(mapv (fn [e] (assoc-in e [:answer :confidence] 0.1)) %))
                                       decision config policy 0)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (identity/model-event ledger flow-ledger decision config
                                       (assoc policy :version "policy/2") 0)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (identity/append-event ledger (assoc-in event [:model-proof :receipt :model] "forged"))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (identity/append-event rejected (identity/model-event rejected flow-ledger decision config policy 1))))))

(deftest code-builds-source-bound-identity-question
  (let [a (athlete "a" "One Diver" :citation {:job-id "job-a" :ordinal 0 :source-sha256 "sha-a"})
        b (athlete "b" "One Diver" :citation {:job-id "job-b" :ordinal 0 :source-sha256 "sha-b"})
        ledger (identity/empty-ledger [a b])
        decision (identity/jev-decision ledger "a" "b")]
    (is (= :identity (:family decision)))
    (is (= :same-person (:action decision)))
    (is (= ["a" "b"] (:candidates decision)))
    (is (= {"a" (:citation a) "b" (:citation b)}
           (get-in decision [:subject :observation-versions])))
    (is (= #{(:citation a) (:citation b)} (set (map :citation (:evidence decision)))))
    (is (= ["a" "b" "c"]
           (:candidates (identity/jev-decision (identity/empty-ledger
                                                [a b (athlete "c" "One Diver"
                                                              :citation {:job-id "job-c" :ordinal 0
                                                                         :source-sha256 "sha-c"})])
                                               "a" "b"))))))

(deftest failed-parent-invalidates-dependent-model-link
  (let [a (athlete "a" "One Diver" :citation {:job-id "job-a" :ordinal 0 :source-sha256 "sha-a"})
        b (athlete "b" "One Diver" :citation {:job-id "job-b" :ordinal 0 :source-sha256 "sha-b"})
        ledger (identity/empty-ledger [a b])
        child (identity/jev-decision ledger "a" "b" {:dependencies ["parent"]})
        parent (assoc child :id "parent" :dependencies [])
        config {:provider :jev :model "jev-test" :version "config/1"}
        policy {:version "policy/1" :thresholds {:identity {:same-person {:min-confidence 0.9
                                                                          :min-probability 0.9 :min-margin 0.2}}}}
        execute! (fn [_] {:model "jev-test" :usage {}
                          :answers {(:id child) {:type "choice" :choice "same_person" :confidence 0.96
                                                 :probabilities {"same_person" 0.94
                                                                 "different_person" 0.04 "unknown" 0.02}}}})
        approved (flow/run! (flow/empty-ledger) [parent child]
                            {:config config :policy policy :execute! execute!
                             :deterministic-results {"parent" {:status :approve :rule-version "rule/1"}}})
        linked (identity/append-event ledger (identity/model-event ledger approved child config policy 0))
        canonical-failure (identity/dependency-reversal-event
                           linked approved child config {"parent" :unresolved} 1)
        corrected (flow/append-human-event approved
                                           {:id "owner-parent-no" :decision-id "parent"
                                            :status :rejected :reason "bad parent"})
        failed (flow/run! corrected [parent child]
                          {:config config :policy policy :execute! execute!})
        reverse-event (identity/dependency-reversal-event linked failed child 1)
        reversed (identity/append-event linked reverse-event)]
    (is (= 1 (:accepted-group-count (identity/project linked))))
    (is (= 0 (:accepted-group-count
              (identity/project (identity/append-event linked canonical-failure)))))
    (is (= :model (:actor-kind reverse-event)))
    (is (= 0 (:accepted-group-count (identity/project reversed))))
    (is (= reversed (identity/append-event reversed reverse-event)))))

(deftest stricter-policy-invalidates-current-model-link
  (let [a (athlete "a" "One Diver" :citation {:job-id "job-a" :ordinal 0 :source-sha256 "sha-a"})
        b (athlete "b" "One Diver" :citation {:job-id "job-b" :ordinal 0 :source-sha256 "sha-b"})
        ledger (identity/empty-ledger [a b])
        d (identity/jev-decision ledger "a" "b")
        config {:provider :jev :model "jev-test" :version "config/1"}
        policy {:version "policy/1" :thresholds {:identity {:same-person {:min-confidence 0.9
                                                                          :min-probability 0.9 :min-margin 0.2}}}}
        execute! (fn [_] {:model "jev-test" :usage {}
                          :answers {(:id d) {:type "choice" :choice "same_person" :confidence 0.96
                                             :probabilities {"same_person" 0.94
                                                             "different_person" 0.04 "unknown" 0.02}}}})
        approved (flow/run! (flow/empty-ledger) [d]
                            {:config config :policy policy :execute! execute!})
        linked (identity/append-event ledger (identity/model-event ledger approved d config policy 0))
        strict-policy (assoc-in (assoc policy :version "policy/2")
                                [:thresholds :identity :same-person :min-confidence] 0.99)
        now-unresolved (flow/run! approved [d]
                                  {:config config :policy strict-policy :execute! execute!})
        reverse-event (identity/dependency-reversal-event
                       linked now-unresolved d config {} 1)]
    (is (= :unresolved (get-in (flow/inspect now-unresolved [d] config) [(:id d) :status])))
    (is (= :current-approval-unavailable (:reason reverse-event)))
    (is (= 0 (:accepted-group-count
              (identity/project (identity/append-event linked reverse-event)))))))
