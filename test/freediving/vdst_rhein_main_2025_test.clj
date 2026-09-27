(ns freediving.vdst-rhein-main-2025-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [freediving.vdst-rhein-main-2025 :as cup]))

(def source "/tmp/vdst-b33-originals/rmc2025.pdf")

(defn- ranked-line [n result]
  (format "%d.          Athlete %d                2000   Example Club                              %s"
          n n result))

(defn- status-lines [numbers]
  (concat ["abgemeldet"]
          (map #(format "            Athlete %d                2000 Example Club" %) numbers)))

(defn- fixture-page [event result ranked-numbers status-numbers]
  (str/join "\n" (concat [(str "Wettkampf " event)
                          "RMC: Damen Senior - AK18-49 (Jg. 2007-1976)"]
                         (map #(ranked-line % result) ranked-numbers)
                         (status-lines status-numbers))))

(def synthetic-pages
  ["22. Rhein-Main Cup 2025\nHessische Statik Meisterschaft\n27.09.2025\nerstellt am: 15.09.2025 10:31"
   "22. Rhein-Main Cup 2025"
   "22. Rhein-Main Cup 2025"
   (str "Abschnitt 1 - Samstag 27.09.2025\n"
        (fixture-page "1 - Statik" "01:00,00" (range 1 21) (range 51 59)))
   (str/join "\n"
             (concat ["Hessische Statik Meisterschaft: Damen"]
                     (map #(format "%d.       Athlete %d                     2000 Example Club                             01:00,00" % %)
                          (range 1 6))
                     [(fixture-page "2 - 2 x 25m Speed-Apnea" "00:25,00" (range 21 31) [59])]))
   (fixture-page "3 - DBF" "75,0m" (range 31 46) (range 60 66))
   (str (fixture-page "4 - DYN" "100,0m" (range 46 51) (range 66 69))
        "\nProtokollende: 15:55 Uhr")])

(deftest synthetic-protocol-requires-complete-coverage
  (let [artifact (cup/parse-pages cup/source-sha256 synthetic-pages)
        reconciliation (:reconciliation artifact)]
    (is (= synthetic-pages (mapv :text (:pages artifact))))
    (is (= 68 (:printed-count reconciliation)))
    (is (= 68 (:parsed-count reconciliation)))
    (is (= 68 (:unresolved-count reconciliation)))
    (is (= 0 (:unparsed-count reconciliation)))
    (is (= [0 0 0 28 11 21 8]
           (mapv :printed-count (:per-page reconciliation))))
    (is (= 5 (:repeated-hessian-count reconciliation)))
    (is (= 0 (:hessian-unmatched-count reconciliation)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (cup/parse-pages cup/source-sha256
                                  (update synthetic-pages 3 str/replace-first
                                          (ranked-line 1 "01:00,00") ""))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (cup/parse-pages cup/source-sha256
                                  (update synthetic-pages 4 str/replace-first
                                          "Athlete 1" "Other Name"))))))

(deftest original-protocol-reconciles-main-and-hessian-views
  (when (.exists (io/file source))
    (let [artifact (cup/parse-pdf source)
          rows (:candidates artifact)
          reconciliation (:reconciliation artifact)]
      (is (= 7 (:page-count reconciliation)))
      (is (= 68 (:printed-count reconciliation)))
      (is (= 50 (:ranked-count reconciliation)))
      (is (= 18 (:status-count reconciliation)))
      (is (= 5 (:repeated-hessian-count reconciliation)))
      (is (= 0 (:hessian-unmatched-count reconciliation)))
      (is (= #{["Andrea Sibylle Claussen" "05:02,00"]
               ["Svanja Clausen" "04:05,00"]
               ["Rosalba Hartmann" "04:03,00"]
               ["David Kurtscheidt" "06:19,00"]
               ["Uwe Kiehl" "05:39,00"]}
             (set (map (juxt :source-name :result) (:repeated-hessian-results artifact)))))
      (is (= [0 0 0 28 11 21 8]
             (mapv :printed-count (:per-page reconciliation))))
      (is (= 68 (count rows)))
      (is (= 0 (:unparsed-count reconciliation)))
      (is (= "06:19,00" (get-in (first (filter #(= "David Kurtscheidt" (get-in % [:parsed :source-name])) rows)) [:raw :fields :result])))
      (is (= 1 (count (filter #(and (= "David Kurtscheidt" (get-in % [:parsed :source-name]))
                                    (= "STA" (get-in % [:parsed :discipline]))) rows))))
      (is (every? #(seq (:source-lines %)) rows))
      (is (every? #(= 3 (count (:metadata-evidence %))) rows))
      (is (= "erstellt am: 15.09.2025 10:31"
             (some-> artifact :metadata-discrepancies first :source-line :text str/trim)))
      (is (= {:ranked 50 :withdrawn 7 :did-not-start 4
              :disqualified 5 :outside-competition 2}
             (frequencies (map #(get-in % [:parsed :status]) rows))))
      (is (some #(and (= "Markus Willhardt" (get-in % [:parsed :source-name]))
                      (= "BQ DO" (get-in % [:parsed :status-code]))) rows))
      (is (every? #(= :blocked (get-in % [:publication :status])) rows)))))

(deftest source-binding-rejects-other-sha
  (is (thrown? clojure.lang.ExceptionInfo
               (cup/parse-pages (apply str (repeat 64 "0")) []))))

(defn -main [& _]
  (let [result (run-tests 'freediving.vdst-rhein-main-2025-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
