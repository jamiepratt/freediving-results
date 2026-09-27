(ns freediving.fipsas-classifica-2026-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
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
    (is (= "fipsas-classifica-2026/1" (:parser-version artifact)))
    (is (extraction/fipsas-artifact? artifact))
    (is (not (contains? (get-in row [:raw :fields]) :penalty)))
    (is (not (contains? (:parsed row) :penalty)))
    (is (not (contains? (:parsed row) :source-role)))
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

(deftest regional-standings-keep-printed-category-and-source-role
  (let [pages (fixture "22° Campionato Regionale Toscano / 5° Memorial Marino Vannucchi"
                       "8 marzo 2026" "regionale 1CF - DYN"
                       " 1           Gentile   Eva    Barracuda Sub A.P.S.D.      2010                    1:40.00                                  0:53.90                 75,00                       0:53.90                75,00           0:46.10              15.0")
        artifact (fipsas/parse-pages "f21d1ed286a97c55db8832fcee79fbdcf126822b3654d3f31d7914097a510998" pages)
        row (first (:candidates artifact))]
    (is (= "1CF" (get-in row [:parsed :category])))
    (is (= :regional-supporting-ranking (get-in row [:parsed :source-role])))
    (is (= 75.00M (get-in row [:parsed :final-performance])))
    (is (= {:page 2 :line 5} (:coordinates row)))))

(deftest championship-date-range-and-junior-standings
  (let [pages (fixture "Campionati Italiani per Categorie Indoor" "20-22 marzo 2026"
                       "Junior SPEEDF - SPEED"
                       " 1           Spotti      Carolina    Np Varedo Ssd A.R.L.        2009                       2:00.00                                  0:41.81                100,00                       0:41.81              100,00         10.0")
        artifact (fipsas/parse-pages "d28e6de1b4c353b1d9a94e283ee310a597264dd8e6d85cc31594782b61749e88" pages)
        row (first (:candidates artifact))]
    (is (= "Junior SPEEDF" (get-in row [:parsed :category])))
    (is (= "fipsas-classifica-2026/2" (:parser-version artifact)))
    (is (extraction/fipsas-artifact? artifact))
    (is (= "2026-03-20/2026-03-22" (get-in row [:parsed :printed-event-date])))
    (is (nil? (get-in row [:parsed :event-date])))
    (is (= :junior-supporting-ranking (get-in row [:parsed :source-role])))
    (is (= "0:41.81" (get-in row [:parsed :final-performance])))))

(deftest printed-penalty-preserves-approved-distance
  (let [pages (fixture "35° Trofeo Just Apnea" "14 marzo 2026" "1CM (14) - DYN"
                       " 2           Solazzo    Donato     Ranidae S.S.D.R.L.     1997                    0:25.93                                   0:34.46                 25,00      PG               0:34.46                22,00           0:08.53           9.0")
        row (first (:candidates (fipsas/parse-pages
                                 "c81c2a99ce8cf1a9ab2824d384d415791cba30d1cd372877a3a896a74f489e5c" pages)))]
    (is (= :parsed (:parse-status row)))
    (is (= "PG" (get-in row [:raw :fields :penalty])))
    (is (= 25.00M (get-in row [:parsed :realized-distance])))
    (is (= 22.00M (get-in row [:parsed :final-performance])))))

(deftest printed-distance-declaration-without-time
  (let [pages (fixture "Campionati Italiani per Categorie Indoor" "20-22 marzo 2026"
                       "M1F - DNF"
                       " 1           Sacchi     Raffaella    Pro Desenzano Tritone Sub        1972                                              129,00            2:25.38                120,50                       2:25.38              120,50         10.0")
        row (first (:candidates (fipsas/parse-pages
                                 "d28e6de1b4c353b1d9a94e283ee310a597264dd8e6d85cc31594782b61749e88" pages)))]
    (is (= :parsed (:parse-status row)))
    (is (nil? (get-in row [:parsed :declared-time])))
    (is (= 129.00M (get-in row [:parsed :declared-distance])))
    (is (= 120.50M (get-in row [:parsed :final-performance])))))

(deftest april-and-may-classifica-sources
  (doseq [[index sha title date iso-date heading row expected-performance]
          [[15 "e5e90a2286cf658b4d19c3bfbadebdad8e8813ce020c141ac788a41da61cdf76"
            "5° Trofeo Sporting Lodi Apnea" "19 aprile 2026" "2026-04-19" "1CF - DYN"
            "1           Rainero       Cristina           1° Club Lacustre Sommozzatori              1972      1:55.00                   1:54.21      126,50                  1:54.21      126,50     0:00.79         15.0" 126.50M]
           [16 "7ef04835ef26c9989f951e5e56e895816d5155c8100fc5f6e74e0a1ed14e1c68"
            "Deep Blue Freediving Contest 2026" "19 aprile 2026" "2026-04-19" "1CF - DNF"
            "1           Valdes    Tiziana    Deep Blue Sardinia      1974                    1:58.00                                  1:46.10                 85,50                       1:46.10                85,50           0:11.90         15.0" 85.50M]
           [17 "05fff83f80421555516eecaf70e8061d4ebd5b4953e14902eaacd7d84cda42fc"
            "12° Trofeo Club Subacqueo Scaligero Verona" "26 aprile 2026" "2026-04-26" "1CF - DNF"
            "1           Brambilla   Lucia   1° Club Lacustre Sommozzatori     1973                       2:18.00                                 0:58.75                 45,00                       0:58.75                45,00           1:19.25         15.0" 45.00M]
           [18 "205f29ca1108950dc9bb93dc4ce0d67435261ba9919bdec835391fd93ee2d3c8"
            "15° Trofeo Angelo Rota" "10 maggio 2026" "2026-05-10" "1CF - DNF"
            "1           Aloisio   Elena      Paviapnea                    1989                    2:10.00                                  1:27.41                 75,00                       1:27.41                75,00           0:42.59         15.0" 75.00M]]]
    (is (= sha (fipsas/source-sha256 index)))
    (let [artifact (fipsas/parse-pages sha (fixture title date heading row))
          candidate (first (:candidates artifact))]
      (is (= :parsed (:parse-status candidate)))
      (is (= expected-performance (get-in candidate [:parsed :final-performance])))
      (is (= {:page 2 :line 5} (:coordinates candidate)))
      (is (= iso-date (get-in candidate [:parsed :calendar-event-date])))
      (is (= iso-date (get-in candidate [:parsed :printed-event-date]))))))

(deftest lodi-printed-absence-has-no-performance
  (let [sha (fipsas/source-sha256 15)
        pages (fixture "5° Trofeo Sporting Lodi Apnea" "19 aprile 2026" "EM - DYNB"
                       (str "             Ferrario       Roberto             Sc63 S.S.D.R.L.                          1974                          150,00      0:00.00                                          0:00.00\n"
                            "                                                                                                                                                             Assente"))
        row (first (:candidates (fipsas/parse-pages sha pages)))]
    (is (= :parsed (:parse-status row)))
    (is (= :absent (get-in row [:parsed :status])))
    (is (nil? (get-in row [:parsed :final-performance])))
    (is (= "Assente" (get-in row [:raw :fields :status])))
    (is (some #(= "Assente" (str/trim (:text %))) (:source-lines row)))))

(deftest scaligero-elite-time-declaration-without-distance
  (let [sha (fipsas/source-sha256 17)
        pages (fixture "12° Trofeo Club Subacqueo Scaligero Verona" "26 aprile 2026" "EF - DNF"
                       "4           Andreotti   Paola        Club Sommozzatori Mestre A.S.D.        1973                    2:02.00                                  1:23.90                 68,00                       1:23.90                68,00          6.0")
        row (first (:candidates (fipsas/parse-pages sha pages)))]
    (is (= :parsed (:parse-status row)))
    (is (= "2:02.00" (get-in row [:parsed :declared-time])))
    (is (nil? (get-in row [:parsed :declared-distance])))
    (is (= 68.00M (get-in row [:parsed :final-performance])))))

(defn -main [& _]
  (let [result (run-tests 'freediving.fipsas-classifica-2026-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
