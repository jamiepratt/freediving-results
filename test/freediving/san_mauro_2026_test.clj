(ns freediving.san-mauro-2026-test
  (:require [clojure.string :as str]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is run-tests]]
            [freediving.archive :as archive]
            [freediving.extraction :as extraction]
            [freediving.san-mauro-2026 :as san-mauro]))

(def sources
  [{:sha "7641234fbddbeba295e08fc7fbea974a56915bdac12ad38bf5dade28e862e2db"
    :fixture "male.txt" :counts {"bipinne" 52 "monopinna" 8 "rana" 9} :gender "M"}
   {:sha "c3af0173bb25424c7c142cdf4e8660d5d020eefe50a237c6259b609f4bcef7fd"
    :fixture "female.txt" :counts {"bipinne" 21 "monopinna" 5 "rana" 2} :gender "F"}])

(defn fixture-pages [filename]
  (let [raw (slurp (str "test/resources/fixtures/san-mauro-2026/" filename))
        parts (str/split raw #"\f" -1)]
    (if (= "" (last parts)) (pop (vec parts)) (vec parts))))

(deftest san-mauro-sheets-reconcile-all-printed-results
  (doseq [{:keys [sha fixture counts gender]} sources]
    (let [result (extraction/parse-pages sha (fixture-pages fixture))
          candidates (:candidates result)]
      (is (= "san-mauro-dynamic-2026/1" (:parser-version result)))
      (is (= 3 (:schema-version result)))
      (is (= counts (frequencies (map #(get-in % [:parsed :discipline]) candidates))))
      (is (= (reduce + (vals counts)) (get-in result [:reconciliation :candidate-count])))
      (is (= (count candidates) (get-in result [:reconciliation :parsed-count])))
      (is (zero? (get-in result [:reconciliation :unparsed-count])))
      (is (every? #(= :parsed (:parse-status %)) candidates))
      (is (every? #(= gender (get-in % [:parsed :gender])) candidates))
      (is (every? #(= "2026-03-01" (get-in % [:parsed :event-date])) candidates))
      (is (every? #(= "m" (get-in % [:parsed :unit])) candidates))
      (is (every? #(and (= 1 (get-in % [:coordinates :page]))
                        (= (:text (first (:source-lines %))) (get-in % [:raw :line]))) candidates))
      (is (= :blocked (get-in result [:publication :status]))))))

(deftest san-mauro-preserves-awkward-source-values
  (let [male (:candidates (extraction/parse-pages (:sha (first sources)) (fixture-pages "male.txt")))
        female (:candidates (extraction/parse-pages (:sha (second sources)) (fixture-pages "female.txt")))
        oliveri (first (filter #(= "Oliveri Del Castillo Umberto" (get-in % [:parsed :source-name])) male))
        helzel (first (filter #(= "Helzel Marta" (get-in % [:parsed :source-name])) female))
        bellotti (first (filter #(= "Bellotti Serena" (get-in % [:parsed :source-name])) female))]
    (is (= ["Subacquei Partenopei" "107" "0" "5%" "132,145"]
           (mapv #(get-in oliveri [:raw :fields %]) [:club :distance :bonus :uncertain-exit :points])))
    (is (= ["25" "21" "0%" "0"]
           (mapv #(get-in helzel [:raw :fields %]) [:distance :bonus :uncertain-exit :points])))
    (is (= ["143" "5%" "163,02"]
           (mapv #(get-in bellotti [:raw :fields %]) [:distance :uncertain-exit :points])))))

(deftest san-mauro-source-binding-and-import-replay
  (let [sha (:sha (first sources))
        pages (fixture-pages "male.txt")
        raw (str (str/join "\f" pages) "\f")
        artifact (merge (san-mauro/parse-pages sha pages)
                        {:source-sha256 sha :raw-text raw
                         :tool {:name "pdftotext" :version "test-version"
                                :arguments ["-layout" "-enc" "UTF-8"]}})
        fake-sh (fn [& args] {:exit 0 :out (if (= "-v" (second args)) "" raw)
                              :err (if (= "-v" (second args)) "test-version" "")})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"different source"
                          (extraction/parse-pages (apply str (repeat 64 "0")) pages)))
    (with-redefs [archive/inspect (fn [& _] {:artifact-path "archived.pdf"})
                  shell/sh fake-sh]
      (is (= artifact (extraction/validate-san-mauro-artifact! "archive" artifact)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source replay"
                            (extraction/validate-san-mauro-artifact!
                             "archive" (assoc-in artifact [:candidates 0 :parsed :points] "999")))))))

(defn -main [& _]
  (let [result (run-tests 'freediving.san-mauro-2026-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
