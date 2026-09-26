(ns freediving.camotes-challenge-2026-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [freediving.archive :as archive]
            [freediving.archive-test :as fixture]
            [freediving.camotes-challenge-2026 :as challenge]
            [freediving.observations]))

(defn row [page number]
  {:page page :row number
   :cells {:last-name "Glazer" :first-name "Gina" :country "USA"
           :discipline "FIM" :ap "60" :top-time "9:00"
           :dive-time "2:30" :rp "60" :card "R DQBO surface"
           :record "" :points "0"}})

(deftest retains-printed-values-and-page-date
  (let [candidate (challenge/parse-row (row 1 1))]
    (is (= "2026-04-24" (get-in candidate [:parsed :event-date])))
    (is (= "R DQBO surface" (get-in candidate [:raw :fields :card])))
    (is (= "R DQBO surface" (get-in candidate [:parsed :card])))
    (is (= {:page 1 :line 1 :row 1} (:coordinates candidate)))
    (is (= :parsed (:parse-status candidate)))))

(deftest dns-card-remains-source-status
  (let [candidate (challenge/parse-row
                   (assoc-in (row 3 17) [:cells :card] "DNS"))]
    (is (= "DNS" (get-in candidate [:parsed :status])))
    (is (= "60" (get-in candidate [:raw :fields :rp])))))

(deftest all-printed-positions-reconcile
  (let [rows (vec (for [page (range 1 5) number (range 1 19)]
                    (row page number)))
        artifact (challenge/parse-ledger {:source-sha256 challenge/source-sha256
                                          :rows rows})]
    (is (= 72 (get-in artifact [:reconciliation :printed-count])))
    (is (= 72 (get-in artifact [:reconciliation :parsed-count])))
    (is (= [18 18 18 18]
           (mapv :candidate-count (get-in artifact [:reconciliation :per-page]))))
    (is (= 72 (count (:candidates artifact))))))

(deftest missing-position-or-other-source-is-rejected
  (is (thrown? clojure.lang.ExceptionInfo
               (challenge/parse-ledger {:source-sha256 challenge/source-sha256
                                        :rows [(row 1 1)]})))
  (is (thrown? clojure.lang.ExceptionInfo
               (challenge/parse-ledger {:source-sha256 (apply str (repeat 64 "0"))
                                        :rows []}))))

(deftest private-source-replays-all-printed-positions
  (let [pdf (io/file "/tmp/cmas-2026-camotes-challenge-b12.pdf")
        ledger (io/file "/tmp/camotes-2026-rows-b12.tsv")]
    (when (and (.exists pdf) (.exists ledger))
      (let [root (str (fixture/workspace) "/archive")
            _ (archive/register! root (.getCanonicalPath pdf)
                                 (assoc fixture/manifest :sha256 challenge/source-sha256))
            options {:actor "source-test" :config {}}
            receipt (challenge/extract-reviewed-scan!
                     root challenge/source-sha256 (.getCanonicalPath ledger) options)
            artifact (edn/read-string (slurp (:artifact-path receipt)))]
        (is (= :created (:run-status receipt)))
        (is (= :skipped (:run-status
                         (challenge/extract-reviewed-scan!
                          root challenge/source-sha256 (.getCanonicalPath ledger) options))))
        (is (= [18 18 18 18]
               (mapv :candidate-count (get-in artifact [:reconciliation :per-page]))))
        (is (= ["DNS" "42" 0]
               [(get-in artifact [:candidates 52 :parsed :status])
                (get-in artifact [:candidates 52 :raw :fields :rp])
                (get-in artifact [:candidates 52 :parsed :points])]))
        (is (= artifact (challenge/validate-artifact! root artifact)))
        (let [verify-import (var-get (ns-resolve 'freediving.observations 'verified))
              rows-for-import (var-get (ns-resolve 'freediving.observations 'observation-rows))
              checked (:artifact (verify-import root (:job-id receipt)))]
          (is (= artifact checked))
          (is (= {"result-row" 72}
                 (frequencies (map :kind (rows-for-import checked))))))
        (is (thrown? clojure.lang.ExceptionInfo
                     (challenge/validate-artifact!
                      root (assoc-in artifact [:candidates 0 :raw :fields :rp] "999"))))))))
