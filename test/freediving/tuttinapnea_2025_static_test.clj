(ns freediving.tuttinapnea-2025-static-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.java.shell :as shell]
            [freediving.archive :as archive]
            [freediving.extraction :as extraction]
            [freediving.tuttinapnea-2025-static :as tutt]))

(def heading (str "RESOCONTO GARA NAZIONALE\n"
                  "\" TUTTINAPNEA GENNAIO 2025 \"\n"
                  "STATICA\n"
                  "Sesso   Tempo Effettuato            PENALITA'\n"
                  "PUNTEGGIO STATICA\n"
                  "CLASSIFICA FEMMINILE\n"))

(deftest january-static-keeps-printed-values-and-source-lines
  (let [page (str heading
                  "    APNEA FIRENZE         GURTSKAIA SOFIA          F           5,56                 124,6\n"
                  "    APNEA CENTER          Lo Presti Massimo         M           2,33          50     26,775\n")
        result (extraction/parse-pages tutt/source-sha256 [page])
        [first-row second-row] (:candidates result)]
    (is (= tutt/parser-version (:parser-version result)))
    (is (= {:page-count 1 :candidate-count 2 :parsed-count 2 :unparsed-count 0 :unresolved-count 2}
           (select-keys (:reconciliation result)
                        [:page-count :candidate-count :parsed-count :unparsed-count :unresolved-count])))
    (is (= {:event-month "2025-01" :event-date nil :discipline "STA"
            :source-name "GURTSKAIA SOFIA" :gender "F"
            :realised-performance "5,56" :yellow-card nil :points "124,6"}
           (select-keys (:parsed first-row)
                        [:event-month :event-date :discipline :source-name :gender
                         :realised-performance :yellow-card :points])))
    (is (= "50" (get-in second-row [:parsed :yellow-card])))
    (is (= "26,775" (get-in second-row [:parsed :points])))
    (is (= {:page 1 :line 7} (select-keys (:coordinates first-row) [:page :line])))
    (is (= :blocked (get-in result [:publication :status])))))

(deftest broken-prefix-and-unsupported-source-stay-unresolved
  (let [page (str heading "c       UONEA TRAINING        Verri Angelo        M       2,55       61,25\n")
        result (tutt/parse-pages tutt/source-sha256 [page])]
    (is (= 1 (get-in result [:reconciliation :candidate-count])))
    (is (= 0 (get-in result [:reconciliation :parsed-count])))
    (is (= :unparsed (get-in result [:candidates 0 :parse-status])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (tutt/parse-pages (apply str (repeat 64 "0")) [page])))))

(deftest source-bound-artifact-is-recognized-for-import-replay
  (let [artifact {:schema-version 3 :source-sha256 tutt/source-sha256
                  :parser-version tutt/parser-version}]
    (is (extraction/tuttinapnea-2025-static-artifact? artifact))
    (is (extraction/tuttinapnea-2025-static-claim? artifact))
    (is (not (extraction/requires-geometry-validation? artifact)))
    (is (false? (extraction/tuttinapnea-2025-static-artifact?
                 (assoc artifact :source-sha256 (apply str (repeat 64 "0"))))))))

(deftest import-replay-detects-edited-result
  (let [page (str heading "APNEA FIRENZE   GURTSKAIA SOFIA   F   5,56   124,6\n")
        raw (str page "\f")
        artifact (merge (tutt/parse-pages tutt/source-sha256 [page])
                        {:source-sha256 tutt/source-sha256 :raw-text raw
                         :tool {:name "pdftotext" :version "test-version"
                                :arguments ["-layout" "-enc" "UTF-8"]}})]
    (with-redefs [archive/inspect (fn [& _] {:artifact-path "archived.pdf"})
                  shell/sh (fn [& args]
                             {:exit 0 :out (if (= "-v" (second args)) "" raw)
                              :err (if (= "-v" (second args)) "test-version" "")})]
      (is (= artifact (extraction/validate-tuttinapnea-2025-static-artifact!
                       "archive" artifact)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (extraction/validate-tuttinapnea-2025-static-artifact!
                    "archive" (assoc-in artifact [:candidates 0 :parsed :points] "999")))))))

(defn -main [& _]
  (let [result (run-tests 'freediving.tuttinapnea-2025-static-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
