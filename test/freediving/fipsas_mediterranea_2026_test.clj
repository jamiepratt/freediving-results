(ns freediving.fipsas-mediterranea-2026-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [freediving.fipsas-mediterranea-2026 :as cup]))

(defn- page [heading rows]
  (str "Mediterranea Cup 2026\nCefalu' - 11-12 luglio 2026\n"
       "Classiﬁca " heading "\nPosizione Cognome Nome Società\n" rows))

(deftest keeps-depth-columns-and-the-printed-date-range
  (let [artifact (cup/parse-pages cup/source-sha256
                                  [(page "OPEN M - CWT OPEN"
                                         (str "                                                                                                                                                     PG\n"
                                              " 1           Sergi          Giacomo      Mediterranea A.S.D.              1996              2:30.00                 84     2:39.05            82                 2:39.05           79     0:09.05         20.0\n"
                                              "                                                                                                                                                     PG no tag\n"))])
        row (first (:candidates artifact))]
    (is (= :partial-unsupported-needs-review (:status artifact)))
    (is (= [1 6] ((juxt :page :line) (:coordinates row))))
    (is (= "Sergi Giacomo" (get-in row [:parsed :source-name])))
    (is (= "CWT" (get-in row [:parsed :discipline])))
    (is (= 84 (get-in row [:parsed :declared-depth])))
    (is (= 82 (get-in row [:parsed :realized-depth])))
    (is (= 79 (get-in row [:parsed :final-performance])))
    (is (= "PG" (get-in row [:raw :fields :penalty])))
    (is (= "PG no tag" (get-in row [:raw :fields :penalty-note])))
    (is (= "2026-07-11" (get-in row [:parsed :calendar-event-date])))
    (is (= "2026-07-11/2026-07-12" (get-in row [:parsed :printed-event-date])))
    (is (= "m" (get-in row [:parsed :unit])))
    (is (= :blocked (get-in row [:publication :status])))))

(deftest disqualification-and-unprinted-points
  (let [artifact (cup/parse-pages cup/source-sha256
                                  [(page "OPEN F - CWT OPEN"
                                         (str " DQ\n"
                                              "             Trevisan    Valentina   In Apnea A.S.D.    1993                    1:50.00                       50\n"
                                              " SP\n"
                                              " 7           Pedrazzini     Davide        S.A.E.T. Magenta   1999            1:32.00                38     1:13.38             38                 1:13.38           38     0:18.62\n"))])
        [dq ranked] (:candidates artifact)]
    (is (= :disqualified (get-in dq [:parsed :status])))
    (is (nil? (get-in dq [:parsed :final-performance])))
    (is (= "SP" (get-in dq [:raw :fields :status-note])))
    (is (= 50 (get-in dq [:parsed :declared-depth])))
    (is (= 38 (get-in ranked [:parsed :final-performance])))
    (is (nil? (get-in ranked [:parsed :points])))
    (is (some #{:printed-points-absent} (:unresolved-reasons ranked)))))

(deftest rejects-other-source
  (is (thrown? clojure.lang.ExceptionInfo
               (cup/parse-pages (apply str (repeat 64 "0")) []))))

(deftest club-aggregates-remain-separate-from-individual-positions
  (let [artifact (cup/parse-pages cup/source-sha256
                                  ["Mediterranea Cup 2026\nCefalu' - 11-12 luglio 2026\nClassiﬁca società\nNome Regione Località Punteggio\nMediterranea A.S.D.   Sicilia   Palermo   80.0\n"
                                   "Mediterranea Cup 2026\nFuori gara\nNome Regione Località Punteggio\nU.S.S. Dario Gonzatti A.S.D.   Liguria   Genova   0\n"])]
    (is (empty? (:candidates artifact)))
    (is (= [1 1] (mapv (comp count :rows) (:supporting-tables artifact))))
    (is (= [1 5] ((juxt :page :line)
                  (get-in artifact [:supporting-tables 0 :rows 0]))))
    (is (= "U.S.S. Dario Gonzatti A.S.D.   Liguria   Genova   0"
           (get-in artifact [:supporting-tables 1 :rows 0 :text])))))

(defn -main [& _]
  (let [result (run-tests 'freediving.fipsas-mediterranea-2026-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
