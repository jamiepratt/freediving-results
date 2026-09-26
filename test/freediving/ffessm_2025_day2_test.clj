(ns freediving.ffessm-2025-day2-test
  (:require [clojure.test :refer [deftest is]]
            [freediving.ffessm-2025-day2 :as day2]))

(def header
  (str "                                                               Résultats Eau Libre J2\n"
       "                                                  Championnat de France 2025 - Villefranche-sur-mer\n"
       "                                                                    Profondeur                Profondeur   Pénalités prof    Défaut       Total\n"
       "            Compétiteurs            Sexe   Nationalité Fédération                Discipline                                                        Carton          COMMENTAIRES\n"
       "                                                                    Annoncée                   Réalisée       1pts/m      plaquette 1pt   Points\n\n"))

(def white-row
  "GOMBERT             Charles-Antoine Homme Française   FFESSM              82 m FIM              82 m             0                         82      Blanc")
(def yellow-row
  "SIMONIS             Marine         Femme Belge        FFESSM              57 m CNF              25 m            32              1           -8     Jaune    Annonce non atteinte")
(def compact-depth-row
  "GOIX                Maxime         Homme Française    FFESSM              51 m CNF               7m             44              1          -38     Jaune    Annonce non atteinte")
(def red-row
  "KANSCHINE           Sylvain        Homme Française    FFESSM              57 m CNF              57 m             0                          0      Rouge    DQ syncope surface")

(deftest day-two-rows-have-exact-source-citations-and-scores
  (let [result (day2/parse-pages [(str header white-row "\n" yellow-row "\n"
                                       compact-depth-row "\n" red-row "\n")])
        rows (:candidates result)]
    (is (= 4 (count rows)))
    (is (= [7 8 9 10] (mapv #(get-in % [:coordinates :line]) rows)))
    (is (= [white-row yellow-row compact-depth-row red-row]
           (mapv #(get-in % [:raw :line]) rows)))
    (is (every? #(= :parsed (:parse-status %)) rows))
    (is (= "2025-06-28" (get-in rows [0 :parsed :event-date])))
    (is (= {:announced-depth 57M :realized-depth 25M :depth-penalty 32M
            :plate-penalty 1M :final-points -8M :card "Jaune"}
           (select-keys (get-in rows [1 :parsed])
                        [:announced-depth :realized-depth :depth-penalty
                         :plate-penalty :final-points :card])))
    (is (= "7m" (get-in rows [2 :raw :fields :realized-depth])))
    (is (= -38M (get-in rows [2 :parsed :final-points])))
    (is (= "DQ syncope surface" (get-in rows [3 :parsed :comments])))
    (is (= :partial-unsupported-needs-parser (:status result)))))

(deftest unknown-layout-stays-visible-and-unparsed
  (let [bad "OTHER               Person         Homme Française   FFESSM              82 m XYZ              82 m             0                         82      Blanc"
        result (day2/parse-pages [(str header bad "\n")])]
    (is (= :unparsed (get-in result [:candidates 0 :parse-status])))
    (is (= bad (get-in result [:candidates 0 :raw :line])))
    (is (= 1 (get-in result [:reconciliation :unparsed-count])))
    (is (= :partial-unsupported-needs-parser (:status result)))))
