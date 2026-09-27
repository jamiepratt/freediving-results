(ns freediving.fipsas-trofeo-mare-2026-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [freediving.extraction :as extraction]
            [freediving.fipsas-trofeo-mare-2026 :as trofeo]))

(def women-page
  (str "Classifiche federali - Gara Marina di Camerota 2026\n"
       "FEMMINILE\n"
       "CNF - Promotion\n"
       "Pos. Societa' Atleta Prof. Dich. Prof. Ragg. Tempo diff tempo Early turn Prof. effettiva\n"
       "1       Water Instinct                Polido Ariza Carolina   27          27          1'09''              0              27\n"
       "CWT - Open\n"
       "3      Centro Sub Riviera Dei Fiori Grassi Susanna            42          42          1'27''              0              42\n"))

(def men-pages
  [(str "Classifiche federali - Gara Marina di Camerota 2026\nMaschili\n"
        "CWT - oltre 40 m\n"
        "1    A.S.D. Mondovi' Sub       Frigo Paolo                          60             60        2'05'' 0'07''        0              60\n")
   (str "7   Natatorium Treviso            Casini Luca                      50            36                              15             21\n"
        "CWTB - fino a 40 m\n"
        "16 Subacquei Partenopei          Rossi Marco                       28              8        0'15''              21             -13\n")])

(deftest women-source-positions-keep-printed-fields-and-citations
  (let [artifact (trofeo/parse-pages (trofeo/source-sha256 2) [women-page])
        [first-row merged-club] (:candidates artifact)]
    (is (= :partial-unsupported-needs-review (:status artifact)))
    (is (= 2 (get-in artifact [:reconciliation :parsed-count])))
    (is (= 0 (get-in artifact [:reconciliation :unparsed-count])))
    (is (= [1 5] ((juxt :page :line) (:coordinates first-row))))
    (is (= (get-in first-row [:raw :line]) (get-in artifact [:pages 0 :lines 4 :text])))
    (is (= "Polido Ariza Carolina" (get-in first-row [:parsed :source-name])))
    (is (= 27 (get-in first-row [:parsed :declared-depth])))
    (is (= 27 (get-in first-row [:parsed :reached-depth])))
    (is (= "1'09''" (get-in first-row [:parsed :time])))
    (is (= 0 (get-in first-row [:parsed :early-turn-penalty])))
    (is (= 27 (get-in first-row [:parsed :final-performance])))
    (is (= "m" (get-in first-row [:parsed :unit])))
    (is (= "2026-05-22" (get-in first-row [:parsed :calendar-event-date])))
    (is (nil? (get-in first-row [:parsed :printed-event-date])))
    (is (= "Centro Sub Riviera Dei Fiori" (get-in merged-club [:parsed :club])))
    (is (= "Grassi Susanna" (get-in merged-club [:parsed :source-name])))
    (is (= :blocked (get-in merged-club [:publication :status])))))

(deftest men-page-continuations-and-negative-effective-depth
  (let [artifact (trofeo/parse-pages (trofeo/source-sha256 3) men-pages)
        [frigo casini rossi] (:candidates artifact)]
    (is (= 3 (count (:candidates artifact))))
    (is (= "CWT" (get-in casini [:parsed :discipline])))
    (is (= "oltre 40 m" (get-in casini [:parsed :category])))
    (is (nil? (get-in casini [:parsed :time])))
    (is (some #{:time-unprinted} (:unresolved-reasons casini)))
    (is (= "2'05'' 0'07''" (get-in frigo [:raw :fields :time])))
    (is (= -13 (get-in rossi [:parsed :final-performance])))
    (is (= "-13" (get-in rossi [:raw :fields :effective-depth])))
    (is (= [2 3] ((juxt :page :line) (:coordinates rossi))))))

(deftest exact-source-identity-required
  (is (thrown? clojure.lang.ExceptionInfo
               (trofeo/parse-pages (apply str (repeat 64 "0")) [women-page]))))

(deftest public-extraction-route-recognizes-trofeo
  (let [artifact (extraction/parse-pages (trofeo/source-sha256 2) [women-page])]
    (is (= trofeo/parser-version (:parser-version artifact)))
    (is (extraction/fipsas-artifact? artifact))
    (is (= 2 (get-in artifact [:reconciliation :candidate-count])))))

(defn -main [& _]
  (let [result (run-tests 'freediving.fipsas-trofeo-mare-2026-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
