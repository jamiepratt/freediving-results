(ns freediving.extraction-test
  (:require [clojure.test :refer [deftest is]]
            [freediving.extraction :as extraction]
            [freediving.indoor-2026 :as indoor-2026]
            [freediving.aida :as aida]
            [freediving.athens :as athens]
            [freediving.archive :as archive]
            [freediving.archive-test :as fixture]
            [freediving.belgrade-2026 :as belgrade-2026]
            [freediving.deep-dominica-2026 :as deep-dominica-2026]
            [freediving.deep-dominica-2026-test :as dominica-2026-fixture]
            [clojure.java.shell :as shell]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def header "2025 CMAS World Championship Freediving Outdoor\n09/09/2025\nCWT MEN SENIORS\nRANK SURNAME & NAME NAT CATEGORY DEPTH PEN. STATUS NOTES\n")

(deftest belgrade-2026-results-route-through-public-extraction
  (let [pages [(str "2026 Belgrade Freediving Open\n"
                    "Serbia, Futog, 25.04.2026\n"
                    " DNF          Athlete               Pol              Klub         Ostvareno\n"
                    "  1        Tijana Nikolić     Žensko / Female   RK Sebastijan       106\n")]
        result (extraction/parse-pages pages)]
    (is (= belgrade-2026/parser-version (:parser-version result)))
    (is (= 1 (get-in result [:reconciliation :candidate-count])))
    (is (= :blocked (get-in result [:publication :status])))))

(deftest deep-dominica-2026-results-route-through-public-extraction
  (let [result (extraction/parse-pages dominica-2026-fixture/pages)]
    (is (= "cmas-deep-dominica-2026/1" (:parser-version result)))
    (is (= 30 (get-in result [:reconciliation :candidate-count])))
    (is (= :blocked (get-in result [:publication :status])))))

(deftest source-bound-2026-replay-rejects-edited-results
  (doseq [[source-hash parse-pages pages artifact? validate!]
          [[belgrade-2026/source-sha256 belgrade-2026/parse-pages
            [(str "2026 Belgrade Freediving Open\nSerbia, Futog, 25.04.2026\n"
                  " DNF          Athlete               Pol              Klub         Ostvareno\n"
                  "  1        Tijana Nikolić     Žensko / Female   RK Sebastijan       106\n")]
            extraction/belgrade-2026-artifact? extraction/validate-belgrade-2026-artifact!]
           [deep-dominica-2026/source-sha256 deep-dominica-2026/parse-pages dominica-2026-fixture/pages
            extraction/deep-dominica-2026-artifact? extraction/validate-deep-dominica-2026-artifact!]]]
    (let [raw (str (str/join "\f" pages) "\f")
          artifact (merge (parse-pages pages)
                          {:source-sha256 source-hash :raw-text raw
                           :tool {:name "pdftotext" :version "test-version"
                                  :arguments ["-layout" "-enc" "UTF-8"]}})]
      (is (artifact? artifact))
      (with-redefs [archive/inspect (fn [& _] {:artifact-path "archived.pdf"})
                    shell/sh (fn [& args] {:exit 0 :out (if (= "-v" (second args)) "" raw)
                                           :err (if (= "-v" (second args)) "test-version" "")})]
        (is (= artifact (validate! "archive" artifact)))
        (is (thrown? clojure.lang.ExceptionInfo
                     (validate! "archive" (assoc-in artifact [:candidates 0 :parsed :source-name] "edited"))))))))
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
    (is (= "cmas-athens-pool/6" (:parser-version r)))
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
                                   (str/replace athens-header "DNF" "STA")
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
      (is (= "cmas-athens-pool/7" (:parser-version r)))
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

(def dynbf-header (-> athens-header (str/replace "DNF" "DYNBF") (str/replace "20, 2025" "21, 2025")))
(deftest athens-dynbf-distance-and-notes
  (let [notes ["PANAMERICAN RECORD" "DOLPHIN KICK" "WALL AT START" "DQ SP CHIN" "DQ SP NO OK"
               "GOLD MEDAL, WORLD RECORD SENIORS" "BRONZE MEDAL, WORLD RECORD MASTERS M1"]
        r (extraction/parse-pages [(str dynbf-header (str/join "\n" (map #(str "1 Synthetic NAME CMAS1 141,0 138,0 " %) notes)))])]
    (is (= "cmas-athens-pool/6" (:parser-version r)))
    (is (= 7 (get-in r [:reconciliation :parsed-count])))
    (is (= notes (mapv #(get-in % [:parsed :notes]) (:candidates r))))
    (is (every? #(= "DYNBF" (get-in % [:parsed :discipline])) (:candidates r)))
    (is (every? #(= "2025-05-21" (get-in % [:parsed :event-date])) (:candidates r)))
    (is (every? #(nil? (get-in % [:parsed :penalty])) (:candidates r)))))

(deftest athens-final-only-dns-requires-column-evidence
  (let [header (str/replace dynbf-header "# Name & surname Country Realized Final Notes" "# Name & surname Country     Realized     Final        Notes")
        r (extraction/parse-pages [(str header "  Synthetic NAME GBR                         0,0 DNS\n  Other NAME GBR 0 DNS\n")])]
    (is (= :parsed (get-in r [:candidates 0 :parse-status])))
    (is (= "0,0" (get-in r [:candidates 0 :raw :fields :final-distance])))
    (is (= 0M (get-in r [:candidates 0 :parsed :final-distance])))
    (is (nil? (get-in r [:candidates 0 :parsed :realized-distance])))
    (is (= "DNS" (get-in r [:candidates 0 :parsed :status])))
    (is (= :unparsed (get-in r [:candidates 1 :parse-status])))))

(deftest athens-wrapped-name-and-footer-retain-every-source-line
  (let [header (str/replace dynbf-header "# Name & surname Country Realized Final Notes" "# Name & surname Country     Realized     Final        Notes")
        first-line "3    ̇ Synthetic SURNAME"
        second-line "    Given NAME         TUR         176,0        176,0 BRONZE MEDAL"
        r (extraction/parse-pages [(str header first-line "\n" second-line "\n     7\n\n\n                                   1\n")])
        candidate (first (:candidates r))]
    (is (= 2 (get-in r [:reconciliation :candidate-count])))
    (is (= :unparsed (:parse-status candidate)))
    (is (= [first-line second-line] (mapv :text (:source-lines candidate))))
    (is (= "TUR" (get-in candidate [:raw :fields :representation])))
    (is (some #{:ambiguous-wrapped-name} (:unresolved-reasons candidate)))
    (is (= "     7" (get-in r [:candidates 1 :raw :line])))
    (is (= :page-footer (:classification (last (get-in r [:reconciliation :noncandidate-lines])))))
    (is (= (get-in r [:reconciliation :nonblank-line-count])
           (+ (reduce + (map #(count (or (:source-lines %) [%])) (:candidates r)))
              (count (get-in r [:reconciliation :noncandidate-lines])))))))

(deftest athens-dns-cannot-borrow-previous-page-column-positions
  (let [header (str/replace dynbf-header "# Name & surname Country Realized Final Notes" "# Name & surname Country     Realized     Final        Notes")
        r (extraction/parse-pages [header "# Name & surname Country\nDistance (m) Distance (m)\n  Synthetic NAME GBR                         0,0 DNS\n\n\n                                   2\n"])]
    (is (= [:unparsed :unparsed] (mapv :parse-status (:candidates r))))))

(def dyn-header (str/replace athens-header "DNF" "DYN"))
(deftest athens-dyn-explicit-distance-and-uncertain-cell-groups
  (let [r (extraction/parse-pages [(str dyn-header
                                        "    Alpha NAME GBR 200 200 GOLD MEDAL,\n"
                                        "1\n"
                                        "    Beta NAME POL 200 200 WORLD RECORD SENIORS\n"
                                        "3 Gamma NAME ITA 180 180 BRONZE MEDAL\n")])
        [a b c] (:candidates r)]
    (is (= "cmas-athens-pool/6" (:parser-version r)))
    (is (= 3 (get-in r [:reconciliation :candidate-count])))
    (is (= [:unparsed :unparsed :parsed] (mapv :parse-status [a b c])))
    (is (= (:group-evidence a) (:group-evidence b)))
    (is (= 3 (count (get-in a [:group-evidence :source-lines]))))
    (is (= "Alpha NAME" (get-in a [:raw :fields :source-name])))
    (is (= "Beta NAME" (get-in b [:raw :fields :source-name])))
    (is (nil? (:parsed a)))
    (is (= "DYN" (get-in c [:parsed :discipline])))
    (is (= :blocked (get-in r [:publication :status])))))

(deftest athens-dyn-multiline-notes-and-explicit-dq-cells
  (let [header (str/replace dyn-header "# Name & surname Country Realized Final Notes"
                            "# Name & surname Country     Realized     Final        Notes")
        before (str (apply str (repeat 60 " ")) "GOLD MEDAL,")
        body "1 Synthetic NAME GBR          210,5       210,5"
        after (str (apply str (repeat 60 " ")) "WORLD RECORD SENIORS")
        r (extraction/parse-pages [(str header before "\n" body "\n" after "\n"
                                        "  Other NAME GBR              148,0                    DQ SP\n"
                                        "  Third NAME GBR 140 0 DQ SP OK DIR\n"
                                        "  Fourth NAME GBR 130 0 DQ EQUIPMENT\n")])
        c (first (:candidates r))]
    (is (= 4 (get-in r [:reconciliation :parsed-count])))
    (is (= "GOLD MEDAL, WORLD RECORD SENIORS" (get-in c [:parsed :notes])))
    (is (= [before body after] (mapv :text (:source-lines c))))
    (is (= :join-note-lines (get-in c [:repairs 0 :operation])))
    (is (nil? (get-in r [:candidates 1 :parsed :final-distance])))
    (is (= 148.0M (get-in r [:candidates 1 :parsed :realized-distance])))
    (is (every? #(nil? (get-in % [:parsed :penalty])) (:candidates r)))
    (is (= ["DQ" "DQ" "DQ"] (mapv #(get-in % [:parsed :status]) (rest (:candidates r)))))))

(deftest athens-dyn-note-joining-requires-current-column-evidence
  (doseq [header [dyn-header (str/replace dyn-header "Realized Final Notes" "")]]
    (let [r (extraction/parse-pages [(str header "GOLD MEDAL,\n1 Synthetic NAME GBR 210 210\nWORLD RECORD SENIORS\n")])]
      (is (= [:unparsed :parsed :unparsed] (mapv :parse-status (:candidates r))))
      (is (every? #(nil? (:repairs %)) (:candidates r)))))
  (let [r (extraction/parse-pages [(str athens-header "  Other NAME GBR 140 0 DQ SP OK DIR\n")])]
    (is (= :unparsed (get-in r [:candidates 0 :parse-status])))))

(deftest athens-dyn-multiline-notes-never-overwrite-row-notes
  (let [padding (apply str (repeat 70 " "))
        r (extraction/parse-pages [(str dyn-header padding "GOLD MEDAL,\n"
                                        "1 Synthetic NAME GBR 210 210 SILVER MEDAL\n"
                                        padding "WORLD RECORD SENIORS\n")])]
    (is (= [:unparsed :parsed :unparsed] (mapv :parse-status (:candidates r))))
    (is (= "SILVER MEDAL" (get-in r [:candidates 1 :parsed :notes])))
    (is (every? #(nil? (:repairs %)) (:candidates r)))))

(def sta-header
  (str "2025 CMAS WORLD CHAMPIONSHIP FREEDIVING INDOOR, GREECE\n"
       "MAY, 23, 2025\nSTA FINAL RESULTS\nSENIORS – WOMEN\n"
       "# Name & surname Country Final Result Notes\n"))

(deftest athens-sta-preserves-time-without-inventing-units
  (let [r (extraction/parse-pages [(str sta-header "1 Éva NAME CMAS1 04:05 GOLD MEDAL\n"
                                        "  Other NAME GBR 05:32 DQ\n")])
        [a b] (:candidates r)]
    (is (= "cmas-athens-pool/6" (:parser-version r)))
    (is (= 2 (get-in r [:reconciliation :parsed-count])))
    (is (= "04:05" (get-in a [:raw :fields :final-time])))
    (is (= {:components [4 5] :fraction nil :fraction-digits 0 :notation :colon-separated}
           (get-in a [:parsed :final-time])))
    (is (nil? (get-in a [:parsed :final-duration])))
    (is (nil? (get-in a [:parsed :unit])))
    (is (some #{:time-unit-not-explicit} (:unresolved-reasons a)))
    (is (= "05:32" (get-in b [:raw :fields :final-time])))
    (is (= "DQ" (get-in b [:parsed :status])))
    (is (nil? (get-in b [:parsed :realized-time])))
    (is (nil? (get-in b [:parsed :penalty])))
    (is (= :blocked (get-in r [:publication :status])))))

(deftest athens-sta-damaged-name-and-multiline-note-evidence
  (let [header (str/replace sta-header "Final Result Notes" "Final Result       Notes")
        padding (apply str (repeat 70 " "))
        a "3    ̇ Synthetic SURNAME"
        b "    Given NAME TUR 05:35 BRONZE MEDAL"
        r (extraction/parse-pages [(str header a "\n" b "\n"
                                        padding "SILVER MEDAL,\n2 Other NAME CMAS1 06:38\n"
                                        padding "PANAMERICAN RECORD\n"
                                        padding "GOLD MEDAL,\n1 Third NAME GBR 05:05\n"
                                        padding "WORLD RECORD MASTERS M2\n")])
        [damaged silver gold] (:candidates r)]
    (is (= 3 (get-in r [:reconciliation :candidate-count])))
    (is (= [:unparsed :parsed :parsed] (mapv :parse-status (:candidates r))))
    (is (= [a b] (mapv :text (:source-lines damaged))))
    (is (some #{:ambiguous-wrapped-name} (:unresolved-reasons damaged)))
    (is (= "SILVER MEDAL, PANAMERICAN RECORD" (get-in silver [:parsed :notes])))
    (is (= "GOLD MEDAL, WORLD RECORD MASTERS M2" (get-in gold [:parsed :notes])))
    (is (= 3 (count (:source-lines gold))))
    (is (= :join-note-lines (get-in gold [:repairs 0 :operation])))
    (is (= (get-in r [:reconciliation :nonblank-line-count])
           (+ (reduce + (map #(count (:source-lines %)) (:candidates r)))
              (count (get-in r [:reconciliation :noncandidate-lines])))))))

(deftest athens-sta-time-syntax-precision-and-invalid-fields
  (let [r (extraction/parse-pages [(str sta-header
                                        "1 Exact NAME GBR 04:05.120\n"
                                        "2 Unknown NAME GBR 04:60\n"
                                        "3 Bad NAME GBR 04:xx\n")])
        [exact unknown bad] (:candidates r)]
    (is (= "04:05.120" (get-in exact [:raw :fields :final-time])))
    (is (= "120" (get-in exact [:parsed :final-time :fraction])))
    (is (= 3 (get-in exact [:parsed :final-time :fraction-digits])))
    (is (= [4 60] (get-in unknown [:parsed :final-time :components])))
    (is (= :ambiguous (get-in unknown [:fields :unit :status])))
    (is (= :unparsed (:parse-status bad)))
    (is (= :invalid (get-in bad [:fields :final-time :status])))
    (is (some #{:invalid-time-syntax} (:unresolved-reasons bad)))
    (is (= "04:xx" (get-in bad [:raw :fields :final-time])))))

(deftest athens-sta-scopes-continuations-and-rejects-conflicting-headers
  (let [continuation "# Name & surname Country Final Result Notes\n2 Next NAME GBR 04:00\n"
        r (extraction/parse-pages [(str sta-header "1 First NAME GBR 05:00\n")
                                   continuation "SPEED2X50 FINAL RESULTS\n" continuation])]
    (is (= [3 4] (get-in r [:reconciliation :unsupported-pages])))
    (is (= 2 (get-in r [:reconciliation :parsed-count])))
    (is (= "2025-05-23" (get-in r [:candidates 1 :parsed :event-date])))
    (is (= #{1 2} (set (map :page (get-in r [:candidates 1 :metadata-evidence]))))))
  (doseq [header [(str sta-header "Distance (m) Distance (m)\n")
                  (str sta-header "Realized Final Notes\n")
                  (str athens-header "# Name & surname Country Final Result Notes\n")]]
    (let [r (extraction/parse-pages [(str header "1 Mixed NAME GBR 05:00\n")])]
      (is (= [1] (get-in r [:reconciliation :unsupported-pages])))
      (is (empty? (:candidates r))))))

(deftest athens-sta-note-joining-needs-alignment-adjacency-and-empty-note
  (doseq [body ["SILVER MEDAL,\n2 Other NAME GBR 06:38\nPANAMERICAN RECORD\n"
                (str (apply str (repeat 70 " ")) "SILVER MEDAL,\n\n2 Other NAME GBR 06:38\n"
                     (apply str (repeat 70 " ")) "PANAMERICAN RECORD\n")
                (str (apply str (repeat 70 " ")) "SILVER MEDAL,\n2 Other NAME GBR 06:38 BRONZE MEDAL\n"
                     (apply str (repeat 70 " ")) "PANAMERICAN RECORD\n")]]
    (let [r (extraction/parse-pages [(str sta-header body)])]
      (is (= 3 (get-in r [:reconciliation :candidate-count])))
      (is (every? #(nil? (:repairs %)) (:candidates r))))))

(deftest athens-sta-three-letter-surnames-are-not-representation
  (let [r (extraction/parse-pages [(str sta-header "1 Synthetic UWE GBR 05:27 BRONZE MEDAL\n2 Other KOO CMAS1 06:19\n")])]
    (is (= 2 (get-in r [:reconciliation :parsed-count])))
    (is (= ["Synthetic UWE" "Other KOO"] (mapv #(get-in % [:parsed :source-name]) (:candidates r))))))

(deftest athens-sta-invalid-time-cannot-gain-parsed-note
  (let [padding (apply str (repeat 70 " "))
        r (extraction/parse-pages [(str sta-header padding "SILVER MEDAL,\n2 Other NAME GBR 04:xx\n"
                                        padding "PANAMERICAN RECORD\n")])]
    (is (= 3 (get-in r [:reconciliation :candidate-count])))
    (is (every? #(nil? (:parsed %)) (:candidates r)))))

(deftest athens-sta-shared-cells-retain-only-group-evidence
  (let [r (extraction/parse-pages [(str sta-header "Alpha NAME GBR 05:00 GOLD MEDAL\n1\nBeta NAME POL 05:00\n")])
        [a b] (:candidates r)]
    (is (= 2 (get-in r [:reconciliation :candidate-count])))
    (is (= (:group-evidence a) (:group-evidence b)))
    (is (every? #(nil? (:parsed %)) [a b]))
    (is (every? #(some #{:time-unit-not-explicit} (:unresolved-reasons %)) [a b]))
    (is (every? #(= :ambiguous (get-in % [:fields :unit :status])) [a b]))))

(deftest athens-sta-column-padding-is-not-part-of-source-name
  (let [r (extraction/parse-pages [(str sta-header
                                        "1   Éva  UWE                     CMAS1           04:05 GOLD MEDAL\n"
                                        "2   X                           GBR             05:06\n")])]
    (is (= ["Éva  UWE" "X"] (mapv #(get-in % [:parsed :source-name]) (:candidates r))))
    (is (= ["1" "2"] (mapv #(get-in % [:raw :fields :rank]) (:candidates r))))
    (is (= "1   Éva  UWE                     CMAS1           04:05 GOLD MEDAL"
           (get-in r [:candidates 0 :raw :line])))))

(defn speed-header [family]
  (str athens/title "\nMAY, 22, 2025\nSPEED " family " FINAL RESULTS\nSENIORS – WOMEN\n"
       "# Name & surname Country       Realized     Final %\n"
       "                                                        Notes\n"
       "                                 Result    Result\n"))

(deftest athens-speed-families-preserve-time-syntax-and-unknown-units
  (doseq [[family token components] [["8X50" "04:51.36" [4 51]] ["2X50" "39.170" [39]] ["4X50" "1:01.33" [1 1]]]]
    (let [r (extraction/parse-pages [(str (speed-header family) "1 Éva UWE CMAS1 " token " " token " GOLD MEDAL, WORLD RECORD\n")])
          c (first (:candidates r))]
      (is (= 1 (get-in r [:reconciliation :parsed-count])))
      (is (= (str "SPEED " family) (get-in c [:parsed :discipline])))
      (is (= token (get-in c [:raw :fields :realized-time]) (get-in c [:raw :fields :final-time])))
      (is (= components (get-in c [:parsed :final-time :components])))
      (is (= "Éva UWE" (get-in c [:parsed :source-name])))
      (is (= :unknown (get-in c [:fields :final-duration :status])))
      (is (= :unknown (get-in c [:fields :realized-duration :status])))
      (is (= :unknown (get-in c [:fields :split-time :status])))
      (is (= :unknown (get-in c [:fields :penalty :status])))
      (is (= :ambiguous (get-in c [:fields :unit :status])))
      (is (some #{:final-percent-header-ambiguous} (:unresolved-reasons c)))
      (is (= :blocked (get-in r [:publication :status]))))))

(deftest athens-speed-missing-cells-status-and-invalid-syntax
  (let [r (extraction/parse-pages [(str (speed-header "4X50")
                                        "1 Synthetic NAME GBR            1:46.27                 GOLD MEDAL\n"
                                        "2 Other NAME GBR                            2:12.34    SILVER MEDAL\n"
                                        "  Absent NAME GBR                                      DNS\n"
                                        "  Disqualified NAME GBR                                DQ NO FINISH\n"
                                        "3 Bad NAME GBR 04:xx 04:51.36\n"
                                        "4 Unknown NAME GBR 39.17 39.18 MYSTERY NOTE\n"
                                        "5 Misplaced NAME GBR 1:46.27 GOLD MEDAL\n")])
        [a b dns dq bad unknown misplaced] (:candidates r)]
    (is (= 7 (get-in r [:reconciliation :candidate-count])))
    (is (= [:parsed :parsed :parsed :parsed :unparsed :unparsed :unparsed] (mapv :parse-status (:candidates r))))
    (is (= "1:46.27" (get-in a [:raw :fields :realized-time])))
    (is (nil? (get-in a [:parsed :final-time])))
    (is (= "2:12.34" (get-in b [:raw :fields :final-time])))
    (is (nil? (get-in b [:parsed :realized-time])))
    (is (= "DNS" (get-in dns [:parsed :status])))
    (is (= "DQ" (get-in dq [:parsed :status])))
    (is (= "DQ NO FINISH" (get-in dq [:raw :fields :notes])))
    (is (= :invalid (get-in bad [:fields :realized-time :status])))
    (is (= "04:xx" (get-in bad [:raw :fields :realized-time])))
    (is (= "MYSTERY NOTE" (get-in unknown [:raw :fields :notes])))
    (is (some #{:unknown-source-note} (:unresolved-reasons unknown)))
    (is (nil? (:parsed misplaced)))))

(deftest athens-speed-uncertain-groups-and-overflow-stay-unparsed
  (let [r (extraction/parse-pages [(str (speed-header "2X50")
                                        "Alpha NAME GBR 39.17 39.17 GOLD MEDAL\n1\nBeta NAME POL 39.17 39.17\n"
                                        "3 Wrapped SURNAME\n    Given NAME GBR 40.00 40.00\n"
                                        "4 Huge NAME GBR 99999999999999999999999.12 39.17\n")])]
    (is (= 4 (count (:candidates r))))
    (is (every? #(= :unparsed (:parse-status %)) (:candidates r)))
    (is (some #{:ambiguous-merged-cells} (get-in r [:candidates 0 :unresolved-reasons])))
    (is (some #{:ambiguous-wrapped-name} (get-in r [:candidates 2 :unresolved-reasons])))
    (is (= :invalid (get-in r [:candidates 3 :fields :realized-time :status])))))

(deftest athens-speed-context-and-duplicates
  (let [h (speed-header "2X50")
        columns (str/join "\n" (drop 4 (str/split-lines h)))
        row "1 Éva  NAME GBR 39.170 39.180\n"
        r (extraction/parse-pages [(str h row row) (str columns "\n" row)
                                   "SPEED 9X50 FINAL RESULTS\n" (str columns "\n" row)])]
    (is (= 3 (get-in r [:reconciliation :parsed-count])))
    (is (= [3 4] (get-in r [:reconciliation :unsupported-pages])))
    (is (= ["Éva  NAME" "Éva  NAME" "Éva  NAME"] (mapv #(get-in % [:parsed :source-name]) (:candidates r))))
    (is (= "170" (get-in r [:candidates 0 :parsed :realized-time :fraction])))
    (is (= "180" (get-in r [:candidates 0 :parsed :final-time :fraction])))
    (is (every? #(nil? (get-in % [:parsed :penalty])) (:candidates r)))))

(deftest athens-speed-mixed-headers-never-authorize-context
  (doseq [h [(str sta-header "Notes\n") (str athens-header "Result Result\n")
             (str (speed-header "2X50") "Distance (m) Distance (m)\n")
             (str (speed-header "2X50") "# Name & surname Country Final Result Notes\n")]]
    (is (= [1] (get-in (extraction/parse-pages [(str h "1 Example NAME GBR 39.17 39.17\n")])
                       [:reconciliation :unsupported-pages])))))

(deftest athens-speed-single-cell-requires-current-bounded-column-evidence
  (let [h (str athens/title "\nMAY, 23, 2025\nSPEED 4X50 FINAL RESULTS\nMASTERS M1 – WOMEN\n"
               "                                         Realized    Final %\n"
               "                                                           Notes\n"
               "#   Name & surname             Country\n"
               "                                           Result   Result\n")
        r (extraction/parse-pages [(str h "1   Example NAME                GBR       1:46.27         GOLD MEDAL\n"
                                        "2   Far NAME                    GBR                                 1:46.27\n")])]
    (is (= [:parsed :unparsed] (mapv :parse-status (:candidates r))))
    (is (= "1:46.27" (get-in r [:candidates 0 :raw :fields :realized-time])))
    (is (nil? (get-in r [:candidates 0 :raw :fields :final-time])))))

(def novi-header
  (str "2026 CMAS WORLD CHAMPIONSHIP FREEDIVING INDOOR\n"
       "NOVI SAD, SERBIA                    JUNE, 11, 2026\n"
       "DNF\nJUNIORS – WOMEN\n"
       "                                      Realized     Final           Notes\n"
       "#      Name & surname       Country\n"
       "                                      Distance (m) Distance (m)\n"))

(deftest novi-sad-junior-dnf-retains-distance-evidence
  (let [line "1   Zoë EXAMPLE               FRA             86,5          86,5 GOLD MEDAL"
        r (extraction/parse-pages [(str novi-header line "\n")])
        c (first (:candidates r))]
    (is (= "cmas-novi-sad-dnf-juniors/1" (:parser-version r)))
    (is (= 3 (:schema-version r)))
    (is (= 1 (get-in r [:reconciliation :parsed-count])))
    (is (= {:source-name "Zoë EXAMPLE" :representation "FRA" :rank 1
            :realized-distance 86.5M :final-distance 86.5M :unit "m"
            :event-date "2026-06-11" :discipline "DNF" :category "JUNIORS – WOMEN"}
           (select-keys (:parsed c) [:source-name :representation :rank :realized-distance
                                     :final-distance :unit :event-date :discipline :category])))
    (is (= "86,5" (get-in c [:raw :fields :final-distance])))
    (is (= line (get-in c [:source-lines 0 :text])))
    (is (= 7 (count (:metadata-evidence c))))
    (is (= :unknown (get-in c [:fields :penalty :status])))
    (is (= :blocked (get-in r [:publication :status])))))

(deftest novi-sad-dsq-does-not-invent-final-or-penalty
  (let [r (extraction/parse-pages [(str novi-header
                                        "    Ada EXAMPLE              TUR             72,5                 DSQ SP\n"
                                        "    Elif EXAMPLE             TUR             71,0                 DSQ SURFACE BO\n")])]
    (is (= 2 (get-in r [:reconciliation :parsed-count])))
    (doseq [c (:candidates r)]
      (is (= "DSQ" (get-in c [:parsed :status])))
      (is (nil? (get-in c [:parsed :final-distance])))
      (is (= :unknown (get-in c [:fields :final-distance :status])))
      (is (= :unknown (get-in c [:fields :penalty :status])))
      (is (nil? (get-in c [:raw :fields :final-distance]))))
    (is (= 72.5M (get-in r [:candidates 0 :parsed :realized-distance])))))

(deftest novi-sad-supported-counts-do-not-hide-unsupported-pages
  (let [row "1 Synthetic ATHLETE AIN 80,0 80,0\n"
        men (str/replace novi-header "JUNIORS – WOMEN" "JUNIORS \u2014 MEN")
        pages (into [(str men row) (str novi-header row)]
                    (map #(str % row) [(str/replace novi-header "JUNIORS" "SENIORS")
                                       (str/replace novi-header "DNF\n" "DYN\n")
                                       (str/replace novi-header "11, 2026" "12, 2026")
                                       (str/replace novi-header "Distance (m)" "Distance")
                                       "" "UNSUPPORTED PAGE\n"]))
        r (extraction/parse-pages pages)]
    (is (= pages (mapv :text (:pages r))))
    (is (= [3 4 5 6 7 8] (get-in r [:reconciliation :unsupported-pages])))
    (is (= :supported-pages-only (get-in r [:reconciliation :counts-scope])))
    (is (= 2 (get-in r [:reconciliation :candidate-count])))
    (is (= 2 (get-in r [:reconciliation :parsed-count])))
    (is (= "JUNIORS \u2014 MEN" (get-in r [:candidates 0 :parsed :category])))
    (doseq [p (drop 2 (get-in r [:reconciliation :per-page]))]
      (is (false? (:supported? p)))
      (is (nil? (:candidate-count p)))
      (is (nil? (:parsed-count p))))
    (is (= "cmas-cwt-men/1" (:parser-version (extraction/parse-pages [header]))))
    (is (= "cmas-athens-pool/6" (:parser-version (extraction/parse-pages [athens/title]))))))

(deftest novi-sad-ambiguous-single-distance-remains-unparsed
  (let [r (extraction/parse-pages [(str novi-header
                                        "    Someone EXAMPLE          TUR                          72,5    DSQ SP\n"
                                        "    Other EXAMPLE            TUR             71,0                 DSQ UNKNOWN\n")])]
    (is (= 2 (get-in r [:reconciliation :unparsed-count])))
    (is (every? nil? (map :parsed (:candidates r))))))

(deftest novi-sad-archive-extraction-retains-schema-and-parser-identity
  (let [pdf (synthetic-pdf (str "BT /F1 8 Tf 20 750 Td "
                                "(2026 CMAS WORLD CHAMPIONSHIP FREEDIVING INDOOR) Tj 0 -20 Td "
                                "(NOVI SAD, SERBIA    JUNE, 11, 2026) Tj 0 -20 Td "
                                "(DNF) Tj 0 -20 Td (JUNIORS \\261 WOMEN) Tj 0 -20 Td "
                                "(Realized Final Notes) Tj 0 -20 Td "
                                "(# Name & surname Country) Tj 0 -20 Td "
                                "(Distance \\(m\\) Distance \\(m\\)) Tj 0 -20 Td "
                                "(1 Synthetic NAME GBR 82,5 82,5) Tj ET"))
        [root digest] (with-redefs [synthetic-pdf (constantly pdf)] (registered-pdf))
        opts {:actor "synthetic-novi-sad" :config {}}
        old-extract! #(with-redefs [indoor-2026/supported? (constantly false)] (extraction/extract! root digest opts))
        receipt (old-extract!)
        r (edn/read-string (slurp (:artifact-path receipt)))]
    (is (= "cmas-novi-sad-dnf-juniors/1" (:parser-version r)))
    (is (= 3 (:schema-version r)))
    (is (= 1 (get-in r [:reconciliation :parsed-count])))
    (is (= 82.5M (get-in r [:candidates 0 :parsed :final-distance])))
    (is (= :skipped (:run-status (old-extract!))))))

(deftest novi-sad-conflicting-section-evidence-fails-closed
  (doseq [extra ["SENIORS – WOMEN" "DYN" "JUNE, 12, 2026" "NOVI SAD, SERBIA JUNE, 12, 2026"]]
    (let [r (extraction/parse-pages [(str novi-header extra "\n1 Synthetic NAME GBR 80 80\n")])]
      (is (= [1] (get-in r [:reconciliation :unsupported-pages])) extra)
      (is (empty? (:candidates r)) extra))))
