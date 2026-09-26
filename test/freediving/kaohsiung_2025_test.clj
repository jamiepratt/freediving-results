(ns freediving.kaohsiung-2025-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.shell :as shell]
            [freediving.archive :as archive]
            [freediving.extraction :as extraction]
            [freediving.kaohsiung-2025 :as kaohsiung]))

(defn page [date discipline category body]
  (str "2025 CMAS WORLD CUP FREEDIVING INDOOR, KAOHSIUNG\n"
       date "\n" discipline " FINAL RESULTS               SENIORS - " category "\n"
       "# Name & surname Country Realized Distance (m) Final Distance (m) Notes\n"
       body))

(deftest attempt-tables-preserve-source-positions-and-status
  (let [women (page "10-Sep-25" "DNF" "WOMEN"
                    "1   Yu-Han LIU                        TPE          153            140.5\n")
        women (str women "    Yu- Min LIU                       TPE          75               0          DQBO-SURFACE\n"
                   "    Tracy ROXANNE                      PHI         120                         DNS\n")
        bifins (page "11-Sep-25" "DYNBF" "MEN"
                     "1    Po-Yen LEE                         TPE           M              254\n")
        ranking "2025 CMAS WORLD CUP FREEDIVING INDOOR, KAOHSIUG\nFINAL RESULTS SENIORS - MEN\n1 Po-Yen LEE TPE 168.5 254 462.94\n"
        result (extraction/parse-pages [women bifins women bifins women bifins ranking])
        [first-row dq dns bf] (:candidates result)]
    (is (= kaohsiung/parser-version (:parser-version result)))
    (is (= [1 1 1 2] (mapv #(get-in % [:coordinates :page]) (take 4 (:candidates result)))))
    (is (= [5 6 7 5] (mapv #(get-in % [:coordinates :line]) (take 4 (:candidates result)))))
    (is (= 140.5M (get-in first-row [:parsed :final-distance])))
    (is (= "2025-09-10" (get-in first-row [:parsed :event-date])))
    (is (= "DQBO-SURFACE" (get-in dq [:parsed :status])))
    (is (= 0M (get-in dq [:parsed :final-distance])))
    (is (= "DNS" (get-in dns [:parsed :status])))
    (is (nil? (get-in dns [:parsed :final-distance])))
    (is (= "M" (get-in bf [:raw :fields :realized-distance])))
    (is (nil? (get-in bf [:parsed :realized-distance])))
    (is (= :supporting-ranking (get-in result [:reconciliation :per-page 6 :classification])))
    (is (= :blocked (get-in result [:publication :status])))))

(deftest wrapped-penalty-note-belongs-to-one-attempt
  (let [result (kaohsiung/parse-pages (conj (vec (repeat 5 "")) (page "11-Sep-25" "DYN" "MEN"
                                                                      (str "                  RESULT 88M PENALTIES 3M - WALL AT\n"
                                                                           "9    Raymond KO                          USA          110             85\n"
                                                                           "                  START\n"))))
        candidate (first (:candidates result))]
    (is (= 1 (count (:candidates result))))
    (is (= [6 5 7] (mapv :line (:source-lines candidate))))
    (is (= "RESULT 88M PENALTIES 3M - WALL AT START" (get-in candidate [:parsed :notes])))
    (is (= :partial-unsupported-needs-parser (:status result)))))

(deftest archived-source-replay-rejects-altered-attempt
  (let [pages [(page "10-Sep-25" "DNF" "WOMEN"
                     "1   Yu-Han LIU                        TPE          153            140.5\n")]
        raw (str (first pages) "\f")
        artifact (merge (kaohsiung/parse-pages pages)
                        {:source-sha256 kaohsiung/source-sha256
                         :raw-text raw :tool {:name "pdftotext" :version "test-version"
                                              :arguments ["-layout" "-enc" "UTF-8"]}})
        fake-sh (fn [& args] {:exit 0 :out (if (= "pdftotext" (first args)) raw "")
                              :err (if (= "-v" (second args)) "test-version" "")})]
    (with-redefs [archive/inspect (fn [& _] {:artifact-path "archived.pdf"})
                  shell/sh fake-sh]
      (is (= artifact (extraction/validate-kaohsiung-artifact! "archive" artifact)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source replay"
                            (extraction/validate-kaohsiung-artifact!
                             "archive" (assoc-in artifact [:candidates 0 :parsed :final-distance] 999M)))))))
