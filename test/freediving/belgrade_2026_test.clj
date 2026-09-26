(ns freediving.belgrade-2026-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [freediving.belgrade-2026 :as belgrade]))

(def sample-page
  (str "2026 Belgrade Freediving Open\n"
       "Serbia, Futog, 25.04.2026\n"
       " DNF          Athlete               Pol              Klub         Ostvareno\n"
       "  1        Tijana Nikolić     Žensko / Female   RK Sebastijan       106\n"
       " DNF          Athlete               Pol              Klub         Ostvareno\n"
       "  1      Nenad Pavković        Muško / Male      KPA Spartak        177.5\n"
       " 11     ALEKSANDAR BEREC       Muško / Male     KPA “Beograd”       DNS\n"
       "       VID BELOVIĆ BODROŽA     Muško / Male     KPA “Beograd”     49 DSQ SP\n"))

(deftest belgrade-dnf-preserves-ranked-and-unranked-results
  (let [r (belgrade/parse-pages [sample-page])
        cs (:candidates r)]
    (is (= belgrade/parser-version (:parser-version r)))
    (is (= 3 (:schema-version r)))
    (is (= :needs-review (:status r)))
    (is (= 4 (get-in r [:reconciliation :candidate-count])))
    (is (= 4 (get-in r [:reconciliation :parsed-count])))
    (is (= [4 6 7 8] (mapv #(get-in % [:coordinates :line]) cs)))
    (is (= ["Female" "Male" "Male" "Male"]
           (mapv #(get-in % [:parsed :category]) cs)))
    (is (= [1 1 11 nil] (mapv #(get-in % [:parsed :rank]) cs)))
    (is (= ["106" "177.5" "DNS" "49 DSQ SP"]
           (mapv #(get-in % [:parsed :result]) cs)))
    (is (= ["valid" "valid" "DNS" "DSQ SP"]
           (mapv #(get-in % [:parsed :result-status]) cs)))
    (is (= "VID BELOVIĆ BODROŽA" (get-in (last cs) [:parsed :source-name])))
    (is (= "       VID BELOVIĆ BODROŽA     Muško / Male     KPA “Beograd”     49 DSQ SP"
           (get-in (last cs) [:raw :line])))
    (is (= :blocked (get-in r [:publication :status])))))

(deftest belgrade-special-discipline-and-uncertain-club
  (let [r (belgrade/parse-pages
           [(str "2026 Belgrade Freediving Open\nSerbia, Futog, 25.04.2026\n"
                 " SPE\n              Athlete               Pol              Klub         Ostvareno\n 2x50\n"
                 "  1       Valentina Cafolla   Žensko / Female    RK Submania      00:41,53\n")
            (str "2026 Belgrade Freediving Open\nSerbia, Futog, 25.04.2026\n"
                 "DYNBF          Athlete              Pol              Klub         Ostvareno\n"
                 "                                                 RK Danubius\n"
                 "           Dejan Gligorić      Muško / Male                       78 DSQ SP\n"
                 "                                                   Spasilac\n")])
        [a b] (:candidates r)]
    (is (= 2 (get-in r [:reconciliation :candidate-count])))
    (is (= "SPE 2x50" (get-in a [:parsed :discipline])))
    (is (= "00:41,53" (get-in a [:parsed :result])))
    (is (= "DYNBF" (get-in b [:parsed :discipline])))
    (is (nil? (get-in b [:parsed :club])))
    (is (= [4 6] (mapv :line (get-in b [:raw :adjacent-source-evidence]))))
    (is (some #{:club-unresolved} (:unresolved-reasons b)))))

(deftest changed-heading-is-not-silently-parsed
  (let [r (belgrade/parse-pages
           [(str "2026 Belgrade Freediving Open\nSerbia, Futog, 25.04.2026\n"
                 "XYZ          Athlete              Pol              Klub         Ostvareno\n"
                 "  1        Tijana Nikolić     Žensko / Female   RK Sebastijan       106\n")])]
    (is (= [] (:candidates r)))
    (is (= [1] (get-in r [:reconciliation :unsupported-pages])))
    (is (= :partial-unsupported-needs-parser (:status r)))))

(defn -main []
  (let [result (run-tests 'freediving.belgrade-2026-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
