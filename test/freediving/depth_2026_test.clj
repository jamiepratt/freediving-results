(ns freediving.depth-2026-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [freediving.archive :as archive]
            [freediving.archive-test :as fixture]
            [freediving.extraction :as extraction]
            [freediving.depth-2026 :as depth-2026]
            [freediving.depth-test :as previous]
            [freediving.observations :as observations]
            [freediving.extraction-test :as pdf]))

(defn text-at [x y s]
  (str "BT /F1 7 Tf " x " " y " Td (" s ") Tj ET\n"))
(defn row [y values]
  (apply str (map #(text-at %1 y %2) [25 65 170 207 247 283 319 360 390 460 530] values)))
(def header
  (str (text-at 120 750 "2026 CMAS World Championship")
       (text-at 120 738 "Freediving Depth Seniors Masters")
       (text-at 120 726 "14/08/2026 - 27/08/2026")
       (text-at 220 710 "Results")
       (text-at 140 680 "FREE IMMERSION") (text-at 500 680 "19/08/2026")
       (text-at 65 662 "SURNAME &") (text-at 207 662 "DEC.") (text-at 319 662 "FINAL")
       (row 654 ["RANK" "" "NAT" "" "RESULT" "PEN." "" "STATUS" "NOTES" "MEDAL" "RECORD"])
       (text-at 75 646 "NAME") (text-at 207 646 "DEPTH") (text-at 319 646 "RESULT")
       (text-at 240 625 "SENIORS MEN")))
(defn pdf-pages [streams]
  (let [objects (into ["<< /Type /Catalog /Pages 2 0 R >>"
                       (str "<< /Type /Pages /Kids [" (str/join " " (map #(str (+ 4 (* 2 %)) " 0 R") (range (count streams)))) "] /Count " (count streams) " >>")
                       "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"]
                      (mapcat (fn [i stream]
                                [(str "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents " (+ 5 (* 2 i)) " 0 R >>")
                                 (str "<< /Length " (count stream) " >>\nstream\n" stream "\nendstream")]) (range) streams))
        pieces (map-indexed #(str (inc %1) " 0 obj\n" %2 "\nendobj\n") objects)
        body (str "%PDF-1.4\n" (apply str pieces))
        offsets (butlast (reductions + 9 (map count pieces)))]
    (str body "xref\n0 " (inc (count objects)) "\n0000000000 65535 f \n"
         (apply str (map #(format "%010d 00000 n \n" %) offsets))
         "trailer\n<< /Size " (inc (count objects)) " /Root 1 0 R >>\nstartxref\n" (count body) "\n%%EOF\n")))
(defn extract-stream [stream]
  (let [dir (fixture/workspace) root (str dir "/archive") source (str dir "/synthetic.pdf")
        document (if (string? stream) (pdf/synthetic-pdf stream) (pdf-pages stream))
        hash (.formatHex (java.util.HexFormat/of) (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes document "UTF-8")))
        options {:actor "synthetic-2026-test" :config {}}]
    (spit source document)
    (archive/register! root source (assoc fixture/manifest :sha256 hash))
    (let [receipt (extraction/extract! root hash options)]
      {:root root :hash hash :options options :receipt receipt
       :result (edn/read-string (slurp (:artifact-path receipt)))})))
(deftest depth-2026-extraction-preserves-fields-and-context
  (let [{:keys [result]} (extract-stream (str header (row 600 ["1" "SAMPLE Person" "AIN" "090" "80" "11" "69" "" "EARLY TURN" "GOLD MEDAL" "WRSENIORS"])))
        c (first (:candidates result))]
    (is (= "cmas-2026-depth/1" (:parser-version result)))
    (is (= 1 (get-in result [:reconciliation :parsed-count])))
    (is (= [90 80 11 69] (mapv #(get-in c [:parsed %]) [:declared-depth :attempted-depth :penalty :final-depth])))
    (is (= "090" (get-in c [:raw :fields :declared-depth])))
    (is (= ["FIM" "SENIORS MEN" "2026-08-19" "EARLY TURN" "GOLD MEDAL" "WRSENIORS"]
           (mapv #(get-in c [:parsed %]) [:discipline :category :event-date :notes :medal :record])))
    (is (= :unknown (get-in c [:fields :unit :status])))
    (is (= :blocked (get-in result [:publication :status])))))

(deftest unknown-category-boundaries-do-not-inherit-previous-category
  (let [{:keys [result]} (extract-stream (str header
                                              (row 600 ["1" "FIRST Person" "AIN" "90" "90" "" "90"])
                                              (text-at 240 575 "MASTERS M4 MEN")
                                              (row 550 ["1" "SECOND Person" "AIN" "80" "80" "" "80"])))]
    (is (= 1 (get-in result [:reconciliation :parsed-count])))
    (is (= :unparsed (:parse-status (last (:candidates result)))))))

(deftest numeric-words-crossing-a-column-boundary-fail-closed
  (let [{:keys [result]} (extract-stream (str header
                                              (row 600 ["1" "SAMPLE Person" "AIN" "" "80" "11" "69"])
                                              (text-at 231 600 "090")))]
    (is (zero? (get-in result [:reconciliation :parsed-count])))))

(deftest legacy-dispatch-keeps-prior-parser-identity
  (let [legacy (str (previous/header "FIM") (previous/row "1" "SAMPLE Person" "AIN" "90" "90" "" "90" "" ""))
        result (extraction/parse-pages [legacy "2026 CMAS World Championship\nFreediving Depth Seniors Masters\n"])]
    (is (= "cmas-women-depth/1" (:parser-version result)))))

(deftest source-replay-is-enforced-before-import
  (let [{:keys [root receipt result]} (extract-stream (str header (row 600 ["1" "SAMPLE Person" "AIN" "90" "80" "11" "69"])))
        job (:job-id receipt)
        changed (assoc-in result [:candidates 0 :parsed :final-depth] 999)
        encoded (pr-str changed)
        hash (.formatHex (java.util.HexFormat/of) (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes encoded "UTF-8")))
        derivation (str root "/derivations/" job ".edn")]
    (spit (str root "/derived-objects/" hash) encoded)
    (spit derivation (pr-str (assoc (edn/read-string (slurp derivation)) :artifact-sha256 hash)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"archived source replay" (observations/import! nil root job)))))

(deftest missing-and-zero-values-retain-their-own-columns
  (doseq [[values expected] [[["1" "ZERO Person" "AIN" "50" "0" "0" "0"] [50 0 0 0]]
                             [["1" "BLANK Person" "AIN" "50" "" "2" ""] [50 nil 2 nil]]
                             [["" "DNS Person" "AIN" "50" "" "" "" "DNS"] [50 nil nil nil]]
                             [["" "DSQ Person" "AIN" "50" "40" "11" "" "DSQ" "PULL"] [50 40 11 nil]]]]
    (let [{:keys [result]} (extract-stream (str header (row 600 values))) c (first (:candidates result))]
      (is (= :parsed (:parse-status c)))
      (is (= expected (mapv #(get-in c [:parsed %]) [:declared-depth :attempted-depth :penalty :final-depth])))
      (is (= :unknown (get-in c [:fields :unit :status])))
      (is (nil? (get-in c [:parsed :card]))))))

(deftest categories-and-page-boundaries-require-local-evidence
  (let [m1 (str/replace header "SENIORS MEN" "MASTERS M1 MEN")
        {:keys [result]} (extract-stream [(str header (row 600 ["1" "FIRST Person" "AIN" "90" "90" "" "90"])
                                               (text-at 240 575 "MASTERS M1 MEN")
                                               (row 550 ["1" "SECOND Person" "AIN" "80" "80" "" "80"]))
                                          (str m1 (row 600 ["2" "THIRD Person" "AIN" "70" "70" "" "70"]))
                                          (str (str/replace header (text-at 240 625 "SENIORS MEN") "")
                                               (row 600 ["3" "UNKNOWN Person" "AIN" "60" "60" "" "60"]))])]
    (is (= ["SENIORS MEN" "MASTERS M1 MEN" "MASTERS M1 MEN" nil]
           (mapv #(get-in % [:parsed :category]) (:candidates result))))
    (is (= [1 1 2 3] (mapv #(get-in % [:coordinates :page]) (:candidates result))))
    (is (= 3 (get-in result [:reconciliation :parsed-count])))))

(deftest above-row-notes-join-the-correct-athlete
  (let [{:keys [result]} (extract-stream (str header
                                              (row 600 ["1" "FIRST Person" "AIN" "90" "90" "" "90"])
                                              (text-at 380 575 "EARLY TURN, NO")
                                              (row 570 ["2" "SECOND Person" "AIN" "80" "70" "11" "59" "" "MARKER"])))
        c (last (:candidates result))]
    (is (= 2 (get-in result [:reconciliation :parsed-count])))
    (is (nil? (get-in result [:candidates 0 :parsed :notes])))
    (is (= "EARLY TURN, NO MARKER" (get-in c [:parsed :notes])))
    (is (= 2 (count (:source-lines c))))
    (is (= (str/join "\n" (map :text (:source-lines c))) (get-in c [:raw :line])))))

(deftest malformed-context-and-geometry-stay-unparsed
  (doseq [bad-header [(str/replace header "19/08/2026" "31/02/2026")
                      (str header (text-at 140 690 "FREE IMMERSION") (text-at 500 690 "20/08/2026"))
                      (str/replace header "SENIORS MEN" "JUNIORS MEN")
                      (str/replace header "PEN." "OTHER")]]
    (let [{:keys [result]} (extract-stream (str bad-header (row 600 ["1" "SAMPLE Person" "AIN" "90" "90" "" "90"])))]
      (is (zero? (get-in result [:reconciliation :parsed-count])))))
  (let [{:keys [result]} (extract-stream (str header (row 600 ["1" "SAMPLE Person" "AIN" "90" "90" "" "90"])))
        pages (mapv :text (:pages result))]
    (doseq [xml ["" (str/replace (:geometry-xml result) "xMin=\"" "xMin=\"NaN")]]
      (is (zero? (get-in (depth-2026/parse-pages-with-geometry pages xml) [:reconciliation :parsed-count]))))))

(deftest extraction-retains-old-jobs-and-is-idempotent
  (let [{:keys [root hash options receipt result]} (extract-stream (str header (row 600 ["1" "SAMPLE Person" "AIN" "90" "90" "" "90"])))
        old (with-redefs [depth-2026/supported? (constantly false)] (extraction/extract! root hash options))
        old-bytes (slurp (:artifact-path old))
        again (extraction/extract! root hash options)
        revised (with-redefs [depth-2026/parser-version "cmas-2026-depth/test-next"] (extraction/extract! root hash options))]
    (is (= :skipped (:run-status again)))
    (is (= (:artifact-sha256 receipt) (:artifact-sha256 again)))
    (is (= 3 (count (set (map :job-id [receipt old revised])))))
    (is (= old-bytes (slurp (:artifact-path old))))
    (is (= "cmas-cwt-men/1" (:parser-version (edn/read-string old-bytes))))
    (is (= 2 (:schema-version result)))
    (is (= ["-bbox-layout" "-enc" "UTF-8"] (get-in result [:tool :geometry-arguments])))))

(deftest source-backed-replay-rejects-all-derived-evidence-tampering
  (let [{:keys [root result]} (extract-stream (str header (row 600 ["1" "SAMPLE Person" "AIN" "90" "90" "" "90"])))]
    (is (= result (extraction/validate-geometry-artifact! root result)))
    (doseq [changed [(assoc-in result [:candidates 0 :raw :fields :medal] "GOLD MEDAL")
                     (assoc-in result [:candidates 0 :parsed :final-depth] 999)
                     (assoc-in result [:candidates 0 :metadata-evidence 0 :text] "fabricated")
                     (assoc-in result [:candidates 0 :geometry-evidence 0 :x-min] 0.0)
                     (assoc result :geometry-xml "")
                     (assoc result :raw-text "fabricated")
                     (assoc-in result [:tool :geometry-arguments] ["-raw"])]]
      (is (thrown? clojure.lang.ExceptionInfo (extraction/validate-geometry-artifact! root changed))))))

(defn canonical [x]
  (cond (map? x) (into (sorted-map) (map (fn [[k v]] [k (canonical v)]) x))
        (sequential? x) (mapv canonical x) :else x))
(defn sha [s]
  (.formatHex (java.util.HexFormat/of) (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))))
(deftest downgrading-identity-cannot-bypass-source-validation
  (let [{:keys [root receipt result]} (extract-stream (str header (row 600 ["1" "SAMPLE Person" "AIN" "90" "90" "" "90"])))]
    (doseq [changed [(assoc result :parser-version "cmas-cwt-men/1")
                     (-> result (assoc :parser-version "cmas-cwt-men/1" :schema-version 1)
                         (dissoc :geometry-xml) (update :tool dissoc :geometry-arguments))]]
      (let [job (sha (pr-str (canonical (select-keys changed observations/identity-keys))))
            encoded (pr-str (assoc changed :job-id job)) hash (sha encoded)]
        (spit (str root "/derived-objects/" hash) encoded)
        (spit (str root "/derivations/" job ".edn") (pr-str (assoc receipt :job-id job :artifact-sha256 hash)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid geometry extraction contract"
                              (observations/import! nil root job)))))))

(deftest adjacent-competing-categories-fail-closed
  (let [{:keys [result]} (extract-stream (str header (text-at 240 613 "MASTERS M1 MEN")
                                              (row 590 ["1" "SAMPLE Person" "AIN" "90" "90" "" "90"])))]
    (is (zero? (get-in result [:reconciliation :parsed-count])))))

(deftest mixed-legacy-artifacts-keep-their-import-contract
  (let [{:keys [root receipt result]} (extract-stream [(str (text-at 20 750 "2025 CMAS World Championship Freediving Depth")
                                                            (text-at 20 730 "FIM Women SENIORS")
                                                            (text-at 20 700 "SYNTHETIC unresolved row"))
                                                       (str header (row 600 ["1" "SAMPLE Person" "AIN" "90" "90" "" "90"]))])]
    (is (= "cmas-women-depth/1" (:parser-version result)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Database URL required"
                          (observations/import! nil root (:job-id receipt))))))
