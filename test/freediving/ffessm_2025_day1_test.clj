(ns freediving.ffessm-2025-day1-test
  (:require [clojure.test :refer [deftest is]]
            [freediving.extraction :as extraction]
            [freediving.ffessm-2025-day1 :as day1]))

(def header
  (str "                                                     Résultats Eau Libre J1\n"
       "                                         Championnat de France 2025 - Villefranche sur mer\n"
       "                                                                                                                     Défaut\n"
       "                                                                Profondeur                Profondeur Pénalités prof           Total\n"
       "        Compétiteurs            Sexe   Nationalité Fédération                Discipline                             plaquette          Carton      COMMENTAIRES\n"
       "                                                                Annoncée                   Réalisée     1pts/m                Points\n"
       "                                                                                                                       1pt\n"))

(def white-row
  "MARSELLA        Raphael         Homme Française   FFESSM          85 m       FIM             85 m          0                    85      Blanc")
(def yellow-row
  "PROVENZANI      Kevin           Homme Française   FFESSM          85 m       FIM             79 m          6            1       72     Jaune    Annonce non atteinte")
(def red-row
  "GOMBERT         Charles-Antoine Homme Française   FFESSM          85 m       FIM             85 m          0                     0     Rouge    DQ syncope surface")

(deftest cited-rows-preserve-printed-values
  (let [result (day1/parse-pages [(str header white-row "\n" yellow-row "\n" red-row "\n")])
        rows (:candidates result)]
    (is (= 3 (count rows)))
    (is (= [:parsed :parsed :parsed] (mapv :parse-status rows)))
    (is (= [8 9 10] (mapv #(get-in % [:coordinates :line]) rows)))
    (is (= [white-row yellow-row red-row] (mapv #(get-in % [:raw :line]) rows)))
    (is (= {:announced-depth 85M :realized-depth 79M :depth-penalty 6M
            :plate-penalty 1M :final-points 72M :card "Jaune"
            :comments "Annonce non atteinte"}
           (select-keys (get-in rows [1 :parsed])
                        [:announced-depth :realized-depth :depth-penalty :plate-penalty
                         :final-points :card :comments])))
    (is (= "DQ syncope surface" (get-in rows [2 :parsed :comments])))
    (is (= "2025-06-27" (get-in rows [0 :parsed :event-date])))
    (is (= :partial-unsupported-needs-parser (:status result)))
    (is (= day1/parser-version
           (:parser-version (extraction/parse-pages [(str header white-row "\n")]))))))

(deftest unknown-layout-remains-unparsed
  (let [bad "OTHER           Person          Homme Française   FFESSM          85 m       UNKNOWN         85 m          0                    85      Blanc"
        result (day1/parse-pages [(str header bad "\n")])]
    (is (= :unparsed (get-in result [:candidates 0 :parse-status])))
    (is (= bad (get-in result [:candidates 0 :raw :line])))
    (is (= :partial-unsupported-needs-parser (:status result)))))
