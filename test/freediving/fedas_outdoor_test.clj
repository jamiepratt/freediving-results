(ns freediving.fedas-outdoor-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [freediving.fedas-outdoor :as outdoor]))

(def header-2025 "FEDERACIÓN ESPAÑOLA DE ACTIVIDADES SUBACUÁTICAS\nLanzarote 20-21 de Septiembre de 2025\nRESULTADOS DEL CAMPEONATO DE ESPAÑA DE APNEA OUTDOOR")
(def header-2026 "FEDERACIÓN ESPAÑOLA DE ACTIVIDADES SUBACUÁTICAS\nRadazul, Tenerife, 04-06/09/2026\nVIII CAMPEONATO DE ESPAÑA DE APNEA OUTDOOR TENERIFE 2026")

(deftest printed-results-keep-immutable-values-and-citations
  (let [page (str header-2025 "\nCLASIFICACIÓN MASCULINA FIM\n"
                  "   1       Lluís               MELÚS PEREIRA     FECDAS       86         86                   86      OK   100,00\n"
                  "   6       Fabien                  DUCOS         FMDAS        61         58     4             54      PEN   62,79\n"
                  "           Isabel               SÁNCHEZ ARÁN       FECDAS       81                                      DNS\n")
        result (outdoor/parse-pages outdoor/outdoor-2025-sha256 [page])
        [winner penalty dns] (:candidates result)]
    (is (= 3 (count (:candidates result))))
    (is (= [5 6 7] (mapv #(get-in % [:coordinates :line]) (:candidates result))))
    (is (every? #(= (get-in % [:source-lines 0 :text]) (get-in % [:raw :line])) (:candidates result)))
    (is (= {:rank 1 :source-name "Lluís MELÚS PEREIRA" :discipline "FIM" :gender "M"
            :final-performance 86M :unit "m" :result-status :valid}
           (select-keys (:parsed winner) [:rank :source-name :discipline :gender :final-performance :unit :result-status])))
    (is (= 4M (get-in penalty [:parsed :depth-penalty])))
    (is (= 54M (get-in penalty [:parsed :final-performance])))
    (is (= "PEN" (get-in penalty [:raw :fields :status])))
    (is (= :did-not-start (get-in dns [:parsed :result-status])))
    (is (nil? (get-in dns [:parsed :rank])))
    (is (= :blocked (get-in result [:publication :status])))))

(deftest status-and-identity-boundary
  (let [page (str header-2026 "\nCLASIFICACIÓN MASCULINA CNF\n"
                  "   5       Fidel             LÓPEZ CAMPANARIO         FAAS        55          44           12    32      PEN     48,48\n"
                  "           Xan                LAMAS SERRANO          FEGAS        54                                    DQ-PS\n"
                  "   6       Broken          NAME         FAAS        xx          44           12    32      PEN     48,48\n")
        result (outdoor/parse-pages outdoor/outdoor-2026-sha256 [page])
        [penalty dq malformed] (:candidates result)]
    (is (= 3 (count (:candidates result))))
    (is (= 2 (get-in result [:reconciliation :parsed-count])))
    (is (= 1 (get-in result [:reconciliation :unparsed-count])))
    (is (= :partial-unsupported-needs-parser (:status result)))
    (is (= 32M (get-in penalty [:parsed :final-performance])))
    (is (= :disqualified (get-in dq [:parsed :result-status])))
    (is (= "DQ-PS" (get-in dq [:parsed :status])))
    (is (= :unparsed (:parse-status malformed)))
    (is (= [:owner-review-required :unparsed-source-line] (:unresolved-reasons malformed)))
    (is (false? (outdoor/supported? outdoor/outdoor-2025-sha256 [page])))))

(deftest bifins-and-federation-totals-have-distinct-scopes
  (let [page (str header-2025 "\nCLASIFICACIÓN MASCULINA CWT-BF\n"
                  "   1       Luis Alberto   GUTIERREZ GARCÍA      FAAS         95        95                     95      OK   100,00\n"
                  "CLASIFICACIÓN FINAL POR COMUNIDADES\n"
                  "FAAS      613,75    63,08                       88,30     30,68            94,19   52,33    67,50   100,00   45,26     72,41\n")
        result (outdoor/parse-pages outdoor/outdoor-2025-sha256 [page])]
    (is (= 1 (count (:candidates result))))
    (is (= {[:M "CWT-BF"] 1} (get-in result [:reconciliation :section-counts])))
    (is (= "CWT-BF" (get-in result [:candidates 0 :parsed :discipline])))))

(defn -main [& _]
  (let [result (run-tests 'freediving.fedas-outdoor-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
