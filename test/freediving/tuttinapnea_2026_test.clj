(ns freediving.tuttinapnea-2026-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [freediving.tuttinapnea-2026 :as tutt]))

(def heading "RESOCONTO GARA NAZIONALE\n\" TUTTINAPNEA FEBBRAIO 2026 \"\n")
(def static-heading (str heading "STATICA\nSocietà / Gruppo / Associazione  CITTA'  REGIONE  Atleta  Sesso  Tempo Effettuato  Cartellino GIALLO  Cartellino ROSSO  PUNTEGGIO STATICA\n"))
(def dynamic-heading (str heading "DINAMICA\nSocietà / Gruppo / Associazione  CITTA'  REGIONE  Atleta  Sesso  Specialità  Distanza raggiunta  Cartellino GIALLO  Cartellino ROSSO  PUNTEGGIO DINAMICA\n"))

(deftest static-sheet-keeps-printed-time-penalty-and-null-result
  (let [page (str static-heading
                  "IN APNEA             ROMA             LAZIO           MARICA SANTACROCE       F       3,55           10             74,025\n"
                  "BREATHLESS                           LOMBARDIA       Cristina Mirizzi        F       3,4                           77\n"
                  "MONDOVI'             MONDOVI' (CN)     PIEMONTE        Silvio Giordano         M                         X       PN\n")
        result (tutt/parse-pages tutt/static-sha256 [page])
        rows (:candidates result)]
    (is (= :needs-review (:status result)))
    (is (= {:page-count 1 :candidate-count 3 :parsed-count 3 :unparsed-count 0 :unresolved-count 3}
           (select-keys (:reconciliation result) [:page-count :candidate-count :parsed-count :unparsed-count :unresolved-count])))
    (is (= "3,55" (get-in rows [0 :parsed :realised-performance])))
    (is (= "10" (get-in rows [0 :parsed :yellow-card])))
    (is (= "74,025" (get-in rows [0 :parsed :points])))
    (is (nil? (get-in rows [1 :parsed :city])))
    (is (= "2026-02" (get-in rows [0 :parsed :event-month])))
    (is (nil? (get-in rows [0 :parsed :event-date])))
    (is (= "PN" (get-in rows [2 :parsed :points])))
    (is (= "X" (get-in rows [2 :parsed :red-card])))
    (is (= :null-result (get-in rows [2 :parsed :status])))
    (is (= {:page 1 :line 5} (select-keys (:coordinates (first rows)) [:page :line])))))

(deftest dynamic-sheet-keeps-type-distance-and-null-result
  (let [page (str dynamic-heading
                  "SOTTOPRESSIONE      MILANO           LOMBARDIA      SILVANA LONGONI       F       B       119       10       107,1\n"
                  "MILANO IN APNEA     MILANO           LOMBARDIA      BESCHI LUCA           M       M                       X\n")
        result (tutt/parse-pages tutt/dynamic-sha256 [page])
        rows (:candidates result)]
    (is (= 2 (get-in result [:reconciliation :candidate-count])))
    (is (= "B" (get-in rows [0 :parsed :type])))
    (is (= "119" (get-in rows [0 :parsed :realised-performance])))
    (is (= "m" (get-in rows [0 :parsed :unit])))
    (is (= "M" (get-in rows [1 :parsed :type])))
    (is (= :null-result (get-in rows [1 :parsed :status])))
    (is (= :blocked (get-in result [:publication :status])))))

(deftest source-binding-and-damaged-row-remain-explicit
  (let [page (str static-heading "IN APNEA  ROMA  LAZIO  SOMEONE  F  invalid  99\n")
        result (tutt/parse-pages tutt/static-sha256 [page])]
    (is (= 1 (get-in result [:reconciliation :candidate-count])))
    (is (= 0 (get-in result [:reconciliation :parsed-count])))
    (is (= :unparsed (get-in result [:candidates 0 :parse-status])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (tutt/parse-pages (apply str (repeat 64 "0")) [page])))))

(deftest lowercase-red-card-preserves-source-spelling
  (let [page (str static-heading "MONDAPNEA  TORINO  PIEMONTE  BONINO ROBERTO  M  x  PN\n")
        row (first (:candidates (tutt/parse-pages tutt/static-sha256 [page])))]
    (is (= :parsed (:parse-status row)))
    (is (= "x" (get-in row [:parsed :red-card])))
    (is (= :null-result (get-in row [:parsed :status])))))

(defn -main [& _]
  (let [result (run-tests 'freediving.tuttinapnea-2026-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
