(ns freediving.vdst-neckar-2025-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [freediving.vdst-neckar-2025 :as neckar]))

(def source neckar/source-sha256)
(def pages
  ["1. Neckar Apnoe Cup und 2. Baden-Württembergische Meisterschaft - Freibad Edition\nAbschnitt 1 - Samstag 06.09.2025\nWettkampf 1 - DNF (Streckentauchen ohne Flossen)\nNeckar Apnoe Cup: Senior Damen\nPlatz Schwimmerin Jg. Verein Ergebnis\n1.           Annette Birkett Faraud          2000    SSF Bonn                                  102,0m\n             nicht am Start\n             Verena Fleißner                 1976    Tauchsportclub Berlin e.V.\nLandesmeisterschaft Baden-Württemberg: Damen\n1. und Baden-Württembergische Meisterin\n          Karen Bayer             1992               Tauchclub Heilbronn e. V.                 85,0m\nEsslingen am Neckar, Vereinsbad des SSV Esslingen 07.09.2025 - Protokoll - Seite 10"
   "1. Neckar Apnoe Cup und 2. Baden-Württembergische Meisterschaft - Freibad Edition\nWettkampf 2 - DBF (Streckentauchen Bi-Fins)\nNeckar Apnoe Cup: Senior Herren\n1.           Lars Müller                     1989    Tauchclub Uni Stuttgart Manatees e.V.     130,0m\n             rote Karte\n             Tjark Kohberg                   1978    Polizeisportverein Stuttgart e.V.\n            SP\n            Uhrzeit der Bekanntgabe: 10:55\nEsslingen am Neckar, Vereinsbad des SSV Esslingen 07.09.2025 - Protokoll - Seite 11"
   "1. Neckar Apnoe Cup und 2. Baden-Württembergische Meisterschaft - Freibad Edition\nWettkampf 4 - 2x50m / 4x25m Speed-Apnoe\nNeckar Apnoe Cup: weibliche Jugend\n1.           Elisa Hölzer                    2009    Tauchclub Heilbronn e. V.                00:49,00\nEsslingen am Neckar, Vereinsbad des SSV Esslingen 07.09.2025 - Protokoll - Seite 12"
   "1. Neckar Apnoe Cup und 2. Baden-Württembergische Meisterschaft - Freibad Edition\nnoch Wettkampf 4 - 2x50m / 4x25m Speed-Apnoe\nLandesmeisterschaft Baden-Württemberg: Damen\n1. und Baden-Württembergische Meisterin\n          Jessika Baier           2000               Tauchclub Heilbronn e. V.                00:47,17\nEsslingen am Neckar, den 06.09.2025\nEsslingen am Neckar, Vereinsbad des SSV Esslingen 07.09.2025 - Protokoll - Seite 13"])

(deftest cup-positions-status-and-championship-view-are-distinct
  (let [artifact (neckar/parse-pages source pages)
        rows (:candidates artifact)]
    (is (= :partial-unsupported-needs-parser (:status artifact)))
    (is (= 5 (count rows)))
    (is (= [2 2 1 0] (mapv :cup-entry-count (get-in artifact [:reconciliation :per-page]))))
    (is (= 3 (get-in artifact [:reconciliation :ranked-count])))
    (is (= 2 (get-in artifact [:reconciliation :status-count])))
    (is (= 2 (get-in artifact [:reconciliation :championship-repeat-count])))
    (is (= {:page 1 :line 6 :column-start 1 :column-end 102} (:coordinates (first rows))))
    (is (= "1.           Annette Birkett Faraud          2000    SSF Bonn                                  102,0m"
           (get-in (first rows) [:raw :line])))
    (is (= "2025-09-06" (get-in (first rows) [:parsed :event-date])))
    (is (= 102.0M (get-in (first rows) [:parsed :performance])))
    (is (= "m" (get-in (first rows) [:parsed :unit])))
    (is (= "DNF" (get-in (first rows) [:parsed :discipline])))
    (is (= "Senior Damen" (get-in (first rows) [:parsed :category])))
    (is (= "nicht am Start" (get-in (second rows) [:parsed :status])))
    (is (= "SP" (get-in (nth rows 3) [:parsed :status-code])))
    (is (= "10:55" (get-in (nth rows 3) [:parsed :announcement-time])))
    (is (= "00:49,00" (get-in (last rows) [:parsed :realised-performance])))
    (is (= [0 49 0] (get-in (last rows) [:parsed :realized-time :components])))
    (is (= :blocked (get-in artifact [:publication :status])))))

(deftest unknown-source-and-damaged-position-remain-unresolved
  (let [unsupported (neckar/parse-pages (apply str (repeat 64 "0")) pages)
        damaged (neckar/parse-pages source (update pages 0 str/replace "102,0m" "unknown"))]
    (is (= :unsupported-needs-parser (:status unsupported)))
    (is (empty? (:candidates unsupported)))
    (is (= 1 (get-in damaged [:reconciliation :unparsed-count])))
    (is (= "unknown" (get-in (first (:candidates damaged)) [:raw :result])))))

(deftest shared-status-headings-cover-every-person
  (let [expanded (-> pages
                     (update 0 str/replace
                             "             Verena Fleißner                 1976    Tauchsportclub Berlin e.V."
                             "             Verena Fleißner                 1976    Tauchsportclub Berlin e.V.\n             Tessa Muster                   1975    Tauchsportclub Berlin e.V.")
                     (update 1 str/replace
                             "            Uhrzeit der Bekanntgabe: 10:55"
                             "            Uhrzeit der Bekanntgabe: 10:55\n             Simon Hermann                   1997    Tauchclub Heilbronn e. V.\n            BO\n            Uhrzeit der Bekanntgabe: 11:08"))
        artifact (neckar/parse-pages source expanded)
        status-rows (filter #(get-in % [:parsed :status]) (:candidates artifact))]
    (is (= 4 (count status-rows)))
    (is (= ["Verena Fleißner" "Tessa Muster" "Tjark Kohberg" "Simon Hermann"]
           (mapv #(get-in % [:parsed :source-name]) status-rows)))
    (is (= [nil nil "SP" "BO"]
           (mapv #(get-in % [:parsed :status-code]) status-rows)))
    (is (every? #(= :parsed (:parse-status %)) status-rows))))

(defn complete-page [page cup-count repeat-count]
  (let [title "1. Neckar Apnoe Cup und 2. Baden-Württembergische Meisterschaft - Freibad Edition"
        row "1.           Test Diver                     2000    Test Club                                100,0m"]
    (str/join "\n"
              (concat [title]
                      (when (= page 1) ["Abschnitt 1 - Samstag 06.09.2025"])
                      [(str "Wettkampf " page " - DNF (Streckentauchen ohne Flossen)")
                       "Neckar Apnoe Cup: Senior Damen"]
                      (repeat cup-count row)
                      ["Landesmeisterschaft Baden-Württemberg: Damen"]
                      (repeat repeat-count row)
                      [(str "Esslingen am Neckar, Vereinsbad des SSV Esslingen 07.09.2025 - Protokoll - Seite " (+ 9 page))]))))

(deftest dropped-position-makes-otherwise-complete-source-partial
  (let [complete-pages (mapv (fn [page cup repeat-count]
                               (complete-page page cup repeat-count))
                             [1 2 3 4] [17 14 13 0] [7 9 4 7])
        complete (neckar/parse-pages source complete-pages)
        shortened (update complete-pages 0 str/replace-first
                          "1.           Test Diver                     2000    Test Club                                100,0m\n" "")
        damaged (neckar/parse-pages source shortened)]
    (is (= :needs-review (:status complete)))
    (is (= :complete (get-in complete [:reconciliation :coverage])))
    (is (= 44 (get-in complete [:reconciliation :candidate-count])))
    (is (= 27 (get-in complete [:reconciliation :championship-repeat-count])))
    (is (= :partial-unsupported-needs-parser (:status damaged)))
    (is (= :partial (get-in damaged [:reconciliation :coverage])))
    (is (= 43 (get-in damaged [:reconciliation :candidate-count])))
    (is (= 0 (get-in damaged [:reconciliation :unparsed-count])))))

(defn -main [& _]
  (let [result (run-tests 'freediving.vdst-neckar-2025-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
