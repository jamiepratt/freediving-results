(ns freediving.fipsas-monsub-2026-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [freediving.fipsas-monsub-2026 :as monsub]))

(defn- sample-page [heading rows]
  (str "22° Trofeo Monsub / Memorial Luigino Ceppi\n"
       "Classiﬁca " heading "\n" rows))

(deftest source-bound-monsub-positions
  (let [dnf (monsub/parse-pages monsub/dnf-source-sha256
                                [(sample-page "3CM - DNF"
                                              (str " 1           Lazzaretti   Enrico     Sub Rimini \"Gian Neri\"   1991                    1:30.00                                  1:27.55                 75,00                         1:27.55                75,00           0:02.45           5.0\n"
                                                   " BO\n"
                                                   "             Andreucci    Mirco      Monsub Jesi              1986                    1:31.00\n"
                                                   " Superficie\n"))])
        dyn (monsub/parse-pages monsub/dyn-source-sha256
                                [(sample-page "ESF - DYN"
                                              (str " DQ\n"
                                                   "             Di Tullio   Marzia   A.S.D. Apnea Team Abruzzo   1991             0:43.00                       0:56.06        58,50\n"
                                                   " Superato distanza massima cat.\n"))])]
    (is (= [1 1] (mapv #(get-in % [:coordinates :page]) (:candidates dnf))))
    (is (= [3 5] (mapv #(get-in % [:coordinates :line]) (:candidates dnf))))
    (is (= 75.00M (get-in dnf [:candidates 0 :parsed :final-performance])))
    (is (= :blackout (get-in dnf [:candidates 1 :parsed :status])))
    (is (nil? (get-in dnf [:candidates 1 :parsed :final-performance])))
    (is (= :disqualified (get-in dyn [:candidates 0 :parsed :status])))
    (is (= "Superato distanza massima cat." (get-in dyn [:candidates 0 :raw :fields :status-note])))
    (is (= "2026-04-19" (get-in dyn [:candidates 0 :parsed :calendar-event-date])))
    (is (nil? (get-in dyn [:candidates 0 :parsed :printed-event-date])))
    (is (every? #(= :blocked (get-in % [:publication :status]))
                (concat (:candidates dnf) (:candidates dyn))))
    (is (= [2 1] (mapv #(get-in % [:reconciliation :candidate-count]) [dnf dyn])))
    (is (every? #(= :partial (get-in % [:reconciliation :coverage])) [dnf dyn]))))

(deftest elite-disqualification-with-declared-distance-only
  (let [artifact (monsub/parse-pages monsub/dyn-source-sha256
                                     [(sample-page "EM - DYNB"
                                                   (str " DQ\n"
                                                        "             Palombarani    Marco       Asd Centro Sub Monte Conero        1984                                           183,00\n"))])
        row (first (:candidates artifact))]
    (is (= :parsed (:parse-status row)))
    (is (= :disqualified (get-in row [:parsed :status])))
    (is (= 183.00M (get-in row [:parsed :declared-distance])))
    (is (nil? (get-in row [:parsed :final-performance])))))

(deftest rejects-other-pdfs
  (is (thrown? clojure.lang.ExceptionInfo
               (monsub/parse-pages (apply str (repeat 64 "0")) []))))

(defn -main [& _]
  (let [result (run-tests 'freediving.fipsas-monsub-2026-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
