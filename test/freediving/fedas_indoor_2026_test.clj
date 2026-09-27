(ns freediving.fedas-indoor-2026-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [freediving.fedas-indoor-2026 :as fedas]))

(def heading "CLASIFICACIÓN FINAL POR PRUEBAS\nCLASIFICACIÓN MASCULINA DYN")
(def ranked
  " 24       David            CERRATO TOMÀS     FBDAS - Balear       125    142,70   1   139,70   PEN   60,59")
(def unranked
  "           Aurora       SANTOME DAVILA              FEGAS - Gallega            102                             DQ-Vias")

(deftest retained-indoor-positions-preserve-printed-columns
  (let [result (fedas/parse-pages fedas/source-2026-sha256
                                  [(str heading "\n" ranked "\n" unranked)])
        [first-row second-row] (:candidates result)]
    (is (= :needs-review (:status result)))
    (is (= 2 (get-in result [:reconciliation :candidate-count])))
    (is (= 2 (get-in result [:reconciliation :parsed-count])))
    (is (= {:page 1 :line 3 :column-start 1}
           (select-keys (:coordinates first-row) [:page :line :column-start])))
    (is (= ranked (get-in first-row [:raw :line])))
    (is (= "David CERRATO TOMÀS" (get-in first-row [:parsed :source-name])))
    (is (= "FBDAS - Balear" (get-in first-row [:parsed :representation])))
    (is (= "DYN" (get-in first-row [:parsed :discipline])))
    (is (= "M" (get-in first-row [:parsed :gender])))
    (is (= 139.70M (get-in first-row [:parsed :final-performance])))
    (is (= "1" (get-in first-row [:raw :fields :penalty])))
    (is (= "PEN" (get-in first-row [:parsed :status])))
    (is (= 60.59M (get-in first-row [:parsed :points])))
    (is (nil? (get-in first-row [:parsed :unit])))
    (is (nil? (get-in first-row [:parsed :event-year])))
    (is (= {:event-year "2026" :basis :acquisition-route-not-pdf}
           (:source-context result)))
    (is (nil? (get-in second-row [:parsed :rank])))
    (is (= "DQ-Vias" (get-in second-row [:parsed :status])))
    (is (= "102" (get-in second-row [:raw :fields :announced-performance])))))

(deftest continuation-pages-keep-section-and-exclude-other-rankings
  (let [result (fedas/parse-pages fedas/source-2026-sha256
                                  [heading
                                   (str " 17   Léo            TRIPIANA           FASRM - Murcia       5:17   5:35   5:35   OK   64,80\n"
                                        "CLASIFICACIÓN POR COMUNIDADES\n"
                                        "FASCV - Valenciana   1061,36 92,84")])]
    (is (= 1 (get-in result [:reconciliation :candidate-count])))
    (is (= {:page 2 :line 1}
           (select-keys (get-in result [:candidates 0 :coordinates]) [:page :line])))))

(deftest malformed-position-stays-unresolved
  (let [bad "  1        Garikoitz        IRURETAGOIENA URKOLA   EHUIF - Vasca          8:41      ???              8:37     OK   100,00"
        result (fedas/parse-pages fedas/source-2026-sha256
                                  [(str "CLASIFICACIÓN FINAL POR PRUEBAS\nCLASIFICACIÓN MASCULINA STA\n" bad)])]
    (is (= 1 (get-in result [:reconciliation :candidate-count])))
    (is (= :unparsed (get-in result [:candidates 0 :parse-status])))
    (is (= :partial-unsupported-needs-parser (:status result)))))

(deftest exact-source-hash-required
  (is (= :partial-unsupported-needs-parser
         (:status (fedas/parse-pages "wrong" [(str heading "\n" ranked)])))))

(defn -main [& _]
  (let [result (run-tests 'freediving.fedas-indoor-2026-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
