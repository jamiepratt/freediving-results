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

(def static-view-url "https://results.microplustimingservices.com/CMAS/Results/#/1/static-result-json/JUF/001/007/001")
(def static-json-url "https://results.microplustimingservices.com/CMAS/ExportPOST/export/CMAS_1/NUJUF001CLAS07%20001.JSON")
(def static-headers
  (-> headers
      (assoc "jsonfilename" "NUJUF001CLAS07 001.JSON" "tipologia" "001")
      (assoc-in ["Sport" "Cod"] "NU")
      (assoc-in ["Competition" "Cod"] "001")
      (assoc-in ["Competition" "Eng"] "Static Apnea")
      (assoc-in ["Category" "Cod"] "JUF")
      (assoc-in ["Category" "Eng"] "Junior Women")
      (assoc-in ["Round" "Eng"] "HEATS")
      (assoc-in ["Heat" "Eng"] "Heat 1")
      (assoc-in ["Heat" "UffDate"] "23/05/2025")
      (assoc-in ["Event" "Date"] "23/05/2025")))
(def static-row
  (assoc row "PlaCat" "" "PlaCatEff" "JUNIORS" "b" "1"
         "MemPrest" "4:05.00" "MemPoint" "" "MemFields" [{"V" ""} {"V" "4:05.00"}]))
(defn static-source [rows] (.getBytes (json/write-str (assoc static-headers "data" rows)) "UTF-8"))

(def speed-view-url "https://results.microplustimingservices.com/CMAS/Results/#/1/speed-result-json/JUF/004/007/001")
(def speed-json-url "https://results.microplustimingservices.com/CMAS/ExportPOST/export/CMAS_1/NUJUF004CLAS07%20001.JSON")
(def speed-headers
  (-> static-headers
      (assoc "jsonfilename" "NUJUF004CLAS07 001.JSON" "tipologia" "004")
      (assoc-in ["Competition" "Cod"] "004")
      (assoc-in ["Competition" "Eng"] "Speed Apnea 8x50")
      (assoc-in ["Heat" "UffDate"] "22/05/2025")
      (assoc-in ["Event" "Date"] "22/05/2025")))
(def speed-row
  (assoc static-row "MemPrest" "5:31.41" "MemQual" ""
         "MemFields" [{"V" ""} {"V" "24.16" "T" "" "P" "2"}
                      {"V" "1:06.90" "T" "42.74" "P" "1"}
                      {"V" "1:47.38" "T" "40.48" "P" "1"}
                      {"V" "2:30.37" "T" "42.99" "P" "2"}
                      {"V" "3:11.87" "T" "41.50" "P" "1"}
                      {"V" "3:57.71" "T" "45.84" "P" "1"}
                      {"V" "4:18.14" "T" "20.43" "P" "1"}
                      {"V" "5:31.41" "T" "1:13.27" "P" "1"}]))
(defn speed-source [rows]
  (.getBytes (json/write-str (assoc speed-headers "data" rows)) "UTF-8"))

(def speed-2x50-view-url
  "https://results.microplustimingservices.com/CMAS/Results/#/1/speed-result-json/JUF/002/007/001")
(def speed-2x50-json-url
  "https://results.microplustimingservices.com/CMAS/ExportPOST/export/CMAS_1/NUJUF002CLAS07%20001.JSON")
(def speed-2x50-headers
  (-> speed-headers
      (assoc "jsonfilename" "NUJUF002CLAS07 001.JSON" "tipologia" "002")
      (assoc-in ["Competition" "Cod"] "002")
      (assoc-in ["Competition" "Eng"] "Speed Apnea 2x50")))
(def speed-2x50-row
  (assoc speed-row "MemPrest" "43.05" "Last50" "23.91"
         "MemFields" [{"V" ""} {"V" "19.14" "T" "" "P" "1"}
                      {"V" "43.05" "T" "23.91" "P" "1"}
                      {"V" "" "T" "" "P" ""} {"V" "" "T" "" "P" ""}
                      {"V" "" "T" "" "P" ""} {"V" "" "T" "" "P" ""}
                      {"V" "" "T" "" "P" ""} {"V" "" "T" "" "P" ""}]))
(defn speed-2x50-source [rows]
  (.getBytes (json/write-str (assoc speed-2x50-headers "data" rows)) "UTF-8"))

(def speed-4x50-view-url
  "https://results.microplustimingservices.com/CMAS/Results/#/1/speed-result-json/JUF/003/007/001")
(def speed-4x50-json-url
  "https://results.microplustimingservices.com/CMAS/ExportPOST/export/CMAS_1/NUJUF003CLAS07%20001.JSON")
(def speed-4x50-headers
  (-> speed-headers
      (assoc "jsonfilename" "NUJUF003CLAS07 001.JSON" "tipologia" "003")
      (assoc-in ["Competition" "Cod"] "003")
      (assoc-in ["Competition" "Eng"] "Speed Apnea 4x50")
      (assoc-in ["Heat" "UffDate"] "23/05/2025")
      (assoc-in ["Event" "Date"] "23/05/2025")))
(def speed-4x50-row
  (assoc speed-row "MemPrest" "2:23.84" "Last50" "35.30"
         "MemFields" [{"V" ""} {"V" "27.02" "T" "" "P" "3"}
                      {"V" "1:03.54" "T" "36.52" "P" "2"}
                      {"V" "1:48.54" "T" "45.00" "P" "1"}
                      {"V" "2:23.84" "T" "35.30" "P" "1"}
                      {"V" "" "T" "" "P" ""} {"V" "" "T" "" "P" ""}
                      {"V" "" "T" "" "P" ""} {"V" "" "T" "" "P" ""}]))
(defn speed-4x50-source [rows]
  (.getBytes (json/write-str (assoc speed-4x50-headers "data" rows)) "UTF-8"))

(deftest athens-speed-4x50-preserves-finish-and-status-with-nested-clock
  (let [dsq (-> speed-4x50-row
                (assoc "PlaCod" "100002" "MemPrest" "DSQ" "Last50" "")
                (assoc-in ["MemFields" 4 "V"] "2:27.40"))
        dns (-> speed-4x50-row
                (assoc "PlaCod" "100003" "MemPrest" "DNS" "Last50" "")
                (assoc-in ["MemFields" 4 "V"] ""))
        conflict (assoc speed-4x50-row "PlaCod" "100004" "MemPrest" "2:23.85")
        bad-tail (assoc-in speed-4x50-row ["MemFields" 5 "V"] "2:24.00")
        result (indoor/parse-result (speed-4x50-source [speed-4x50-row dsq dns conflict bad-tail])
                                    {:view-url speed-4x50-view-url :json-url speed-4x50-json-url})]
    (is (= "cmas-2025-indoor-json/9" (:parser-version result)))
    (is (= [0 1 2] (mapv #(get-in % [:coordinates :row-index-zero-based]) (:candidates result))))
    (is (= ["2:23.84" nil nil] (mapv #(get-in % [:parsed :time-token]) (:candidates result))))
    (is (= [nil "DSQ" "DNS"] (mapv #(get-in % [:parsed :status-token]) (:candidates result))))
    (is (= "2:27.40" (get-in result [:candidates 1 :raw "MemFields" 4 "V"])))
    (is (= [3 4] (mapv #(get-in % [:coordinates :row-index-zero-based]) (:unparsed-rows result))))
    (is (= 3 (get-in result [:reconciliation :candidate-count])))))

(deftest athens-speed-4x50-requires-observed-result-binding
  (let [parse (fn [data page response]
                (indoor/parse-result (.getBytes (json/write-str (assoc data "data" [speed-4x50-row])) "UTF-8")
                                     {:view-url page :json-url response}))]
    (doseq [bad-data [(assoc-in speed-4x50-headers ["Event" "Date"] "22/05/2025")
                      (assoc-in speed-4x50-headers ["Heat" "UffDate"] "22/05/2025")
                      (assoc-in speed-4x50-headers ["Round" "Eng"] "FINAL")
                      (assoc-in speed-4x50-headers ["Competition" "Eng"] "Speed Apnea 2x50")
                      (assoc speed-4x50-headers "jsonfilename" "NUJUF002CLAS07 001.JSON")]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (parse bad-data speed-4x50-view-url speed-4x50-json-url))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (parse speed-4x50-headers speed-4x50-view-url
                        (str/replace speed-4x50-json-url "CMAS_1" "CMAS_2"))))))

(deftest athens-speed-4x50-keeps-finish-with-blank-intermediate-splits
  (let [row (reduce (fn [row index] (assoc-in row ["MemFields" index "V"] ""))
                    speed-4x50-row [1 2 3])
        result (indoor/parse-result (speed-4x50-source [row])
                                    {:view-url speed-4x50-view-url :json-url speed-4x50-json-url})]
    (is (= "2:23.84" (get-in result [:candidates 0 :parsed :time-token])))
    (is (= ["" "" ""]
           (mapv #(get-in result [:candidates 0 :raw "MemFields" % "V"]) [1 2 3])))
    (is (empty? (:unparsed-rows result)))))

(deftest athens-speed-2x50-preserves-finish-and-unknown-split
  (let [missing-split (-> speed-2x50-row
                          (assoc "PlaCod" "100025" "MemPrest" "39.17" "Last50" "")
                          (assoc-in ["MemFields" 1 "V"] "")
                          (assoc-in ["MemFields" 2 "V"] "39.17")
                          (assoc-in ["MemFields" 2 "T"] ""))
        result (indoor/parse-result (speed-2x50-source [speed-2x50-row missing-split])
                                    {:view-url speed-2x50-view-url :json-url speed-2x50-json-url})]
    (is (= "cmas-2025-indoor-json/9" (:parser-version result)))
    (is (= [0 1] (mapv #(get-in % [:coordinates :row-index-zero-based]) (:candidates result))))
    (is (= ["43.05" "39.17"] (mapv #(get-in % [:parsed :time-token]) (:candidates result))))
    (is (= "" (get-in result [:candidates 1 :raw "MemFields" 1 "V"])))
    (is (= 2 (get-in result [:reconciliation :candidate-count])))))

(deftest athens-speed-2x50-statuses-and-conflicts
  (let [dsq (-> speed-2x50-row
                (assoc "PlaCod" "100026" "MemPrest" "DSQ" "Last50" "")
                (assoc-in ["MemFields" 2 "V"] "57.77"))
        dns (-> speed-2x50-row
                (assoc "PlaCod" "100027" "MemPrest" "DNS" "Last50" "")
                (assoc-in ["MemFields" 1 "V"] "")
                (assoc-in ["MemFields" 2 "V"] ""))
        conflict (assoc speed-2x50-row "PlaCod" "100028" "MemPrest" "43.06")
        bad-tail (assoc-in speed-2x50-row ["MemFields" 3 "V"] "44.00")
        result (indoor/parse-result (speed-2x50-source [dsq dns conflict bad-tail])
                                    {:view-url speed-2x50-view-url :json-url speed-2x50-json-url})]
    (is (= [0 1] (mapv #(get-in % [:coordinates :row-index-zero-based]) (:candidates result))))
    (is (= ["DSQ" "DNS"] (mapv #(get-in % [:parsed :status-token]) (:candidates result))))
    (is (= [nil nil] (mapv #(get-in % [:parsed :time-token]) (:candidates result))))
    (is (= "57.77" (get-in result [:candidates 0 :raw "MemFields" 2 "V"])))
    (is (= [2 3] (mapv #(get-in % [:coordinates :row-index-zero-based]) (:unparsed-rows result))))
    (is (= 2 (get-in result [:reconciliation :unparsed-count])))))

(deftest athens-speed-2x50-requires-exact-event-binding
  (let [parse (fn [data page response]
                (indoor/parse-result (.getBytes (json/write-str (assoc data "data" [speed-2x50-row])) "UTF-8")
                                     {:view-url page :json-url response}))]
    (doseq [bad-data [(assoc-in speed-2x50-headers ["Event" "Date"] "23/05/2025")
                      (assoc-in speed-2x50-headers ["Heat" "UffDate"] "23/05/2025")
                      (assoc-in speed-2x50-headers ["Round" "Eng"] "FINAL")
                      (assoc-in speed-2x50-headers ["Competition" "Eng"] "Speed Apnea 8x50")
                      (assoc speed-2x50-headers "jsonfilename" "NUJUF004CLAS07 001.JSON")]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (parse bad-data speed-2x50-view-url speed-2x50-json-url))))
    (doseq [bad-page [(str/replace speed-2x50-view-url "/002/" "/004/")
                      (str/replace speed-2x50-view-url "/007/" "/006/")
                      (str/replace speed-2x50-view-url "speed-result-json" "static-result-json")]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (parse speed-2x50-headers bad-page speed-2x50-json-url))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (parse speed-2x50-headers speed-2x50-view-url
                        (str/replace speed-2x50-json-url "CMAS_1" "CMAS_2"))))))

(deftest athens-speed-apnea-preserves-finish-and-split-tokens
  (let [result (indoor/parse-result (speed-source [speed-row])
                                    {:view-url speed-view-url :json-url speed-json-url})
        candidate (first (:candidates result))]
    (is (= "cmas-2025-indoor-json/9" (:parser-version result)))
    (is (= 0 (get-in candidate [:coordinates :row-index-zero-based])))
    (is (= speed-row (:raw candidate)))
    (is (= "5:31.41" (get-in candidate [:parsed :performance-token])))
    (is (= "5:31.41" (get-in candidate [:parsed :time-token])))
    (is (= :unknown (get-in candidate [:parsed :performance-unit])))
    (is (= 1 (get-in result [:reconciliation :candidate-count])))
    (is (= :blocked (get-in result [:publication :status])))))

(deftest athens-speed-apnea-keeps-statuses-and-quarantines-ambiguous-finish
  (let [status-fields (assoc-in (vec (get speed-row "MemFields")) [8 "V"] "")
        dsq (assoc speed-row "PlaCod" "121" "MemPrest" "DSQ" "MemFields" status-fields)
        dns (assoc speed-row "PlaCod" "122" "MemPrest" "DNS" "MemFields" status-fields)
        conflict (assoc speed-row "PlaCod" "123" "MemPrest" "5:32.00")
        malformed (assoc speed-row "PlaCod" "124" "MemPrest" 42)
        result (indoor/parse-result (speed-source [speed-row dsq dns conflict malformed])
                                    {:view-url speed-view-url :json-url speed-json-url})]
    (is (= [0 1 2] (mapv #(get-in % [:coordinates :row-index-zero-based]) (:candidates result))))
    (is (= [nil "DSQ" "DNS"] (mapv #(get-in % [:parsed :status-token]) (:candidates result))))
    (is (= ["5:31.41" nil nil] (mapv #(get-in % [:parsed :time-token]) (:candidates result))))
    (is (= [{:coordinates {:row-index-zero-based 3}
             :reason :ambiguous-speed-result-row :raw conflict}
            {:coordinates {:row-index-zero-based 4}
             :reason :ambiguous-speed-result-row :raw malformed}]
           (:unparsed-rows result)))
    (is (= {:source-row-count 5 :candidate-count 3 :unparsed-count 2
            :unresolved-count 5 :status :unreviewed}
           (:reconciliation result)))))

(deftest athens-speed-apnea-requires-exact-route-and-headers
  (let [parse (fn [data page response]
                (indoor/parse-result (.getBytes (json/write-str (assoc data "data" [speed-row])) "UTF-8")
                                     {:view-url page :json-url response}))]
    (doseq [bad-data [(assoc-in speed-headers ["Event" "Date"] "23/05/2025")
                      (assoc-in speed-headers ["Heat" "UffDate"] "23/05/2025")
                      (assoc-in speed-headers ["Round" "Eng"] "FINAL")
                      (assoc-in speed-headers ["Competition" "Eng"] "Static Apnea")
                      (assoc speed-headers "jsonfilename" "NUJUF001CLAS07 001.JSON")]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (parse bad-data speed-view-url speed-json-url))))
    (doseq [bad-page [(str/replace speed-view-url "/004/" "/001/")
                      (str/replace speed-view-url "speed-result-json" "static-result-json")
                      (str/replace speed-view-url "/JUF/" "/JUM/")]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (parse speed-headers bad-page speed-json-url))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (parse speed-headers speed-view-url
                        (str/replace speed-json-url "CMAS_1" "CMAS_2"))))))

(deftest athens-static-cgr1-preserves-time-and-status-tokens
  (let [rows [static-row (assoc static-row "PlaCod" "100002" "PlaLane" "2"
                                "MemPrest" "DSQ" "MemFields" [{"V" ""} {"V" "DSQ"}])]
        result (indoor/parse-result (static-source rows)
                                    {:view-url static-view-url :json-url static-json-url})]
    (is (= "cmas-2025-indoor-json/9" (:parser-version result)))
    (is (= [0 1] (mapv #(get-in % [:coordinates :row-index-zero-based]) (:candidates result))))
    (is (= ["4:05.00" "DSQ"] (mapv #(get-in % [:parsed :performance-token]) (:candidates result))))
    (is (= ["4:05.00" nil] (mapv #(get-in % [:parsed :time-token]) (:candidates result))))
    (is (= [nil "DSQ"] (mapv #(get-in % [:parsed :status-token]) (:candidates result))))
    (is (= ["" ""] (mapv #(get-in % [:parsed :points-token]) (:candidates result))))
    (is (= ["JUNIORS" "JUNIORS"] (mapv #(get-in % [:parsed :category]) (:candidates result))))
    (is (every? #(= :unknown (get-in % [:parsed :performance-unit])) (:candidates result)))
    (is (every? #(= :unknown (get-in % [:parsed :points-unit])) (:candidates result)))
    (is (every? #(and (= :unreviewed (:review-status %)) (= :blocked (:selection-status %)))
                (:candidates result)))
    (is (= :blocked (get-in result [:publication :status])))))

(deftest athens-static-requires-exact-route-and-header-binding
  (let [parse (fn [data page response]
                (indoor/parse-result (.getBytes (json/write-str (assoc data "data" [static-row])) "UTF-8")
                                     {:view-url page :json-url response}))]
    (doseq [bad-data [(assoc-in static-headers ["Event" "Date"] "24/05/2025")
                      (assoc-in static-headers ["Round" "Eng"] "Final")
                      (assoc-in static-headers ["Heat" "UffDate"] "24/05/2025")
                      (assoc-in static-headers ["Competition" "Eng"] "Dynamic Apnea")
                      (assoc-in static-headers ["Sport" "Cod"] "TF")
                      (assoc static-headers "jsonfilename" "NUJUF026CLAS07 001.JSON")]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (parse bad-data static-view-url static-json-url))))
    (doseq [bad-page [(str/replace static-view-url "/JUF/" "/JUM/")
                      (str/replace static-view-url "/007/" "/006/")
                      (str/replace static-view-url "static-result-json" "dynamic-result-json")]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (parse static-headers bad-page static-json-url))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (parse static-headers static-view-url
                        (str/replace static-json-url "CMAS_1" "CMAS_2"))))))

(deftest athens-dynamic-apnea-cgr1-keeps-source-rows-unreviewed
  (doseq [category ["JUF" "JUM" "MAF" "MAM" "SEF" "SEM"]]
    (let [source-data (-> headers
                          (assoc "jsonfilename" (str "TF" category "026CLAS07 001.JSON")
                                 "tipologia" "026")
                          (assoc-in ["Competition" "Cod"] "026")
                          (assoc-in ["Competition" "Eng"] "Dynamic Apnea")
                          (assoc-in ["Category" "Cod"] category)
                          (assoc-in ["Event" "Date"] "24/05/2025")
                          (assoc "data" [row (assoc row "PlaLane" "2")]))
          page (str/replace (str/replace view-url "/MAM/" (str "/" category "/"))
                            "/011/" "/026/")
          response (str/replace json-url "TFMAM011" (str "TF" category "026"))
          result (indoor/parse-result (.getBytes (json/write-str source-data) "UTF-8")
                                      {:view-url page :json-url response})]
      (is (= "cmas-2025-indoor-json/9" (:parser-version result)))
      (is (= [0 1] (mapv #(get-in % [:coordinates :row-index-zero-based]) (:candidates result))))
      (is (= ["DSQ" "DSQ"] (mapv #(get-in % [:parsed :performance-token]) (:candidates result))))
      (is (every? #(and (= :unreviewed (:review-status %))
                        (= :blocked (:selection-status %))) (:candidates result)))
      (is (= :blocked (get-in result [:publication :status]))))))

(deftest athens-bi-fins-cgr1-keeps-each-source-row-unreviewed
  (doseq [category ["JUF" "JUM" "MAF" "MAM" "SEF" "SEM"]]
    (let [source-data (-> headers
                          (assoc "jsonfilename" (str "TF" category "016CLAS07 001.JSON")
                                 "tipologia" "016")
                          (assoc-in ["Competition" "Cod"] "016")
                          (assoc-in ["Competition" "Eng"] "Dynamic Apnea Bi Fins")
                          (assoc-in ["Category" "Cod"] category)
                          (assoc-in ["Event" "Date"] "21/05/2025")
                          (assoc "data" [(assoc row "PlaCod" "120")
                                         (assoc row "PlaCod" "120" "PlaLane" "2")]))
          page (str/replace (str/replace view-url "/MAM/" (str "/" category "/"))
                            "/011/" "/016/")
          response (str/replace json-url "TFMAM011" (str "TF" category "016"))
          result (indoor/parse-result (.getBytes (json/write-str source-data) "UTF-8")
                                      {:view-url page :json-url response})]
      (is (= "cmas-2025-indoor-json/9" (:parser-version result)))
      (is (= [0 1] (mapv #(get-in % [:coordinates :row-index-zero-based]) (:candidates result))))
      (is (= ["120" "120"] (mapv #(get-in % [:raw "PlaCod"]) (:candidates result))))
      (is (every? #(and (= :unreviewed (:review-status %))
                        (= :blocked (:selection-status %))) (:candidates result)))
      (is (= :blocked (get-in result [:publication :status]))))))

(deftest athens-bi-fins-requires-its-observed-result-binding
  (let [source-data (-> headers
                        (assoc "jsonfilename" "TFMAM016CLAS07 001.JSON" "tipologia" "016")
                        (assoc-in ["Competition" "Cod"] "016")
                        (assoc-in ["Competition" "Eng"] "Dynamic Apnea Bi Fins")
                        (assoc-in ["Event" "Date"] "21/05/2025")
                        (assoc "data" [row]))
        page (str/replace view-url "/011/" "/016/")
        response (str/replace json-url "TFMAM011" "TFMAM016")
        parse (fn [data url]
                (indoor/parse-result (.getBytes (json/write-str data) "UTF-8")
                                     {:view-url url :json-url response}))]
    (doseq [data [(assoc-in source-data ["Event" "Date"] "20/05/2025")
                  (assoc-in source-data ["Competition" "Eng"] "Dynamic Apnea Without Fin")
                  (assoc-in source-data ["Round" "Cod"] "006")
                  (assoc-in source-data ["Heat" "Cod"] "002")]]
      (is (thrown? clojure.lang.ExceptionInfo (parse data page))))
    (doseq [url [(str/replace page "/007/" "/006/")
                 (str/replace page "/001" "/002")]]
      (is (thrown? clojure.lang.ExceptionInfo (parse source-data url))))))

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
      (let [v8 (assoc artifact :parser-version "cmas-2025-indoor-json/8")]
        (is (= v8 (indoor/validate-artifact! root v8))))
      (let [v7 (assoc artifact :parser-version "cmas-2025-indoor-json/7")]
        (is (= v7 (indoor/validate-artifact! root v7))))
      (let [v6 (assoc artifact :parser-version "cmas-2025-indoor-json/6")]
        (is (= v6 (indoor/validate-artifact! root v6))))
      (let [v5 (assoc artifact :parser-version "cmas-2025-indoor-json/5")]
        (is (= v5 (indoor/validate-artifact! root v5))))
      (let [v4 (assoc artifact :parser-version "cmas-2025-indoor-json/4")]
        (is (= v4 (indoor/validate-artifact! root v4))))
      (let [previous (assoc artifact :parser-version "cmas-2025-indoor-json/3")]
        (is (= previous (indoor/validate-artifact! root previous))))
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
