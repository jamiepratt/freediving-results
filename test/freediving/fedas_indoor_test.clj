(ns freediving.fedas-indoor-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [freediving.fedas-indoor :as fedas]))

(def page-2025
  (str "RESULTADOS DEL CAMPEONATO DE ESPAÑA DE APNEA INDOOR\n"
       "Vitoria 26-27 de Abril de 2025\n"
       "CLASIFICACIÓN MASCULINA STA\n"
       "Posición        Nombre            Apellido       Comunidad           OBTENIDA    ESTADO PUNT.\n"
       "   1       Garikoitz         IRURETAGOIENA     Euskadi                 8:08        OK    100,00\n"
       "   2       Antonio José      TALAVERA          Canarias                7:14        OK     88,93"))

(deftest spanish-indoor-2025-positions-have-source-citations
  (let [result (fedas/parse-pages fedas/source-2025-sha256 [page-2025])
        first-row (first (:candidates result))]
    (is (= :needs-review (:status result)))
    (is (= 2 (get-in result [:reconciliation :parsed-count])))
    (is (= {:page 1 :line 5 :column-start 1}
           (select-keys (:coordinates first-row) [:page :line :column-start])))
    (is (= "Garikoitz IRURETAGOIENA" (get-in first-row [:parsed :source-name])))
    (is (= "8:08" (get-in first-row [:parsed :final-performance])))
    (is (= "STA" (get-in first-row [:parsed :discipline])))
    (is (= "M" (get-in first-row [:parsed :gender])))
    (is (= "2025-04-26" (get-in first-row [:parsed :event-date])))
    (is (= (nth (str/split-lines page-2025) 4) (get-in first-row [:raw :line])))))

(deftest spanish-indoor-unranked-statuses-remain-results
  (let [page (str page-2025 "\n"
                  "           Oleksiy           KRYSANOV          C. Valenciana                     DQ-NPS\n"
                  "           Carlos Thomas     GARCIA            Cantabria                           DNS")
        result (fedas/parse-pages fedas/source-2025-sha256 [page])]
    (is (= 4 (get-in result [:reconciliation :candidate-count])))
    (is (= 4 (get-in result [:reconciliation :parsed-count])))
    (is (nil? (get-in result [:candidates 2 :parsed :rank])))
    (is (= "DQ-NPS" (get-in result [:candidates 2 :parsed :status])))
    (is (= "DNS" (get-in result [:candidates 3 :parsed :status])))))

(deftest changed-position-remains-unresolved
  (let [page (str page-2025 "\n   3       Javier            PASCUAL           Euskadi                 ???         OK     80,00")
        result (fedas/parse-pages fedas/source-2025-sha256 [page])]
    (is (= 3 (get-in result [:reconciliation :candidate-count])))
    (is (= 1 (get-in result [:reconciliation :unparsed-count])))
    (is (= :unparsed (get-in result [:candidates 2 :parse-status])))
    (is (= :partial-unsupported-needs-parser (:status result)))))

(defn -main [& _]
  (let [result (run-tests 'freediving.fedas-indoor-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
