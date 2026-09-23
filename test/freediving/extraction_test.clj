(ns freediving.extraction-test
  (:require [clojure.test :refer [deftest is]]
            [freediving.extraction :as extraction]
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

(defn synthetic-pdf []
  (let [stream "BT /F1 12 Tf 40 750 Td (Synthetic result page) Tj ET"
        objects ["<< /Type /Catalog /Pages 2 0 R >>"
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
         "trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n" (count body) "\n%%EOF\n")))

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
