(ns freediving.vdst-chemnitz-2025-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [freediving.vdst-chemnitz-2025 :as cup]))

(def source "/tmp/vdst-chemnitz-20260927/capc2025.pdf")

(deftest original-protocol-reconciles-every-printed-position
  (when (.exists (io/file source))
    (let [artifact (cup/parse-pdf source)
          rows (:candidates artifact)
          rec (:reconciliation artifact)]
      (is (= :needs-review (:status artifact)))
      (is (= 91 (:printed-count rec)))
      (is (= 91 (:parsed-count rec)))
      (is (= 0 (:unparsed-count rec)))
      (is (= [0 0 0 10 17 18 19 18 9] (mapv :printed-count (:per-page rec))))
      (is (= {"main" 56 "super-finale-round-1" 10
              "super-finale-round-2" 7 "super-finale-final" 6
              "parcours" 12}
             (:by-section rec)))
      (is (= 10 (:status-count rec)))
      (is (= 81 (:result-count rec)))
      (is (= {:ranked 66 :completed-unranked 15 :did-not-start 6
              :disqualified 2 :withdrawn 2}
             (frequencies (map #(get-in % [:parsed :status]) rows))))
      (is (= {nil 68 "Runde 1" 10 "Runde 2" 7 "Finalrunde" 6}
             (frequencies (map #(get-in % [:parsed :round]) rows))))
      (is (= 7 (count (filter #(get-in % [:parsed :penalty]) rows))))
      (is (= 2 (count (filter #(get-in % [:parsed :round-decision]) rows))))
      (is (= 6 (count (filter #(= "super-finale-final" (get-in % [:parsed :section])) rows))))
      (is (= [1 1]
             (mapv #(get-in % [:parsed :rank])
                   (filter #(and (= "super-finale-final" (get-in % [:parsed :section]))
                                 (= 1 (get-in % [:parsed :rank]))) rows))))
      (is (every? #(nil? (get-in % [:parsed :round]))
                  (filter #(= "parcours" (get-in % [:parsed :section])) rows)))
      (is (every? (fn [row]
                    (= (get-in row [:raw :line])
                       (get-in artifact [:pages (dec (get-in row [:coordinates :page]))
                                         :lines (dec (get-in row [:coordinates :line])) :text])))
                  rows))
      (is (every? #(and (seq (:source-lines %))
                        (= :blocked (get-in % [:publication :status]))) rows)))))

(deftest source-binding-and-missing-position-block-coverage
  (is (thrown? clojure.lang.ExceptionInfo
               (cup/parse-pages (apply str (repeat 64 "0")) [])))
  (when (.exists (io/file source))
    (let [artifact (cup/parse-pdf source)
          pages (mapv :text (:pages artifact))
          cut-page (update pages 3 (fn [page]
                                     (str/replace-first
                                      page #"(?m)^1\.\s+.+?00:24,13\s*$" "")))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (cup/parse-pages cup/source-sha256 cut-page))))))

(defn -main [& _]
  (let [result (run-tests 'freediving.vdst-chemnitz-2025-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
