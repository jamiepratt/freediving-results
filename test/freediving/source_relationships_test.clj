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

(defn -main []
  (let [result (run-tests 'freediving.source-relationships-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
