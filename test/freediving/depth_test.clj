(ns freediving.depth-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [freediving.extraction :as extraction]
            [freediving.depth :as depth]
            [freediving.depth-2025 :as depth-2025]
            [freediving.public-results :as public]
            [freediving.archive :as archive]
            [freediving.archive-test :as fixture]
            [freediving.extraction-test :as pdf]
            [clojure.edn :as edn]))

(defn header [discipline]
  (str "2025 CMAS World Championship Freediving Depth\n10/09/2025\nResult\n"
       discipline " Women SENIORS\n"
       "                                                        DEC.                             FINAL\n"
       "RANK SURNAME & NAME                              NAT               DEPTH       PEN.                  STATUS   NOTES\n"
       "                                                        DEPTH                            DEPTH\n"))
(defn row [rank name nat declared depth penalty final status notes]
  (format "%4s %-42s %-6s %7s %9s %10s %10s %10s        %s"
          rank name nat declared depth penalty final status notes))

(deftest women-depth-columns-preserve-source-semantics
  (doseq [discipline ["CWT" "FIM" "CNF"]]
    (let [line (row "01" "ÉXAMPLE  Person" "CMAS1" "0100" "0095" "06" "0089" "PEN" "EARLY TURN")
          result (extraction/parse-pages [(str (header discipline) line "\nReport Created 15/09/2025 11:02 1 of 1\n")])
          c (first (:candidates result))]
      (is (= "cmas-women-depth/1" (:parser-version result)))
      (is (= 1 (get-in result [:reconciliation :parsed-count])))
      (is (= [100 95 6 89] (mapv #(get-in c [:parsed %]) [:declared-depth :attempted-depth :penalty :final-depth])))
      (is (= "0100" (get-in c [:raw :fields :declared-depth])))
      (is (= "ÉXAMPLE  Person" (get-in c [:parsed :source-name])))
      (is (= discipline (get-in c [:parsed :discipline])))
      (is (= "2025-09-10" (get-in c [:parsed :event-date])))
      (is (= :unknown (get-in c [:fields :unit :status])))
      (is (= line (get-in c [:raw :line])))
      (is (= :blocked (get-in result [:publication :status]))))))

(deftest wrapped-note-has-one-candidate-with-exact-evidence
  (let [body (row "2" "Synthetic Name" "AIN" "100" "98" "3" "95" "PEN" "EARLY TURN")
        note (str (apply str (repeat 110 " ")) "SILVER MEDAL")
        r (extraction/parse-pages [(str (header "FIM") body "\n" note "\n")])
        c (first (:candidates r))]
    (is (= 1 (get-in r [:reconciliation :candidate-count])))
    (is (= "EARLY TURN SILVER MEDAL" (get-in c [:parsed :notes])))
    (is (= [body note] (mapv :text (:source-lines c))))
    (is (= ["EARLY TURN" "SILVER MEDAL"] (get-in c [:raw :fields :note-fragments])))
    (is (= :join-note-lines (get-in c [:repairs 0 :operation])))
    (is (= (get-in r [:reconciliation :nonblank-line-count])
           (+ (count (get-in r [:reconciliation :noncandidate-lines]))
              (reduce + (map #(count (:source-lines %)) (:candidates r))))))))

(deftest tokens-crossing-columns-cannot-be-split-into-invented-values
  (let [line (row "1" "Synthetic Name" "AIN" "" "" "" "80" "" "")
        ;; The current header puts the declared/performed boundary at column 63.
        crossing (str (subs line 0 62) "8888" (subs line 66))
        r (extraction/parse-pages [(str (header "CWT") crossing "\n")])]
    (is (= :unparsed (get-in r [:candidates 0 :parse-status])))
    (is (nil? (get-in r [:candidates 0 :parsed])))
    (is (= crossing (get-in r [:candidates 0 :raw :line])))))

(deftest ties-and-unranked-statuses-remain-separate-rows
  (let [r (extraction/parse-pages [(str (header "CNF")
                                        (row "6" "One Name" "AIN" "50" "50" "" "50" "" "") "\n"
                                        (row "6" "Other Name" "CMAS1" "50" "50" "" "50" "" "") "\n"
                                        (row "" "Absent Name" "GBR" "42" "" "" "" "DNS" "") "\n"
                                        (row "" "Disqualified Name" "GBR" "61" "61" "" "" "DSQ" "SP NO OK") "\n")])
        [a b dns dsq] (:candidates r)]
    (is (= 4 (get-in r [:reconciliation :parsed-count])))
    (is (= [6 6 nil nil] (mapv #(get-in % [:parsed :rank]) [a b dns dsq])))
    (is (= [nil nil "DNS" "DSQ"] (mapv #(get-in % [:parsed :status]) [a b dns dsq])))
    (is (= 42 (get-in dns [:parsed :declared-depth])))
    (is (nil? (get-in dns [:parsed :attempted-depth])))
    (is (nil? (get-in dsq [:parsed :final-depth])))
    (is (= 61 (get-in dsq [:parsed :attempted-depth])))
    (is (= "SP NO OK" (get-in dsq [:raw :fields :notes])))
    (is (every? #(not (contains? (:parsed %) :citizenship)) [a b dns dsq]))))

(deftest dates-and-malformed-context-stay-explicit
  (let [body (row "1" "Synthetic Name" "AIN" "90" "90" "" "90" "" "")]
    (doseq [date ["31/02/2025" "10/09/2025\n11/09/2025" ""]]
      (let [r (extraction/parse-pages [(str (str/replace (header "CWT") "10/09/2025" date)
                                            body "\nReport Created 15/09/2025 11:02 1 of 1\n")])]
        (is (= :parsed (get-in r [:candidates 0 :parse-status])))
        (is (= :unknown (get-in r [:candidates 0 :fields :event-date :status])))))
    (doseq [h [(str/replace (header "FIM") "DEC." "")
               (str (header "FIM") "CNF Women SENIORS\n")]]
      (let [r (extraction/parse-pages [(str h body)])]
        (is (= :unparsed (get-in r [:candidates 0 :parse-status])))
        (is (= body (get-in (last (:candidates r)) [:raw :line])))))
    (let [r (extraction/parse-pages [(str (header "CNF") body) body])]
      (is (= [:parsed :unparsed] (mapv :parse-status (:candidates r)))))))

(deftest orphan-misaligned-or-nonadjacent-notes-stay-unparsed
  (let [body (row "2" "Synthetic Name" "AIN" "100" "98" "3" "95" "PEN" "EARLY TURN")
        aligned (str (apply str (repeat 110 " ")) "SILVER MEDAL")]
    (doseq [lines [(str "SILVER MEDAL\n" body) (str body "\nSILVER MEDAL")
                   (str body "\n\n" aligned) (str aligned "\n" body)
                   (str body "\n" (str/replace aligned "SILVER MEDAL" "MYSTERY NOTE"))]]
      (let [r (extraction/parse-pages [(str (header "FIM") lines)])]
        (is (= 2 (get-in r [:reconciliation :candidate-count])))
        (is (= 1 (get-in r [:reconciliation :unparsed-count])))
        (is (every? #(nil? (:repairs %)) (:candidates r)))))))

(deftest pdf-cli-versioning-retains-historical-unsupported-artifact
  (let [dir (fixture/workspace) root (str dir "/archive") source (str dir "/synthetic.pdf")
        lines (str/split-lines (str (header "FIM") (row "1" "Synthetic Person" "AIN" "90" "90" "" "90" "" "")))
        stream (str "BT /F1 6 Tf 10 750 Td " (str/join " 0 -12 Td " (map #(str "(" % ") Tj") lines)) " ET")
        document (str/replace (pdf/synthetic-pdf stream) "/Helvetica" "/Courier  ")
        hash (.formatHex (java.util.HexFormat/of)
                         (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes document "UTF-8")))
        options {:actor "synthetic-test" :config {}} file (str dir "/options.edn")]
    (spit source document)
    (spit file (pr-str options))
    (archive/register! root source (assoc fixture/manifest :sha256 hash))
    (let [old (with-redefs [depth/supported? (constantly false)] (extraction/extract! root hash options))
          old-bytes (slurp (:artifact-path old))
          cli (fixture/java "-m" "freediving.extraction" root hash file)
          receipt (edn/read-string (:out cli))
          result (edn/read-string (slurp (:artifact-path receipt)))
          again (extraction/extract! root hash options)]
      (is (= 0 (:exit cli)) (:err cli))
      (is (= :unsupported-needs-parser (:status (edn/read-string old-bytes))))
      (is (= "cmas-women-depth/1" (:parser-version result)))
      (is (= 2 (:schema-version result)))
      (is (= 1 (get-in result [:reconciliation :parsed-count])))
      (is (not= (:job-id old) (:job-id receipt)))
      (is (= :skipped (:run-status again)))
      (is (= (:artifact-sha256 receipt) (:artifact-sha256 again)))
      (is (= old-bytes (slurp (:artifact-path old)))))))

(deftest public-fields-retain-declaration-separately-without-private-evidence
  (doseq [values [{:declared-depth 100 :attempted-depth 95 :final-depth 89}
                  {:declared-depth "0100" :attempted-depth "0095" :final-depth "0089"}
                  {:declared-depth nil :attempted-depth nil :final-depth nil}]]
    (is (= values (public/public-fields (assoc values :source-lines [{:text "private"}] :raw {:line "private"})))))
  (is (= {} (public/public-fields {:declared-depth {:secret "private"}}))))

(deftest senior-men-and-bifins-have-explicit-context
  ;; Synthetic layout, no real athlete records.
  (doseq [[discipline category] [["FIM" "Men SENIORS"] ["CNF" "Men SENIORS"] ["CWT-BF" "Women SENIORS"]]]
    (let [h (str/replace (header "CWT") "CWT Women SENIORS" (str discipline " " category))
          r (extraction/parse-pages [(str h (row "1" "Synthetic Person" "AIN" "00" "0" "" "0" "" "WR TEST"))])
          c (first (:candidates r))]
      (is (= "cmas-2025-depth/1" (:parser-version r)))
      (is (= [discipline category 0 "00" "WR TEST"]
             [(get-in c [:parsed :discipline]) (get-in c [:parsed :category])
              (get-in c [:parsed :final-depth]) (get-in c [:raw :fields :declared-depth])
              (get-in c [:parsed :notes])])))))

(deftest combined-masters-sections-use-their-own-context
  (let [h (str/replace (header "FIM") "FIM Women SENIORS" "FIM Men MASTERS M1 + M2\nMasters M1 - Men")
        h2 (str/join "\n" (drop 4 (str/split-lines (header "FIM"))))
        r (extraction/parse-pages [(str h (row "1" "Synthetic One" "AIN" "80" "80" "" "80" "" "")
                                        "\nMasters M2 - Men\n" h2 "\n"
                                        (row "" "Synthetic Two" "AIN" "60" "44" "10" "" "DSQ" "PULL, EARLY TURN"))])]
    (is (= 2 (get-in r [:reconciliation :parsed-count])))
    (is (= ["Masters M1 - Men" "Masters M2 - Men"] (mapv #(get-in % [:parsed :category]) (:candidates r))))
    (is (= 10 (get-in r [:candidates 1 :parsed :penalty])))))

(deftest split-column-headings-and-note-wraps-preserve-values
  (let [h (str/replace (header "CWT") "CWT Women SENIORS" "CWT-BF Men SENIORS")
        h (str/replace h "RANK SURNAME & NAME                              NAT               DEPTH       PEN.                  STATUS   NOTES\n                                                        DEPTH                            DEPTH"
                       "RANK SURNAME & NAME                              NAT     DEPTH\n                                                                  DEPTH       PEN.\n                                                                                         DEPTH\n                                                                                                    STATUS   NOTES")
        body (row "1" "Synthetic Person" "AIN" "100" "100" "1" "99" "PEN" "DOLPHIN KICK")
        r (extraction/parse-pages [(str h body "\n" (apply str (repeat 110 " ")) "GOLD MEDAL")])]
    (is (= 1 (get-in r [:reconciliation :parsed-count])))
    (is (= "DOLPHIN KICK GOLD MEDAL" (get-in r [:candidates 0 :parsed :notes])))))

(deftest inline-category-cwt-retains-dns-zero-and-record-status
  (let [h (str "2025 CMAS World Championship Freediving Depth\n09/09/2025\nResult\nCWT MEN MASTERS M3\n"
               "                                                                          DEC.                        FINAL\n"
               "RANK   SURNAME & NAME                NAT   CATEGORY                                   DEPTH PEN.              STATUS   NOTES\n"
               "                                                                         DEPTH                        DEPTH\n")
        line (format "%2s     %-28s %-5s %-28s %5s %12s %6s %7s %11s %s" "1" "Synthetic Person" "AIN" "Men Master M3" "80" "80" "" "80" "WR MM3" "GOLD MEDAL")
        zero (format "%2s     %-28s %-5s %-28s %5s %12s %6s %7s %11s %s" "" "Synthetic Absent" "AIN" "Men Master M3" "70" "0" "" "" "DNS" "")
        r (extraction/parse-pages [(str h line "\n" zero)])]
    (is (= 2 (get-in r [:reconciliation :parsed-count])))
    (is (= ["Men Master M3" "WR MM3" 0] [(get-in r [:candidates 0 :parsed :category])
                                         (get-in r [:candidates 0 :parsed :status])
                                         (get-in r [:candidates 1 :parsed :attempted-depth])]))))

(deftest contradictory-sections-and-headerless-pages-fail-closed
  (let [body (row "1" "Synthetic Person" "AIN" "50" "50" "" "50" "" "")
        h (str/replace (header "FIM") "FIM Women SENIORS" "FIM Men MASTERS M1 + M2")]
    (doseq [section ["Masters M3 - Men" "Masters M1 - Women" "Masters M1 - Men\nMasters M2 - Men"]]
      (let [r (extraction/parse-pages [(str h "\n" section "\n" body)])]
        (is (zero? (get-in r [:reconciliation :parsed-count])))))
    (let [r (extraction/parse-pages [(str (str/replace h "FIM Men MASTERS M1 + M2" "FIM Men SENIORS") body)
                                     (str "2025 CMAS World Championship Freediving Depth\n10/09/2025\nResult\n" body)])]
      (is (= [:parsed :unparsed] (mapv :parse-status (:candidates r)))))
    (let [r (extraction/parse-pages [(str h "\nUnrecognized source text\nMasters M1 - Men\n" body
                                          "\nMasters M2 - Men\n" body)])]
      (is (= (get-in r [:reconciliation :nonblank-line-count])
             (+ (count (get-in r [:reconciliation :noncandidate-lines]))
                (reduce + (map #(count (:source-lines %)) (:candidates r)))))))))

(deftest headerless-continuations-cannot-shift-missing-numeric-fields
  (let [h (str/replace (header "FIM") "FIM Women SENIORS" "FIM Men SENIORS")
        first-page (str h (row "1" "Synthetic One" "AIN" "90" "90" "" "90" "" "")
                        "\nReport Created 12/09/2025 13:02 1 of 2\n")]
    (doseq [body [(row "" "Synthetic Person" "AIN" "55" "" "12" "" "DSQ" "PULL")
                  (row "" "Synthetic Person" "AIN" "" "44" "12" "" "DSQ" "PULL")
                  "2 Synthetic Two AIN 80 70 11 59 PEN EARLY TURN"]]
      (let [second-page (str "2025 CMAS World Championship Freediving Depth\n10/09/2025\nResult\n"
                             body "\nReport Created 12/09/2025 13:02 2 of 2\n")
            r (extraction/parse-pages [first-page second-page])]
        (is (= [:parsed :unparsed] (mapv :parse-status (:candidates r))))
        (is (= body (get-in r [:candidates 1 :raw :line])))
        (is (nil? (get-in r [:candidates 1 :parsed])))))))

(deftest incomplete-or-contradictory-context-retains-unparsed-evidence
  (let [good (str/replace (header "FIM") "FIM Women SENIORS" "FIM Men MASTERS M1\nMasters M1 - Men")
        body (row "1" "Synthetic Person" "AIN" "50" "50" "" "50" "" "")]
    (doseq [bad ["2025 CMAS World Championship Freediving Depth\nMasters M1 - Men\nSynthetic row"
                 (str good "\nCNF Men MASTERS M1\n" body)
                 "2025 CMAS World Championship Freediving Depth\n10/09/2025\nFIM Men SENIORS\n        DEC. FINAL\nRANK SURNAME & NAME NAT DEPTH\n                                DEPTH PEN.\nDEPTH\nSTATUS NOTES\nSynthetic row"]]
      (let [r (extraction/parse-pages [(str good body) bad])]
        (is (= :unparsed (:parse-status (last (:candidates r)))))))))

(deftest additional-depth-version-retains-old-jobs-and-replays
  (let [dir (fixture/workspace) root (str dir "/archive") source (str dir "/synthetic.pdf")
        lines (str/split-lines (str (str/replace (header "FIM") "Women SENIORS" "Men SENIORS") (row "1" "Synthetic Person" "AIN" "90" "90" "" "90" "" "")))
        stream (str "BT /F1 6 Tf 10 750 Td " (str/join " 0 -12 Td " (map #(str "(" % ") Tj") lines)) " ET")
        document (str/replace (pdf/synthetic-pdf stream) "/Helvetica" "/Courier  ")
        hash (.formatHex (java.util.HexFormat/of)
                         (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes document "UTF-8")))
        options {:actor "synthetic-test" :config {}} file (str dir "/options.edn")]
    (spit source document)
    (spit file (pr-str options))
    (archive/register! root source (assoc fixture/manifest :sha256 hash))
    (let [old (with-redefs [depth-2025/supported? (constantly false)] (extraction/extract! root hash options))
          old-bytes (slurp (:artifact-path old))
          cli (fixture/java "-m" "freediving.extraction" root hash file)
          receipt (edn/read-string (:out cli))
          result (edn/read-string (slurp (:artifact-path receipt)))
          again (extraction/extract! root hash options)]
      (is (= 0 (:exit cli)) (:err cli))
      (is (= :unsupported-needs-parser (:status (edn/read-string old-bytes))))
      (is (= "cmas-2025-depth/1" (:parser-version result)))
      (is (= 2 (:schema-version result)))
      (is (= 1 (get-in result [:reconciliation :parsed-count])))
      (is (not= (:job-id old) (:job-id receipt)))
      (is (= :skipped (:run-status again)))
      (is (= (:artifact-sha256 receipt) (:artifact-sha256 again)))
      (is (= old-bytes (slurp (:artifact-path old)))))))
