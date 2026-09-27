(ns freediving.camotes-world-cup-continuation-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [freediving.archive :as archive]
            [freediving.archive-test :as fixture]
            [freediving.camotes-world-cup-continuation :as cup]
            [freediving.observations]))

(defn sample-row [[page block line]]
  {:page page :block block :line line
   :cells {:last-name "Ota" :first-name "Yoko" :country "Japan" :gender "Female"
           :discipline "CWT" :ap "60" :rp "60" :card "W" :record "" :points "60"}
   :note ""})

(defn sample-evidence []
  {:source-sha256 cup/source-sha256
   :pages (mapv (fn [page] {:page page :render-sha256 (cup/render-sha256 page)
                            :printed-date nil :block-row-counts (cup/block-counts page)}) [2 3 4])
   :positions (mapv (fn [[page block line]]
                      {:page page :block block :line line :region [4 (+ 400 (* 25 line)) 1100 (+ 425 (* 25 line))]})
                    cup/expected-positions)})

(deftest continuation-reconciles-every-scanned-position
  (let [rows (mapv sample-row cup/expected-positions)
        artifact (cup/parse-ledger {:source-sha256 cup/source-sha256 :rows rows} (sample-evidence))]
    (is (= [2 3 4] (get-in artifact [:reconciliation :scoped-pages])))
    (is (= 108 (get-in artifact [:reconciliation :printed-count])))
    (is (= 108 (count (:candidates artifact))))
    (is (empty? (:unresolved-positions artifact)))
    (is (= 4 (:pdf-page-count artifact)))
    (is (empty? (get-in artifact [:pages 0 :lines])))
    (is (= "2025" (get-in artifact [:candidates 0 :parsed :event-year])))
    (is (nil? (get-in artifact [:candidates 0 :parsed :event-date])))
    (is (nil? (get-in artifact [:candidates 0 :parsed :rank])))
    (is (nil? (get-in artifact [:candidates 0 :parsed :category])))
    (is (= {:page 2 :line 1} (select-keys (get-in artifact [:candidates 0 :coordinates]) [:page :line])))))

(deftest blank-rp-for-dns-and-blank-card-remain-literal
  (let [rows (-> (mapv sample-row cup/expected-positions)
                 (assoc-in [0 :cells :rp] "")
                 (assoc-in [0 :cells :card] "DNS")
                 (assoc-in [1 :cells :card] ""))
        artifact (cup/parse-ledger {:source-sha256 cup/source-sha256 :rows rows} (sample-evidence))]
    (is (= :parsed (get-in artifact [:candidates 0 :parse-status])))
    (is (nil? (get-in artifact [:candidates 0 :parsed :distance])))
    (is (= "" (get-in artifact [:candidates 0 :parsed :result])))
    (is (= "" (get-in artifact [:candidates 1 :parsed :card])))))

(deftest changed-or-missing-position-cannot-pass
  (let [rows (mapv sample-row cup/expected-positions)
        evidence (sample-evidence)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (cup/parse-ledger {:source-sha256 cup/source-sha256 :rows (pop rows)} evidence)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (cup/parse-ledger {:source-sha256 cup/source-sha256 :rows rows}
                                   (update evidence :positions pop))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (cup/parse-ledger {:source-sha256 cup/source-sha256 :rows rows}
                                   (assoc-in evidence [:pages 0 :printed-date] "18/05/2025"))))))

(deftest private-continuation-replays-when-available
  (let [base "/tmp/camotes-worldcup-p234"
        paths [(str base "/original.pdf")
               (str base "/page-2-reviewed.tsv") (str base "/page-3-reviewed.tsv") (str base "/page-4-reviewed.tsv")
               (str base "/row-evidence.json")
               (str base "/page-2.png") (str base "/page-3.png") (str base "/page-4.png")]]
    (when (every? #(.exists (io/file %)) paths)
      (let [artifact (apply cup/parse-source paths)]
        (is (= 108 (get-in artifact [:reconciliation :printed-count])))
        (is (= 107 (get-in artifact [:reconciliation :candidate-count])))
        (is (= 1 (get-in artifact [:reconciliation :unresolved-count])))
        (is (= 4 (:pdf-page-count artifact)))
        (is (nil? (get-in artifact [:candidates 0 :parsed :event-date])))))))

(deftest private-continuation-archive-replays-when-available
  (let [base "/tmp/camotes-worldcup-p234"
        paths [(str base "/original.pdf")
               (str base "/page-2-reviewed.tsv") (str base "/page-3-reviewed.tsv") (str base "/page-4-reviewed.tsv")
               (str base "/row-evidence.json")
               (str base "/page-2.png") (str base "/page-3.png") (str base "/page-4.png")]]
    (when (every? #(.exists (io/file %)) paths)
      (let [root (str (fixture/workspace) "/archive")
            _ (archive/register! root (.getCanonicalPath (io/file (first paths))) (assoc fixture/manifest :sha256 cup/source-sha256))
            args [root cup/source-sha256 (mapv #(.getCanonicalPath (io/file %)) (subvec (vec paths) 1)) {:actor "scan-test" :config {}}]
            receipt (apply cup/extract-reviewed-scan! args)
            artifact (edn/read-string (slurp (:artifact-path receipt)))
            verified (var-get (ns-resolve 'freediving.observations 'verified))]
        (is (= :created (:run-status receipt)))
        (is (= :skipped (:run-status (apply cup/extract-reviewed-scan! args))))
        (is (= artifact (cup/validate-artifact! root artifact)))
        (is (= artifact (:artifact (verified root (:job-id artifact)))))))))

(defn -main [& _]
  (let [result (clojure.test/run-tests 'freediving.camotes-world-cup-continuation-test)]
    (System/exit (if (pos? (+ (:fail result) (:error result))) 1 0))))
