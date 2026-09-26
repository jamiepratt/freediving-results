(ns freediving.deep-dominica-2026
  "Printed positions in the official Deep Dominica 2026 results PDF. The PDF
  prints month and day only; the 2026 year comes from the official source index."
  (:require [clojure.string :as str]))

(def source-sha256 "1ee31123009acfa319a790debc7ecf76f9bf8c16fc53ee99486beb37af21912a")
(def parser-version "cmas-deep-dominica-2026/1")
(def ^:private expected-counts [0 0 5 5 5 5 5 5 0])
(def ^:private expected-dates {3 "July 28" 4 "July 29" 5 "July 29"
                               6 "July 31" 7 "July 29" 8 "July 31"})

(defn- source-lines [page text]
  (mapv (fn [i s] {:page page :line (inc i) :text s})
        (range) (str/split text #"\n" -1)))

(defn- printed-date [text]
  (some-> (re-find #"(?m)^\s*(July (?:28|29|31))\s*$" text) second))

(defn- result-page? [page text]
  (and (= (expected-dates page) (printed-date text))
       (str/includes? text "Official Results")
       (boolean (re-find #"(?m)^\s*Name\s+Country\s+Discipline\s+DD\s+RD\s+Final Results\s+Notes\s*$" text))
       (str/includes? text "Supported By")))

(defn supported? [pages]
  (and (= 9 (count pages))
       (every? (fn [page]
                 (if (expected-dates page)
                   (result-page? page (nth pages (dec page)))
                   (str/blank? (nth pages (dec page)))))
               (range 1 10))))

(defn- table-lines [lines]
  (->> lines
       (drop-while #(not (re-find #"^\s*Name\s+Country\s+Discipline\b" (:text %))))
       rest
       (take-while #(not (str/includes? (:text %) "Supported By")))
       (remove #(str/blank? (:text %)))
       vec))

(defn- parse-row [text date]
  (let [parts (str/split (str/trim text) #"\s{2,}")
        [name discipline declared reached final-result card & tail] parts
        dns? (= reached "DNS")
        notes (when (seq tail) (str/join "  " tail))
        raw {:printed-date date :source-name name :representation nil
             :discipline discipline :declared-depth declared :reached-depth reached
             :final-result final-result :card card :notes notes}
        valid? (and (not (str/blank? name))
                    (#{"CNF" "CWT" "CWTBF" "FIM"} discipline)
                    (re-matches #"\d+" (or declared ""))
                    (if dns?
                      (= 4 (count parts))
                      (and (re-matches #"\d+" (or reached ""))
                           (re-matches #"\d+" (or final-result ""))
                           (#{"White" "Yellow" "Red"} card))))
        event-date (case date "July 28" "2026-07-28" "July 29" "2026-07-29"
                         "July 31" "2026-07-31" nil)]
    {:raw raw
     :parsed (when (and valid? event-date)
               {:federation "CMAS" :event-date event-date :source-name name
                :representation nil :gender nil :category nil :discipline discipline
                :declared-depth (bigdec declared)
                :reached-depth (when-not dns? (bigdec reached))
                :final-result (when-not dns? (bigdec final-result))
                :card (when-not dns? card) :status (when dns? "DNS")
                :notes notes :unit nil})}))

(defn- candidate [line date repeat-of metadata]
  (let [{:keys [raw parsed]} (parse-row (:text line) date)]
    {:coordinates {:page (:page line) :line (:line line)
                   :column-start 1 :column-end (inc (count (:text line)))}
     :source-lines [line] :metadata-evidence metadata
     :raw {:line (:text line) :fields raw}
     :parse-status (if parsed :parsed :unparsed) :parsed parsed
     :fields (into {} (map (fn [[k v]]
                             [k {:status (if (nil? v) :unknown :parsed) :value v}]) parsed))
     :review-status :unreviewed
     :repeated-page-of repeat-of
     :unresolved-reasons (cond-> [:owner-review-required :year-not-printed-in-pdf :unit-not-explicit]
                           repeat-of (conj :repeated-table-source-position)
                           (nil? parsed) (conj :unparsed-source-line))}))

(defn parse-pages [pages]
  (let [repeated-pages (into {} (for [page (range 1 (inc (count pages)))
                                      :let [text (nth pages (dec page))
                                            prior (first (filter #(and (not (str/blank? text))
                                                                       (= text (nth pages (dec %))))
                                                                 (range 1 page)))]
                                      :when prior]
                                  [page prior]))
        processed (mapv (fn [page text]
                          (let [lines (source-lines page text)
                                date (printed-date text)
                                table (table-lines lines)
                                metadata (filterv #(or (str/includes? (:text %) "Official Results")
                                                       (str/includes? (:text %) "Name       Country")
                                                       (= date (str/trim (:text %)))) lines)
                                rows (mapv #(candidate % date (repeated-pages page) metadata) table)
                                candidate-lines (set (map #(get-in % [:coordinates :line]) rows))]
                            {:page {:page page :text text :lines lines
                                    :status (if (str/blank? text) :blank :needs-review)}
                             :candidates rows
                             :per-page {:page page
                                        :classification (if (str/blank? text) :blank :attempt-results)
                                        :printed-date date :repeated-page-of (repeated-pages page)
                                        :candidate-count (count rows)
                                        :parsed-count (count (filter #(= :parsed (:parse-status %)) rows))
                                        :unparsed-count (count (filter #(= :unparsed (:parse-status %)) rows))
                                        :nonblank-line-count (count (remove #(str/blank? (:text %)) lines))}
                             :noncandidate-lines (vec (remove #(or (str/blank? (:text %))
                                                                   (candidate-lines (:line %))) lines))}))
                        (range 1 (inc (count pages))) pages)
        per-page (mapv :per-page processed)
        candidates (vec (mapcat :candidates processed))
        unparsed (count (filter #(= :unparsed (:parse-status %)) candidates))
        complete? (and (supported? pages)
                       (= expected-counts (mapv :candidate-count per-page))
                       (= {5 4, 7 4, 8 6} repeated-pages)
                       (zero? unparsed))]
    {:parser-version parser-version :schema-version 3
     :status (if complete? :needs-review :partial-unsupported-needs-parser)
     :pages (mapv :page processed) :candidates candidates
     :reconciliation {:page-count (count pages) :coverage (if complete? :complete :partial)
                      :candidate-count (count candidates)
                      :parsed-count (- (count candidates) unparsed)
                      :unparsed-count unparsed :unresolved-count (count candidates)
                      :per-page per-page :repeated-pages repeated-pages
                      :nonblank-line-count (reduce + (map :nonblank-line-count per-page))
                      :noncandidate-lines (vec (mapcat :noncandidate-lines processed))
                      :status :unreviewed}
     :publication {:status :blocked
                   :reasons [:owner-review-required :reconciliation-unreviewed
                             :year-not-printed-in-pdf :unit-not-explicit]}}))
