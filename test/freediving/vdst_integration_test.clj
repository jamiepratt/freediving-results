(ns freediving.vdst-integration-test
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [freediving.archive :as archive]
            [freediving.extraction :as extraction]
            [freediving.observations :as observations]
            [freediving.vdst-neckar-2025 :as neckar]
            [freediving.vdst-rhein-main-2025 :as rhein]
            [freediving.vdst-chemnitz-2025 :as chemnitz-2025]
            [freediving.vdst-chemnitz-2026 :as chemnitz-2026]))

(deftest chemnitz-sources-are-vdst-claims
  (doseq [[sha parser] [[chemnitz-2025/source-sha256 chemnitz-2025/parser-version]
                        [chemnitz-2026/source-sha256 chemnitz-2026/parser-version]]]
    (is (extraction/vdst-claim? {:source-sha256 sha}))
    (is (extraction/vdst-artifact? {:schema-version 3
                                    :source-sha256 sha :parser-version parser}))))

(deftest chemnitz-originals-route-and-retain-printed-positions
  (doseq [[path sha parser printed]
          [["/tmp/vdst-chemnitz-20260927/capc2025.pdf"
            chemnitz-2025/source-sha256 chemnitz-2025/parser-version 91]
           ["/tmp/vdst-chemnitz-20260927/capc2026.pdf"
            chemnitz-2026/source-sha256 chemnitz-2026/parser-version 121]]]
    (when (.exists (java.io.File. path))
      (let [raw (:out (shell/sh "pdftotext" "-layout" "-enc" "UTF-8" path "-"))
            pages (vec (remove str/blank? (str/split raw #"\f")))
            artifact (extraction/parse-pages sha pages)]
        (is (= parser (:parser-version artifact)))
        (is (= printed (count (:candidates artifact))))
        (is (= :blocked (get-in artifact [:publication :status])))))))

(def neckar-pages
  (mapv (fn [index]
          (str "1. Neckar Apnoe Cup und 2. Baden-Württembergische Meisterschaft - Freibad Edition\n"
               (when (zero? index) "Abschnitt 1 - Samstag 06.09.2025\n")
               "Wettkampf 1 - DNF (Streckentauchen ohne Flossen)\n"
               "Neckar Apnoe Cup: Senior Damen\n"
               "1.           Test Diver                     2000    Test Club                                100,0m\n"
               "Landesmeisterschaft Baden-Württemberg: Damen\n"
               "Esslingen am Neckar, Vereinsbad des SSV Esslingen 07.09.2025 - Protokoll - Seite " (+ 10 index)))
        (range 4)))
(defn rhein-row [rank index result]
  (str (when rank (str rank ". "))
       (format "%-22s  2000    Test Club" (str "Diver" index " Smith"))
       (when result (str "       " result))))
(defn rhein-page [page discipline ranked statuses]
  (str/join "\n"
            (concat ["22. Rhein-Main Cup 2025"
                     (str "Wettkampf " (- page 3) " - " discipline)
                     "RMC: Senior Damen"]
                    (map-indexed (fn [index _]
                                   (rhein-row (inc index) (str page "-" (inc index))
                                              (if (= page 4) "02:00,00" "100,0m")))
                                 (range ranked))
                    (when (pos? statuses) ["nicht am Start"])
                    (map (fn [index] (rhein-row nil (str page "-s" index) nil))
                         (range statuses))
                    (when (= page 4)
                      (concat ["Hessische Statik Meisterschaft: Damen"]
                              (map (fn [index]
                                     (rhein-row (inc index) (str page "-" (inc index)) "02:00,00"))
                                   (range 5))))
                    (when (= page 7) ["Protokollende: 15:55 Uhr"]))))
(def rhein-pages
  ["22. Rhein-Main Cup 2025\nerstellt am: 15.09.2025"
   "22. Rhein-Main Cup 2025"
   "22. Rhein-Main Cup 2025\nAbschnitt 1 - Samstag 27.09.2025"
   (rhein-page 4 "Statik" 23 5)
   (rhein-page 5 "2 x 25m" 11 0)
   (rhein-page 6 "DBF" 13 8)
   (rhein-page 7 "DYN" 3 5)])
(def sources [[neckar/source-sha256 neckar/parser-version neckar-pages]
              [rhein/source-sha256 rhein/parser-version rhein-pages]])

(deftest only-exact-vdst-sources-route
  (doseq [[sha version pages] sources]
    (let [result (extraction/parse-pages sha pages)]
      (is (= version (:parser-version result)))
      (is (= 3 (:schema-version result)))
      (is (= sha (:source-sha256 result)))
      (is (= :blocked (get-in result [:publication :status]))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"different source"
                          (extraction/parse-pages (apply str (repeat 64 "0")) pages)))))

(deftest vdst-claims-reject-forged-source-or-version
  (doseq [artifact [{:source-sha256 (apply str (repeat 64 "0"))
                     :parser-version neckar/parser-version :schema-version 3}
                    {:source-sha256 neckar/source-sha256
                     :parser-version "generic/1" :schema-version 3}]]
    (is (extraction/vdst-claim? artifact))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source replay"
                          (extraction/validate-vdst-artifact! "archive" artifact)))))

(deftest vdst-extraction-and-source-replay
  (doseq [[sha version pages] sources]
    (let [raw (str (str/join "\f" pages) "\f")]
      (with-redefs [archive/inspect (fn [& _] {:artifact-path "archived.pdf"
                                               :acquisitions [{:url "https://example.test"}]})
                    archive/extraction-evidence (constantly [])
                    archive/derive! (fn [_ _ build & _] (build))
                    shell/sh (fn [& args]
                               {:exit 0 :out (case (first args)
                                               "pdfinfo" (str "Pages: " (count pages) "\n")
                                               "pdftotext" raw)
                                :err (if (some #{"-v"} args) "test-version" "")})]
        (let [artifact (extraction/extract! "archive" sha {:actor "test" :config {}})]
          (is (= version (:parser-version artifact)))
          (is (= 3 (:schema-version artifact)))
          (is (= artifact (extraction/validate-vdst-artifact! "archive" artifact)))
          (is (= artifact ((deref #'freediving.observations/validate-pages!) artifact)))
          (when (seq (:candidates artifact))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source-lines evidence"
                                  ((deref #'freediving.observations/validate-pages!)
                                   (assoc-in artifact [:candidates 0 :source-lines 0 :text] "forged"))))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"raw text"
                                  ((deref #'freediving.observations/validate-pages!)
                                   (assoc-in artifact [:candidates 0 :raw :line] "forged")))))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source replay"
                                (extraction/validate-vdst-artifact! "archive"
                                                                    (assoc artifact :parser-version "generic/1"))))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source replay"
                                (extraction/validate-vdst-artifact! "archive"
                                                                    (assoc-in artifact [:reconciliation :candidate-count] 999)))))))))

(defn -main [& _]
  (let [result (run-tests 'freediving.vdst-integration-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
