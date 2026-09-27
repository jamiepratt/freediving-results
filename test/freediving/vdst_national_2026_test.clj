(ns freediving.vdst-national-2026-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is run-tests]]
            [freediving.vdst-national-2026 :as national]))

(def source "/tmp/vdst-national-2026.pdf")

(deftest national-2026-separates-results-from-standings
  (when (.exists (io/file source))
    (let [artifact (national/parse-pdf source)
          rec (:reconciliation artifact)
          rows (:candidates artifact)]
      (is (= 26 (:page-count rec)))
      (is (= 28 (:competition-count rec)))
      (is (= 95 (:ranking-heading-count rec)))
      (is (= 19 (:aggregate-heading-count rec)))
      (is (= 84 (:aggregate-position-count rec)))
      (is (= 121 (:aggregate-component-count rec)))
      (is (= 318 (:printed-ranked-count rec)))
      (is (= 182 (:supporting-repeat-count rec)))
      (is (= 136 (:ranked-count rec)))
      (is (= 22 (:status-count rec)))
      (is (= 158 (count rows)))
      (is (= 0 (:unresolved-count rec)))
      (is (= 1 (:unique-jury-decision-count rec)))
      (is (= {:withdrawn 14 :not-started 5 :disqualified 3}
             (frequencies (map #(get-in % [:parsed :status])
                               (remove #(= :ranked (get-in % [:parsed :status])) rows)))))
      (is (= #{"2026-03-07" "2026-03-08"}
             (set (map #(get-in % [:parsed :event-date]) rows))))
      (is (= 1 (count (filter #(and (= 27 (get-in % [:parsed :competition]))
                                    (= "122,0m" (get-in % [:parsed :result]))
                                    (= :disqualification-overturned
                                       (get-in % [:parsed :jury-decision :outcome]))) rows))))
      (is (every? #(= :blocked (get-in % [:publication :status])) rows))
      (is (every? (fn [row]
                    (every? (fn [{:keys [page line text]}]
                              (= text (get-in artifact [:pages (dec page) :lines (dec line) :text])))
                            (:source-lines row))) rows))
      (is (every? #(= (get-in % [:raw :line])
                      (get-in artifact [:pages (dec (get-in % [:coordinates :page]))
                                        :lines (dec (get-in % [:coordinates :line])) :text])) rows)))))

(deftest national-2026-is-bound-to-exact-source
  (is (thrown? clojure.lang.ExceptionInfo
               (national/parse-pages (apply str (repeat 64 "0")) []))))

(defn -main [& _]
  (let [result (run-tests 'freediving.vdst-national-2026-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
