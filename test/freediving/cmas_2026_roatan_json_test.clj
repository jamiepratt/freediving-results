(ns freediving.cmas-2026-roatan-json-test
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [freediving.archive :as archive]
            [freediving.archive-test :as fixture]
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

(defn registered-source [root unit rows]
  (let [bytes (source rows)
        sha (.formatHex (java.util.HexFormat/of)
                        (.digest (java.security.MessageDigest/getInstance "SHA-256") bytes))
        path (str root "-source-" unit ".json")
        {:keys [view-url json-url]} (routes unit)
        manifest (assoc fixture/manifest :sha256 sha :discovery-url json-url
                        :final-url json-url :content-type "application/json"
                        :provenance {:publisher-url json-url :redirect-chain [json-url]
                                     :source-page-url view-url})]
    (spit path (String. bytes "UTF-8"))
    (archive/register! root path manifest)
    sha))

(defn visible-evidence [unit rows sha]
  (let [{:keys [view-url json-url]} (routes unit)]
    {:schema "roatan-cwt-men-source-check/v1"
     :routes [{:requested_url json-url :final_url json-url :status 200
               :content_type "application/json; charset=utf-8"
               :sha256 sha :transport_rows (count rows)}]
     :browser_observations [{:view_url view-url :label "OFFICIAL" :rows (count rows)
                             :row_key_check (str "All " (count rows) " visible rank/name/representation/birth-year tuples matched the corresponding source API rows in order.")
                             :observed_window_utc "2026-09-28 08:30-08:33 UTC"}]}))

(deftest archived-roatan-json-requires-visible-row-evidence
  (let [dir (fixture/workspace) root (str dir "/archive") rows [(row 3551)]
        sha (registered-source root 3551 rows)
        evidence (archive/retain-evidence! root (.getBytes (json/write-str (visible-evidence 3551 rows sha)) "UTF-8"))
        options {:actor "synthetic" :config {} :citation-evidence-sha256 (:sha256 evidence)}
        first-run (roatan/extract! root sha options)
        artifact (edn/read-string (slurp (:artifact-path first-run)))]
    (is (= :created (:run-status first-run)))
    (is (= :skipped (:run-status (roatan/extract! root sha options))))
    (is (= 5 (:schema-version artifact)))
    (is (= :unreviewed (get-in artifact [:candidates 0 :review-status])))
    (is (= (:view-url (routes 3551)) (get-in artifact [:candidates 0 :citation :view-url])))
    (is (= 0 (get-in artifact [:candidates 0 :citation :row-index-zero-based])))
    (is (= artifact (roatan/validate-artifact! root artifact)))
    (is (thrown? Exception (roatan/validate-artifact! root (update-in artifact [:candidates 0] dissoc :citation))))
    (is (thrown? Exception (roatan/validate-artifact! root (assoc-in artifact [:candidates 0 :citation :visible-tuple :name] "Other name"))))
    (is (thrown? Exception (roatan/extract! root sha (assoc options :citation-evidence-sha256 (apply str (repeat 64 "0"))))))
    (is (thrown? Exception (roatan/extract! root sha (dissoc options :citation-evidence-sha256))))))

(deftest changed-json-bytes-retain-both-unreviewed-versions
  (let [dir (fixture/workspace) root (str dir "/archive") rows [(row 3559)]
        first-sha (registered-source root 3559 rows)
        first-evidence (:sha256 (archive/retain-evidence! root
                                                          (.getBytes (json/write-str (visible-evidence 3559 rows first-sha)) "UTF-8")))
        first-run (roatan/extract! root first-sha {:actor "synthetic" :config {}
                                                   :citation-evidence-sha256 first-evidence})
        changed [(assoc (row 3559) "ResResult" "81")]
        second-sha (registered-source root 3559 changed)
        second-evidence (:sha256 (archive/retain-evidence! root
                                                           (.getBytes (json/write-str (visible-evidence 3559 changed second-sha)) "UTF-8")))
        second-run (roatan/extract! root second-sha {:actor "synthetic" :config {}
                                                     :citation-evidence-sha256 second-evidence})
        first-artifact (edn/read-string (slurp (:artifact-path first-run)))
        second-artifact (edn/read-string (slurp (:artifact-path second-run)))]
    (is (not= first-sha second-sha))
    (is (not= (:job-id first-run) (:job-id second-run)))
    (is (= first-artifact (roatan/validate-artifact! root first-artifact)))
    (is (= second-artifact (roatan/validate-artifact! root second-artifact)))
    (is (= "80" (get-in first-artifact [:candidates 0 :raw "ResResult"])))
    (is (= "81" (get-in second-artifact [:candidates 0 :raw "ResResult"])))
    (is (= [:unreviewed :unreviewed]
           (mapv #(get-in % [:candidates 0 :review-status]) [first-artifact second-artifact])))))
