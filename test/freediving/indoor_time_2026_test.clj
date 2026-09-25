(ns freediving.indoor-time-2026-test
  "Synthetic PDFs reproduce inspected source layouts, never real competitor data."
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [freediving.extraction :as extraction]
            [freediving.observations :as observations]
            [freediving.indoor-time-2026 :as time-parser]
            [freediving.depth-2026-test :as fixture]
            [freediving.indoor-2026-test :as distance]))

(deftest sta-preserves-source-cells-without-resolving-conflicting-units
  (let [{:keys [result]} (fixture/extract-stream
                          (str (distance/header "STA" "SENIORS - WOMEN" "13")
                               (distance/row 640 ["1" "SAMPLE Person" "AIN" "07:21" "07:20" "GOLD MEDAL"])))
        c (first (:candidates result))]
    (is (= "cmas-2026-indoor-time/2" (:parser-version result)))
    (is (= :parsed (:parse-status c)))
    (is (= ["07:21" "07:20"] (mapv #(get-in c [:parsed %]) [:realized-value :final-value])))
    (is (= {:realized-value "Realized Distance (m)" :final-value "Final Distance (m)"}
           (get-in c [:parsed :column-labels])))
    (is (every? nil? (map #(get-in c [:parsed %]) [:unit :realized-time :final-time :realized-duration :final-duration :penalty])))
    (is (some #{:sta-distance-header-conflict} (:unresolved-reasons c)))
    (is (some #{:source-semantics-unresolved} (:unresolved-reasons c)))))

(defn speed-header [discipline category day]
  (str (distance/text-at 120 750 "2026 CMAS WORLD CHAMPIONSHIP FREEDIVING INDOOR")
       (distance/text-at 120 732 (str "NOVI SAD, SERBIA JUNE, " day ", 2026"))
       (distance/text-at 120 712 discipline) (distance/text-at 210 692 category)
       (distance/text-at 370 672 "Final Result") (distance/text-at 440 672 "Notes")
       (distance/text-at 30 666 "#") (distance/text-at 70 666 "Name & surname")
       (distance/text-at 250 666 "Country") (distance/text-at 370 660 "(time)")))

(deftest speed-final-and-unlabeled-column-remain-distinct
  (doseq [[discipline day left final] [["2X50" "12" "00:36.35" "00:36.35"]
                                       ["4X50" "13" "3:23.30" "03:23.30"]
                                       ["8X50" "11" "" "07:52:05"]]]
    (let [{:keys [result]} (fixture/extract-stream
                            (str (speed-header discipline "MASTERS M1 - MEN" day)
                                 (distance/row 640 ["1" "SAMPLE Person" "AIN" left final])
                                 (distance/text-at 440 640 "GOLD MEDAL")))
          c (first (:candidates result))]
      (is (= :parsed (:parse-status c)))
      (is (= (when (seq left) left) (get-in c [:parsed :unlabeled-value])))
      (is (= final (get-in c [:parsed :final-value])))
      (is (= "Final Result (time)" (get-in c [:parsed :column-labels :final-value])))
      (is (nil? (get-in c [:parsed :realized-time])))
      (is (= (if (= discipline "8X50") [7 52 5] [(if (= discipline "4X50") 3 0) (if (= discipline "4X50") 23 36)])
             (get-in c [:parsed :final-time :components])))
      (is (some #{:time-unit-not-explicit} (:unresolved-reasons c))))))

(deftest note-glyphs-can-start-slightly-left-of-the-heading
  (let [{:keys [result]} (fixture/extract-stream
                          (str (speed-header "8X50" "SENIORS - MEN" "11")
                               (distance/row 640 ["1" "SAMPLE Person" "AIN" "" "04:10.22"])
                               (distance/text-at 439.2 640 "CONTINENTAL RECORD ASIA")))]
    (is (= 1 (get-in result [:reconciliation :parsed-count])))
    (is (= "CONTINENTAL RECORD ASIA" (get-in result [:candidates 0 :parsed :notes])))))

(deftest speed-zero-is-literal-and-does-not-erase-the-unlabeled-cell
  (let [{:keys [result]} (fixture/extract-stream
                          (str (speed-header "4X50" "JUNIORS - WOMEN" "13")
                               (distance/row 640 ["" "SAMPLE Person" "AIN" "02:41.10" "0"])
                               (distance/text-at 440 640 "DSQ SURFACING")))
        c (first (:candidates result))]
    (is (= :parsed (:parse-status c)))
    (is (= ["02:41.10" "0" "DSQ" nil nil]
           (mapv #(get-in c [:parsed %]) [:unlabeled-value :final-value :status :penalty :unit])))
    (is (= {:components [0] :fraction nil :fraction-digits 0 :notation :integer}
           (get-in c [:parsed :final-time])))))

(deftest blank-final-left-only-final-only-and-dns-never-shift-cells
  (doseq [[left final note expected]
          [["01:40.10" "" "DSQ SURFACE BO" ["01:40.10" nil "DSQ"]]
           ["" "01:40.10" "" [nil "01:40.10" nil]]
           ["" "" "DNS" [nil nil "DNS"]]
           ["" "" "DSQ NO FINISH" [nil nil "DSQ"]]]]
    (let [{:keys [result]} (fixture/extract-stream
                            (str (speed-header "4X50" "SENIORS - WOMEN" "13")
                                 (distance/row 640 ["" "SAMPLE Person" "AIN" left final])
                                 (distance/text-at 440 640 note)))
          c (first (:candidates result))]
      (is (= :parsed (:parse-status c)))
      (is (= expected (mapv #(get-in c [:parsed %]) [:unlabeled-value :final-value :status])))
      (is (every? nil? (map #(get-in c [:parsed %]) [:penalty :realized-time :realized-duration :final-duration :split-time]))))))

(deftest wrapped-notes-and-all-source-categories-retain-evidence
  (let [pages (for [[d day] [["STA" "13"] ["2X50" "12"] ["4X50" "13"] ["8X50" "11"]]
                    cat ["JUNIORS - MEN" "JUNIORS - WOMEN" "SENIORS - MEN" "SENIORS - WOMEN"
                         "MASTERS M1 - MEN" "MASTERS M1 - WOMEN" "MASTERS M2 - MEN"
                         "MASTERS M2 - WOMEN" "MASTERS M3 - MEN" "MASTERS M3 - WOMEN"]]
                (str (if (= "STA" d) (distance/header d cat day) (speed-header d cat day))
                     (distance/text-at 440 645 "GOLD MEDAL, CONTINENTAL")
                     (distance/row 640 ["1" "SAMPLE Person" "AIN" (cond (= d "STA") "05:03" (= d "8X50") "" :else "05:03.20") (if (= d "STA") "05:03" "05:03.20")])
                     (distance/text-at 440 635 "RECORD EUROPE")))
        {:keys [result]} (fixture/extract-stream pages)
        rows (:candidates result)
        positions (map (juxt :page :line) (concat (mapcat :source-lines rows) (get-in result [:reconciliation :noncandidate-lines])))]
    (is (= 40 (count rows) (get-in result [:reconciliation :parsed-count])))
    (is (every? #(= "GOLD MEDAL, CONTINENTAL RECORD EUROPE" (get-in % [:parsed :notes])) rows))
    (is (every? #(= 3 (count (:source-lines %))) rows))
    (is (= (count positions) (count (set positions)) (get-in result [:reconciliation :nonblank-line-count])))))

(deftest malformed-headings-context-and-geometric-crossings-fail-closed
  (let [h (speed-header "2X50" "SENIORS - MEN" "12")
        r (distance/row 640 ["1" "SAMPLE Person" "AIN" "00:31.47" "00:31.47"])]
    (doseq [bad [(str/replace h "time" "seconds")
                 (str/replace h "Final Result" "Realized Result")
                 (str h (distance/text-at 500 672 "Notes"))
                 (str h (distance/text-at 120 700 "STA"))
                 (str h (distance/text-at 120 700 "MASTERS M4 - MEN"))
                 (str/replace h "JUNE, 12" "JUNE, 13")
                 (str/replace h "370 672" "280 672")]]
      (let [{:keys [result]} (fixture/extract-stream (str bad r))]
        (is (zero? (get-in result [:reconciliation :parsed-count])))))
    (doseq [value ["00:31junk" "00:31.47 00:32.10"]]
      (let [{:keys [result]} (fixture/extract-stream (str h (distance/row 640 ["1" "SAMPLE Person" "AIN" value "00:31.47"])))]
        (is (zero? (get-in result [:reconciliation :parsed-count])))))
    (let [{:keys [result]} (fixture/extract-stream
                            (str h (distance/row 640 ["1" "SAMPLE Person" "AIN" "" "00:31.47"])
                                 (distance/text-at 358 640 "00:31.47")))]
      (is (zero? (get-in result [:reconciliation :parsed-count]))))))

(deftest time-pages-require-their-own-explicit-context
  (let [sta (str (distance/header "STA" "SENIORS - MEN" "13")
                 (distance/row 640 ["1" "FIRST Person" "AIN" "05:03" "05:03"]))
        sta-full (distance/header "STA" "SENIORS - MEN" "13")
        sta-orphan (str (subs sta-full (.indexOf sta-full (distance/text-at 300 672 "Realized")))
                        (distance/row 640 ["2" "SECOND Person" "AIN" "05:03" "05:03"]))
        full (speed-header "2X50" "SENIORS - MEN" "12")
        orphan (str (subs full (.indexOf full (distance/text-at 370 672 "Final Result")))
                    (distance/row 640 ["2" "SECOND Person" "AIN" "05:03" "05:03"]))]
    (doseq [pages [[orphan] [sta-orphan] [sta sta-orphan] [sta orphan] [sta (distance/text-at 120 700 "OTHER SECTION") orphan]]]
      (let [{:keys [result]} (fixture/extract-stream pages)]
        (is (= (if (= 1 (count pages)) 0 1) (or (get-in result [:reconciliation :parsed-count]) 0)))))))

(deftest time-source-replay-rejects-rehashed-fields-labels-geometry-and-identity
  (let [stream (str (speed-header "2X50" "JUNIORS - WOMEN" "12")
                    (distance/row 640 ["1" "SAMPLE Person" "AIN" "00:40.00" "00:40.00"]))
        {:keys [root receipt result]} (fixture/extract-stream stream)]
    (is (= result (extraction/validate-geometry-artifact! root result)))
    (doseq [changed [(assoc-in result [:candidates 0 :parsed :final-value] "99:99")
                     (update-in result [:candidates 0 :unresolved-reasons] #(filterv (complement #{:source-semantics-unresolved}) %))
                     (-> result
                         (update-in [:candidates 0 :unresolved-reasons] #(filterv (complement #{:source-semantics-unresolved}) %))
                         (update-in [:publication :reasons] conj :source-semantics-unresolved))
                     (update-in result [:candidates 0 :unresolved-reasons] conj :unknown-error)
                     (assoc-in result [:candidates 0 :coordinates :page] 2)
                     (assoc-in result [:candidates 0 :parsed :event-date] "2026-06-13")
                     (assoc result :parser-version "cmas-2026-indoor-time/1")
                     (assoc-in result [:candidates 0 :parsed :unit] "seconds")
                     (assoc-in result [:candidates 0 :parsed :column-labels :unlabeled-value] "Realized time")
                     (assoc-in result [:candidates 0 :raw :fields :final-value] "99:99")
                     (assoc-in result [:candidates 0 :geometry-evidence 0 :x-min] 0.0)
                     (assoc-in result [:candidates 0 :metadata-evidence 0 :text] "invented")
                     (assoc result :geometry-xml "")
                     (assoc result :parser-version "cmas-2026-indoor-distance/1")
                     (-> (walk/postwalk #(if (string? %) (str/replace % "2026 CMAS WORLD CHAMPIONSHIP FREEDIVING INDOOR" "Invented event") %) result)
                         (assoc :parser-version "cmas-cwt-men/1" :schema-version 1)
                         (dissoc :geometry-xml) (update :tool dissoc :geometry-arguments))]]
      (let [job (fixture/sha (pr-str (fixture/canonical (select-keys changed observations/identity-keys))))
            encoded (pr-str (assoc changed :job-id job)) hash (fixture/sha encoded)]
        (spit (str root "/derived-objects/" hash) encoded)
        (spit (str root "/derivations/" job ".edn") (pr-str (assoc receipt :job-id job :artifact-sha256 hash)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source replay|geometry extraction contract|Invalid candidate page"
                              (observations/import! nil root job)))))))

(deftest new-time-jobs-are-idempotent-and-old-distance-jobs-replay-unchanged
  (let [streams [(str (distance/header) (distance/row 640 ["1" "FIRST Person" "AIN" "100" "100"]))
                 (str (distance/header "STA" "SENIORS - MEN" "13")
                      (distance/row 640 ["1" "SECOND Person" "AIN" "05:03" "05:03"]))]
        {:keys [root hash options receipt]} (fixture/extract-stream streams)
        old (with-redefs [time-parser/supported? (constantly false)] (extraction/extract! root hash options))
        bytes (slurp (:artifact-path old))
        historical (edn/read-string bytes)
        again (extraction/extract! root hash options)]
    (is (= :skipped (:run-status again)))
    (is (= (:artifact-sha256 receipt) (:artifact-sha256 again)))
    (is (not= (:job-id receipt) (:job-id old)))
    (is (= bytes (slurp (:artifact-path old))))
    (is (= historical (extraction/validate-geometry-artifact! root historical)))
    (is (= (first (:candidates historical)) (first (:candidates (edn/read-string (slurp (:artifact-path receipt)))))))))

(deftest historical-time-jobs-replay-with-global-blocker-and-distinct-identity
  (let [streams [(str (distance/header) (distance/row 640 ["1" "DISTANCE Person" "AIN" "100" "100"]))
                 (str (speed-header "8X50" "SENIORS - MEN" "11")
                      (distance/row 640 ["1" "TIMING Person" "AIN" "" "07:52:05"]))]
        {:keys [root hash options receipt result]} (fixture/extract-stream streams)
        old (with-redefs [time-parser/parser-version time-parser/legacy-parser-version
                          time-parser/parse-pages-with-geometry time-parser/parse-legacy-pages-with-geometry]
              (extraction/extract! root hash options))
        bytes (slurp (:artifact-path old))
        historical (edn/read-string bytes)]
    (is (= "cmas-2026-indoor-time/1" (:parser-version historical)))
    (is (not= (:job-id receipt) (:job-id old)))
    (is (= historical (extraction/validate-geometry-artifact! root historical)))
    (is (some #{:source-semantics-unresolved} (get-in historical [:publication :reasons])))
    (is (not-any? #{:source-semantics-unresolved} (get-in historical [:candidates 1 :unresolved-reasons])))
    (is (= (first (:candidates historical)) (first (:candidates result))))
    (is (= (get-in historical [:candidates 1])
           (get-in (update-in result [:candidates 1 :unresolved-reasons] #(filterv (complement #{:source-semantics-unresolved}) %)) [:candidates 1])))
    (is (= (:pages historical) (:pages result)))
    (is (= (:reconciliation historical) (:reconciliation result)))
    (is (= :skipped (:run-status (extraction/extract! root hash options))))
    (is (= bytes (slurp (:artifact-path old))))))

(deftest mixed-malformed-timing-sections-never-inherit-distance-readiness
  (let [distance-page (str (distance/header) (distance/row 640 ["1" "DISTANCE Person" "AIN" "100" "100"]))
        timing-page (str (speed-header "4X50" "SENIORS - MEN" "13")
                         (distance/row 640 ["1" "TIMING Person" "AIN" "bad-token" "03:20.00"]))]
    (doseq [pages [[distance-page timing-page] [timing-page distance-page]]]
      (let [{:keys [result]} (fixture/extract-stream pages)
            rows (:candidates result)
            distance-row (first (filter #(= "DISTANCE Person" (get-in % [:parsed :source-name])) rows))
            malformed (filter #(= :unparsed (:parse-status %)) rows)]
        (is (= 1 (get-in result [:reconciliation :parsed-count])))
        (is (seq malformed))
        (is (not-any? #{:source-semantics-unresolved} (:unresolved-reasons distance-row)))
        (is (every? #(some #{:source-semantics-unresolved} (:unresolved-reasons %)) malformed))))))
