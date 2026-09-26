(ns freediving.vertical-blue-2025
  "Source-bound rows in the nine day Vertical Blue 2025 CMAS result PDF."
  (:require [clojure.string :as str]))

(def source-sha256 "7b8b9b51b3adb1dcd87559e0366758f4d477275b96cdf7ab484bf57c2dff88f6")
(def parser-version "cmas-vertical-blue-2025/2")
(def ^:private expected-counts [7 7 15 6 10 9 12 4 9])
(def ^:private expected-days [1 2 3 4 5 6 7 8 9])

(defn- date-on-page [text]
  (when-let [[_ _ day] (re-find #"DAY (\d+) - July (\d+),? 2025" text)]
    (format "2025-07-%02d" (parse-long day))))

(defn supported? [pages]
  (and (= 9 (count pages))
       (every? true?
               (map (fn [page day]
                      (and (str/includes? page "VERTICAL BLUE 2025")
                           (str/includes? page "RESULTS")
                           (str/includes? page (str "DAY " day " - July "))
                           (some? (date-on-page page))))
                    pages expected-days))))

(defn- source-lines [page text]
  (mapv (fn [i s] {:page page :line (inc i) :text s})
        (range) (str/split text #"\n" -1)))

(defn- core-line? [text]
  (boolean (re-find #"\s+[FM]\s{2,}.*?\bSeniors?\s+(?:CWT-BF|CWT|CNF|FIM)\s+\d+" text)))

(defn- left-fragment [line]
  (let [text (:text line) start (count (re-find #"^\s*" text))
        fragment (-> text str/trim (str/split #"\s{2,}") first)]
    (when (and (< start 25)
               (< (count fragment) 45)
               (re-matches #"[\p{L}][\p{L} .'-]*" fragment))
      fragment)))

(defn- country-fragment [line]
  (let [text (:text line) start (count (re-find #"^\s*" text))]
    (when (and (<= 25 start 45)
               (re-matches #"[\p{L}][\p{L} ]*"
                           (str/trim (subs text start (min (count text) 65)))))
      (str/trim (subs text start (min (count text) 65))))))

(defn- right-fragment [line]
  (let [text (:text line) start (count (re-find #"^\s*" text))]
    (when (>= start 105) (str/trim text))))

(defn- parse-core [line extra-before extra-after date]
  (let [parts (str/split (str/trim (:text line)) #"\s{2,}")
        ;; The gender column is present even when the name is printed on surrounding lines.
        gender-index (first (keep-indexed (fn [i v] (when (#{"M" "F"} v) i)) parts))
        name (when (= 1 gender-index) (first parts))
        gender (when gender-index (nth parts gender-index))
        remaining (when gender-index (subvec (vec parts) (inc gender-index)))
        category-index (first (keep-indexed (fn [i v] (when (#{"Senior" "Seniors"} v) i)) remaining))
        country (when (= 1 category-index) (first remaining))
        category (when category-index (nth remaining category-index))
        discipline (when category-index (nth remaining (inc category-index) nil))
        value-parts (when category-index (subvec remaining (+ category-index 2)))
        tokens (some->> value-parts (str/join " ") (re-seq #"\S+") vec)
        [declared first-after] tokens
        reached (when (re-matches #"(?:\d+|DNS)" (or first-after "")) first-after)
        after-reached (if reached (vec (drop 2 tokens)) (vec (drop 1 tokens)))
        [time-or-card card-or-final & tail] after-reached
        time? (boolean (re-matches #"\d:\d{2}:\d{2}" (or time-or-card "")))
        dive-time (when time? time-or-card)
        card (if time? card-or-final time-or-card)
        after-card (if time? tail (cons card-or-final tail))
        penalty (when (and (= "Y" card) (re-matches #"\d+" (or (first after-card) ""))) (first after-card))
        after-penalty (if penalty (rest after-card) after-card)
        final-value (first after-penalty)
        inline-reason (str/join " " (rest after-penalty))
        wrapped-reason (when (and (= "R" card) (str/blank? inline-reason))
                         (str/join " " (keep right-fragment (concat extra-before extra-after))))
        reason (str/trim (str inline-reason " " wrapped-reason))
        name-parts (concat (keep left-fragment extra-before) (when name [name])
                           (keep left-fragment extra-after))
        raw-source-name (str/join " " name-parts)
        source-name (str/replace raw-source-name #"- " "-")
        country-parts (if country [country]
                          (concat (keep country-fragment extra-before)
                                  (keep country-fragment extra-after)))
        representation (when (seq country-parts) (str/join " " country-parts))
        raw {:source-name raw-source-name :gender gender :representation representation
             :category category :discipline discipline :depth-declared declared
             :depth-reached reached :dive-time dive-time :card card
             :penalties penalty :final-performance final-value :reason reason}
        valid? (and (not (str/blank? source-name)) gender
                    (#{"Senior" "Seniors"} category)
                    (#{"CWT-BF" "CWT" "CNF" "FIM"} discipline)
                    (re-matches #"\d+" (or declared ""))
                    (or (nil? reached) (re-matches #"(?:\d+|DNS)" reached))
                    (#{"W" "Y" "R" "DNS"} card)
                    (re-matches #"\d+" (or final-value ""))
                    (or (not= "Y" card) penalty)
                    (or (= "DNS" card) dive-time))]
    {:raw raw
     :parsed (when valid?
               {:federation "CMAS" :event-date date :source-name source-name
                :gender gender :representation representation :category category
                :discipline discipline :depth-declared (bigdec declared)
                :depth-reached (when (and reached (not= "DNS" reached)) (bigdec reached))
                :dive-time dive-time :card card :penalties (some-> penalty bigdec)
                :final-performance (bigdec final-value)
                :status (cond (= "DNS" card) "DNS" (= "R" card) reason
                              :else nil)
                :reason (when-not (str/blank? reason) reason)
                :unit nil})}))

(defn- row-candidate [page line before after date]
  (let [{:keys [raw parsed]} (parse-core line before after date)
        lines (vec (concat before [line] after))]
    {:coordinates {:page page :line (or (:line (first before)) (:line line))
                   :column-start 1 :column-end (inc (count (:text line)))}
     :source-lines lines :raw {:line (:text line) :lines (mapv :text lines) :fields raw}
     :parse-status (if parsed :parsed :unparsed) :parsed parsed
     :fields (into {} (map (fn [[k v]]
                             [k {:status (if (nil? v) :unknown :parsed) :value v}]) parsed))
     :review-status :unreviewed
     :unresolved-reasons (cond-> [:owner-review-required]
                           (nil? parsed) (conj :unparsed-source-line))}))

(defn- page-info [page text]
  (let [lines (source-lines page text)
        body (->> lines (drop-while #(not (str/includes? (:text %) "Perfomance"))) rest
                  (take-while #(not (str/includes? (:text %) "Diego Rodriguez"))) vec)
        core-indexes (keep-indexed (fn [i line] (when (core-line? (:text line)) i)) body)
        date (date-on-page text)
        footer-text (last (re-seq #"July \d+ 2025 \d{2}:\d{2}" text))
        footer-date (when-let [[_ day] (and footer-text (re-find #"July (\d+) 2025" footer-text))]
                      (format "2025-07-%02d" (parse-long day)))
        candidates
        (:candidates
         (reduce (fn [{:keys [candidates consumed]} i]
                   (let [previous (last (filter #(< % i) core-indexes))
                         next-one (first (filter #(> % i) core-indexes))
                         prior (subvec body (if previous (inc previous) 0) i)
                         later (subvec body (inc i) (or next-one (count body)))
                         free-prior (remove #(contains? consumed (:line %)) prior)
                         name-before (->> free-prior reverse
                                          (take-while left-fragment) reverse vec)
                         split-core (str/split (str/trim (:text (body i))) #"\s{2,}")
                         missing-name? (#{"M" "F"} (first split-core))
                         gender-index (if missing-name? 0 1)
                         missing-country? (#{"Senior" "Seniors"}
                                           (nth split-core (inc gender-index) nil))
                         red-card? (boolean (re-find #"\sR\s+" (:text (body i))))
                         name-after (when (or missing-name? (seq name-before))
                                      (->> later (take-while left-fragment) vec))
                         auxiliary (->> (concat prior later)
                                        (filter #(or (and missing-country? (country-fragment %))
                                                     (and red-card? (right-fragment %)))) vec)
                         before (vec (distinct (concat name-before
                                                       (filter #(and (< (:line %) (:line (body i)))
                                                                     (not (contains? consumed (:line %)))) auxiliary))))
                         after (vec (distinct (concat name-after
                                                      (filter #(> (:line %) (:line (body i))) auxiliary))))
                         candidate (row-candidate page (body i) before after date)]
                     {:candidates (conj candidates candidate)
                      :consumed (into consumed (map :line name-after))}))
                 {:candidates [] :consumed #{}} core-indexes))
        candidate-lines (set (mapcat #(map :line (:source-lines %)) candidates))
        nonblank (vec (remove #(str/blank? (:text %)) lines))
        classification (if (and date (str/includes? text "VERTICAL BLUE 2025"))
                         :attempt-results :unsupported-page)]
    {:page {:page page :text text :lines lines :status (if (seq candidates) :needs-review classification)}
     :candidates candidates
     :reconciliation {:page page :classification classification :event-date date
                      :footer-text footer-text :date-conflict? (and footer-date (not= footer-date date))
                      :candidate-count (count candidates)
                      :parsed-count (count (filter #(= :parsed (:parse-status %)) candidates))
                      :unparsed-count (count (filter #(= :unparsed (:parse-status %)) candidates))
                      :nonblank-line-count (count nonblank)}
     :noncandidate-lines (mapv #(assoc % :classification classification)
                               (remove #(contains? candidate-lines (:line %)) nonblank))}))

(defn parse-pages [pages]
  (let [processed (mapv (fn [i text] (page-info (inc i) text)) (range) pages)
        per-page (mapv :reconciliation processed)
        candidates (vec (mapcat :candidates processed))
        unparsed (count (filter #(= :unparsed (:parse-status %)) candidates))
        complete? (and (supported? pages) (= expected-counts (mapv :candidate-count per-page))
                       (zero? unparsed)
                       (every? #(= :attempt-results (:classification %)) per-page))]
    {:parser-version parser-version :schema-version 3
     :status (if complete? :needs-review :partial-unsupported-needs-parser)
     :pages (mapv :page processed) :candidates candidates
     :reconciliation {:page-count (count pages) :coverage (if complete? :complete :partial)
                      :candidate-count (count candidates) :parsed-count (- (count candidates) unparsed)
                      :unparsed-count unparsed :unresolved-count (count candidates)
                      :per-page per-page :nonblank-line-count (reduce + (map :nonblank-line-count per-page))
                      :noncandidate-lines (vec (mapcat :noncandidate-lines processed))
                      :status :unreviewed}
     :publication {:status :blocked :reasons [:owner-review-required :reconciliation-unreviewed]}}))
