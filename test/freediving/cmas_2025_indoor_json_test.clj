(ns freediving.cmas-2025-indoor-json-test
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
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

(def sef-source-sha256
  "84b1294ebab01c4c173cca7a2d49b9d9c9ccf3349c656f3d01962ec5ee76af84")
(defn sef-source-bytes []
  (let [resource (io/resource "freediving/fixtures/sef-2025-athens-dnf.json.gz")]
    (when-not resource (throw (ex-info "Missing exact SEF test fixture" {})))
    (let [out (java.io.ByteArrayOutputStream.)]
      (with-open [in (java.util.zip.GZIPInputStream. (io/input-stream resource))]
        (io/copy in out))
      (let [bytes (.toByteArray out)
            hash (.formatHex (java.util.HexFormat/of)
                             (.digest (java.security.MessageDigest/getInstance "SHA-256") bytes))]
        (when-not (= sef-source-sha256 hash)
          (throw (ex-info "SEF test fixture SHA-256 mismatch" {:actual hash})))
        bytes))))
(def sef-provenance
  {:view-url (str/replace view-url "/MAM/" "/SEF/")
   :json-url (str/replace json-url "TFMAM011" "TFSEF011")})

(deftest archived-seniors-women-quarantines-only-the-invalid-source-row
  (let [bytes (sef-source-bytes)
        result (indoor/parse-result bytes sef-provenance)
        indices (mapv #(get-in % [:coordinates :row-index-zero-based]) (:candidates result))]
    (is (= 50 (get-in result [:reconciliation :source-row-count])))
    (is (= 49 (get-in result [:reconciliation :candidate-count])))
    (is (= (vec (remove #{19} (range 50))) indices))
    (is (= [{:coordinates {:row-index-zero-based 19}
             :reason :invalid-utf8 :byte-offset 12982 :raw-byte-hex "98"
             :raw-row-byte-span {:start-inclusive 12789 :end-exclusive 13382
                                 :sha256 "17cec89ba54cd1ca192ef0078446eb40ef7a12efa3a98a3b33b0e30967179df3"}}]
           (:unparsed-rows result)))
    (is (nil? (:raw-json result)))
    (is (every? #(and (= :parsed (:parse-status %))
                      (= :unreviewed (:review-status %))
                      (= :blocked (:selection-status %))) (:candidates result)))
    (is (= :blocked (get-in result [:publication :status])))))

(deftest malformed-byte-outside-the-archived-sef-source-stays-rejected
  (let [good (source [row])
        bytes (byte-array (alength good))]
    (System/arraycopy good 0 bytes 0 (alength good))
    (aset-byte bytes 20 (unchecked-byte 0x98))
    (is (thrown? clojure.lang.ExceptionInfo (indoor/parse-result bytes provenance)))
    (is (thrown? clojure.lang.ExceptionInfo (indoor/parse-result bytes sef-provenance)))))

(deftest athens-dnf-categories-retain-distinct-source-positions
  (doseq [[category label] [["JUF" "Juniors Women"] ["JUM" "Juniors Men"]
                            ["MAF" "Masters Women"] ["SEF" "Seniors Women"]
                            ["SEM" "Seniors Men"]]]
    (let [filename (str "TF" category "011CLAS07 001.JSON")
          source-data (-> headers
                          (assoc "jsonfilename" filename)
                          (assoc-in ["Category" "Cod"] category)
                          (assoc-in ["Category" "Eng"] label)
                          (assoc "data" [(assoc row "PlaCod" "120" "PlaCat" label)
                                         (assoc row "PlaCod" "120" "PlaCat" label "PlaLane" "2")]))
          bytes (.getBytes (json/write-str source-data) "UTF-8")
          page (str/replace view-url "/MAM/" (str "/" category "/"))
          response (str/replace json-url "TFMAM011" (str "TF" category "011"))
          result (indoor/parse-result bytes {:view-url page :json-url response})]
      (is (= label (get-in result [:headers "Category" "Eng"])))
      (is (= [0 1] (mapv #(get-in % [:coordinates :row-index-zero-based]) (:candidates result))))
      (is (= ["120" "120"] (mapv #(get-in % [:raw "PlaCod"]) (:candidates result))))
      (is (every? #(and (= :unreviewed (:review-status %))
                        (= :blocked (:selection-status %))
                        (= page (:source-page-url %))) (:candidates result))))))

(deftest result-must-be-the-2025-athens-dnf-event
  (doseq [source-data [(assoc-in headers ["Event" "Date"] "21/05/2025")
                       (assoc-in headers ["Competition" "Eng"] "Dynamic Apnea With Fin")]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (indoor/parse-result (.getBytes (json/write-str (assoc source-data "data" [row])) "UTF-8")
                                      provenance)))))

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
      (let [prior (-> artifact
                      (assoc :parser-version "cmas-2025-indoor-json/2")
                      (dissoc :unparsed-rows)
                      (update :reconciliation dissoc :unparsed-count))]
        (is (= prior (indoor/validate-artifact! root prior))))
      (let [legacy (-> artifact
                       (assoc :parser-version "cmas-2025-indoor-json/1")
                       (dissoc :unparsed-rows)
                       (update :reconciliation dissoc :unparsed-count)
                       (assoc-in [:candidates 0 :parsed :source-name] "Timothy BECHTEL"))]
        (is (= legacy (indoor/validate-artifact! root legacy))))
      (is (thrown? clojure.lang.ExceptionInfo
                   (indoor/validate-artifact! root (assoc-in artifact [:candidates 0 :parsed :points-token] "99")))))))
