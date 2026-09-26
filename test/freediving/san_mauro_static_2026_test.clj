(ns freediving.san-mauro-static-2026-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [freediving.san-mauro-static-2026 :as static]))

(def sample
  (str "    GIRO D'ITALIA IN APNEA - TROFEO SAN MAURO\n"
       "            Pomigliano d'Arco 1 marzo 2026\n"
       "                   APNEA STATICA\n"
       "                  Classifica femminile\n"
       "                                                                         TEMPO EFFETTIVO\n"
       "                                                                                             totale punti\n"
       "                SQUADRA GIA                     Atleta             M/F      INSERIRE       individuali sta\n"
       "                                                                            ( MM,SS )\n"
       " 1 In Apnea A.S.D.                  Giovagnoli Arianna              F         5,36             117,60\n"
       " 2 Water Instinct - N.B.A. Roma     Demma Shirin                    F         3,4               77,00\n"
       "                                  Classifica maschile\n"
       " 1 A.S.D. Tresse Diving Club        Beretta Lorenzo                M          6,43             141,05\n"
       " 2 Subacquei Partenopei             Oliveri Del Castillo Umberto   M          2,45             57,75\n"
       " 3 Subacquei Partenopei             Farina Marco                   M           5               105,00\n"))

(deftest static-results-preserve-source-cells-and-evidence
  (let [result (static/parse-pages static/source-sha256 [sample])
        rows (:candidates result)
        by-name (into {} (map (juxt #(get-in % [:parsed :source-name]) identity) rows))]
    (is (= "san-mauro-static-2026/1" (:parser-version result)))
    (is (= 5 (get-in result [:reconciliation :candidate-count])))
    (is (= 5 (get-in result [:reconciliation :parsed-count])))
    (is (= 0 (get-in result [:reconciliation :unparsed-count])))
    (is (= {"F" 2 "M" 3} (frequencies (map #(get-in % [:parsed :gender]) rows))))
    (is (every? #(and (= "2026-03-01" (get-in % [:parsed :event-date]))
                      (= "STA" (get-in % [:parsed :discipline]))
                      (= "min,sec" (get-in % [:parsed :unit]))
                      (= :parsed (:parse-status %))
                      (= :unreviewed (:review-status %))
                      (= (:text (first (:source-lines %))) (get-in % [:raw :line]))
                      (= 1 (get-in % [:coordinates :page]))
                      (pos? (get-in % [:coordinates :line]))) rows))
    (is (= ["Water Instinct - N.B.A. Roma" "3,4" "77,00"]
           (mapv #(get-in (by-name "Demma Shirin") [:raw :fields %])
                 [:club :time :points])))
    (is (some #{:abbreviated-time-cell} (:unresolved-reasons (by-name "Demma Shirin"))))
    (is (some #{:bare-minute-cell} (:unresolved-reasons (by-name "Farina Marco"))))
    (is (= "Oliveri Del Castillo Umberto" (get-in (by-name "Oliveri Del Castillo Umberto") [:parsed :source-name])))
    (is (= :blocked (get-in result [:publication :status])))))

(deftest static-source-binding-and-unparsed-rank-accounting
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"different source"
                        (static/parse-pages (apply str (repeat 64 "0")) [sample])))
  (let [broken (str/replace sample "2,45             57,75" "2,45             ???")
        result (static/parse-pages static/source-sha256 [broken])]
    (is (= 5 (get-in result [:reconciliation :candidate-count])))
    (is (= 4 (get-in result [:reconciliation :parsed-count])))
    (is (= 1 (get-in result [:reconciliation :unparsed-count])))
    (is (= "Oliveri Del Castillo Umberto" (get-in (last (butlast (:candidates result))) [:raw :fields :source-name])))))

(defn -main [& _]
  (let [result (run-tests 'freediving.san-mauro-static-2026-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
