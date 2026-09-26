(ns freediving.deep-dominica-2026-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [freediving.deep-dominica-2026 :as dominica]))

(defn page [date rows]
  (str "                                                                               " date "\n"
       "                                                                           Official Results\n\n\n"
       "      Name       Country   Discipline   DD         RD    Final Results         Notes\n"
       (str/join "\n" rows) "\n                                        Supported By\n"))

(def july-28
  (page "July 28"
        ["Jade Agboton                    CNF        61           48    0        Red        DSQ Pull"
         "Jenna Tanner                   CWTBF       72           72   72       White"
         "Jon Fane                        FIM        78           64   49       Yellow      Early Turn"
         "Mateusz Malina                  CNF        93                          DNS"
         "Stéphane Tourreau               CWT        123         123   123      White"]))

(def july-29
  (page "July 29"
        ["Kimmy Williams                FIM       15          15   15       White      OPENER"
         "Jenna Tanner                  CNF       33          33   33       White         PB"
         "Claire Paris                  FIM       64          43   21       Yellow     Early Turn"
         "Jade Agboton                  FIM       82          50    0        Red     DSQ SP NO OK"
         "Mateusz Malina                CNF       93          84   74       Yellow     Early Turn"]))

(def july-31
  (page "July 31"
        ["Jade Agboton                    CNF        61           61   61       White          NR"
         "Claire Paris                    FIM        64           64   64       White"
         "Jenna Tanner                    CWT        75           75   75       White"
         "Jon Fane                        FIM        80           74   67       Yellow      Early Turn"
         "Stéphane Tourreau               CWT        125         125   125      White"]))

(def pages ["" "" july-28 july-29 july-29 july-31 july-29 july-31 ""])

(deftest printed-positions-and-repeats-are-preserved
  (let [result (dominica/parse-pages pages)
        rows (:candidates result)
        counts (mapv :candidate-count (get-in result [:reconciliation :per-page]))]
    (is (dominica/supported? pages))
    (is (= :needs-review (:status result)))
    (is (= [0 0 5 5 5 5 5 5 0] counts))
    (is (= 30 (count rows)))
    (is (= 30 (get-in result [:reconciliation :parsed-count])))
    (is (= {5 4, 7 4, 8 6} (get-in result [:reconciliation :repeated-pages])))
    (is (= [3 4 5 6 7 8] (->> rows (map #(get-in % [:coordinates :page])) distinct vec)))
    (is (= "2026-07-28" (get-in (first rows) [:parsed :event-date])))
    (is (= "July 28" (get-in (first rows) [:raw :fields :printed-date])))
    (is (= "CWTBF" (get-in (second rows) [:raw :fields :discipline])))
    (is (= "DSQ Pull" (get-in (first rows) [:parsed :notes])))
    (is (= "DNS" (get-in (nth rows 3) [:parsed :status])))
    (is (nil? (get-in (nth rows 3) [:parsed :reached-depth])))
    (is (= 0M (get-in (nth rows 8) [:parsed :final-result])))
    (is (= "DSQ SP NO OK" (get-in (nth rows 8) [:parsed :notes])))
    (is (nil? (get-in (first rows) [:parsed :representation])))
    (is (nil? (get-in (first rows) [:parsed :unit])))
    (is (= :blocked (get-in result [:publication :status])))))

(deftest unexpected-row-remains-unparsed-and-coverage-blocked
  (let [changed (update pages 3 str/replace
                        "Kimmy Williams                FIM       15          15   15       White      OPENER"
                        "Kimmy Williams                FIM       15          15   ???       White      OPENER")
        result (dominica/parse-pages changed)]
    (is (= 30 (get-in result [:reconciliation :candidate-count])))
    (is (= 1 (get-in result [:reconciliation :unparsed-count])))
    (is (= :partial-unsupported-needs-parser (:status result)))
    (is (= :unparsed (:parse-status (nth (:candidates result) 5))))))

(deftest another-layout-is-not-selected
  (is (not (dominica/supported? ["July 29\nOfficial Results\nOther Event"]))))
