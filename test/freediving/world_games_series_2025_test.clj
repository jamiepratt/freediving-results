(ns freediving.world-games-series-2025-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [freediving.extraction :as extraction]
            [freediving.world-games-series-2025 :as series]))

(defn result-page [heading phase body]
  (str "CSU Natatorium Freediving\n"
       heading "\n"
       "SAT 29 MAR 2025 " phase "\n"
       "Results\n"
       "Event Number 2\n"
       "Rank Lane Name NOC Code Card Realized Dist. (m) Penalty (m) Final Dist. (m)\n"
       body))

(deftest heat-results-are-attempts-and-summary-is-supporting-evidence
  (let [heat (result-page "Men's Dynamic without Fins" "Heat 1"
                          " 1  8  PAVKOVIC Nenad                    SRB   WHITE  194.0  194.0\n    1  ERGUN Yagmur                      TUR   RED           DSQ")
        summary (str/replace heat "Heat 1\nResults" "Final\nResults Summary")
        result (extraction/parse-pages [(str "RESULTS BOOK\n" summary) heat])
        [first-row second-row] (:candidates result)]
    (is (= series/parser-version (:parser-version result)))
    (is (= 2 (get-in result [:reconciliation :candidate-count])))
    (is (= [2 2] (mapv #(get-in % [:coordinates :page]) (:candidates result))))
    (is (= [7 8] (mapv #(get-in % [:coordinates :line]) (:candidates result))))
    (is (= "PAVKOVIC Nenad" (get-in first-row [:parsed :source-name])))
    (is (= 194.0M (get-in first-row [:parsed :final-distance])))
    (is (= "DNF" (get-in first-row [:parsed :discipline])))
    (is (= "Heat 1" (get-in first-row [:parsed :session])))
    (is (= "2025-03-29" (get-in first-row [:parsed :event-date])))
    (is (= "DSQ" (get-in second-row [:parsed :status])))
    (is (nil? (get-in second-row [:parsed :final-distance])))
    (is (= :supporting-summary (get-in result [:reconciliation :per-page 0 :classification])))
    (is (= :blocked (get-in result [:publication :status])))))
