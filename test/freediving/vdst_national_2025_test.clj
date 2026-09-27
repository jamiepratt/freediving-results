(ns freediving.vdst-national-2025-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is run-tests]]
            [freediving.vdst-national-2025 :as national]))

(def source "/tmp/vdst-national-2025.pdf")

(deftest national-protocol-preserves-primary-results-and-statuses
  (when (.exists (io/file source))
    (let [artifact (national/parse-pdf source)
          rows (:candidates artifact)
          rec (:reconciliation artifact)]
      (is (= 21 (:page-count rec)))
      (is (= 314 (:printed-ranked-count rec)))
      (is (= 157 (:supporting-repeat-count rec)))
      (is (= 157 (:ranked-count rec)))
      (is (= 49 (:status-count rec)))
      (is (= 34 (:status-block-count rec)))
      (is (= 206 (count rows)))
      (is (= 0 (:unresolved-count rec)))
      (is (= {1 38, 2 8, 3 33, 4 7, 5 29, 6 7, 7 35}
             (:ranked-by-competition rec)))
      (is (= {:not-started 33, :withdrawn 8, :disqualified 8, :ranked 157}
             (frequencies (map #(get-in % [:parsed :status]) rows))))
      (is (= 3 (:unique-jury-decision-count rec)))
      (is (= national/source-url (:source-url artifact)))
      (is (every? #(some? (get-in % [:parsed :event-date])) rows))
      (is (every? #(some? (get-in % [:parsed :discipline])) rows))
      (is (every? #(= :blocked (get-in % [:publication :status])) rows))
      (is (every? #(= (get-in % [:raw :line])
                      (get-in artifact [:pages (dec (get-in % [:coordinates :page]))
                                        :lines (dec (get-in % [:coordinates :line])) :text])) rows))
      (is (= 157 (reduce + (map #(count (:supporting-views %)) rows))))
      (is (= 1 (count (filter #(= :restart-after-disqualification
                                  (get-in % [:parsed :jury-decision :outcome])) rows))))
      (is (= 1 (count (filter #(= :disqualification-overturned
                                  (get-in % [:parsed :jury-decision :outcome])) rows))))
      (is (= 1 (count (filter #(= :white-card-after-video
                                  (get-in % [:parsed :jury-decision :outcome])) rows))))
      (is (= 2 (count (get-in (first (filter #(= :restart-after-disqualification
                                                 (get-in % [:parsed :jury-decision :outcome])) rows))
                              [:parsed :jury-decision :source-notes]))))
      (is (= 8 (count (filter #(and (= :disqualified (get-in % [:parsed :status]))
                                    (some? (get-in % [:parsed :status-note]))) rows)))))))

(deftest national-protocol-is-bound-to-archived-bytes
  (is (thrown? clojure.lang.ExceptionInfo
               (national/parse-pages (apply str (repeat 64 "0")) []))))

(defn -main [& _]
  (let [result (run-tests 'freediving.vdst-national-2025-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
