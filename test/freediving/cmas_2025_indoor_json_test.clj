(ns freediving.cmas-2025-indoor-json-test
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [freediving.archive :as archive]
            [freediving.archive-test :as fixture]
            [freediving.cmas-2025-indoor-json :as indoor]))

(def view-url "https://results.microplustimingservices.com/CMAS/Results/#/2/dynamic-result-json/MAM/011/007/001")
(def json-url "https://results.microplustimingservices.com/CMAS/ExportPOST/export/CMAS_2/TFMAM011CLAS07%20001.JSON")
(def headers {"jsonfilename" "TFMAM011CLAS07 001.JSON" "tipologia" "011"
              "Sport" {"Cod" "TF"}
              "Competition" {"Cod" "011" "Eng" "Dynamic Apnea Without Fin"}
              "Document" {"Cod" "CGR1" "Eng" "Result"}
              "Category" {"Cod" "MAM" "Eng" "Masters Men"}
              "Round" {"Cod" "007" "Ita" "Final"}
              "Heat" {"Cod" "001" "Eng" "Heat 61" "Revised" ""}
              "Event" {"Date" "20/05/2025" "Time" "8:30"}})
(def row {"PlaCod" "120" "PlaName" "Timothy" "PlaSurname" "BECHTEL"
          "PlaNat" "GER" "PlaCat" "MASTERS M1" "b" "61" "PlaLane" "1"
          "MemPrest" "DSQ" "MemPoint" "" "MemNote" "" "PlaCls" ""})
(defn source [rows] (.getBytes (json/write-str (assoc headers "data" rows)) "UTF-8"))
(def provenance {:view-url view-url :json-url json-url})

(deftest final-result-retains-source-and-unreviewed-rows
  (let [finished (assoc row "PlaCod" "125" "PlaName" "James" "PlaSurname" "PRATT"
                        "b" "64" "PlaLane" "2" "MemPrest" "" "MemPoint" "107.50")
        bytes (source [row finished]) result (indoor/parse-result bytes provenance)
        candidate (first (:candidates result)) second-candidate (second (:candidates result))]
    (is (= (String. bytes "UTF-8") (:raw-json result)))
    (is (= view-url (:view-url result)))
    (is (= view-url (:source-page-url result)))
    (is (= view-url (:source-page-url candidate)))
    (is (= json-url (:json-url result)))
    (is (= "CGR1" (get-in result [:headers "Document" "Cod"])))
    (is (= row (:raw candidate)))
    (is (= 0 (get-in candidate [:coordinates :row-index-zero-based])))
    (is (= "120" (get-in candidate [:parsed :source-pla-code])))
    (is (= "BECHTEL Timothy" (get-in candidate [:parsed :source-name])))
    (is (= "61" (get-in candidate [:parsed :heat])))
    (is (= "1" (get-in candidate [:parsed :lane])))
    (is (= "MASTERS M1" (get-in candidate [:parsed :category])))
    (is (= "DSQ" (get-in candidate [:parsed :performance-token])))
    (is (= "" (get-in candidate [:parsed :points-token])))
    (is (= "107.50" (get-in second-candidate [:parsed :points-token])))
    (is (= "" (get-in second-candidate [:parsed :performance-token])))
    (is (= :unreviewed (:review-status candidate)))
    (is (= :blocked (:selection-status second-candidate)))
    (is (= 2 (get-in result [:reconciliation :source-row-count])))
    (is (= :blocked (get-in result [:publication :status])))))

(deftest mismatched-page-and-malformed-row-fail-closed
  (doseq [bad-url [(str/replace view-url "/MAM/" "/SEF/")
                   (str/replace view-url "/007/" "/006/")]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (indoor/parse-result (source [row]) (assoc provenance :view-url bad-url)))))
  (doseq [bad-row [(dissoc row "PlaCod") (assoc row "PlaLane" nil)]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (indoor/parse-result (source [bad-row]) provenance))))
  (is (thrown? Exception
               (indoor/parse-result (byte-array [(byte 0xff)]) provenance)))
  (is (thrown? clojure.lang.ExceptionInfo
               (indoor/parse-result (source [row])
                                    (assoc provenance :json-url (str/replace json-url "CMAS_2" "CMAS_1")))))
  (is (thrown? clojure.lang.ExceptionInfo
               (indoor/parse-result (source [row])
                                    (assoc provenance :view-url (str/replace view-url "result-json" "summary-json"))))))

(deftest registered-result-replays-exact-json-and-page-binding
  (let [dir (fixture/workspace) root (str dir "/archive") path (str dir "/result.json")
        bytes (source [row]) hash (.formatHex (java.util.HexFormat/of)
                                              (.digest (java.security.MessageDigest/getInstance "SHA-256") bytes))
        manifest (assoc fixture/manifest :sha256 hash :discovery-url json-url
                        :final-url json-url :content-type "application/json"
                        :provenance {:publisher-url json-url :redirect-chain [json-url]
                                     :source-page-url view-url})]
    (java.nio.file.Files/write (java.nio.file.Paths/get path (make-array String 0)) bytes
                               (make-array java.nio.file.OpenOption 0))
    (archive/register! root path manifest)
    (let [first-run (indoor/extract! root hash {:actor "synthetic" :config {}})
          rerun (indoor/extract! root hash {:actor "synthetic" :config {}})
          artifact (edn/read-string (slurp (:artifact-path first-run)))]
      (is (= :created (:run-status first-run)))
      (is (= :skipped (:run-status rerun)))
      (is (= (:job-id first-run) (:job-id rerun)))
      (is (= 5 (:schema-version artifact)))
      (is (= view-url (:view-url artifact)))
      (is (= json-url (:json-url artifact)))
      (is (= artifact (indoor/validate-artifact! root artifact)))
      (let [legacy (-> artifact
                       (assoc :parser-version "cmas-2025-indoor-json/1")
                       (assoc-in [:candidates 0 :parsed :source-name] "Timothy BECHTEL"))]
        (is (= legacy (indoor/validate-artifact! root legacy))))
      (is (thrown? clojure.lang.ExceptionInfo
                   (indoor/validate-artifact! root (assoc-in artifact [:candidates 0 :parsed :points-token] "99")))))))
