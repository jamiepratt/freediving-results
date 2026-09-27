(ns freediving.fipsas-championship-2026-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [freediving.extraction :as extraction]))

(defn- fixture [title heading row]
  [(str title "\nTorino - 12-14 giugno 2026\nClassiﬁca società\n")
   (str title "\n" title "\nClassiﬁca " heading "\n"
        "Posizione Cognome Nome Società Anno di nascita Tempo dichiarato Distanza dichiarata Tempo realizzato Distanza realizzata Penalità Tempo omologato Distanza omologata Differenza tempo Punteggio\n"
        row "\n")])

(deftest official-championship-position-preserves-evidence-and-classification
  (let [sha "6340ff918d44e6a7c9de8292a03e995251e98592fa56d1e9469f760503e1cc08"
        title "Campionati Italiani Paralimpici di Apnea Indoor (atleti ﬁsici e sensoriali)"
        pages (fixture title "1CF (2) - DNF"
                       " 1           Biolcati   Michela Aruni   Club Sommozzatori Rovigo     1985                    0:01.24                                  0:38.00                 29,00                       0:38.00                29,00           0:36.76         15.0")
        artifact (extraction/parse-pages sha pages)
        row (first (:candidates artifact))]
    (is (= "fipsas-championship-2026/1" (:parser-version artifact)))
    (is (extraction/fipsas-artifact? artifact))
    (is (= {:page 2 :line 5} (:coordinates row)))
    (is (= "Biolcati Michela Aruni" (get-in row [:parsed :source-name])))
    (is (= "1CF (2)" (get-in row [:parsed :category])))
    (is (= :physical-and-sensory (get-in row [:parsed :athlete-group])))
    (is (= 29.00M (get-in row [:parsed :final-performance])))
    (is (= "2026-06-12/2026-06-14" (get-in row [:parsed :printed-event-date])))
    (is (nil? (get-in row [:parsed :event-date])))
    (is (some #{:event-date-range} (:unresolved-reasons row)))
    (is (= :partial (get-in artifact [:reconciliation :coverage])))
    (is (= :blocked (get-in artifact [:publication :status])))))

(deftest exact-source-rejects-mismatched-pdf-title
  (is (thrown? clojure.lang.ExceptionInfo
               (extraction/parse-pages
                "6340ff918d44e6a7c9de8292a03e995251e98592fa56d1e9469f760503e1cc08"
                (fixture "Wrong championship" "1CF (2) - DNF" "")))))

(deftest absolute-championship-junior-row-is-supporting-ranking
  (let [title "Campionati Italiani Assoluti di Apnea Indoor"
        row " 1           Gabrieli     Sveva     Sc63 S.S.D.R.L.       2009                        3:45.84                                   3:34.09                                             3:34.09                             10.0"
        pages (into (fixture title "STAF - STA" row)
                    [(str title "\nClassiﬁca Junior STAF - STA\n" row "\n")])
        artifact (extraction/parse-pages
                  "a80317270e08d8b93b31778a88d12d77271cdd22b7cc50a77b945f835e5eae68"
                  pages)
        primary (first (:candidates artifact))
        support (first (:supporting-positions artifact))]
    (is (= 1 (get-in artifact [:reconciliation :candidate-count])))
    (is (= 1 (get-in artifact [:reconciliation :supporting-position-count])))
    (is (= 2 (get-in artifact [:reconciliation :printed-position-count])))
    (is (= :parsed (:parse-status primary)))
    (is (= :junior-supporting-ranking (get-in support [:parsed :source-role])))
    (is (= (:coordinates primary) (:primary-coordinates support)))
    (is (= {:page 3 :line 3} (:coordinates support)))
    (is (= :open (get-in support [:parsed :athlete-group])))
    (is (= "3:34.09" (get-in support [:parsed :final-performance])))))

(deftest relational-championship-does-not-merge-class-codes
  (let [artifact (extraction/parse-pages
                  "92bf1e849540fbf555b90043f5f66fe7c7da37c3e82fdbdf8ae1595cfd8ab7ba"
                  (fixture "Campionati Italiani Paralimpici di Apnea Indoor (atleti int. - relaz.)"
                           "1CM (21) - DNF"
                           " 1           Donaggio   Andrea    Club Sommozzatori Mestre A.S.D.     1991                    0:40.20                                  0:35.91                 25,00                       0:35.91                25,00           0:04.29         15.0"))
        row (first (:candidates artifact))]
    (is (= :parsed (:parse-status row)))
    (is (= "1CM (21)" (get-in row [:parsed :category])))
    (is (= :intellectual-and-relational (get-in row [:parsed :athlete-group])))
    (is (= 25.00M (get-in row [:parsed :final-performance])))))

(deftest sensory-championship-preserves-declaration-variants
  (let [sha "6340ff918d44e6a7c9de8292a03e995251e98592fa56d1e9469f760503e1cc08"
        title "Campionati Italiani Paralimpici di Apnea Indoor (atleti ﬁsici e sensoriali)"
        with-both (first (:candidates (extraction/parse-pages
                                       sha (fixture title "2CF (2) - DYN"
                                                    " 1           Campagnoli   Simona     U.S.S. Dario Gonzatti A.S.D.   1974                      1:00.00                   1,00           0:51.36                 50,00                       0:51.36                50,00           0:08.64         10.0"))))
        with-neither (first (:candidates (extraction/parse-pages
                                          sha (fixture title "EM (1) - DNF"
                                                       " 1           Mancin    Roberto    Asd Club Sommozzatori Padova         1971                                                             1:15.12                 39,50                              1:15.12                39,50        20.0"))))]
    (is (= :parsed (:parse-status with-both)))
    (is (= "1:00.00" (get-in with-both [:parsed :declared-time])))
    (is (= 1.00M (get-in with-both [:parsed :declared-distance])))
    (is (= 50.00M (get-in with-both [:parsed :final-performance])))
    (is (= :parsed (:parse-status with-neither)))
    (is (nil? (get-in with-neither [:parsed :declared-time])))
    (is (nil? (get-in with-neither [:parsed :declared-distance])))
    (is (some #{:declaration-not-printed} (:unresolved-reasons with-neither)))))

(deftest printed-penalty-and-record-annotation-stay-with-the-position
  (let [sha "92bf1e849540fbf555b90043f5f66fe7c7da37c3e82fdbdf8ae1595cfd8ab7ba"
        title "Campionati Italiani Paralimpici di Apnea Indoor (atleti int. - relaz.)"
        row " 7           Qualizza     Mattia    A.S.D. Pinna Sub San Vito           1991                2:00.00                            1:31.00                                                 1:31.00"
        pages (fixture title "STAM (14) - STA" (str row "\n\nPG"))
        candidate (first (:candidates (extraction/parse-pages sha pages)))]
    (is (= "PG" (get-in candidate [:raw :fields :penalty])))
    (is (some #(= "PG" (:text %)) (:source-lines candidate)))))

(defn -main [& _]
  (let [result (run-tests 'freediving.fipsas-championship-2026-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
