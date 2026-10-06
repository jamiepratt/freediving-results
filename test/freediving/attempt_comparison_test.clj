(ns freediving.attempt-comparison-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [freediving.attempt-comparison :as comparison]))

(def hash-a (apply str (repeat 64 "a")))
(def hash-b (apply str (repeat 64 "b")))
(defn row [id federation value]
  {:reference {:job-id (str "job-" id) :ordinal 0 :candidate-id id
               :source-sha256 (if (= federation "CMAS") hash-a hash-b)
               :artifact-sha256 (if (= federation "CMAS") hash-b hash-a)}
   :source {:federation federation :event-id (if (= federation "CMAS") "novi-sad" "4852")
            :view-id (if (= federation "CMAS") "seniors-women-dnf" "2026-06-03")
            :authority :official-results :finality :verified-final :sanction :eligible :environment :pool}
   :candidate {:parse-status :parsed :review-status :unreviewed
               :source-family (if (= federation "CMAS") :results :attempts)
               :raw {:fields (if (= federation "CMAS")
                               {:realized-distance (str value) :final-distance (str value) :status nil}
                               {"RP" (str value " m") "Card" "WHITE" "Penalties" nil})}
               :parsed (merge {:federation federation :source-name id :representation "POL"
                               :discipline "DNF" :event-date (if (= federation "CMAS") "2026-06-11" "2026-06-03")
                               :unit "m"}
                              (if (= federation "CMAS")
                                {:category "SENIORS - WOMEN" :realized-distance value :final-distance value :status nil}
                                {:gender "Women" :performance value :realised-performance (str value " m")
                                 :card "WHITE" :penalty nil}))}
   :evidence {:review :verified :outcome :finally-valid :final {:value value :unit "m" :basis :verified-post-penalty
                                                                :decimal-places 0 :conversion :verified}
              :attempt-relationship :distinct :source-conflict :resolved}})

(def request {:year 2026 :discipline "DNF" :environment :pool :gender :women
              :category :seniors :federations #{"CMAS" "AIDA"}
              :views #{{:federation "CMAS" :event-id "novi-sad" :view-id "seniors-women-dnf" :source-sha256 hash-a}
                       {:federation "AIDA" :event-id "4852" :view-id "2026-06-03" :source-sha256 hash-b}}
              :representations #{"POL"}})

(deftest ranks-only-reviewed-distinct-finally-valid-attempts
  (let [a (row "cmas" "CMAS" 100M)
        b (row "aida" "AIDA" 100M)
        c (row "lower" "CMAS" 90M)
        result (comparison/compare-attempts request [c b a])]
    (is (= ["aida" "cmas" "lower"] (mapv #(get-in % [:reference :candidate-id]) (:rows result))))
    (is (= [1 1 3] (mapv :rank (:rows result))))
    (is (= {:provided 3 :in-scope 3 :ranked 3 :withheld 0 :excluded 0} (:coverage result)))
    (is (= {:fields {"RP" "100 m" "Card" "WHITE" "Penalties" nil}}
           (get-in result [:rows 0 :candidate :raw])))))

(deftest withholds-unreviewed-and-conflicting-rows-without-losing-source-data
  (let [unreviewed (assoc-in (row "unreviewed" "AIDA" 110M) [:evidence :review] :unreviewed)
        conflict (assoc-in (row "conflict" "CMAS" 120M) [:candidate :parsed :final-distance] 115M)
        unresolved (assoc-in (row "unresolved" "AIDA" 130M) [:evidence :attempt-relationship] :unknown)
        result (comparison/compare-attempts request [unreviewed conflict unresolved])]
    (is (= 0 (get-in result [:coverage :ranked])))
    (is (= 3 (get-in result [:coverage :withheld])))
    (is (true? (:provisional? result)))
    (is (every? nil? (map :rank (:rows result))))
    (is (some #(= [:source-conflict] (:reasons %)) (:rows result)))
    (is (= "130 m" (get-in (first (filter #(= "unresolved" (get-in % [:reference :candidate-id])) (:rows result)))
                           [:candidate :raw :fields "RP"])))))

(deftest filters-only-exact-source-and-representation
  (let [other-source (assoc-in (row "wrong-source" "AIDA" 140M) [:reference :source-sha256] hash-a)
        other-country (assoc-in (row "other-country" "CMAS" 150M) [:candidate :parsed :representation] "FRA")
        result (comparison/compare-attempts request [other-source other-country])]
    (is (= {:provided 2 :in-scope 0 :ranked 0 :withheld 0 :excluded 2} (:coverage result)))
    (is (every? nil? (map :rank (:rows result))))))

(deftest rejects-inconsistent-source-context
  (let [row (assoc-in (row "date" "AIDA" 90M) [:candidate :parsed :event-date] "2026-06-04")
        result (comparison/compare-attempts request [row])]
    (is (= :withheld (get-in result [:rows 0 :comparison-status])))
    (is (some #{:source-context-conflict} (get-in result [:rows 0 :reasons])))))

(deftest accepts-retained-cmas-category-separators
  (let [row (assoc-in (row "printed" "CMAS" 90M) [:candidate :parsed :category] "SENIORS – WOMEN")
        result (comparison/compare-attempts request [row])]
    (is (= :ranked (get-in result [:rows 0 :comparison-status])))))

(deftest withholds-final-without-verified-precision-or-conversion
  (let [missing-precision (update-in (row "precision" "CMAS" 90M) [:evidence :final] dissoc :decimal-places)
        unknown-conversion (assoc-in (row "conversion" "AIDA" 91M) [:evidence :final :conversion] :unknown)
        result (comparison/compare-attempts request [missing-precision unknown-conversion])]
    (is (= 0 (get-in result [:coverage :ranked])))
    (is (some #{:unverified-final-precision}
              (:reasons (first (filter #(= "precision" (get-in % [:reference :candidate-id])) (:rows result))))))
    (is (some #{:unverified-final-conversion}
              (:reasons (first (filter #(= "conversion" (get-in % [:reference :candidate-id])) (:rows result))))))))

(deftest stale-rank-never-survives-unreviewed-row
  (let [unreviewed (assoc (row "stale" "CMAS" 90M) :rank 1
                          :comparison-status :ranked :reasons [])
        result (comparison/compare-attempts request [(assoc-in unreviewed [:evidence :review] :unreviewed)])
        output (first (:rows result))]
    (is (= :withheld (:comparison-status output)))
    (is (not (contains? output :rank)))))

(deftest cited-selected-source-conflict-is-disclosed-with-provisional-peer-ranks
  (let [selected (-> (row "selected" "CMAS" 100M)
                     (assoc-in [:evidence :source-conflict] :selected-provisional)
                     (assoc-in [:evidence :source-selection]
                               {:selected-reference (:reference (row "selected" "CMAS" 100M))
                                :conflicting-references [(:reference (row "alternative" "AIDA" 90M))]
                                :basis :source-authority :authority-citation {:synthetic "official authority"}
                                :selection-citation {:synthetic "exact selected revision"}}))
        result (comparison/compare-attempts request [selected (row "peer" "AIDA" 90M)])]
    (is (= 2 (get-in result [:coverage :ranked])))
    (is (every? :provisional-rank? (:rows result)))
    (is (= :source-authority (get-in result [:rows 0 :evidence :source-selection :basis])))
    (is (= 1 (get-in (comparison/compare-attempts request [(update-in selected [:evidence :source-selection] dissoc :authority-citation)])
                     [:coverage :withheld])))))

(deftest disqualified-hypothetical-never-enters-main-peer-denominator
  (let [dq (-> (row "dq" "AIDA" 110M)
               (assoc-in [:candidate :parsed :card] "RED")
               (assoc-in [:evidence :outcome] :disqualified)
               (assoc-in [:evidence :hypothetical]
                         {:final {:value 110M :unit "m" :basis :verified-source-achieved
                                  :decimal-places 0 :conversion :verified}
                          :citation {:synthetic "explicit reviewed hypothetical"}}))
        result (comparison/compare-attempts request [dq (row "valid" "CMAS" 100M)])
        withheld (first (filter #(= "dq" (get-in % [:reference :candidate-id])) (:rows result)))]
    (is (= 1 (get-in result [:coverage :ranked])))
    (is (nil? (:rank withheld)))
    (is (= {:rank 1 :eligible-peer-denominator 1 :value 110M :unit "m" :status :disqualified :basis :verified-source-achieved
            :citation {:synthetic "explicit reviewed hypothetical"}} (:hypothetical withheld)))
    (is (nil? (:hypothetical (first (:rows (comparison/compare-attempts request [(update dq :evidence dissoc :hypothetical)]))))))))

(deftest equal-authority-administrative-choice-validates-the-supported-stable-rule
  (let [selected (row "selected" "CMAS" 100M)
        alternative (row "alternative" "AIDA" 90M)
        choice (-> selected
                   (assoc-in [:evidence :source-conflict] :selected-provisional)
                   (assoc-in [:evidence :source-selection]
                             {:selected-reference (:reference selected) :conflicting-references [(:reference alternative)]
                              :basis :administrative-tie-break :equal-authority true
                              :tie-break-rule :exact-reference-lexical-v1
                              :authority-citation {:synthetic "equal authorities"}
                              :selection-citation {:synthetic "stable selected source"}}))
        good (comparison/compare-attempts request [choice alternative])
        wrong-rule (comparison/compare-attempts request [(assoc-in choice [:evidence :source-selection :tie-break-rule] :invented)])]
    (is (= 1 (get-in good [:coverage :ranked])))
    (is (= 1 (get-in good [:coverage :withheld])))
    (is (= :non-selected-source-claim (last (:reasons (last (:rows good))))))
    (is (= 0 (get-in wrong-rule [:coverage :ranked])))))

(defn -main [& _]
  (let [result (run-tests 'freediving.attempt-comparison-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
