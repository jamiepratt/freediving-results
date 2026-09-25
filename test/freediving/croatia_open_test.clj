(ns freediving.croatia-open-test
  (:require [clojure.test :refer [deftest is]]
            [freediving.croatia-open :as croatia]
            [freediving.extraction :as extraction]))

(defn page [date discipline rows]
  (str "18th Submania CUP /                     " date "\n"
       "Croatian Open National\nFreediving Championship\n"
       "                                      " discipline "\n"
       "Rank Name                    Gender Nationality Discipline   Result   Card\n"
       rows))

(deftest croatian-open-preserves-source-cells-and-line-evidence
  (let [source (page "27/03/2026" "STA"
                     "  1 Example ATHLETE             F       CRO         STA        8:37    WHITE\n")
        source (str source "  - Zero ATHLETE                M       SLO         STA        0:00    RED\n")
        r (extraction/parse-pages [source])
        [a b] (:candidates r)]
    (is (= croatia/parser-version (:parser-version r)))
    (is (= 2 (get-in r [:reconciliation :parsed-count])))
    (is (= [6 7] (mapv #(get-in % [:coordinates :line]) [a b])))
    (is (= ["Example ATHLETE" "Zero ATHLETE"] (mapv #(get-in % [:parsed :source-name]) [a b])))
    (is (= ["8:37" "0:00"] (mapv #(get-in % [:raw :fields :result]) [a b])))
    (is (= ["WHITE" "RED"] (mapv #(get-in % [:parsed :card]) [a b])))
    (is (nil? (get-in b [:parsed :rank])))
    (is (nil? (get-in a [:parsed :unit])))
    (is (= :blocked (get-in r [:publication :status])))))

(deftest croatian-open-explicit-meters-and-fail-closed-headers
  (let [source (page "28/03/2026" "DYN"
                     "  1 Example ATHLETE             F       CRO         DYN        250.50m    WHITE\n")
        r (extraction/parse-pages [source])]
    (is (= "250.50m" (get-in r [:candidates 0 :raw :fields :result])))
    (is (= "m" (get-in r [:candidates 0 :parsed :unit])))
    (is (= 250.50M (get-in r [:candidates 0 :parsed :distance])))
    (is (= [1] (get-in (extraction/parse-pages [(str source "DNF\n")])
                       [:reconciliation :unsupported-pages])))))
