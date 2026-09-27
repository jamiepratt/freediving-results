(ns freediving.vdst-mitteldeutscher-2025-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [freediving.vdst-mitteldeutscher-2025 :as cup]))

(def layout "/tmp/vdst-mdc-2025-layout.txt")
(def source "/tmp/vdst-mdc-2025.pdf")

(defn- original-pages []
  (when (.exists (io/file layout))
    (->> (str/split (slurp layout) #"\f")
         (remove str/blank?) vec)))

(deftest only-final-apnea-positions-enter-corpus
  (when-let [pages (original-pages)]
    (let [artifact (cup/parse-pages cup/source-sha256 pages)
          rows (:candidates artifact)
          rec (:reconciliation artifact)]
      (is (= 30 (:page-count rec)))
      (is (= 28 (:printed-count rec)))
      (is (= 28 (:parsed-count rec)))
      (is (= 0 (:unparsed-count rec)))
      (is (= {101 10, 102 6, 103 7, 104 2, 105 1, 106 2}
             (:by-competition rec)))
      (is (= {11 5, 12 11, 19 7, 20 2, 25 3}
             (into {} (map (juxt :page :printed-count)
                           (filter (comp pos? :printed-count) (:per-page rec))))))
      (is (= {:ranked 27, :disqualified 1}
             (frequencies (map #(get-in % [:parsed :status]) rows))))
      (is (= #{101 102 103 104 105 106}
             (set (map #(get-in % [:parsed :competition]) rows))))
      (is (= #{"Abschnitt 1"} (set (map #(get-in % [:parsed :session]) rows))))
      (is (= #{"min:sec,centisec"}
             (set (keep #(get-in % [:parsed :unit]) rows))))
      (is (every? #(str/includes? (get-in % [:parsed :discipline]) "Speed Apnea") rows))
      (is (= "Lia Fischer" (get-in (first (filter #(= :disqualified (get-in % [:parsed :status])) rows))
                                   [:parsed :source-name])))
      (is (= "Gesicht aus dem Wasser bei 90 m"
             (get-in (first (filter #(= :disqualified (get-in % [:parsed :status])) rows))
                     [:parsed :disqualification-reason])))
      (is (every? (fn [row]
                    (= (get-in row [:raw :line])
                       (get-in artifact [:pages (dec (get-in row [:coordinates :page]))
                                         :lines (dec (get-in row [:coordinates :line])) :text]))) rows))
      (is (every? #(= :blocked (get-in % [:publication :status])) rows)))))

(deftest altered-source-or-missing-position-is-rejected
  (is (thrown? clojure.lang.ExceptionInfo
               (cup/parse-pages (apply str (repeat 64 "0")) [])))
  (when-let [pages (original-pages)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (cup/parse-pages cup/source-sha256
                                  (update pages 10 str/replace-first
                                          #"(?m)^1\.\s+Valeria Lazarenko.+$" ""))))))

(deftest archived-original-is-source-bound
  (when (.exists (io/file source))
    (let [artifact (cup/parse-pdf source)]
      (is (= cup/source-sha256 (:source-sha256 artifact)))
      (is (= 28 (count (:candidates artifact)))))))

(defn -main [& _]
  (let [result (run-tests 'freediving.vdst-mitteldeutscher-2025-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
