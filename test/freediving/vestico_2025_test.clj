(ns freediving.vestico-2025-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [freediving.vestico-2025 :as vestico]))

(def source (slurp "test/resources/fixtures/vestico-2025/results.html" :encoding "UTF-8"))
(def source-sha256 "238dd1a1e5792f9c0ce5deb6be24263470271c4f2396f17399db27fc639ab09b")

(deftest official-dyn-result-reconciles-every-live-position
  (let [artifact (vestico/parse-html source)
        rows (:candidates artifact)
        first-woman (first rows)
        first-man (nth rows 8)]
    (is (= source-sha256 (:source-sha256 artifact)))
    (is (= source (:raw-html artifact)))
    (is (= :needs-review (:status artifact)))
    (is (= [8 4] (mapv :data-row-count (:tables artifact))))
    (is (= [1 2] (mapv :table (:tables artifact))))
    (is (= 12 (count rows)))
    (is (= 12 (get-in artifact [:reconciliation :printed-count])))
    (is (= 12 (get-in artifact [:reconciliation :parsed-count])))
    (is (zero? (get-in artifact [:reconciliation :unparsed-count])))
    (is (= 12 (get-in artifact [:reconciliation :unresolved-count])))
    (is (= :blocked (get-in artifact [:publication :status])))
    (is (= {:table 1 :row 2} (:coordinates first-woman)))
    (is (= {:table 2 :row 2} (:coordinates first-man)))
    (is (= "Törőcsik Zsófia" (get-in first-woman [:parsed :source-name])))
    (is (= "Čolak Goran" (get-in first-man [:parsed :source-name])))
    (is (= "22.03.2025. 09:32" (get-in first-woman [:raw :fields "OT"])))
    (is (= "2025-03-22" (get-in first-woman [:parsed :event-date])))
    (is (= "09:32" (get-in first-woman [:parsed :official-time])))
    (is (= "nOxygen Apnea Club" (get-in first-woman [:parsed :club])))
    (is (= "277,5" (get-in first-woman [:raw :fields "Result"])))
    (is (= 277.5M (get-in first-woman [:parsed :performance])))
    (is (nil? (get-in first-woman [:parsed :unit])))
    (is (= "W" (get-in first-woman [:parsed :card])))
    (is (= "DYN" (get-in first-woman [:parsed :discipline])))
    (is (= "Results female" (get-in first-woman [:parsed :category])))
    (is (= "Results male" (get-in first-man [:parsed :category])))
    (is (every? #(= :unreviewed (:review-status %)) rows))
    (is (every? #(.contains source (get-in % [:raw :html])) rows))
    (is (every? #(= 9 (count (get-in % [:raw :cell-html]))) rows))
    (is (not-any? #(str/includes? (get-in % [:raw :html]) "Ivan Šulc") rows))))

(deftest unsupported-or-damaged-view-stays-unresolved
  (let [wrong-view (str/replace source "DYN - Dinamika s perajom" "DNF - Dinamika bez peraja")
        damaged (str/replace-first source "<td class=\"text-right\">277,5</td>" "<td class=\"text-right\">unknown</td>")
        wrong (vestico/parse-html wrong-view)
        bad (vestico/parse-html damaged)
        row (first (:candidates bad))]
    (is (= :unsupported-needs-parser (:status wrong)))
    (is (empty? (:candidates wrong)))
    (is (not= source-sha256 (:source-sha256 bad)))
    (is (= :unparsed (:parse-status row)))
    (is (= "unknown" (get-in row [:raw :fields "Result"])))
    (is (= 1 (get-in bad [:reconciliation :unparsed-count])))
    (is (= 12 (get-in bad [:reconciliation :printed-count])))))

(deftest archived-view-replay-binds-candidates-to-source-bytes
  (let [artifact (vestico/parse-html source)]
    (is (= artifact (vestico/validate-artifact! artifact)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (vestico/validate-artifact!
                  (assoc-in artifact [:candidates 0 :parsed :performance] 999M))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (vestico/validate-artifact!
                  (assoc artifact :source-sha256 (apply str (repeat 64 "0"))))))))
