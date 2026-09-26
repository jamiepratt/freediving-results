(ns freediving.deep-dominica-2025-test
  (:require [clojure.test :refer [deftest is]]
            [freediving.extraction :as extraction]
            [freediving.deep-dominica-2025 :as dominica]))

(def pages
  [(str "DEEP DOMINICA FREEDIVING COMPETITION\n"
        "November 24th - December 1st 2025\nFINAL RESULTS\n"
        "DAY 1          Family Name               Country         Gender   D. Declared   Discipline   D. Reached   Results   Final P. Notes\n"
        "November 24th OPENER                     Dominica        FEMALE       20           FIM           20       White       20\n"
        "               Jon FANE                  United Kingdom male          71         CWT-BF          71       White       71\n"
        "DAY 2          Family Name               Country         Gender   D. Declared   Discipline   D. Reached   Results   Final P. Notes\n"
        "November 25th Marc Anop                 USA             Male         75         CWT-BF         DNS       DNS        DNS       DNS\n"
        "DAY 3          Family Name               Country         Gender   D. Declared   Discipline   D. Reached   Results   Final P. Notes\n"
        "November 27th Sofía Gómez               Colombia        Female       107         CWT           101       Yellow      94       EARLY TURN - NO MARKER\n"
        "DAY 4          Family Name               Country         Gender   D. Declared   Discipline   D. Reached   Results   Final P. Notes\n"
        "November 28th Luciana Blanco Villegas   Argentina       Female       45           CNF          DNS       DNS          0       DNS\n")
   (str "               Hugo Lemos                Argentina       Male         55           FIM          55        White       55\n"
        "DAY 5          Family Name               Country         Gender   D. Declared   Discipline   D. Reached   Results   Final P. Notes\n"
        "November 30th Kateryna Sadurska          Ucrain          Female       88           CNF          88        White       88       WR Seniors\n"
        "DAY 6          Family Name               Country         Gender   D. Declared   Discipline   D. Reached   Results   Final P. Notes\n"
        "December 1st   Sebastian Lira            Chile           Male         108        CWT-BF         108       WHITE       108      CR Seniors - America\n")])

(deftest printed-positions-retain-original-values-and-citations
  (let [result (dominica/parse-pages pages)
        rows (:candidates result)
        [opener jon dns yellow dns-zero continuation wr final] rows]
    (is (dominica/supported? pages))
    (is (= 8 (get-in result [:reconciliation :candidate-count])))
    (is (= [5 1] ((juxt #(get-in % [:coordinates :line]) #(get-in % [:coordinates :page])) opener)))
    (is (= "OPENER" (get-in opener [:parsed :source-name])))
    (is (= "United Kingdom" (get-in jon [:raw :fields :representation])))
    (is (= "2025-11-24" (get-in jon [:parsed :event-date])))
    (is (= "CWT-BF" (get-in jon [:parsed :discipline])))
    (is (= 71M (get-in jon [:parsed :final-points])))
    (is (nil? (get-in jon [:parsed :unit])))
    (is (= "DNS" (get-in dns [:raw :fields :final-points])))
    (is (nil? (get-in dns [:parsed :final-points])))
    (is (= "EARLY TURN - NO MARKER" (get-in yellow [:parsed :notes])))
    (is (= "0" (get-in dns-zero [:raw :fields :final-points])))
    (is (= 0M (get-in dns-zero [:parsed :final-points])))
    (is (= "2025-11-28" (get-in continuation [:parsed :event-date])))
    (is (= "Ucrain" (get-in wr [:parsed :representation])))
    (is (= "2025-12-01" (get-in final [:parsed :event-date])))
    (is (= :blocked (get-in result [:publication :status])))))

(deftest unexpected-result-position-stays-unparsed-and-blocks-coverage
  (let [changed (update pages 1 str "\n               New Athlete               Spain           Male         60           FIM          ???       White       60\n")
        result (dominica/parse-pages changed)]
    (is (= 9 (get-in result [:reconciliation :candidate-count])))
    (is (= 1 (get-in result [:reconciliation :unparsed-count])))
    (is (= :partial-unsupported-needs-parser (:status result)))
    (is (= :unparsed (:parse-status (last (:candidates result)))))))

(deftest source-layout-dispatches-through-extraction-api
  (is (= dominica/parser-version (:parser-version (extraction/parse-pages pages)))))
