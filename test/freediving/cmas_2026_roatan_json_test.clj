(ns freediving.cmas-2026-roatan-json-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [freediving.cmas-2026-roatan-json :as roatan]))

(def routes
  {3551 {:view-url "https://cmas.microplustimingservices.com/#/event-detail/FRD/30/110/661/594/3551/result"
         :json-url "https://cmas-api.microplustimingservices.com/api/units/3551/results"
         :event 661 :phase 594 :date "2026-08-17T00:00:00.000Z"}
   3559 {:view-url "https://cmas.microplustimingservices.com/#/event-detail/FRD/30/110/655/588/3559/result"
         :json-url "https://cmas-api.microplustimingservices.com/api/units/3559/results"
         :event 655 :phase 588 :date "2026-08-21T00:00:00.000Z"}})

(defn row [unit]
  (let [{:keys [event phase date]} (routes unit)]
    {"DCCmpID" 30 "DCDisCODE" "FRD" "UtID" unit "EvID" event "UtPhID" phase
     "EvCatID" 110 "CatID" 110 "EvShortDescr" "CWT"
     "EvLongDescr" "CONSTANT WEIGHT WITH FINS" "EvStartDate" date
     "PhLongDescr" "MEN" "DisCODE" "FRD" "ResID" 100
     "ParID" 200 "ParPrintName" "EXAMPLE Ada" "ParOrgCode" "USA"
     "ResAGID" 213 "AGCodeDescr" "SENM" "AGLongDescr" "Seniors"
     "ResResultType" "POINT" "ResResult" "80" "ResResultFinal" "80"
     "DECLLEN_STR" "82"}))

(defn source [rows] (.getBytes (json/write-str rows) "UTF-8"))
(def category-values
  {"SENM" [213 "Seniors"] "M1M" [214 "Masters M1"]
   "M2M" [215 "Masters M2"] "M3M" [216 "Masters M3"]})

(deftest roatan-cwt-men-source-census
  (doseq [[unit row-count category-counts] [[3551 7 {"SENM" 7}]
                                            [3559 24 {"SENM" 16 "M1M" 5 "M2M" 1 "M3M" 2}]]]
    (let [rows (mapv (fn [index]
                       (let [category (nth (mapcat (fn [[label n]] (repeat n label)) category-counts) index)]
                         (assoc (row unit) "ResID" (+ 100 index) "AGCodeDescr" category
                                "ResAGID" (first (category-values category))
                                "AGLongDescr" (second (category-values category)))))
                     (range row-count))
          parsed (roatan/parse-result (source rows)
                                      (select-keys (routes unit) [:view-url :json-url]))]
      (is (= roatan/parser-version (:parser-version parsed)))
      (is (= row-count (get-in parsed [:reconciliation :source-row-count])))
      (is (= row-count (count (:candidates parsed))))
      (is (= [] (:unparsed-rows parsed)))
      (is (= (vec (range row-count))
             (mapv #(get-in % [:coordinates :row-index-zero-based]) (:candidates parsed))))
      (is (= rows (mapv :raw (:candidates parsed))))
      (is (every? #(= {:attempt-equivalence :unknown :source-finality :unknown
                       :revision-status :unknown :review-status :unreviewed
                       :selection-status :blocked}
                      (select-keys % [:attempt-equivalence :source-finality :revision-status
                                      :review-status :selection-status]))
                  (:candidates parsed))))))

(deftest roatan-route-and-layout-must-agree
  (let [provenance (select-keys (routes 3551) [:view-url :json-url])
        bytes (source [(row 3551)])]
    (testing "one row can be parsed without claiming a complete result"
      (is (= 1 (count (:candidates (roatan/parse-result bytes provenance))))))
    (testing "a different unit response cannot masquerade as this page"
      (is (thrown? Exception
                   (roatan/parse-result bytes
                                        (assoc provenance :json-url (:json-url (routes 3559)))))))
    (testing "a changed event or category fails closed"
      (is (thrown? Exception
                   (roatan/parse-result (source [(assoc (row 3551) "EvID" 655)]) provenance)))
      (is (thrown? Exception
                   (roatan/parse-result (source [(assoc (row 3551) "AGCodeDescr" "SENF")]) provenance))))))
