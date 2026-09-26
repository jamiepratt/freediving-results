(ns freediving.world-games-2025-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [freediving.extraction :as extraction]
            [freediving.world-games-2025 :as world]))

(defn page [discipline category & rows]
  (str "2025 THE WORLD GAMES CHEGDU, CHINA\n"
       "AUGUST, 10, 2025\n"
       discipline "    FINAL RESULTS\n\n"
       category "\n\n"
       "Realized       Final           Notes\n"
       "#       Name & surname            Country\n"
       "Distance (m)   Distance (m)\n\n"
       (str/join "\n" rows)))

(deftest world-games-preserves-page-specific-result-evidence
  (let [r (extraction/parse-pages
           [(page "DNF" "SENIORS — WOMEN"
                  "1        Example ATHLETE            POL            222,5            222,5 GOLD MEDAL,"
                  "                                            WORLD RECORD SENIORS"
                  "      Other ATHLETE              CMAS1          181,5                0 DQ SURFACE BO"
                  "fi")
            (page "DYN" "PARAFREEDIVING — WOMEN — FFS1-FFS2"
                  "1        Third ATHLETE             CHN            149,5            149,5 GOLD MEDAL,")])
        [a b c] (:candidates r)]
    (is (= world/parser-version (:parser-version r)))
    (is (= [2 1] (mapv #(get-in % [:coordinates :page]) [c a])))
    (is (= ["DNF" "DNF" "DYN"] (mapv #(get-in % [:parsed :discipline]) [a b c])))
    (is (= "2025-08-10" (get-in a [:parsed :event-date])))
    (is (= "222,5" (get-in a [:raw :fields :final-distance])))
    (is (= 222.5M (get-in a [:parsed :final-distance])))
    (is (= "m" (get-in a [:parsed :unit])))
    (is (= "GOLD MEDAL, WORLD RECORD SENIORS" (get-in a [:parsed :notes])))
    (is (= [11 12] (mapv :line (:source-lines a))))
    (is (= "DQ" (get-in b [:parsed :status])))
    (is (= "DQ SURFACE BO" (get-in b [:parsed :notes])))
    (is (nil? (get-in b [:parsed :rank])))
    (is (= :uncertain (get-in b [:parsed :source-name-integrity])))
    (is (some #{:detached-name-fragment} (:unresolved-reasons b)))
    (is (= 3 (get-in r [:reconciliation :candidate-count])))
    (is (= 1 (get-in r [:reconciliation :unresolved-fragment-count])))
    (is (= :blocked (get-in r [:publication :status])))))

(deftest unknown-row-and-unsupported-section-remain-explicit
  (let [r (extraction/parse-pages [(page "DNF" "SENIORS — MEN" "unexplained row")
                                   (page "DYN" "OPEN — MIXED" "1 Example ATHLETE POL 100,0 100,0")])]
    (is (= :partial-unsupported-needs-parser (:status r)))
    (is (= [2] (get-in r [:reconciliation :unsupported-pages])))
    (is (= 1 (get-in r [:reconciliation :unparsed-count])))
    (is (= 1 (get-in r [:reconciliation :candidate-count])))))
