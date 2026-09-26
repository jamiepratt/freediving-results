(ns freediving.ffessm-2026-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.string :as str]
            [freediving.ffessm-2026 :as ffessm]))

(defn row [rank surname given nationality announced reached penalty tag points card]
  (format "%s            %-14s %-13s Femme     %-16s FFESSM    %s m         CWT          %s m         %sm                      %-3s        %s       %s"
          rank surname given nationality announced reached penalty (or tag "") points card))

(def sample-page
  (str "Championnat de France Eau Libre 2026\nRésultats Monopalme Femmes\n"
       "EPREUVE\nClassement Compétiteurs Sexe Nationalité Fédération Profondeur Discipline\n"
       (row 1 "Alpha" "Ada" "Belge" 85 85 0 nil 85 "Blanc") "\n"
       (row 2 "Beta" "Bea" "Française" 80 80 0 nil 80 "Blanc") "\n"
       (row 3 "Gamma" "Gia" "Française" 77 77 0 nil 77 "Blanc") "\n"
       (row 4 "Delta" "Dee" "Française" 75 75 0 nil 75 "Blanc") "\n"
       (row 5 "Epsilon" "Eve" "Francaise" 77 74 3 1 70 "Jaune") "    Annonce non atteinte\n"
       (row 6 "Zeta" "Zoe" "Slovakia/italia" 60 60 0 nil 60 "Blanc") "\n"
       "CHAMPIONNAT DE FRANCE\nClassement Compétiteurs Sexe Nationalité Fédération Profondeur Discipline\n"
       (row 1 "Beta" "Bea" "Française" 80 80 0 nil 80 "Blanc") "\n"
       (row 2 "Gamma" "Gia" "Française" 77 77 0 nil 77 "Blanc") "\n"
       (row 3 "Delta" "Dee" "Française" 75 75 0 nil 75 "Blanc") "\n"
       (row 4 "Epsilon" "Eve" "Francaise" 77 74 3 1 70 "Jaune") "    Annonce non atteinte\n"))

(deftest two-rankings-reconcile-without-counting-ten-distinct-performances
  (let [result (ffessm/parse-pages [sample-page])
        candidates (:candidates result)
        first-row (first candidates)
        penalty-row (nth candidates 4)
        subset-row (nth candidates 6)]
    (is (= 10 (get-in result [:reconciliation :candidate-count])))
    (is (= {:epreuve 6 :championnat-de-france 4}
           (get-in result [:reconciliation :section-counts])))
    (is (= 4 (get-in result [:reconciliation :linked-subset-count])))
    (is (= :needs-review (:status result)))
    (is (= "Ada Alpha" (get-in first-row [:parsed :source-name])))
    (is (= "85 m" (get-in first-row [:raw :fields :depth-declared])))
    (is (= "Jaune" (get-in penalty-row [:parsed :card])))
    (is (= 70M (get-in penalty-row [:parsed :final-performance])))
    (is (= "Annonce non atteinte" (get-in penalty-row [:parsed :reason])))
    (is (= "Slovakia/italia" (get-in (nth candidates 5) [:raw :fields :nationality])))
    (is (= 10 (count (filter #(= :unreviewed (:review-status %)) candidates))))
    (is (= :championnat-de-france (get-in subset-row [:parsed :ranking-scope])))
    (is (= [1 (get-in (second candidates) [:coordinates :line])]
           (get-in subset-row [:parsed :same-result-as])))
    (is (= 1 (get-in subset-row [:coordinates :page])))
    (is (pos? (get-in subset-row [:coordinates :line])))
    (is (= :blocked (get-in result [:publication :status])))))

(deftest changed-source-shape-remains-visible-and-incomplete
  (let [page (str/replace-first sample-page "70       Jaune" "??       Jaune")
        result (ffessm/parse-pages [page])]
    (is (= 10 (get-in result [:reconciliation :candidate-count])))
    (is (= 1 (get-in result [:reconciliation :unparsed-count])))
    (is (= :partial-unsupported-needs-parser (:status result)))
    (is (= :unparsed (:parse-status (nth (:candidates result) 4))))))

(defn -main [& _]
  (let [result (run-tests 'freediving.ffessm-2026-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
