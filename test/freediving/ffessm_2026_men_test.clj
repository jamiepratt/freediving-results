(ns freediving.ffessm-2026-men-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [freediving.ffessm-2026-men :as men]))

(def sample-page
  (str "Championnat de France Eau Libre 2026\n"
       "Résultats Monopalme Hommes\n"
       "Villefranche-sur-mer\n"
       "Classement   Compétiteurs   Sexe   Nationalité Fédération   Profondeur Annoncée   Discipline   Profondeur Réalisée   Pénalités prof 1pts/m   Defaut Tag 1pt   Total Points   Carton   COMMENTAIRES\n"
       "1            Jaouen         Nicolas        Homme     Française   FFESSM       95 m                  CWT          95 m         0m                       95       Blanc\n"
       "1            Laffin         Eddy           Homme     Française   FFESSM       95 m                  CWT          95 m         0m                       95       Blanc\n"
       "3            Emile          Thibault       Homme     Française   FFESSM       80 m                  CWT          80 m         0m                       80       Blanc\n"
       "4            Lanoiselier    Aurélien       Homme     Française   FFESSM       76 m                  CWT          76 m         0m                       76       Blanc\n"
       "5            Buchou         Geoffrey       Homme     Française   FFESSM       75 m                  CWT          75 m         0m                       75       Blanc\n"
       "6            Maraio         Mathieu        Homme     Française   FFESSM       70 m                  CWT          70 m         0m                       70       Blanc\n"
       "7            Dromard        Christophe     Homme     Francaise   FFESSM       65 m                  CWT          65 m         0m                       65       Blanc\n"
       "             Gambini        Gilles         Homme     Française   FFESSM       90 m                  CWT          90 m         0m                       0        Rouge    DQ syncope surface\n"))

(deftest eight-printed-positions-reconcile-including-unranked-disqualification
  (let [result (men/parse-pages [sample-page])
        candidates (:candidates result)
        first-row (first candidates)
        dq-row (last candidates)]
    (is (men/supported? [sample-page]))
    (is (= :needs-review (:status result)))
    (is (= 8 (get-in result [:reconciliation :candidate-count])))
    (is (= 8 (get-in result [:reconciliation :parsed-count])))
    (is (= 0 (get-in result [:reconciliation :unparsed-count])))
    (is (= {:classement 8} (get-in result [:reconciliation :section-counts])))
    (is (= "Nicolas Jaouen" (get-in first-row [:parsed :source-name])))
    (is (= 1 (get-in first-row [:parsed :rank])))
    (is (= "95 m" (get-in first-row [:raw :fields :depth-declared])))
    (is (= "m" (get-in first-row [:parsed :unit])))
    (is (nil? (get-in first-row [:parsed :event-date])))
    (is (= "Francaise" (get-in (nth candidates 6) [:raw :fields :nationality])))
    (is (= "Gilles Gambini" (get-in dq-row [:parsed :source-name])))
    (is (nil? (get-in dq-row [:parsed :rank])))
    (is (= :disqualified (get-in dq-row [:parsed :result-status])))
    (is (= "Rouge" (get-in dq-row [:raw :fields :card])))
    (is (= "DQ syncope surface" (get-in dq-row [:parsed :reason])))
    (is (= 0M (get-in dq-row [:parsed :final-performance])))
    (is (= 8 (count (filter #(= :unreviewed (:review-status %)) candidates))))
    (is (every? #(= :owner-review-required (first (:unresolved-reasons %))) candidates))
    (is (= :blocked (get-in result [:publication :status])))))

(deftest changed-source-value-remains-cited-and-incomplete
  (let [changed (str/replace-first sample-page "95       Blanc" "??       Blanc")
        result (men/parse-pages [changed])]
    (is (= 8 (get-in result [:reconciliation :candidate-count])))
    (is (= 1 (get-in result [:reconciliation :unparsed-count])))
    (is (= :partial-unsupported-needs-parser (:status result)))
    (is (= :unparsed (:parse-status (first (:candidates result))))))
  (is (false? (men/supported? [(str/replace sample-page "Monopalme Hommes" "Monopalme Femmes")]))))

(defn -main [& _]
  (let [result (run-tests 'freediving.ffessm-2026-men-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
