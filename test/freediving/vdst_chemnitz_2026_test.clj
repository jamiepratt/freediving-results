(ns freediving.vdst-chemnitz-2026-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [freediving.vdst-chemnitz-2026 :as cup]))

(def original "/tmp/vdst-chemnitz-20260927/capc2026.pdf")

(deftest original-protocol-reconciles-every-sporting-position
  (let [artifact (cup/parse-pdf original)
        reconciliation (:reconciliation artifact)
        rows (:candidates artifact)]
    (is (= :needs-review (:status artifact)))
    (is (= 10 (:page-count reconciliation)))
    (is (= [0 0 0 10 25 20 21 18 19 8]
           (mapv :candidate-count (:per-page reconciliation))))
    (is (= 121 (:printed-position-count reconciliation)))
    (is (= 111 (:result-count reconciliation)))
    (is (= 10 (:status-count reconciliation)))
    (is (= 0 (:unparsed-count reconciliation)))
    (is (= 121 (count rows)))
    (is (= 5 (count (filter #(and (= "131" (get-in % [:parsed :competition-number]))
                                  (get-in % [:parsed :realised-performance])) rows))))
    (is (= 5 (count (filter #(= "132" (get-in % [:parsed :competition-number])) rows))))
    (is (= 3 (count (filter #(= "13" (get-in % [:parsed :competition-number])) rows))))
    (is (= 4 (:supporting-winner-caption-count reconciliation)))
    (is (= 2 (:qualification-note-count reconciliation)))
    (is (= 6 (:penalty-note-count reconciliation)))
    (is (= 0 (:unreconciled-position-count reconciliation)))
    (is (= #{:round-1 :round-2 :end-round}
           (set (map #(get-in % [:parsed :round])
                     (filter #(contains? #{"131" "132" "13"}
                                         (get-in % [:parsed :competition-number])) rows)))))
    (is (= {:disqualified 3 :withdrawn 2 :did-not-start 5}
           (frequencies (map #(get-in % [:parsed :status])
                             (remove #(get-in % [:parsed :realised-performance]) rows)))))
    (is (every? #(and (get-in % [:coordinates :page])
                      (get-in % [:coordinates :line])
                      (get-in % [:raw :line])
                      (seq (:source-lines %))) rows))
    (is (= :blocked (get-in artifact [:publication :status])))))

(deftest source-binding-rejects-other-pdfs
  (is (thrown? clojure.lang.ExceptionInfo
               (cup/parse-pages (apply str (repeat 64 "0")) []))))

(deftest missing-printed-result-is-not-silently-accepted
  (let [artifact (cup/parse-pdf original)
        pages (mapv :text (:pages artifact))
        first-result (first (:candidates artifact))
        page-index (dec (get-in first-result [:coordinates :page]))
        line (get-in first-result [:raw :line])]
    (is (thrown? clojure.lang.ExceptionInfo
                 (cup/parse-pages cup/source-sha256
                                  (update pages page-index str/replace-first line ""))))))

(defn -main [& _]
  (let [result (run-tests 'freediving.vdst-chemnitz-2026-test)]
    (System/exit (if (pos? (+ (:fail result) (:error result))) 1 0))))
