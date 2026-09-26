(ns freediving.unu-tampa-2025-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [clojure.java.shell :as shell]
            [freediving.archive :as archive]
            [freediving.extraction :as extraction]
            [freediving.unu-tampa-2025 :as unu]))

(def static-page
  (str "UNU Tampa Bay Freediving Challenge 2025 - STATIC 06.12.2025\n"
       "#     Given Name         Family Name        Gender   Country       Realized Penalties          Final    Remarks\n"
       "                                                               Performance              Performance\n"
       "                         WOMEN\n"
       "    1 Diana Nicole       NINAMANGO MALLMA   F        PER              05:34                    05:34    OK\n"
       "                         MEN\n"
       "      Pete               ZUCCARINI          M        USA              07:22                       0 DSQ BO SURF\n"))

(def dynamic-page
  (str "UNU Tampa Bay Freediving Challenge 2025 - DYNAMIC DISCIPLINES - RESULTS\n"
       "# Given Name         Family Name        Gender   Country   Discipline       Realized Penalties        Final Remarks\n"
       "                     DYN WOMEN\n"
       "1 Claire             PARIS                F      USA       DYN                  181                   181 WR M2\n"
       "                     DNF MEN\n"
       "  Erik               CINTRA               M      USA       DNF                                             DNS\n"))

(def continuation-page
  (str "# Given Name   Family Name   Gender   Country   Discipline       Realized Penalties        Final Remarks\n"
       "               DYNBF MEN\n"
       "6 Scott        GROFF           M      USA       DYNBF                100                   100 OK\n"
       "  Arnaldo      CABALLERO       M      USA       DYNBF                100                     0 DSQ\n"))

(deftest printed-results-retain-source-position-and-original-values
  (let [result (extraction/parse-pages [static-page dynamic-page continuation-page])
        [static dsq-static dynamic dns dynamic-last dsq-dynamic] (:candidates result)]
    (is (= unu/parser-version (:parser-version result)))
    (is (= [1 1 2 2 3 3] (mapv #(get-in % [:coordinates :page]) (:candidates result))))
    (is (= [5 7 4 6 3 4] (mapv #(get-in % [:coordinates :line]) (:candidates result))))
    (is (= "06.12.2025" (get-in static [:parsed :printed-date])))
    (is (nil? (get-in static [:parsed :event-date])))
    (is (= "STA" (get-in static [:parsed :discipline])))
    (is (= "05:34" (get-in static [:raw :fields :final-performance])))
    (is (= 334 (get-in static [:parsed :final-duration-seconds])))
    (is (= :clock-format-inferred (get-in static [:parsed :unit-evidence])))
    (is (= "DSQ BO SURF" (get-in dsq-static [:parsed :status])))
    (is (= "07:22" (get-in dsq-static [:raw :fields :realized-performance])))
    (is (nil? (get-in dsq-static [:parsed :final-duration-seconds])))
    (is (= "WR M2" (get-in dynamic [:parsed :status])))
    (is (= 181M (get-in dynamic [:parsed :final-distance])))
    (is (= :discipline-convention-inferred (get-in dynamic [:parsed :unit-evidence])))
    (is (= "DNS" (get-in dns [:parsed :status])))
    (is (= "DYNBF" (get-in dynamic-last [:parsed :discipline])))
    (is (= "DSQ" (get-in dsq-dynamic [:parsed :status])))
    (is (= 6 (get-in result [:reconciliation :candidate-count])))
    (is (= 0 (get-in result [:reconciliation :unparsed-count])))
    (is (= :blocked (get-in result [:publication :status])))))

(deftest unexpected-result-position-remains-unparsed
  (let [page (str dynamic-page "1 A             B                   F      USA       DYN                  100                   ??? OK\n")
        result (extraction/parse-pages [static-page page continuation-page])]
    (is (= 7 (get-in result [:reconciliation :candidate-count])))
    (is (= 1 (get-in result [:reconciliation :unparsed-count])))
    (is (= :partial-unsupported-needs-parser (:status result)))
    (is (= :unparsed (:parse-status (last (filter #(= 2 (get-in % [:coordinates :page])) (:candidates result))))))))

(deftest archived-replay-rejects-edited-result
  (let [pages [static-page dynamic-page continuation-page]
        raw (str (str/join "\f" pages) "\f")
        artifact (merge (unu/parse-pages pages)
                        {:source-sha256 unu/source-sha256 :raw-text raw
                         :tool {:name "pdftotext" :version "test-version"
                                :arguments ["-layout" "-enc" "UTF-8"]}})
        fake-sh (fn [& args] {:exit 0 :out (if (= "-v" (second args)) "" raw)
                              :err (if (= "-v" (second args)) "test-version" "")})]
    (with-redefs [archive/inspect (fn [& _] {:artifact-path "archived.pdf"})
                  shell/sh fake-sh]
      (is (= artifact (extraction/validate-unu-tampa-artifact! "archive" artifact)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source replay"
                            (extraction/validate-unu-tampa-artifact!
                             "archive" (assoc-in artifact [:candidates 0 :parsed :final-duration-seconds] 999)))))))
