(ns freediving.ffessm-2026-bipalmes-women-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [freediving.ffessm-2026-bipalmes-women :as women]))

(def source-page
  (str/join "\n"
            ["Championnat de France Eau Libre 2026"
             "Résultats Bipalme Femmes"
             "EPREUVE"
             "1            Passalboni         Anne-Sophie     Femme     Française FFESSM           75 m             CWT bi         75 m            0m                                             75           Blanc"
             "1            Marzin             Chantal         Femme     Francaise FFESSM           75 m             CWT bi         75 m            0m                                             75           Blanc"
             "1            Simonis            Marine          Femme     Belge          FFESSM      75 m             CWT bi         75 m            0m                                             75           Blanc"
             "4            Felicioni          Gabriela        Femme     Slovakia/italiaFFESSM      55 m             CWT bi         55 m            0m                                             55           Blanc"
             "5            Swoboda            Claire          Femme     Française      FFESSM      42 m             CWT bi         42 m            0m                                             42           Blanc"
             "6            Laurencon          Marie           Femme     Française      FFESSM      55 m             CWT bi         47 m            8m                                  1          38           Jaune       Annonce non atteinte"
             "             Al Nabwany         Amal            Femme     Syrienne       FFESSM      53 m             CWT bi         49 m            4m                                  1          0            Rouge       Annonce non atteinte / tractions hors zone virage"
             "CHAMPIONNAT DE FRANCE"
             "    1        Passalboni         Anne-Sophie     Femme     Française     FFESSM                    75 m CWT bi                 75 m                                  0m                         75 Blanc"
             "    1        Marzin             Chantal         Femme     Francaise     FFESSM                    75 m CWT bi                 75 m                                  0m                         75 Blanc"
             "    3        Swoboda            Claire          Femme     Française     FFESSM                    42 m CWT bi                 42 m                                  0m                         42 Blanc"
             "    4        Laurencon          Marie           Femme     Française     FFESSM                    55 m CWT bi                 47 m                                  8m            1            38 Jaune      Annonce non atteinte"]))

(deftest printed-open-and-national-positions-reconcile
  (let [result (women/parse-pages [source-page])
        candidates (:candidates result)]
    (is (women/supported? [source-page]))
    (is (= :needs-review (:status result)))
    (is (= 11 (get-in result [:reconciliation :candidate-count])))
    (is (= {:epreuve 7 :championnat-de-france 4}
           (get-in result [:reconciliation :section-counts])))
    (is (= 11 (get-in result [:reconciliation :parsed-count])))
    (is (= 4 (get-in result [:reconciliation :linked-subset-count])))
    (is (= "CWT bi" (get-in candidates [0 :parsed :discipline])))
    (is (= "Slovakia/italia" (get-in candidates [3 :raw :fields :nationality])))
    (is (nil? (get-in candidates [6 :parsed :rank])))
    (is (= "Rouge" (get-in candidates [6 :parsed :card])))
    (is (= "Annonce non atteinte / tractions hors zone virage"
           (get-in candidates [6 :parsed :reason])))
    (is (= 0M (get-in candidates [6 :parsed :final-performance])))
    (is (= 38M (get-in candidates [5 :parsed :final-performance])))
    (is (= [1 (get-in candidates [0 :coordinates :line])]
           (get-in candidates [7 :parsed :same-result-as])))
    (is (nil? (get-in candidates [0 :parsed :event-date])))
    (is (nil? (get-in result [:reconciliation :unique-performance-count])))
    (is (= :blocked (get-in result [:publication :status])))))

(deftest changed-value-stays-visible-as-unparsed
  (let [changed (str/replace-first source-page "38           Jaune" "??           Jaune")
        result (women/parse-pages [changed])]
    (is (= 11 (get-in result [:reconciliation :candidate-count])))
    (is (= 1 (get-in result [:reconciliation :unparsed-count])))
    (is (= :unparsed (get-in result [:candidates 5 :parse-status])))
    (is (= :partial-unsupported-needs-parser (:status result)))))

(defn -main [& _]
  (let [result (run-tests 'freediving.ffessm-2026-bipalmes-women-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
