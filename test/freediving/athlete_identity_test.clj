(ns freediving.athlete-identity-test
  (:require [clojure.test :refer [deftest is]]
            [freediving.athlete-identity :as identity]))

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
