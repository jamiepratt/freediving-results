(ns freediving.camotes-world-cup-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [freediving.archive :as archive]
            [freediving.archive-test :as fixture]
            [freediving.observations]
            [freediving.camotes-world-cup :as cup]))

(defn sample-row [block line]
  {:page 1 :block block :line line
   :cells {:last-name "Huang" :first-name "Ying" :country "China"
           :gender "Female" :discipline "CWTB" :ap "62" :rp "62"
           :card "NR" :record "M1" :points "62"}
   :note ""})

(defn sample-evidence []
  {:source-sha256 cup/source-sha256
   :render-sha256 cup/render-sha256
   :positions (vec (for [[block n] (map-indexed (fn [i n] [(inc i) n]) [13 12 13])
                         line (range 1 (inc n))]
                     {:page 1 :block block :line line
                      :region [4 (+ 400 (* 30 line)) 1193 (+ 428 (* 30 line))]}))})

(deftest page-one-row-preserves-source-cells
  (let [parsed (cup/parse-row (sample-row 1 1) (first (:positions (sample-evidence))))]
    (is (= {:page 1 :line 1 :block 1 :block-line 1 :region [4 430 1193 458]}
           (:coordinates parsed)))
    (is (= :parsed (:parse-status parsed)))
    (is (= "Ying Huang" (get-in parsed [:parsed :source-name])))
    (is (= "2025-05-18" (get-in parsed [:parsed :event-date])))
    (is (= 62 (get-in parsed [:parsed :distance])))
    (is (= "M1" (get-in parsed [:raw :fields :record])))
    (is (= "NR" (get-in parsed [:parsed :status])))
    (is (= :blocked (get-in parsed [:publication :status])))))

(deftest uncertain-cell-remains-unresolved
  (let [row (-> (sample-row 1 1)
                (assoc-in [:cells :last-name] "")
                (assoc :note "unresolved:last-name:clipped in scan"))
        parsed (cup/parse-row row (first (:positions (sample-evidence))))]
    (is (= :unparsed (:parse-status parsed)))
    (is (nil? (:parsed parsed)))
    (is (= [:ambiguous-source-cell] (:unresolved-reasons parsed)))
    (is (= "unresolved:last-name:clipped in scan" (get-in parsed [:raw :note])))))

(deftest uncertain-depth-stays-unresolved-with-blank-cell
  (let [row (-> (sample-row 1 1)
                (assoc-in [:cells :rp] "")
                (assoc :note "unresolved:rp:digit obscured"))
        parsed (cup/parse-row row (first (:positions (sample-evidence))))]
    (is (= :unparsed (:parse-status parsed)))
    (is (nil? (:parsed parsed)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (cup/parse-row (assoc row :note "")
                                (first (:positions (sample-evidence))))))))

(deftest page-one-evidence-rejects-omission-and-change
  (let [rows (vec (for [[block n] (map-indexed (fn [i n] [(inc i) n]) [13 12 13])
                        line (range 1 (inc n))]
                    (sample-row block line)))
        evidence (sample-evidence)]
    (testing "all 38 printed positions reconcile"
      (let [artifact (cup/parse-ledger {:source-sha256 cup/source-sha256 :rows rows}
                                       evidence)]
        (is (= 38 (get-in artifact [:reconciliation :printed-count])))
        (is (= 38 (get-in artifact [:reconciliation :candidate-count])))
        (is (= 38 (count (:candidates artifact))))))
    (testing "source-bound replay rejects changed evidence"
      (is (thrown? clojure.lang.ExceptionInfo
                   (cup/parse-ledger {:source-sha256 "changed" :rows rows} evidence)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (cup/parse-ledger {:source-sha256 cup/source-sha256 :rows (pop rows)} evidence)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (cup/parse-ledger {:source-sha256 cup/source-sha256 :rows rows}
                                     (assoc evidence :render-sha256 "changed")))))))

(deftest private-page-one-replays-when-available
  (let [paths ["/tmp/camotes-worldcup-source.pdf"
               "/tmp/camotes-worldcup-page1-rows.tsv"
               "/tmp/camotes-worldcup-b09-row-evidence.json"
               "/tmp/camotes-worldcup-page1-b09.png"]]
    (when (every? #(.exists (io/file %)) paths)
      (let [artifact (apply cup/parse-source paths)]
        (is (= 38 (get-in artifact [:reconciliation :printed-count])))
        (is (= 36 (get-in artifact [:reconciliation :parsed-count])))
        (is (= 2 (get-in artifact [:reconciliation :unresolved-count])))
        (is (= 36 (count (:candidates artifact))))
        (is (= 2 (count (:unresolved-positions artifact))))
        (is (= 4 (:pdf-page-count artifact)))
        (is (every? (comp empty? :lines) (rest (:pages artifact))))))))

(deftest private-page-one-archive-derivation-validates-when-available
  (let [[pdf ledger evidence render]
        ["/private/tmp/camotes-worldcup-source.pdf"
         "/private/tmp/camotes-worldcup-page1-rows.tsv"
         "/private/tmp/camotes-worldcup-b09-row-evidence.json"
         "/private/tmp/camotes-worldcup-page1-b09.png"]]
    (when (every? #(.exists (io/file %)) [pdf ledger evidence render])
      (let [root (str (fixture/workspace) "/archive")
            _ (archive/register! root pdf (assoc fixture/manifest :sha256 cup/source-sha256))
            args [root cup/source-sha256 ledger evidence render {:actor "scan-test" :config {}}]
            receipt (apply cup/extract-reviewed-scan! args)
            artifact (edn/read-string (slurp (:artifact-path receipt)))
            verified (var-get (ns-resolve 'freediving.observations 'verified))
            observation-rows (var-get (ns-resolve 'freediving.observations 'observation-rows))]
        (is (= :created (:run-status receipt)))
        (is (= :skipped (:run-status (apply cup/extract-reviewed-scan! args))))
        (is (= artifact (cup/validate-artifact! root artifact)))
        (is (= artifact (:artifact (verified root (:job-id receipt)))))
        (is (= 36 (count (observation-rows artifact))))
        (is (thrown? clojure.lang.ExceptionInfo
                     (cup/validate-artifact! root
                                             (assoc-in artifact [:candidates 0 :parsed :distance] 999))))))))
