(ns freediving.lodz-2025-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.shell :as shell]
            [freediving.archive :as archive]
            [freediving.extraction :as extraction]
            [freediving.lodz-2025 :as lodz]))

(defn page [day body]
  (str "CMAS World Cup Indoor Series - Łódź, Poland 2025\n"
       "Results - " day " March 2025\n"
       "Athlete name   Gender   Country   Discipline   Category   Card   Result   Points   Remarks\n"
       body))

(deftest printed-rows-keep-source-position-and-original-values
  (let [pages [(page "14th" (str "A Test Diver    Men    Poland    DYN    Senior    WHITE    120.5 m    120\n"
                                 "B Test Diver    Women    Czech Republic    DNF    Senior    RED    -    0    DQ surface BO\n"))
               (page "15th" "A Test Diver    Men    Poland    DYN    Senior    WHITE    121 m    121\n")
               (page "16th" "C Test Diver    Men    Italy    DNF    Para Freediving    WHITE    100 m    125    World Record Para Freediving\n")]
        result (extraction/parse-pages pages)
        [first-row dq second-day para] (:candidates result)]
    (is (= lodz/parser-version (:parser-version result)))
    (is (= [1 1 2 3] (mapv #(get-in % [:coordinates :page]) (:candidates result))))
    (is (= [4 5 4 4] (mapv #(get-in % [:coordinates :line]) (:candidates result))))
    (is (= "2025-03-14" (get-in first-row [:parsed :event-date])))
    (is (= 120.5M (get-in first-row [:parsed :final-distance])))
    (is (= "120" (get-in first-row [:raw :fields :points])))
    (is (= "DQ surface BO" (get-in dq [:parsed :status])))
    (is (nil? (get-in dq [:parsed :final-distance])))
    (is (= "2025-03-15" (get-in second-day [:parsed :event-date])))
    (is (= "Para Freediving" (get-in para [:parsed :category])))
    (is (= "World Record Para Freediving" (get-in para [:parsed :notes])))
    (is (= 4 (get-in result [:reconciliation :candidate-count])))
    (is (= :blocked (get-in result [:publication :status])))))

(deftest archived-replay-rejects-edited-attempt
  (let [pages [(page "14th" "A Test Diver    Men    Poland    DYN    Senior    WHITE    120 m    120\n")]
        raw (str (first pages) "\f")
        artifact (merge (lodz/parse-pages pages)
                        {:source-sha256 lodz/source-sha256 :raw-text raw
                         :tool {:name "pdftotext" :version "test-version"
                                :arguments ["-layout" "-enc" "UTF-8"]}})
        fake-sh (fn [& args] {:exit 0 :out (if (= "-v" (second args)) "" raw)
                              :err (if (= "-v" (second args)) "test-version" "")})]
    (with-redefs [archive/inspect (fn [& _] {:artifact-path "archived.pdf"})
                  shell/sh fake-sh]
      (is (= artifact (extraction/validate-lodz-artifact! "archive" artifact)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source replay"
                            (extraction/validate-lodz-artifact!
                             "archive" (assoc-in artifact [:candidates 0 :parsed :final-distance] 999M)))))))
