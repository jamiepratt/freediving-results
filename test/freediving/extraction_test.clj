(ns freediving.extraction-test
  (:require [clojure.test :refer [deftest is]]
            [freediving.extraction :as extraction]
            [freediving.aida :as aida]
            [freediving.athens :as athens]
            [freediving.archive :as archive]
            [freediving.archive-test :as fixture]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def header "2025 CMAS World Championship Freediving Outdoor\n09/09/2025\nCWT MEN SENIORS\nRANK SURNAME & NAME NAT CATEGORY DEPTH PEN. STATUS NOTES\n")
(deftest cwt-candidates-preserve-source-and-unknowns
  (let [line " 1  ÉXAMPLE Zso a  CMAS1 Men Senior  101  1  100  PEN  NO MARKER"
        result (extraction/parse-pages [(str header line "\n") "OTHER Person  AIN  Men Senior  103  DSQ  PULL\n"])]
    (is (= 2 (get-in result [:reconciliation :candidate-count])))
    (is (= "ÉXAMPLE Zso a" (get-in result [:candidates 0 :parsed :source-name])))
    (is (= "CMAS1" (get-in result [:candidates 0 :parsed :representation])))
    (is (= line (get-in result [:candidates 0 :raw :line])))
    (is (= 5 (get-in result [:candidates 0 :coordinates :line])))
    (is (= 100 (get-in result [:candidates 0 :parsed :final-depth])))
    (is (= 1 (get-in result [:candidates 0 :parsed :penalty])))
    (is (nil? (get-in result [:candidates 1 :parsed :final-depth])))
    (is (= "AIN" (get-in result [:candidates 1 :parsed :representation])))
    (is (= :blocked (get-in result [:publication :status])))
    (is (= :unknown (get-in result [:candidates 0 :fields :unit :status])))))

(deftest unsupported-ocr-and-malformed-lines-stay-unresolved
  (let [bad (extraction/parse-pages [(str header "BROKEN RESULT\n")])
        unsupported (extraction/parse-pages ["Other federation table\nAthlete Zso a\n"])
        empty-page (extraction/parse-pages ["\n"])]
    (is (= 1 (get-in bad [:reconciliation :unparsed-count])))
    (is (= :unsupported-needs-parser (:status unsupported)))
    (is (nil? (get-in unsupported [:reconciliation :candidate-count])))
    (is (= :needs-OCR (:status empty-page)))
    (is (= :blocked (get-in bad [:publication :status])))))

(defn synthetic-pdf
  ([] (synthetic-pdf "BT /F1 12 Tf 40 750 Td (Synthetic result page) Tj ET"))
  ([stream]
   (let [objects ["<< /Type /Catalog /Pages 2 0 R >>"
                  "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"
                  "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>"
                  "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
                  (str "<< /Length " (count stream) " >>\nstream\n" stream "\nendstream")]
         pieces (map-indexed #(str (inc %1) " 0 obj\n" %2 "\nendobj\n") objects)
         prefix "%PDF-1.4\n"
         offsets (butlast (reductions + (count prefix) (map count pieces)))
         body (str prefix (apply str pieces))]
     (str body "xref\n0 6\n0000000000 65535 f \n"
          (apply str (map #(format "%010d 00000 n \n" %) offsets))
          "trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n" (count body) "\n%%EOF\n"))))

(defn registered-pdf []
  (let [dir (fixture/workspace) root (str dir "/archive") source (str dir "/source.pdf")
        text (synthetic-pdf)
        digest (.formatHex (java.util.HexFormat/of)
                           (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes text "UTF-8")))]
    (spit source text :encoding "UTF-8")
    (archive/register! root source (assoc fixture/manifest :sha256 digest))
    [root digest]))

(deftest extraction-is-private-idempotent-and-versioned
  (let [[root digest] (registered-pdf)
        options {:actor "synthetic-test" :config {}}
        first-run (extraction/extract! root digest options)
        second-run (extraction/extract! root digest options)
        result (edn/read-string (slurp (:artifact-path first-run)))
        changed (extraction/extract! root digest (assoc options :config {:revision "second"}))]
    (is (= :created (:run-status first-run)))
    (is (= :skipped (:run-status second-run)))
    (is (= (:artifact-sha256 first-run) (:artifact-sha256 second-run)))
    (is (not= (:job-id first-run) (:job-id changed)))
    (is (= digest (:source-sha256 result)))
    (is (= 1 (count (:acquisitions result))))
    (is (= "synthetic-test" (:actor result)))
    (is (= 1 (get-in result [:reconciliation :page-count])))
    (is (.contains (:raw-text result) "Synthetic result page"))
    (is (= :unsupported-needs-parser (:status result)))
    (doseq [file (file-seq (java.io.File. root))]
      (is (empty? (filter #(re-find #"GROUP|OTHERS" (str %))
                          (java.nio.file.Files/getPosixFilePermissions (.toPath file) (make-array java.nio.file.LinkOption 0))))))))

(deftest interrupted-extraction-recovers-and-corruption-is-rejected
  (let [[root digest] (registered-pdf)
        options {:actor "synthetic-test" :config {}}
        crashed (fixture/java "-e" (str "(require '[freediving.extraction :as e])"
                                        "(e/extract! " (pr-str root) " " (pr-str digest) " " (pr-str options)
                                        " {:on-progress (fn [_] (.halt (Runtime/getRuntime) 23))})"))]
    (is (= 23 (:exit crashed)) (:err crashed))
    (let [receipt (extraction/extract! root digest options)]
      (is (= :created (:run-status receipt)))
      (is (= 1 (count (.listFiles (java.io.File. root "derived-objects")))))
      (is (= :skipped (:run-status (extraction/extract! root digest options))))
      (spit (:artifact-path receipt) "corrupt")
      (is (thrown? clojure.lang.ExceptionInfo (extraction/extract! root digest options))))))

(deftest ambiguous-and-impossible-dates-are-unknown
  (doseq [date ["31/02/2025" "09/09/2025\n10/09/2025"]]
    (let [text (str/replace header "09/09/2025" date)
          result (extraction/parse-pages [(str text "1 OTHER Person AIN Men Senior 100 100\n")])]
      (is (nil? (get-in result [:candidates 0 :parsed :event-date])))
      (is (= :unknown (get-in result [:candidates 0 :fields :event-date :status]))))))

(deftest extraction-ignores-repl-print-limits
  (let [[root digest] (registered-pdf) options {:actor "synthetic-test" :config {}}
        receipt (binding [*print-length* 1 *print-level* 1] (extraction/extract! root digest options))]
    (is (= :skipped (:run-status (extraction/extract! root digest options))))
    (is (= digest (:source-sha256 (edn/read-string (slurp (:artifact-path receipt))))))))

(deftest raw-field-spellings-are-retained
  (let [result (extraction/parse-pages [(str header "01 SAMPLE Person AIN Men Senior 0101 01 0100 PEN NO MARKER\n")])]
    (is (= "0100" (get-in result [:candidates 0 :raw :fields :final-depth])))
    (is (= "01" (get-in result [:candidates 0 :raw :fields :penalty])))
    (is (= 100 (get-in result [:candidates 0 :parsed :final-depth])))))

(deftest cli-rejects-extra-options-forms-and-supports-repeat
  (let [[root digest] (registered-pdf)
        file (str (.getParent (java.io.File. root)) "/options.edn")]
    (spit file "{:actor \"synthetic-cli\" :config {}} :unexpected")
    (is (= 1 (:exit (fixture/java "-m" "freediving.extraction" root digest file))))
    (spit file "{:actor \"synthetic-cli\" :config {}}")
    (let [first-run (fixture/java "-m" "freediving.extraction" root digest file)
          second-run (fixture/java "-m" "freediving.extraction" root digest file)]
      (is (= 0 (:exit first-run)) (:err first-run))
      (is (= 0 (:exit second-run)) (:err second-run))
      (is (= :created (:run-status (edn/read-string (:out first-run)))))
      (is (= :skipped (:run-status (edn/read-string (:out second-run))))))))

(deftest concurrent-extraction-produces-one-result
  (let [[root digest] (registered-pdf)
        file (str (.getParent (java.io.File. root)) "/options.edn")]
    (spit file "{:actor \"synthetic-cli\" :config {}}")
    (let [workers (doall (repeatedly 3 #(future (fixture/java "-m" "freediving.extraction" root digest file))))
          results (mapv deref workers)]
      (is (= [0 0 0] (mapv :exit results)) (pr-str results))
      (is (= 1 (count (set (map #(:artifact-sha256 (edn/read-string (:out %))) results)))))
      (is (= 1 (count (.listFiles (java.io.File. root "derived-objects"))))))))

(def aida-header "2/7/25, 13:13 AIDA | 34th AIDA FREEDIVING WORLD CHAMPIONSHIP WAKAYAMA 2025\n\nDYN\n\nFemale\n\nMedals # Name Nationality Result Announced Points Penalties\n\n")
(deftest aida-wrapped-source-rows-retain-evidence
  (let [r (extraction/parse-pages [(str aida-header "     Éva\n1.   Example-   AIN   150 m   1m   75.5   0\n     Test\n\n1. Other Name HUN 120 m 100 m 0 2\n\nhttps://www.aidainternational.org/EventRanking/4349#rankings 1/1\n")])
        c (first (:candidates r))]
    (is (= "aida-wakayama-ranking/1" (:parser-version r)))
    (is (= 2 (get-in r [:reconciliation :parsed-count])))
    (is (= "Éva Example- Test" (get-in c [:parsed :source-name])))
    (is (= ["Éva" "Example-" "Test"] (get-in c [:raw :fields :source-name-fragments])))
    (is (= "AIN" (get-in c [:parsed :representation])))
    (is (= "Female" (get-in c [:parsed :category])))
    (is (= "DYN" (get-in c [:parsed :discipline])))
    (is (= "m" (get-in c [:parsed :unit])))
    (is (= 75.5M (get-in c [:parsed :points])))
    (is (nil? (get-in c [:parsed :event-date])))
    (is (nil? (get-in r [:candidates 1 :parsed :status])))
    (is (= :blocked (get-in r [:publication :status])))))

(deftest aida-extraction-selects-version-and-preserves-cmas-compatibility
  (let [pdf (synthetic-pdf (str "BT /F1 8 Tf 20 750 Td "
                                "(AIDA | 34th AIDA FREEDIVING WORLD CHAMPIONSHIP WAKAYAMA 2025) Tj 0 -20 Td "
                                "(Medals # Name Nationality Result Announced Points Penalties) Tj 0 -20 Td "
                                "(DYN) Tj 0 -20 Td (Female) Tj 0 -20 Td "
                                "(1. Test Name AIN 100 m 1m 50 0) Tj ET"))
        [root digest] (with-redefs [synthetic-pdf (constantly pdf)] (registered-pdf))
        opts {:actor "test" :config {}}
        previous (with-redefs [aida/supported? (constantly false)] (extraction/extract! root digest opts))
        receipt (extraction/extract! root digest opts)
        r (edn/read-string (slurp (:artifact-path receipt)))]
    (is (not= (:job-id previous) (:job-id receipt)))
    (is (= :created (:run-status receipt)))
    (is (= "aida-wakayama-ranking/1" (:parser-version r)))
    (is (= 2 (:schema-version r)))
    (is (= 1 (get-in r [:reconciliation :parsed-count])))
    (is (= :skipped (:run-status (extraction/extract! root digest opts))))
    (is (= "cmas-cwt-men/1" (:parser-version (extraction/parse-pages [header]))))))

(deftest aida-adjacent-status-and-malformed-rows-remain-distinct
  (let [r (extraction/parse-pages [(str aida-header "1. Test One AIN 100 m 1m 50 0\n1. Test Two HUN DNS\n\nBROKEN ENTRY\n\nhttps://www.aidainternational.org/EventRanking/4349#rankings 1/1\n")
                                   "AIDA | 34th AIDA FREEDIVING WORLD CHAMPIONSHIP WAKAYAMA 2025\n\nEVENT RANKING\n\n1. Other Name TPE 200 m 1m 100 0\n"])]
    (is (= 4 (get-in r [:reconciliation :candidate-count])))
    (is (= 3 (get-in r [:reconciliation :parsed-count])))
    (is (= "DNS" (get-in r [:candidates 1 :parsed :status])))
    (is (= :unknown (get-in r [:candidates 1 :fields :performance :status])))
    (is (= :unparsed (get-in r [:candidates 2 :parse-status])))
    (is (nil? (get-in r [:candidates 3 :parsed :category])))
    (is (nil? (get-in r [:candidates 3 :parsed :discipline])))
    (is (= (get-in r [:reconciliation :nonblank-line-count])
           (+ (count (get-in r [:reconciliation :noncandidate-lines]))
              (reduce + (map #(count (:source-lines %)) (:candidates r))))))))

(def athens-header "2025 CMAS WORLD CHAMPIONSHIP FREEDIVING INDOOR, GREECE\nMAY, 20, 2025\nDNF FINAL RESULTS\nSENIORS – WOMEN\n# Name & surname Country Realized Final Notes\nDistance (m) Distance (m)\n")
(deftest athens-dnf-exact-source-and-explicit-fields
  (let [line "1 Zso a EXAMPLE CMAS1 104,5 100,0 GOLD MEDAL"
        r (extraction/parse-pages [(str athens-header line "\nOther EXAMPLE AIN 100 0 DQ SP\nThird EXAMPLE GBR DNS\nfi\n")])]
    (is (= "cmas-athens-dnf/1" (:parser-version r)))
    (is (= 3 (get-in r [:reconciliation :parsed-count])))
    (is (= 1 (get-in r [:reconciliation :unparsed-count])))
    (is (= line (get-in r [:candidates 0 :raw :line])))
    (is (= "Zso a EXAMPLE" (get-in r [:candidates 0 :parsed :source-name])))
    (is (= "CMAS1" (get-in r [:candidates 0 :parsed :representation])))
    (is (= 104.5M (get-in r [:candidates 0 :parsed :realized-distance])))
    (is (= "104,5" (get-in r [:candidates 0 :raw :fields :realized-distance])))
    (is (= "2025-05-20" (get-in r [:candidates 0 :parsed :event-date])))
    (is (= "m" (get-in r [:candidates 0 :parsed :unit])))
    (is (nil? (get-in r [:candidates 0 :parsed :penalty])))
    (is (nil? (get-in r [:candidates 0 :parsed :status])))
    (is (= "DQ" (get-in r [:candidates 1 :parsed :status])))
    (is (= "DNS" (get-in r [:candidates 2 :parsed :status])))
    (is (= :blocked (get-in r [:publication :status])))))

(deftest athens-continuations-reset-and-malformed-rows-remain-visible
  (let [columns "# Name & surname Country\nRealized Final Notes\nDistance (m) Distance (m)\n"
        r (extraction/parse-pages [(str athens-header "1 Sample NAME GBR 100 100\n")
                                   (str columns "Other NAME GBR DNS\n")
                                   (str/replace athens-header "DNF" "DYNBF")
                                   (str columns "Should Not Inherit GBR 100 100\n")
                                   (str athens-header "1 Adjacent NAME GBR 100 100 2 Next NAME GBR 90 90\nWrapped\n2 Fragment NAME GBR 90 90\n")])]
    (is (= :partial-unsupported-needs-parser (:status r)))
    (is (= [3 4] (get-in r [:reconciliation :unsupported-pages])))
    (is (= "SENIORS – WOMEN" (get-in r [:candidates 1 :parsed :category])))
    (is (= 1 (get-in r [:candidates 1 :metadata-evidence 0 :page])))
    (is (nil? (get-in r [:reconciliation :per-page 2 :candidate-count])))
    (is (= :unparsed (get-in r [:candidates 2 :parse-status])))
    (is (= "Wrapped" (get-in r [:candidates 3 :raw :line])))
    (is (= :unparsed (get-in r [:candidates 3 :parse-status])))
    (is (= (get-in r [:reconciliation :nonblank-line-count])
           (+ (count (:candidates r)) (count (get-in r [:reconciliation :noncandidate-lines])))))))

(deftest athens-mixed-headers-cannot-authorize-context
  (doseq [extra ["DYN FINAL RESULTS\n" "MAY, broken, 2025\n" "SENIORS - UNKNOWN\n"]]
    (let [r (extraction/parse-pages [(str athens-header extra "1 Sample NAME GBR 100 100\n")])]
      (is (= [1] (get-in r [:reconciliation :unsupported-pages])))
      (is (empty? (:candidates r))))))

(deftest athens-current-unit-evidence-and-review-reasons
  (let [r (extraction/parse-pages [(str athens-header "1 Sample NAME GBR 100 100\n")
                                   "# Name & surname Country\nRealized Final Notes\nDistance (m) Distance (m)\nOther NAME GBR DNS\nfi\n"])]
    (is (= 3 (:schema-version r)))
    (is (some #(and (= 2 (:page %)) (= 3 (:line %))) (get-in r [:candidates 1 :metadata-evidence])))
    (is (some #{:unparsed-source-line} (get-in r [:candidates 2 :unresolved-reasons])))
    (is (some #{:source-status-not-explicit} (get-in r [:candidates 0 :unresolved-reasons])))))

(deftest athens-world-record-notes-are-not-category-headers
  (let [r (extraction/parse-pages [(str athens-header "1 Sample NAME GBR 100 100 WORLD RECORD MASTERS M1\n")])]
    (is (= 1 (get-in r [:reconciliation :parsed-count])))
    (is (= "WORLD RECORD MASTERS M1" (get-in r [:candidates 0 :parsed :notes])))))

(deftest athens-extraction-versions-old-artifacts-and-recovers-through-cli
  (let [pdf (synthetic-pdf (str "BT /F1 8 Tf 20 750 Td "
                                "(2025 CMAS WORLD CHAMPIONSHIP FREEDIVING INDOOR, GREECE) Tj 0 -20 Td "
                                "(MAY, 20, 2025) Tj 0 -20 Td (DNF FINAL RESULTS) Tj 0 -20 Td "
                                "(SENIORS \\261 WOMEN) Tj 0 -20 Td "
                                "(# Name & surname Country Realized Final Notes) Tj 0 -20 Td "
                                "(Distance \\(m\\) Distance \\(m\\)) Tj 0 -20 Td "
                                "(1 Sample NAME GBR 100 100) Tj ET"))
        [root digest] (with-redefs [synthetic-pdf (constantly pdf)] (registered-pdf))
        opts {:actor "test-athens" :config {}}
        legacy (with-redefs [athens/supported? (constantly false)] (extraction/extract! root digest opts))
        legacy-bytes (slurp (:artifact-path legacy))
        crash (fixture/java "-e" (str "(require '[freediving.extraction :as e])"
                                      "(e/extract! " (pr-str root) " " (pr-str digest) " " (pr-str opts)
                                      " {:on-progress (fn [_] (.halt (Runtime/getRuntime) 23))})"))
        file (str (.getParent (java.io.File. root)) "/athens-options.edn")]
    (is (= 23 (:exit crash)))
    (spit file (pr-str opts))
    (let [cli (fixture/java "-m" "freediving.extraction" root digest file)
          receipt (edn/read-string (:out cli))
          r (edn/read-string (slurp (:artifact-path receipt)))]
      (is (= 0 (:exit cli)) (:err cli))
      (is (= :created (:run-status receipt)))
      (is (not= (:job-id legacy) (:job-id receipt)))
      (is (= legacy-bytes (slurp (:artifact-path legacy))))
      (is (= "cmas-athens-dnf/1" (:parser-version r)))
      (is (= 3 (:schema-version r)))
      (is (= 1 (get-in r [:reconciliation :parsed-count])))
      (is (= :skipped (:run-status (extraction/extract! root digest opts))))
      (is (= (:job-id legacy) (:job-id (with-redefs [athens/supported? (constantly false)]
                                         (extraction/extract! root digest opts)))))
      (is (not= (:job-id receipt) (:job-id (extraction/extract! root digest (assoc opts :config {:revision 2}))))))))

(deftest athens-blank-pages-reset-context-and-preserve-repeated-unicode-rows
  (let [row "1 Éva ÖTHER CMAS1 100 100\n"
        r (extraction/parse-pages [(str athens-header row row) "\n"
                                   "# Name & surname Country\nDistance (m) Distance (m)\n1 Fragment NAME GBR 100 100\n"
                                   (str (str/replace athens-header "SENIORS" "JUNIORS") row)])]
    (is (= :needs-OCR (get-in r [:pages 1 :status])))
    (is (= [2] (get-in r [:reconciliation :needs-ocr-pages])))
    (is (= [2 3] (get-in r [:reconciliation :unsupported-pages])))
    (is (= 3 (get-in r [:reconciliation :parsed-count])))
    (is (= ["Éva ÖTHER" "Éva ÖTHER" "Éva ÖTHER"] (mapv #(get-in % [:parsed :source-name]) (:candidates r))))
    (is (= ["SENIORS – WOMEN" "SENIORS – WOMEN" "JUNIORS – WOMEN"]
           (mapv #(get-in % [:parsed :category]) (:candidates r))))))
