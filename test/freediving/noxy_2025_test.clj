(ns freediving.noxy-2025-test
  (:require [clojure.test :refer [deftest is]]
            [freediving.noxy-2025 :as noxy]))

(defn distance-page [discipline day women men]
  (str "nOxyCup 2025 - Hungarian Open Indoor Apnea Championship\n"
       "BUDAPEST HUNGARY\n\n"
       discipline " FINAL RESULTS\nNOV., " day ", 2025\n\n"
       "SENIORS WOMEN\n"
       "# Name & Surname    Country    Realized Distance (m) Final Distance (m)    Notes\n"
       women "\n\nSENIORS MEN\n"
       "# Name & Surname    Country    Realized Distance (m) Final Distance (m)    Notes\n"
       men "\n"))

(defn sta-page []
  (str "nOxyCup 2025 - Hungarian Open Indoor Apnea Championship\n"
       "BUDAPEST HUNGARY\n\nSTA - FINAL RESULTS\nNOV., 15, 2025\n\n"
       "SENIORS - WOMEN\n# Name & Surname    Country    Final Result    Notes\n"
       "1    Heike Monika Schwerdtner    GER    09:30    NEW WORLD RECORD\n"
       "Pascale Gigon    FRA    DNS\n\n"
       "SENIORS - MEN\nNo Name & Surname    Country    Final Result    Notes\n"
       "1    Goran Čolak    CRO    09:22\n"
       "Aldo Montero    PER    00:00    DQ SURFACE BO\n"))

(def fixture-pages
  [(distance-page "DYN-BF - DYNAMIC WITH BI-FINS" "16"
                  "1 Maria Cordova    ECU    231,0    231,0\nMarie-Lorraine Weiss    BEL    DNS"
                  "1 Goran Čolak    CRO    250,0    250,0\nMiguel Oliveira    PRT    DNS")
   "Boris Milosic   CRO   DNS\n"
   (sta-page)
   (distance-page "DNF - DYNAMIC NO FINS" "15"
                  "1 Hinatea Penilla Y Perella    FRA    149,5    149,5\nAlison Wheeler    GBR    90,0    0,0    DQ SP"
                  "1 Karol Karcz    POL    180,5    180,5\nMiguel Oliveira    PRT    DNS")
   (distance-page "DYN - DYNAMIC WITH FINS" "16"
                  "1 Hinatea Penilla Y Perella    FRA    220,5    220,5"
                  "1 Karol Karcz    POL    250,0    250,0")])

(deftest printed-final-results-keep-citations-and-original-values
  (let [result (noxy/parse-pages fixture-pages)
        candidates (:candidates result)
        first-row (first candidates)
        continuation (first (filter #(= 2 (get-in % [:coordinates :page])) candidates))
        record (first (filter #(= "Heike Monika Schwerdtner" (get-in % [:parsed :source-name])) candidates))
        disqualified (first (filter #(= "Alison Wheeler" (get-in % [:parsed :source-name])) candidates))]
    (is (noxy/supported? fixture-pages))
    (is (= noxy/parser-version (:parser-version result)))
    (is (= 15 (count candidates)))
    (is (= [1 9] ((juxt #(get-in % [:coordinates :page]) #(get-in % [:coordinates :line])) first-row)))
    (is (= "231,0" (get-in first-row [:raw :fields :final-distance])))
    (is (= 231.0M (get-in first-row [:parsed :final-distance])))
    (is (= "2025-11-16" (get-in first-row [:parsed :event-date])))
    (is (= "DYN-BF" (get-in continuation [:parsed :discipline])))
    (is (= "Seniors - Men" (get-in continuation [:parsed :category])))
    (is (= "DNS" (get-in continuation [:parsed :status])))
    (is (nil? (get-in continuation [:parsed :final-distance])))
    (is (= [2 1] ((juxt #(get-in % [:coordinates :page]) #(get-in % [:coordinates :line])) continuation)))
    (is (= "09:30" (get-in record [:raw :fields :final-result])))
    (is (= "NEW WORLD RECORD" (get-in record [:parsed :notes])))
    (is (= "s" (get-in record [:parsed :unit])))
    (is (= 570 (get-in record [:parsed :final-time-seconds])))
    (is (= "DQ SP" (get-in disqualified [:parsed :status])))
    (is (= 0.0M (get-in disqualified [:parsed :final-distance])))
    (is (= [4 1 4 4 2] (mapv :candidate-count (get-in result [:reconciliation :per-page]))))
    (is (= :blocked (get-in result [:publication :status])))))

(deftest unexpected-source-layout-remains-partial-and-accounted-for
  (let [pages (assoc fixture-pages 4 (str (fixture-pages 4) "\nMalformed    XYZ    ambiguous\n"))
        result (noxy/parse-pages pages)]
    (is (= :partial-unsupported-needs-parser (:status result)))
    (is (= 1 (get-in result [:reconciliation :unparsed-count])))
    (is (= "Malformed    XYZ    ambiguous" (get-in (last (:candidates result)) [:raw :line])))
    (is (= 1 (count (filter #(= :unparsed (:parse-status %)) (:candidates result)))))))
