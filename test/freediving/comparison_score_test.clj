(ns freediving.comparison-score-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [freediving.comparison-score :as score]))

(defn attempt [id discipline value unit precision]
  {:id id :discipline discipline :review :verified
   :attempt-relationship :distinct :status :finally-valid
   :achieved {:value value :unit unit} :penalty nil
   :official-final {:value value :unit unit :decimal-places precision
                    :basis :verified-publisher-post-penalty
                    :conversion :verified}
   :citation {:source "synthetic" :position id}})

(defn by-id [result id]
  (first (filter #(= id (:id %)) (:rows result))))

(deftest floors-exact-decimals-at-the-versioned-increment
  (let [rows [(attempt "sta" :sta 61.99M :s 2)
              (attempt "dyn" :dynamic 101.99M :m 2)
              (attempt "depth" :depth 50.99M :m 2)]
        result (score/compare-verified rows)]
    (is (= :aida-baseline-v1 (:policy result)))
    (is (= [12.2M 50.5M 50M] (mapv :comparison-score (:rows result))))
    (is (= [3 1 2] (mapv :comparison-rank (:rows result))))
    (is (= 3 (get-in result [:coverage :ranked])))))

(deftest equal-rounded-scores-and-equal-final-values-share-competition-ranks
  (let [result (score/compare-verified [(attempt "a" :dynamic 100.1M :m 1)
                                        (attempt "b" :dynamic 100.9M :m 1)
                                        (attempt "c" :dynamic 90M :m 0)])]
    (is (= [1 1 3] (mapv :comparison-rank (:rows result))))
    (is (= [2 1 3] (mapv :discipline-rank (:rows result))))))

(deftest publisher-final-controls-valid-penalized-result
  (let [penalized (-> (attempt "penalized" :dynamic 100M :m 0)
                      (assoc :status :finally-valid-penalized :penalty "-3 m")
                      (assoc-in [:official-final :value] 97M))
        without-final (-> penalized (assoc :id "missing")
                          (assoc :official-final nil))
        result (score/compare-verified [penalized without-final])]
    (is (= 48.5M (:comparison-score (by-id result "penalized"))))
    (is (= 100M (get-in (by-id result "penalized") [:achieved :value])))
    (is (= "-3 m" (:penalty (by-id result "penalized"))))
    (is (= :withheld (:comparison-status (by-id result "missing"))))
    (is (some #{:missing-verified-final} (:reasons (by-id result "missing"))))))

(deftest points-require-verified-comparable-basis
  (let [points (-> (attempt "points" :dynamic 100M :m 0)
                   (assoc :official-final {:value 49.99M :unit :points :decimal-places 2
                                           :basis :verified-publisher-post-penalty
                                           :conversion :verified
                                           :comparable-basis {:policy :aida-baseline-v1
                                                              :review :verified
                                                              :citation {:document "synthetic rules"}}}))
        ambiguous (-> points (assoc :id "ambiguous")
                      (update :official-final dissoc :comparable-basis))
        wrong-policy (-> points (assoc :id "wrong-policy")
                         (assoc-in [:official-final :comparable-basis :policy] :other))
        result (score/compare-verified [points ambiguous wrong-policy])]
    (is (= 49.5M (:comparison-score (by-id result "points"))))
    (is (= :withheld (:comparison-status (by-id result "ambiguous"))))
    (is (some #{:unverified-points-basis} (:reasons (by-id result "ambiguous"))))
    (is (some #{:unverified-points-basis} (:reasons (by-id result "wrong-policy"))))))

(deftest ambiguous-values-and-disqualification-never-enter-the-denominator
  (let [valid (attempt "valid" :dynamic 100M :m 0)
        dq (assoc valid :id "dq" :status :disqualified)
        unreviewed (assoc valid :id "unreviewed" :review :unreviewed)
        imprecise (-> valid (assoc :id "imprecise")
                      (assoc-in [:official-final :value] 100.1M))
        unknown-unit (-> valid (assoc :id "unit")
                         (assoc-in [:official-final :unit] :yards))
        speed (-> (attempt "speed" :speed 31.2M :s 1))
        result (score/compare-verified [valid dq unreviewed imprecise unknown-unit speed])]
    (is (= 1 (get-in result [:coverage :ranked])))
    (is (= 4 (get-in result [:coverage :withheld])))
    (is (= 1 (get-in result [:coverage :speed-only])))
    (is (= 1 (:discipline-rank (by-id result "speed"))))
    (is (every? nil? (map :comparison-rank (filter #(not= "valid" (:id %)) (:rows result)))))
    (is (= :withheld (:comparison-status (by-id result "dq"))))
    (is (some #{:unverified-review} (:reasons (by-id result "unreviewed"))))
    (is (some #{:ambiguous-precision} (:reasons (by-id result "imprecise"))))
    (is (some #{:unsupported-unit} (:reasons (by-id result "unit"))))
    (is (some #{:speed-no-common-score} (:reasons (by-id result "speed"))))))

(deftest mixed-final-units-have-no-within-discipline-rank
  (let [metres (attempt "metres" :dynamic 100M :m 0)
        points (-> (attempt "points" :dynamic 100M :m 0)
                   (assoc :official-final {:value 50M :unit :points :decimal-places 0
                                           :basis :verified-publisher-post-penalty
                                           :conversion :verified
                                           :comparable-basis {:policy :aida-baseline-v1
                                                              :review :verified
                                                              :citation {:document "synthetic rules"}}}))
        result (score/compare-verified [metres points])]
    (is (= [1 1] (mapv :comparison-rank (:rows result))))
    (is (every? nil? (map :discipline-rank (:rows result))))
    (is (every? #(some #{:mixed-final-units} (:reasons %)) (:rows result)))))

(defn -main [& _]
  (let [result (run-tests 'freediving.comparison-score-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
