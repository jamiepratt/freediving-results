(ns freediving.ffessm-2026-regular-categories-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [freediving.ffessm-2026-regular-categories :as categories]))

(def men-sha "afd8352fcef2bdc21c284d59331dcc6330ae5f02c6a3d4b7717482a87dfee05e")
(def women-sha "63c0401ff151b566d2710aa5c3e7de9c84b84684d5c87b009fb58b0dd0875fa0")

(def men-page
  (str/join "\n"
            ["Championnat de France Eau Libre 2026"
             "Résultats Bipalme Hommes"
             "Villefranche-sur-mer" "" "CHAMPIONNAT DE FRANCE"
             "Classement Compétiteurs Sexe Nationalité Fédération Discipline COMMENTAIRES"
             "1          Bourdila       Guillaume     Homme     Française   FFESSM       96 m         CWT bi       96 m         0m                                 96       Blanc"
             "2          Jaouen         Nicolas       Homme     Française   FFESSM       90 m         CWT bi       90 m         0m                                 90       Blanc"
             "2          Isy-Schwart    Quentin       Homme     Française   FFESSM       90 m         CWT bi       90 m         0m                                 90       Blanc"
             "4          Vogler         Christian     Homme     Française   FFESSM       81 m         CWT bi       81 m         0m                                 81       Blanc"
             "5          Poggi          Alexis        Homme     Française   FFESSM       75 m         CWT bi       75 m         0m                                 75       Blanc"
             "6          Kanschine      Sylvain       Homme     Française   FFESSM       72 m         CWT bi       72 m         0m                                 72       Blanc"
             "7          Hodebourg      Marc          Homme     Française   FFESSM       70 m         CWT bi       70 m         0m                                 70       Blanc"
             "8          Desrues        Fabrice       Homme     Française   FFESSM       75 m         CWT bi       67 m         8m                      1          58       Jaune"]))

(def women-page
  (str/join "\n"
            ["Championnat de France Eau Libre 2026"
             "Résultats Sans Palmes Femmes" "Villefranche-sur-mer" ""
             "EPREUVE" "Classement Compétiteurs Sexe Nationalité Fédération Discipline COMMENTAIRES"
             "1          Le Bideau      Maelle      Femme     Française   FFESSM       55 m         CNF          55 m        0m                                55       Blanc"
             "2          Al Nabwany     Amal        Femme     Syrienne    FFESSM       42 m         CNF          42 m        0m                                42       Blanc"
             "3          Laurencon      Marie       Femme     Française   FFESSM       42 m         CNF          42 m        0m                     1          41       Jaune"
             "" "CHAMPIONNAT DE FRANCE"
             "Classement Compétiteurs Sexe Nationalité Fédération Discipline COMMENTAIRES"
             "1          Le Bideau      Maelle      Femme     Française   FFESSM       55 m         CNF          55 m       0m                                 55       Blanc"
             "2          Laurencon      Marie       Femme     Française   FFESSM       42 m         CNF          42 m       0m                      1          41       Jaune"]))

(deftest men-national-positions-and-penalty
  (let [result (categories/parse-pages men-sha [men-page])
        rows (:candidates result)
        yellow (last rows)]
    (is (categories/supported? men-sha [men-page]))
    (is (false? (categories/supported? women-sha [men-page])))
    (is (= :needs-review (:status result)))
    (is (= 8 (get-in result [:reconciliation :parsed-count])))
    (is (= {:championnat-de-france 8} (get-in result [:reconciliation :section-counts])))
    (is (= [1 2 2 4 5 6 7 8] (mapv #(get-in % [:parsed :rank]) rows)))
    (is (= "Guillaume Bourdila" (get-in (first rows) [:parsed :source-name])))
    (is (= [1 7] ((juxt :page :line) (:coordinates (first rows)))))
    (is (= "75 m" (get-in yellow [:raw :fields :depth-declared])))
    (is (= "8m" (get-in yellow [:raw :fields :depth-penalty])))
    (is (= 58M (get-in yellow [:parsed :final-performance])))
    (is (= 1 (get-in yellow [:parsed :default-tag])))
    (is (= "Jaune" (get-in yellow [:parsed :card])))
    (is (= "CWT bi" (get-in yellow [:parsed :discipline])))
    (is (nil? (get-in yellow [:parsed :event-date])))
    (is (= :blocked (get-in result [:publication :status])))))

(deftest women-open-and-national-positions-link-by-printed-values
  (let [result (categories/parse-pages women-sha [women-page])
        rows (:candidates result)]
    (is (categories/supported? women-sha [women-page]))
    (is (false? (categories/supported? men-sha [women-page])))
    (is (= :needs-review (:status result)))
    (is (= 5 (get-in result [:reconciliation :candidate-count])))
    (is (= 5 (get-in result [:reconciliation :parsed-count])))
    (is (= {:epreuve 3 :championnat-de-france 2}
           (get-in result [:reconciliation :section-counts])))
    (is (= 2 (get-in result [:reconciliation :linked-subset-count])))
    (is (= "Amal Al Nabwany" (get-in (second rows) [:parsed :source-name])))
    (is (= "Syrienne" (get-in (second rows) [:parsed :representation])))
    (is (= 41M (get-in (nth rows 2) [:parsed :final-performance])))
    (is (= [1 7] (get-in (nth rows 3) [:parsed :same-result-as])))
    (is (= [1 9] (get-in (nth rows 4) [:parsed :same-result-as])))
    (is (nil? (get-in result [:reconciliation :unique-performance-count])))))

(deftest changed-row-remains-cited-and-incomplete
  (let [changed (str/replace men-page "58       Jaune" "??       Jaune")
        result (categories/parse-pages men-sha [changed])]
    (is (= 8 (get-in result [:reconciliation :candidate-count])))
    (is (= 1 (get-in result [:reconciliation :unparsed-count])))
    (is (= :partial-unsupported-needs-parser (:status result)))
    (is (= :unparsed (:parse-status (last (:candidates result)))))))

(defn -main [& _]
  (let [result (run-tests 'freediving.ffessm-2026-regular-categories-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
