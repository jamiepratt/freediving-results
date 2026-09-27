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

(deftest unknown-page-date-remains-explicit-on-candidate
  (testing "the third scan does not print a session date"
    (let [r (challenge/parse-row (sample-row 3 1) {:candidate-scope :page-three-undated})]
      (is (= :parsed (:parse-status r)))
      (is (= "Thibault Guignes" (get-in r [:parsed :source-name])))
      (is (nil? (get-in r [:parsed :event-date])))
      (is (nil? (get-in r [:parsed :session-day])))
      (is (some #{:missing-printed-date} (:unresolved-reasons r)))
      (is (= :blocked (get-in r [:publication :status]))))))

(deftest clipped-cell-stays-unresolved
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
                    (if (= [page row] [1 15])
                      (assoc (sample-row page row) :note "clipped-first-name")
                      (sample-row page row))))
        parsed (challenge/parse-ledger {:source-sha256 challenge/source-sha256
                                        :rows rows}
                                       {:candidate-scope :page-three-undated})]
    (is (= 66 (get-in parsed [:reconciliation :printed-count])))
    (is (= 4 (:pdf-page-count parsed)))
    (is (= 4 (count (:pages parsed))))
    (is (= (get-in parsed [:pages 2 :lines 0 :text])
           (get-in parsed [:candidates 0 :raw :line])))
    (is (= [17 17 17 15]
           (mapv :printed-count (get-in parsed [:reconciliation :per-page]))))
    (is (= [0 0 17 0]
           (mapv :candidate-count (get-in parsed [:reconciliation :per-page]))))
    (is (= 17 (get-in parsed [:reconciliation :parsed-count])))
    (is (= 48 (get-in parsed [:reconciliation :previously-imported-count])))
    (is (= 1 (get-in parsed [:reconciliation :unresolved-count])))
    (is (= 17 (count (:candidates parsed))))
    (is (= 1 (count (:unresolved-positions parsed))))))

(deftest continuation-excludes-the-existing-forty-eight
  (let [rows (vec (for [page (range 1 5)
                        row (range 1 (inc (challenge/printed-counts page)))]
                    (if (= [page row] [1 15])
                      (assoc (sample-row page row) :note "clipped-first-name")
                      (sample-row page row))))
        ledger {:source-sha256 challenge/source-sha256 :rows rows}
        original (challenge/parse-ledger ledger)
        continuation (challenge/parse-ledger ledger {:candidate-scope :page-three-undated})]
    (is (= 48 (count (:candidates original))))
    (is (= [16 17 0 15]
           (mapv :candidate-count (get-in original [:reconciliation :per-page]))))
    (is (= 17 (count (:candidates continuation))))
    (is (= (set (map #(select-keys (:coordinates %) [:page :row]) (:candidates original)))
           (set (for [page [1 2 4]
                      row (range 1 (inc (challenge/printed-counts page)))
                      :when (not= [page row] [1 15])]
                  {:page page :row row}))))
    (is (every? #(= 3 (get-in % [:coordinates :page]))
                (:candidates continuation)))
    (is (= [[1 15]]
           (mapv (comp (juxt :page :row) :coordinates) (:unresolved-positions continuation))))))

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
        (let [continuation-receipt
              (challenge/extract-reviewed-scan! root challenge/source-sha256
                                                (.getPath ledger)
                                                {:actor "scan-test"
                                                 :config {:candidate-scope :page-three-undated}})
              continuation (edn/read-string (slurp (:artifact-path continuation-receipt)))
              verify-import (var-get (ns-resolve 'freediving.observations 'verified))
              rows-for-import (var-get (ns-resolve 'freediving.observations 'observation-rows))]
          (is (not= (:job-id receipt) (:job-id continuation-receipt)))
          (is (= 17 (count (:candidates continuation))))
          (is (= 48 (get-in continuation [:reconciliation :previously-imported-count])))
          (is (= {"result-row" 17}
                 (frequencies (map :kind (rows-for-import continuation)))))
          (is (= continuation (:artifact (verify-import root (:job-id continuation-receipt)))))
          (is (= :skipped (:run-status
                           (challenge/extract-reviewed-scan! root challenge/source-sha256
                                                             (.getPath ledger)
                                                             {:actor "scan-test"
                                                              :config {:candidate-scope :page-three-undated}}))))
          (is (= artifact (:artifact (verify-import root (:job-id receipt))))))
        (is (thrown? clojure.lang.ExceptionInfo
                     (challenge/validate-artifact! root
                                                   (assoc-in artifact [:candidates 17 :raw :fields :rp] "999"))))))))
