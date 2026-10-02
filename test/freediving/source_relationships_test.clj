(ns freediving.source-relationships-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [freediving.source-relationships :as relationships]))

(defn observation [job ordinal overrides]
  (merge {:ref {:job-id job :ordinal ordinal}
          :source-sha256 "source-bytes"
          :position [{:page 1 :line ordinal}]
          :source-text (str "row " ordinal)
          :event-key "ffessm-2026"
          :discipline "FIM"
          :day nil
          :parser-version "parser/1"
          :parsed {:source-name "A Diver" :final-performance 70M :card "Blanc"}}
         overrides))

(defn evidence [left right]
  {:left (:ref left) :right (:ref right) :kind :same-attempt
   :basis {:left-position (:position left) :right-position (:position right)
           :left-text (:source-text left) :right-text (:source-text right)
           :match-fields [:source-name :final-performance :card]}})

(deftest exact-source-position-links-a-printed-subset
  (let [open (observation "one" 0 {:position [{:page 1 :line 10}] :source-text "open row"})
        subset (observation "one" 1 {:position [{:page 1 :line 20}] :source-text "national row"})
        result (relationships/classify {:observations [open subset]
                                        :same-attempt-evidence [(evidence open subset)]})]
    (is (= :same-attempt (-> result :edges first :kind)))
    (is (= {:same-attempt 1 :source-duplicate 0 :parser-revision 0 :unknown 0}
           (:counts result)))
    (is (= (:ref open) (-> result :edges first :left)))
    (is (= (:ref subset) (-> result :edges first :right)))
    (is (= result (relationships/classify {:observations [subset open]
                                           :same-attempt-evidence [(evidence subset open)]})))))

(deftest byte-identical-routes-do-not-invent-another-observation
  (let [routes [{:route-id "vestico-default" :source-sha256 "identical-bytes"}
                {:route-id "vestico-comp-6" :source-sha256 "identical-bytes"}]
        result (relationships/classify {:observations [] :routes routes})]
    (is (= 1 (count (:source-edges result))))
    (is (= :source-duplicate (-> result :source-edges first :kind)))
    (is (= {:same-attempt 0 :source-duplicate 1 :parser-revision 0 :unknown 0}
           (:counts result)))
    (is (empty? (:observations result)))))

(deftest parser-replay-is-revision-only-when-both-versions-have-observations
  (let [old (observation "old" 0 {:position [{:page 1 :line 7}] :source-text "printed row"})
        new (observation "new" 0 {:position [{:page 1 :line 7}] :source-text "printed row"
                                  :parser-version "parser/2"
                                  :parsed {:source-name "A Diver" :final-performance 71M :card "Blanc"}})]
    (is (= :parser-revision (-> (relationships/classify {:observations [new old]})
                                :edges first :kind)))
    (is (= 0 (get-in (relationships/classify {:observations [new]})
                     [:counts :parser-revision])))))

(deftest unsupported-cross-source-overlap-stays-in-review
  (let [a (observation "a" 0 {:source-sha256 "publication-a" :position [{:page 1 :line 3}]
                              :source-text "first view" :day "2026-02-01"})
        b (observation "b" 0 {:source-sha256 "publication-b" :position [{:page 1 :line 9}]
                              :source-text "second view" :day "2026-02-02"})
        result (relationships/classify {:observations [a b]
                                        :same-attempt-evidence [(evidence a b)]})]
    (is (= :unknown (-> result :candidates first :kind)))
    (is (= :insufficient-evidence (-> result :candidates first :reason)))
    (is (= 0 (get-in result [:counts :same-attempt])))))

(deftest uninspected-aggregate-is-a-source-review-candidate
  (let [routes [{:route-id "individual" :source-sha256 "individual-bytes"}
                {:route-id "aggregate" :source-sha256 "aggregate-bytes"}]
        candidate {:left "individual" :right "aggregate"
                   :reason :uninspected-sporting-overlap}
        result (relationships/classify {:routes routes :source-candidates [candidate]})]
    (is (= 1 (count (:source-candidates result))))
    (is (= :unknown (-> result :source-candidates first :kind)))
    (is (= :source-route (-> result :source-candidates first :scope)))
    (is (= 1 (get-in result [:counts-by-scope :source-route :unknown])))
    (is (= 0 (get-in result [:counts-by-scope :observation :unknown])))
    (is (= result (relationships/classify {:routes (reverse routes)
                                           :source-candidates [{:left "aggregate" :right "individual"
                                                                :reason :uninspected-sporting-overlap}]})))))

(deftest observations-without-valid-page-line-citations-are-unpaired
  (let [missing (mapv (fn [n]
                        (observation (str "job-" n) 0
                                     {:position [] :source-text nil}))
                      (range 100))
        malformed [(observation "malformed-a" 0
                                {:position [{:page nil :line nil}] :source-text "row"})
                   (observation "malformed-b" 0
                                {:position [{:page nil :line nil}] :source-text "row"})]
        result (relationships/classify {:observations (into missing malformed)})]
    (is (= 102 (count (:observations result))))
    (is (empty? (:edges result)))
    (is (empty? (:candidates result)))
    (is (= 0 (get-in result [:counts-by-scope :observation :unknown])))))

(def attempt-scope {:event "cup" :day "2026-06-01" :session "am" :round "final"
                    :discipline "FIM" :participant "publisher:42" :attempt "2"})

(defn attempt-fixture []
  (let [positions [{:id "p1" :source-id "official" :locator {:page 1 :line 2}}
                   {:id "p2" :source-id "mirror" :locator {:row 3}}]
        binding (fn [position-id]
                  (let [position (first (filter #(= position-id (:id %)) positions))]
                    {:source-id (:source-id position) :position-id position-id
                     :citation (:locator position) :fields attempt-scope}))]
    (relationships/empty-attempt-ledger
     {:sources [{:id "official" :sha256 "aa"} {:id "mirror" :sha256 "bb"}]
      :positions positions
      :observation-versions [{:id "v1" :position-id "p1" :parser-version "1"
                              :scope attempt-scope :scope-evidence (binding "p1")
                              :values {:raw-performance "70m" :final-performance "69m"
                                       :penalty "1m" :card "yellow" :notes "turn"}}
                             {:id "v2" :position-id "p1" :parser-version "2"
                              :scope attempt-scope :scope-evidence (binding "p1")
                              :values {:raw-performance "70m" :final-performance "69m"}}
                             {:id "v3" :position-id "p2" :parser-version "1"
                              :scope attempt-scope :scope-evidence (binding "p2")
                              :values {:raw-performance "70m" :final-performance "69m"}}]})))

(deftest source-versions-and-mirror-positions-count-one-cited-attempt
  (let [ledger (-> (attempt-fixture)
                   (relationships/append-attempt-event
                    {:id "mirror-link" :action :accept :type :source-dependent
                     :pair ["official" "mirror"] :evidence {:kind :publisher-mirror}})
                   (relationships/append-attempt-event
                    {:id "attempt-link" :action :accept :type :same-attempt
                     :pair ["v1" "v3"] :evidence {:kind :verified-scope}}))
        result (relationships/project-attempts ledger)]
    (is (= {:sources 2 :source-objects 2 :positions 2 :observation-versions 3
            :accepted-attempts 1 :unresolved-observations 0} (:counts result)))
    (is (= #{"v1" "v2" "v3"} (set (-> result :attempts first :observation-ids))))
    (is (= #{"p1" "p2"} (set (-> result :attempts first :position-ids))))
    (is (= "turn" (get-in ledger [:observation-versions "v1" :values :notes])))
    (is (= result (relationships/project-attempts ledger)))))

(deftest scope-contradictions-and-missing-scope-do-not-collapse-attempts
  (let [base (attempt-fixture)
        different-day (-> base
                          (assoc-in [:observation-versions "v3" :scope :day] "2026-06-02")
                          (assoc-in [:observation-versions "v3" :scope-evidence :fields :day] "2026-06-02"))
        missing-session (update-in base [:observation-versions "v3" :scope] dissoc :session)]
    (is (= 2 (get-in (relationships/project-attempts different-day) [:counts :accepted-attempts])))
    (is (= 1 (get-in (relationships/project-attempts missing-session) [:counts :unresolved-observations])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (relationships/append-attempt-event different-day
                                                     {:id "bad" :action :accept :type :same-attempt
                                                      :pair ["v1" "v3"] :evidence {:kind :verified-scope}})))))

(deftest complete-but-uncited-scope-is-unresolved
  (let [base (attempt-fixture)
        unverified (update-in base [:observation-versions "v3"] dissoc :scope-evidence)]
    (is (= 1 (get-in (relationships/project-attempts unverified)
                     [:counts :unresolved-observations])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (relationships/append-attempt-event unverified
                                                     {:id "uncited" :action :accept :type :same-attempt
                                                      :pair ["v1" "v3"] :evidence {:kind :verified-scope}})))))

(deftest publisher-direction-and-reversal-recompute-without-deleting-evidence
  (let [base (attempt-fixture)
        revision {:id "rev" :action :accept :type :source-revision
                  :pair ["official" "mirror"]
                  :evidence {:kind :publisher-version :predecessor "official"
                             :successor "mirror" :citation "publisher correction notice"}}
        linked (-> base
                   (relationships/append-attempt-event revision)
                   (relationships/append-attempt-event
                    {:id "attempt-link" :action :accept :type :same-attempt
                     :pair ["v1" "v3"] :evidence {:kind :verified-scope}}))
        reversed (relationships/append-attempt-event
                  linked {:id "undo" :action :reverse :event-id "attempt-link"})]
    (is (= 1 (get-in (relationships/project-attempts linked) [:counts :accepted-attempts])))
    (is (= 2 (get-in (relationships/project-attempts reversed) [:counts :accepted-attempts])))
    (is (= 3 (count (:events reversed))))
    (is (= 3 (count (:observation-versions reversed))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (relationships/append-attempt-event
                  linked (assoc revision :id "reverse-rev"
                                :evidence {:kind :publisher-version :predecessor "mirror"
                                           :successor "official" :citation "conflict"}))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (relationships/append-attempt-event
                  base (dissoc revision :evidence))))))

(deftest replacement-evidence-invalidates-only-dependent-links
  (let [base (attempt-fixture)
        linked (-> base
                   (relationships/append-attempt-event
                    {:id "dependent" :action :accept :type :source-dependent
                     :pair ["official" "mirror"] :evidence {:kind :publisher-mirror}})
                   (relationships/append-attempt-event
                    {:id "dive" :action :accept :type :same-attempt
                     :pair ["v1" "v3"] :evidence {:kind :verified-scope}}))
        replacement {:sources (vals (:sources base))
                     :positions (vals (:positions base))
                     :observation-versions (mapv #(if (= "v3" (:id %))
                                                    (-> %
                                                        (assoc-in [:scope :day] "2026-06-02")
                                                        (assoc-in [:scope-evidence :fields :day] "2026-06-02")) %)
                                                 (vals (:observation-versions base)))}
        rebased (relationships/rebase-attempt-ledger linked replacement)]
    (is (= #{"dive"} (:invalidated-events rebased)))
    (is (= 2 (get-in (relationships/project-attempts rebased) [:counts :accepted-attempts])))
    (is (= 2 (count (:events rebased))))
    (is (= 1 (count (:source-relationships (relationships/project-attempts rebased)))))))

(defn -main []
  (let [result (run-tests 'freediving.source-relationships-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
