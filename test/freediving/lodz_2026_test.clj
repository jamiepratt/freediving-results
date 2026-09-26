(ns freediving.lodz-2026-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.java.shell :as shell]
            [freediving.archive :as archive]
            [freediving.extraction-test :as extraction-fixture]
            [freediving.extraction :as extraction]
            [freediving.lodz-2026 :as lodz]
            [freediving.observations :as observations])
  (:import [java.security MessageDigest]
           [java.util HexFormat]))

(defn result-page [heading rows]
  (str "CMAS World Cup Indoor Series - Łódź, Poland 2026\n\n"
       heading "\n\nPlace    Athlete name    Country    Result    Card    Notes\n\n"
       rows))

(deftest printed-positions-preserve-source-and-uncertainty
  (let [pages [(result-page "13-03-2026 - Friday - Men - DNF - Senior"
                            (str " 1      Wojciech Raducha      Poland       100 m       WHITE\n"
                                 "        Artsem Hrytskevich                 -           RED    DQ Airways\n"))
               (result-page "14-03-2026 - Saturday - Women - STA - Senior"
                            " 1      Julia Kozerska        Poland       6:09        WHITE\n")
               (result-page "14-03-2026 - Saturday - Men - DYNB - Senior"
                            " 2      Artsem Hrytskevich                 152.5 m     WHITE\n")]
        result (lodz/parse-pages pages)
        [valid dq sta absent-country] (:candidates result)]
    (is (= "cmas-lodz-indoor-world-cup-2026/1" (:parser-version result)))
    (is (= [1 1 2 3] (mapv #(get-in % [:coordinates :page]) (:candidates result))))
    (is (= [7 8 7 7] (mapv #(get-in % [:coordinates :line]) (:candidates result))))
    (is (= "2026-03-13" (get-in valid [:parsed :event-date])))
    (is (= 100M (get-in valid [:parsed :final-distance])))
    (is (= "m" (get-in valid [:parsed :unit])))
    (is (= "DQ Airways" (get-in dq [:parsed :status])))
    (is (nil? (get-in dq [:parsed :representation])))
    (is (some #{:representation-unresolved} (:unresolved-reasons dq)))
    (is (= "6:09" (get-in sta [:parsed :result])))
    (is (= "STA" (get-in sta [:parsed :discipline])))
    (is (nil? (get-in sta [:parsed :final-distance])))
    (is (= "152.5 m" (get-in absent-country [:raw :fields :result])))
    (is (nil? (get-in absent-country [:parsed :representation])))
    (is (= 4 (get-in result [:reconciliation :candidate-count])))
    (is (= :blocked (get-in result [:publication :status])))))

(deftest changed-heading-does-not-silently-parse
  (let [result (lodz/parse-pages
                [(result-page "13-03-2026 - Friday - Men - XYZ - Senior"
                              " 1      A Diver       Poland       100 m       WHITE\n")])]
    (is (empty? (:candidates result)))
    (is (= [1] (get-in result [:reconciliation :unsupported-pages])))))

(deftest source-bound-extraction-replays-the-archived-pdf
  (let [pages [(result-page "13-03-2026 - Friday - Men - DNF - Senior"
                            " 1      Wojciech Raducha      Poland       100 m       WHITE\n")]
        raw (str (first pages) "\f")
        artifact (merge (lodz/parse-pages pages)
                        {:source-sha256 lodz/source-sha256 :raw-text raw
                         :tool {:name "pdftotext" :version "test-version"
                                :arguments ["-layout" "-enc" "UTF-8"]}})]
    (is (= lodz/parser-version (:parser-version (extraction/parse-pages pages))))
    (is (extraction/lodz-2026-artifact? artifact))
    (with-redefs [archive/inspect (fn [& _] {:artifact-path "archived.pdf"})
                  shell/sh (fn [& args] {:exit 0 :out (if (= "-v" (second args)) "" raw)
                                         :err (if (= "-v" (second args)) "test-version" "")})]
      (is (= artifact (extraction/validate-lodz-2026-artifact! "archive" artifact)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (extraction/validate-lodz-2026-artifact!
                    "archive" (assoc-in artifact [:candidates 0 :parsed :result] "999 m")))))))

(defn- canonical [value]
  (cond (map? value) (into (sorted-map) (map (fn [[k v]] [k (canonical v)]) value))
        (sequential? value) (mapv canonical value)
        :else value))

(deftest import-dispatches-through-source-bound-replay
  (let [[root source-sha] (extraction-fixture/registered-pdf)
        page (result-page "13-03-2026 - Friday - Men - DNF - Senior"
                          " 1      Wojciech Raducha      Poland       100 m       WHITE\n")
        identity {:source-sha256 source-sha
                  :acquisitions (:acquisitions (archive/inspect root source-sha))
                  :evidence-sha256 [] :actor "synthetic-test" :config {}
                  :parser-version lodz/parser-version :schema-version 3
                  :pdfinfo-version "test"
                  :tool {:name "pdftotext" :version "test"
                         :arguments ["-layout" "-enc" "UTF-8"]}}
        job-id (.formatHex (HexFormat/of)
                           (.digest (MessageDigest/getInstance "SHA-256")
                                    (.getBytes (pr-str (canonical identity)) "UTF-8")))
        artifact (merge identity (lodz/parse-pages [page])
                        {:job-id job-id :processed-at "2026-09-26T12:00:00Z"
                         :pdf-page-count 1 :raw-text (str page "\f")})]
    (archive/derive! root job-id (constantly artifact) nil)
    (with-redefs [extraction/validate-lodz-2026-artifact!
                  (fn [_ _] (throw (ex-info "Łódź replay invoked" {})))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Łódź replay invoked"
                            (observations/import! nil root job-id))))))

(defn -main []
  (let [result (run-tests 'freediving.lodz-2026-test)]
    (System/exit (if (pos? (+ (:fail result) (:error result))) 1 0))))
