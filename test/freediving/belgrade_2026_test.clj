(ns freediving.belgrade-2026-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [freediving.archive :as archive]
            [freediving.belgrade-2026 :as belgrade]
            [freediving.extraction-test :as extraction-fixture]
            [freediving.observations :as observations])
  (:import [java.security MessageDigest]
           [java.util HexFormat]))

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
    (is (= "belgrade-freediving-open-2026/2" (:parser-version r)))
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

(defn- canonical [v]
  (cond (map? v) (into (sorted-map) (map (fn [[k x]] [k (canonical x)]) v))
        (sequential? v) (mapv canonical v)
        :else v))

(deftest split-club-row-passes-public-import-evidence-validation
  (let [page (str "2026 Belgrade Freediving Open\nSerbia, Futog, 25.04.2026\n"
                  "DYNBF          Athlete              Pol              Klub         Ostvareno\n"
                  "                                                 RK Danubius\n"
                  "           Dejan Gligorić      Muško / Male                       78 DSQ SP\n"
                  "                                                   Spasilac\n")
        [root source-sha] (extraction-fixture/registered-pdf)
        identity {:source-sha256 source-sha
                  :acquisitions (:acquisitions (archive/inspect root source-sha))
                  :evidence-sha256 [] :actor "synthetic-test" :config {}
                  :parser-version "belgrade-import-evidence-contract/1"
                  :schema-version 3 :pdfinfo-version "test"
                  :tool {:name "pdftotext" :version "test" :arguments ["-layout"]}}
        job-id (.formatHex (HexFormat/of)
                           (.digest (MessageDigest/getInstance "SHA-256")
                                    (.getBytes (pr-str (canonical identity)) "UTF-8")))
        artifact (merge identity (belgrade/parse-pages [page])
                        {:job-id job-id :parser-version (:parser-version identity)
                         :processed-at "2026-09-26T12:00:00Z"
                         :pdf-page-count 1 :raw-text page})]
    (archive/derive! root job-id (constantly artifact) nil)
    ;; import! validates all page evidence before attempting a database connection.
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Database URL required"
                          (observations/import! nil root job-id)))))

(defn -main []
  (let [result (run-tests 'freediving.belgrade-2026-test)]
    (System/exit (if (pos? (+ (:fail result) (:error result))) 1 0))))
