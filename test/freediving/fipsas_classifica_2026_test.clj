(ns freediving.fipsas-classifica-2026-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [freediving.extraction :as extraction]
            [freediving.fipsas-classifica-2026 :as fipsas]))

(defn- fixture [title date heading row]
  [(str title "\nUdine - " date "\nClassiﬁca società\nNome Regione Località Punteggio\n")
   (str title "\n" title "\nClassiﬁca " heading "\n"
        "Posizione Cognome Nome Società Anno di nascita Tempo dichiarato\n"
        row "\n")])

(deftest exact-source-and-row-citation
  (let [pages (fixture "3° Friuli Apnea Challenge" "1 febbraio 2026" "1CF - DNF"
                       " 1           De Mattia   Martina   Friulana Subacquei A.S.D.     1983                    2:00.00                                  1:28.18                 80,00                       1:28.18                80,00           0:31.82         15.0")
        sha (fipsas/source-sha256 1)
        artifact (extraction/parse-pages sha pages)
        row (first (:candidates artifact))]
    (is (= fipsas/parser-version (:parser-version artifact)))
    (is (= {:page 2 :line 5} (:coordinates row)))
    (is (= "De Mattia Martina" (get-in row [:parsed :source-name])))
    (is (= 80.00M (get-in row [:parsed :final-performance])))
    (is (= "m" (get-in row [:parsed :unit])))
    (is (= 1 (get-in artifact [:reconciliation :parsed-count])))
    (is (= :partial (get-in artifact [:reconciliation :coverage])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (fipsas/parse-pages sha (fixture "Wrong event" "1 febbraio 2026" "1CF - DNF" ""))))))

(deftest conflicting-calendar-and-printed-date
  (let [pages (fixture "6° Trofeo Apnea Life Team" "15 febbraio 2026" "STAF - STA"
                       " 1           Redoschi   Mariangela   Polisportiva Air Sub Apnea Competition    1974                 4:42.52                   3:18.27                              3:18.27                    10.0")
        row (first (:candidates (fipsas/parse-pages (fipsas/source-sha256 3) pages)))]
    (is (= "2026-02-08" (get-in row [:parsed :calendar-event-date])))
    (is (= "2026-02-15" (get-in row [:parsed :printed-event-date])))
    (is (nil? (get-in row [:parsed :event-date])))
    (is (some #{:event-date-conflict} (:unresolved-reasons row)))
    (is (= "min:sec.centisec" (get-in row [:parsed :unit])))
    (is (= "3:18.27" (get-in row [:parsed :final-performance])))))

(deftest unranked-time-result-without-printed-status
  (let [pages (fixture "4° Trofeo One Wave" "1 febbraio 2026" "END4F - END4"
                       "             Fumagalli       Barbara      Asd Nps       1969                       4:10.00                                    3:06.08                                             3:06.08")
        row (first (:candidates (fipsas/parse-pages (fipsas/source-sha256 2) pages)))]
    (is (= :parsed (:parse-status row)))
    (is (= :unknown (get-in row [:parsed :status])))
    (is (= "3:06.08" (get-in row [:parsed :final-performance])))
    (is (some #{:unprinted-status} (:unresolved-reasons row)))))

(deftest continuation-page-and-inline-disqualification
  (let [pages (conj (fixture "3° Trofeo Colapesce" "1 marzo 2026" "END4M - END4"
                             " 1           Puliafito          Marco Antonio   Blue World Freediving                  1978                 2:44.37                                  2:35.78                400,00                       2:35.78              400,00         10.0")
                    (str "3° Trofeo Colapesce\n"
                         "             Ruvolo             Domenico        Cacciatori Subacquei Messina A.S.D.    1962                 3:02.33                                  2:00.00                   1,00     DQ\n"))
        artifact (fipsas/parse-pages (fipsas/source-sha256 7) pages)
        row (second (:candidates artifact))]
    (is (= 2 (get-in artifact [:reconciliation :candidate-count])))
    (is (= {:page 3 :line 2} (:coordinates row)))
    (is (= :disqualified (get-in row [:parsed :status])))
    (is (= "DQ" (get-in row [:raw :fields :status])))
    (is (= :partial (get-in artifact [:reconciliation :coverage])))))

(defn -main [& _]
  (let [result (run-tests 'freediving.fipsas-classifica-2026-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
