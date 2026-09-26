(ns freediving.camotes-challenge-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [freediving.camotes-challenge :as challenge]))

(defn sample-row [page row]
  {:page page :row row :region [10 1112 2700 1171] :note ""
   :cells {:last-name "Guignes" :first-name "Thibault" :country "France"
           :discipline "FIM" :ap "110" :top-time "9:00" :dive-time "3:35"
           :rp "110" :card "" :record "" :points "110"}})

(deftest challenge-preserves-audited-image-cells
  (let [r (challenge/parse-row (sample-row 2 1))]
    (is (= {:page 2 :line 1 :row 1 :region [10 1112 2700 1171]} (:coordinates r)))
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
      (is (nil? (get-in r [:parsed :event-date])))
      (is (some #{:missing-printed-date} (:unresolved-reasons r)))))
  (testing "a visible prefix is not completed from a later page"
    (let [r (challenge/parse-row
             (assoc (assoc-in (sample-row 1 15) [:cells :first-name] "Michael Angel")
                    :note "clipped-first-name"))]
      (is (= :unparsed (:parse-status r)))
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
    (is (= [17 17 17 15]
           (mapv :candidate-count (get-in parsed [:reconciliation :per-page]))))
    (is (= 49 (get-in parsed [:reconciliation :parsed-count])))
    (is (= 17 (get-in parsed [:reconciliation :unparsed-count])))))

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
