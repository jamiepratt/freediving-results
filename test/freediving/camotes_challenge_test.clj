(ns freediving.camotes-challenge-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [freediving.archive :as archive]
            [freediving.archive-test :as fixture]
            [freediving.camotes-challenge :as challenge]
            [freediving.observations]))

(defn sample-row [page row]
  {:page page :row row :region [10 100 100 200] :note ""
   :cells {:last-name "Guignes" :first-name "Thibault" :country "France"
           :discipline "FIM" :ap "110" :top-time "9:00" :dive-time "3:35"
           :rp "110" :card "" :record "" :points "110"}})

(deftest challenge-preserves-audited-image-cells
  (let [r (challenge/parse-row (sample-row 2 1))]
    (is (= {:page 2 :line 1 :row 1 :region [10 100 100 200]} (:coordinates r)))
    (is (= "2025-04-22" (get-in r [:parsed :event-date])))
    (is (= "110" (get-in r [:raw :fields :rp])))
    (is (= "m" (get-in r [:parsed :unit])))
    (is (= 110 (get-in r [:parsed :distance])))
    (is (= :parsed (:parse-status r)))
    (is (= :blocked (get-in r [:publication :status])))))

(deftest unknown-page-date-and-clipped-cell-stay-unresolved
  (testing "the third scan does not print a session date"
    (let [r (challenge/parse-row (sample-row 3 1))]
      (is (= :unparsed (:parse-status r)))
      (is (nil? (:parsed r)))
      (is (nil? (get-in r [:parsed :event-date])))
      (is (some #{:missing-printed-date} (:unresolved-reasons r)))))
  (testing "a visible prefix is not completed from a later page"
    (let [r (challenge/parse-row
             (assoc (assoc-in (sample-row 1 15) [:cells :first-name] "Michael Angel")
                    :note "clipped-first-name"))]
      (is (= :unparsed (:parse-status r)))
      (is (nil? (:parsed r)))
      (is (some #{:clipped-source-cell} (:unresolved-reasons r))))))

(deftest source-bound-ledger-rejects-omission-and-revision
  (let [row (sample-row 2 1)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (challenge/parse-ledger {:source-sha256 "other" :rows [row]})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (challenge/parse-ledger {:source-sha256 challenge/source-sha256
                                          :rows [row]})))))

(deftest source-bound-ledger-reconciles-every-printed-position
  (let [rows (vec (for [page (range 1 5)
                        row (range 1 (inc (challenge/printed-counts page)))]
                    (sample-row page row)))
        parsed (challenge/parse-ledger {:source-sha256 challenge/source-sha256
                                        :rows rows})]
    (is (= 66 (get-in parsed [:reconciliation :printed-count])))
    (is (= 4 (:pdf-page-count parsed)))
    (is (= 4 (count (:pages parsed))))
    (is (= (get-in parsed [:pages 1 :lines 0 :text])
           (get-in parsed [:candidates 17 :raw :line])))
    (is (= [17 17 17 15]
           (mapv :printed-count (get-in parsed [:reconciliation :per-page]))))
    (is (= [17 17 0 15]
           (mapv :candidate-count (get-in parsed [:reconciliation :per-page]))))
    (is (= 49 (get-in parsed [:reconciliation :parsed-count])))
    (is (= 17 (get-in parsed [:reconciliation :unresolved-count])))
    (is (= 49 (count (:candidates parsed))))
    (is (= 17 (count (:unresolved-positions parsed))))))

(deftest checked-source-rejects-different-pdf-bytes
  (let [pdf (java.io.File/createTempFile "challenge-other" ".pdf")
        ledger (java.io.File/createTempFile "challenge-ledger" ".tsv")]
    (try
      (spit pdf "different source")
      (spit ledger (str/join "\t" challenge/tsv-header))
      (is (thrown? clojure.lang.ExceptionInfo
                   (challenge/parse-source (.getPath pdf) (.getPath ledger))))
      (finally
        (.delete pdf)
        (.delete ledger)))))

(deftest row-region-must-fit-the-rendered-page
  (let [bad (assoc (sample-row 1 1) :region [10 100 1800 200])]
    (is (thrown? clojure.lang.ExceptionInfo (challenge/parse-row bad)))))

(deftest reviewed-scan-derivation-replays-retained-ledger
  ;; The original image-only source and manually reviewed ledger are private.
  (let [source (io/file "data/cmas-scans-20260926/challenge/source.pdf")
        ledger (io/file "data/cmas-scans-20260926/challenge/rows.tsv")]
    (when (and (.exists source) (.exists ledger))
      (let [root (str (fixture/workspace) "/archive")
            _ (archive/register! root (.getPath source)
                                 (assoc fixture/manifest :sha256 challenge/source-sha256))
            opts {:actor "scan-test" :config {}}
            receipt (challenge/extract-reviewed-scan! root challenge/source-sha256
                                                      (.getPath ledger) opts)
            artifact (edn/read-string (slurp (:artifact-path receipt)))]
        (is (= :created (:run-status receipt)))
        (is (= :skipped (:run-status
                         (challenge/extract-reviewed-scan! root challenge/source-sha256
                                                           (.getPath ledger) opts))))
        (is (= 48 (get-in artifact [:reconciliation :parsed-count])))
        (is (= 48 (get-in artifact [:reconciliation :candidate-count])))
        (is (= 18 (get-in artifact [:reconciliation :unresolved-count])))
        (is (= 18 (count (:unresolved-positions artifact))))
        (is (every? #(= :parsed (:parse-status %)) (:candidates artifact)))
        (is (every? nil? (map :parsed (:unresolved-positions artifact))))
        (is (= artifact (challenge/validate-artifact! root artifact)))
        (let [verify-import (var-get (ns-resolve 'freediving.observations 'verified))
              rows-for-import (var-get (ns-resolve 'freediving.observations 'observation-rows))
              checked (:artifact (verify-import root (:job-id receipt)))
              kinds (frequencies (map :kind (rows-for-import checked)))]
          (is (= artifact checked))
          (is (= {"result-row" 48} kinds)))
        (is (thrown? clojure.lang.ExceptionInfo
                     (challenge/validate-artifact! root
                                                   (assoc-in artifact [:candidates 17 :raw :fields :rp] "999"))))))))
