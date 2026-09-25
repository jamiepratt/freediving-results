(ns freediving.indoor-2026-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [freediving.depth-2026-test :as fixture]
            [clojure.edn :as edn]
            [clojure.walk :as walk]
            [freediving.extraction :as extraction]
            [freediving.indoor-2026 :as indoor]
            [freediving.observations :as observations]))
(defn text-at [x y s] (fixture/text-at x y (str/replace s #"([()])" "\\\\$1")))
(defn header
  ([] (header "DNF" "JUNIORS - MEN" "11"))
  ([discipline category day]
   (str (text-at 120 750 "2026 CMAS WORLD CHAMPIONSHIP FREEDIVING INDOOR")
        (text-at 120 732 (str "NOVI SAD, SERBIA     JUNE, " day ", 2026"))
        (text-at 120 712 discipline) (text-at 210 692 category)
        (text-at 300 672 "Realized") (text-at 370 672 "Final") (text-at 440 672 "Notes")
        (text-at 30 666 "#") (text-at 70 666 "Name & surname") (text-at 250 666 "Country")
        (text-at 300 660 "Distance (m)") (text-at 370 660 "Distance (m)"))))
(defn row [y values]
  (apply str (map #(text-at %1 y %2) [30 70 250 307 377 430] values)))
(deftest indoor-distance-extraction-preserves-explicit-columns
  (let [{:keys [result]} (fixture/extract-stream (str (header) (row 640 ["1" "SAMPLE Person" "AIN" "113,5" "113,5" "GOLD MEDAL"])))
        c (first (:candidates result))]
    (is (= "cmas-2026-indoor-distance/1" (:parser-version result)))
    (is (= :parsed (:parse-status c)))
    (is (= [113.5M 113.5M] (mapv #(get-in c [:parsed %]) [:realized-distance :final-distance])))
    (is (= ["DNF" "JUNIORS - MEN" "2026-06-11" "m" "GOLD MEDAL"]
           (mapv #(get-in c [:parsed %]) [:discipline :category :event-date :unit :notes])))))

(deftest notes-wrapped-above-and-below-belong-to-the-geometric-row
  (let [{:keys [result]} (fixture/extract-stream
                          (str (header "DYN" "MASTERS M2 - WOMEN" "14")
                               (text-at 430 645 "GOLD MEDAL, CONTINENTAL")
                               (row 640 ["1" "SAMPLE Person" "AIN" "196,5" "196,0"])
                               (text-at 430 635 "RECORD EUROPE")
                               (row 612 ["2" "OTHER Person" "AIN" "178.5" "178.5" "SILVER MEDAL"])))]
    (is (= 2 (get-in result [:reconciliation :parsed-count])))
    (is (= "GOLD MEDAL, CONTINENTAL RECORD EUROPE" (get-in result [:candidates 0 :parsed :notes])))
    (is (= 3 (count (get-in result [:candidates 0 :source-lines]))))
    (is (= [196.5M 196M nil] (mapv #(get-in result [:candidates 0 :parsed %]) [:realized-distance :final-distance :penalty])))))

(deftest repeated-distance-headers-continue-only-an-immediately-valid-section
  (let [h (header "DYN-BF" "SENIORS - MEN" "12")
        continuation (subs h (.indexOf h (text-at 300 672 "Realized")))
        {:keys [result]} (fixture/extract-stream [(str h (row 640 ["1" "FIRST Person" "AIN" "200" "200"]))
                                                  (str continuation (row 640 ["2" "SECOND Person" "AIN" "0,0" "0,0"]))])]
    (is (= 2 (get-in result [:reconciliation :parsed-count])))
    (is (= ["DYN-BF" "SENIORS - MEN" "2026-06-12"]
           (mapv #(get-in result [:candidates 1 :parsed %]) [:discipline :category :event-date])))
    (is (some #(= 1 (:page %)) (get-in result [:candidates 1 :metadata-evidence])))))

(deftest right-aligned-distances-can-extend-past-the-unit-label
  (let [{:keys [result]} (fixture/extract-stream (str (header)
                                                      (row 640 ["1" "SAMPLE Person" "AIN" "113,5"])
                                                      (text-at 403 640 "113,5")
                                                      (text-at 440 640 "GOLD MEDAL")))]
    (is (= 1 (get-in result [:reconciliation :parsed-count])))))

(deftest small-baseline-offsets-and-three-letter-names-do-not-shift-country
  (let [{:keys [result]} (fixture/extract-stream
                          (str (header)
                               (text-at 30 638 "1") (text-at 70 638 "SAMPLE XYZ")
                               (text-at 250 640 "AIN") (text-at 307 640 "150,0") (text-at 377 640 "149,5")
                               (text-at 430 640 "GOLD MEDAL, CONTINENTAL")
                               (text-at 430 631 "RECORD EUROPE")))]
    (is (= 1 (get-in result [:reconciliation :parsed-count])))
    (is (= "SAMPLE XYZ" (get-in result [:candidates 0 :parsed :source-name])))
    (is (= "AIN" (get-in result [:candidates 0 :parsed :representation])))
    (is (= "GOLD MEDAL, CONTINENTAL RECORD EUROPE" (get-in result [:candidates 0 :parsed :notes])))))

(deftest contradictory-or-malformed-headings-invalidate-the-page
  (doseq [extra ["STA" "DNF junk" "MASTERS M4 - MEN" "NOVI SAD, SERBIA JUNE, 99, 2026" "2026 CMAS OTHER"]]
    (let [{:keys [result]} (fixture/extract-stream (str (header) (text-at 120 700 extra)
                                                        (row 640 ["1" "SAMPLE Person" "AIN" "100" "100"])))]
      (is (zero? (get-in result [:reconciliation :parsed-count])) extra))))

(deftest malformed-column-headings-fail-closed
  (doseq [h [(str/replace (header) "(Notes)" "(Other)")
             (str (header) (text-at 500 672 "Notes"))
             (-> (header) (str/replace "Realized" "TEMP") (str/replace "Final" "Realized") (str/replace "TEMP" "Final"))]]
    (let [{:keys [result]} (fixture/extract-stream (str h (row 640 ["1" "SAMPLE Person" "AIN" "113" "112"])))]
      (is (zero? (get-in result [:reconciliation :parsed-count]))))))

(deftest rehashed-identity-downgrades-cannot-bypass-source-replay
  (let [{:keys [root receipt result]} (fixture/extract-stream (str (header) (row 640 ["1" "SAMPLE Person" "AIN" "113" "112"])))]
    (doseq [[parser schema] [["cmas-novi-sad-dnf-juniors/1" 3] ["cmas-cwt-men/1" 1]]]
      (let [changed (-> result (assoc :parser-version parser :schema-version schema)
                        (dissoc :geometry-xml) (update :tool dissoc :geometry-arguments))
            job (fixture/sha (pr-str (fixture/canonical (select-keys changed observations/identity-keys))))
            encoded (pr-str (assoc changed :job-id job)) hash (fixture/sha encoded)]
        (spit (str root "/derived-objects/" hash) encoded)
        (spit (str root "/derivations/" job ".edn") (pr-str (assoc receipt :job-id job :artifact-sha256 hash)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source replay|geometry extraction contract"
                              (observations/import! nil root job)))))))

(deftest blank-cells-zero-dsq-and-dns-stay-explicit
  (doseq [[values expected] [[["1" "ZERO Person" "AIN" "0,0" "0,0"] [0M 0M nil]]
                             [["" "DSQ Person" "AIN" "72,5" "" "DSQ SURFACE BO"] [72.5M nil "DSQ"]]
                             [["" "DNS Person" "AIN" "" "" "DNS"] [nil nil "DNS"]]
                             [["1" "BLANK Person" "AIN" "" "80,0"] [nil 80M nil]]]]
    (let [{:keys [result]} (fixture/extract-stream (str (header) (row 640 values))) c (first (:candidates result))]
      (is (= :parsed (:parse-status c)))
      (is (= expected (mapv #(get-in c [:parsed %]) [:realized-distance :final-distance :status])))
      (is (= :unknown (get-in c [:fields :penalty :status])))
      (is (= :unknown (get-in c [:fields :announced-distance :status])))
      (is (= :unknown (get-in c [:fields :card :status]))))))

(deftest source-replay-rejects-rehashed-fields-and-geometry-before-import
  (let [{:keys [root receipt result]} (fixture/extract-stream (str (header) (row 640 ["1" "SAMPLE Person" "AIN" "113" "112"])))]
    (is (= result (extraction/validate-geometry-artifact! root result)))
    (doseq [changed [(assoc-in result [:candidates 0 :parsed :final-distance] 999M)
                     (assoc-in result [:candidates 0 :raw :fields :notes] "fabricated")
                     (assoc-in result [:candidates 0 :geometry-evidence 0 :x-min] 0.0)
                     (assoc-in result [:candidates 0 :metadata-evidence 0 :text] "fabricated")
                     (assoc result :geometry-xml "")]]
      (let [encoded (pr-str changed) hash (fixture/sha encoded) job (:job-id receipt)]
        (spit (str root "/derived-objects/" hash) encoded)
        (spit (str root "/derivations/" job ".edn") (pr-str (assoc receipt :artifact-sha256 hash)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source replay" (observations/import! nil root job)))))))

(deftest old-parser-jobs-remain-recoverable-and-new-extraction-is-idempotent
  (let [{:keys [root hash options receipt result]} (fixture/extract-stream (str (header) (row 640 ["1" "SAMPLE Person" "AIN" "113" "112"])))
        old (with-redefs [indoor/supported? (constantly false)] (extraction/extract! root hash options))
        old-bytes (slurp (:artifact-path old))
        again (extraction/extract! root hash options)
        revised (with-redefs [indoor/parser-version "cmas-2026-indoor-distance/test-next"] (extraction/extract! root hash options))]
    (is (= 2 (:schema-version result)))
    (is (= :skipped (:run-status again)))
    (is (= (:artifact-sha256 receipt) (:artifact-sha256 again)))
    (is (= 3 (count (set (map :job-id [receipt old revised])))))
    (is (= old-bytes (slurp (:artifact-path old))))
    (is (= "cmas-novi-sad-dnf-juniors/1" (:parser-version (edn/read-string old-bytes))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Database URL required" (observations/import! nil root (:job-id old))))))

(deftest continuation-rejects-orphan-conflicting-and-intervening-sections
  (let [h (header "DYN" "SENIORS - MEN" "14")
        continuation (subs h (.indexOf h (text-at 300 672 "Realized")))
        first-page (str h (row 640 ["1" "FIRST Person" "AIN" "200" "200"]))
        next-page (str continuation (row 640 ["2" "SECOND Person" "AIN" "190" "190"]))]
    (doseq [pages [[next-page]
                   [first-page (str next-page (text-at 120 700 "STA"))]
                   [first-page (text-at 120 700 "STA") next-page]
                   [first-page (str/replace next-page "(Notes)" "(Other)")]
                   [first-page (str next-page (text-at 120 700 "MASTERS M4 - MEN"))]]]
      (let [{:keys [result]} (fixture/extract-stream pages)]
        (is (= (if (= 1 (count pages)) 0 1) (or (get-in result [:reconciliation :parsed-count]) 0)))))))

(deftest all-distance-categories-and-source-line-accounting
  (let [pages (for [[d day] [["DNF" "11"] ["DYN-BF" "12"] ["DYN" "14"]]
                    category ["JUNIORS - WOMEN" "SENIORS - MEN" "MASTERS M1 - MEN" "MASTERS M2 - WOMEN" "MASTERS M3 - WOMEN"]]
                (str (header d category day) (row 640 ["1" "SAMPLE Person" "AIN" "100" "100"])))
        {:keys [result]} (fixture/extract-stream pages)
        source-lines (mapcat :source-lines (:candidates result))
        metadata (get-in result [:reconciliation :noncandidate-lines])
        positions (map (juxt :page :line) (concat source-lines metadata))]
    (is (= 15 (get-in result [:reconciliation :parsed-count])))
    (is (= (count positions) (count (set positions)) (get-in result [:reconciliation :nonblank-line-count])))))

(deftest rewriting-all-page-titles-cannot-hide-the-archived-parser-family
  (let [{:keys [root receipt result]} (fixture/extract-stream (str (header) (row 640 ["1" "SAMPLE Person" "AIN" "113" "112"])))
        changed (-> (walk/postwalk #(if (string? %) (str/replace % "2026 CMAS WORLD CHAMPIONSHIP FREEDIVING INDOOR" "Fabricated event") %) result)
                    (assoc :parser-version "cmas-cwt-men/1" :schema-version 1)
                    (dissoc :geometry-xml) (update :tool dissoc :geometry-arguments)
                    (assoc-in [:candidates 0 :parsed :final-distance] 999M))
        job (fixture/sha (pr-str (fixture/canonical (select-keys changed observations/identity-keys))))
        encoded (pr-str (assoc changed :job-id job)) hash (fixture/sha encoded)]
    (spit (str root "/derived-objects/" hash) encoded)
    (spit (str root "/derivations/" job ".edn") (pr-str (assoc receipt :job-id job :artifact-sha256 hash)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"geometry extraction contract"
                          (observations/import! nil root job)))))
