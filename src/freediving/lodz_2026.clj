(ns freediving.lodz-2026
  "Source-bound printed results in the March 2026 Łódź Indoor World Cup PDF."
  (:require [clojure.string :as str]))

(def source-sha256 "8e8e8eb1c88e9b9ceb57a0e598a20897307790c0fc02e23c57593af9307ec623")
(def parser-version "cmas-lodz-indoor-world-cup-2026/1")
(def title "CMAS World Cup Indoor Series - Łódź, Poland 2026")

(defn supported? [pages]
  (boolean (some #(and (str/includes? % title)
                       (str/includes? % "Athlete name")
                       (re-find #"\b(?:13|14|15)-03-2026\b" %)) pages)))

(defn- heading [line]
  (when-let [[_ day weekday gender discipline category]
             (re-matches #"\s*(13|14|15)-03-2026 - (Friday|Saturday|Sunday) - (Men|Women) - (DNF|DYNB|DYN|STA) - (Senior|Para Freediving)\s*" line)]
    (when (= weekday ({"13" "Friday" "14" "Saturday" "15" "Sunday"} day))
      {:event-date (str "2026-03-" day) :session weekday :gender gender
       :discipline discipline :category category})))

(defn- result? [value discipline]
  (if (= "STA" discipline)
    (boolean (re-matches #"\d+:\d{2}|-" value))
    (boolean (re-matches #"\d+(?:\.\d+)? m|-" value))))

(defn- printed-row [text discipline]
  (let [parts (str/split (str/trim text) #"\s{2,}")
        rank? (boolean (re-matches #"\d+" (first parts)))
        rank (when rank? (first parts))
        fields (if rank? (subvec parts 1) parts)
        [name second-field & tail] fields
        country? (not (result? second-field discipline))
        country (when country? second-field)
        result (if country? (first tail) second-field)
        remaining (if country? (rest tail) tail)
        card (first remaining)
        notes (second remaining)
        distance (when (and (not= "STA" discipline) result)
                   (second (re-matches #"(\d+(?:\.\d+)?) m" result)))]
    (when (and (not (str/blank? name))
               (result? result discipline)
               (#{"WHITE" "RED"} card)
               (<= (count remaining) 2)
               (or (and (= card "WHITE") (not= result "-"))
                   (and (= card "RED") (= result "-") (not (str/blank? notes)))))
      {:raw {:rank rank :source-name name :representation country :result result
             :card card :notes notes}
       :parsed {:source-name name :rank (some-> rank parse-long)
                :representation country :result result :card card
                :final-distance (some-> distance bigdec)
                :unit (when distance "m")
                :status (when (= card "RED") notes) :notes notes}})))

(defn- field [value]
  {:status (if (nil? value) :unknown :parsed) :value value})

(defn- candidate [line metadata heading-line]
  (let [row (printed-row (:text line) (:discipline metadata))
        parsed (when row (merge {:federation "CMAS"} metadata (:parsed row)))]
    {:coordinates {:page (:page line) :line (:line line) :column-start 1
                   :column-end (inc (count (:text line)))}
     :source-lines [line] :metadata-evidence [heading-line]
     :raw {:line (:text line) :fields (:raw row)}
     :parse-status (if row :parsed :unparsed) :parsed parsed
     :fields (into {} (map (fn [[k v]] [k (field v)]) parsed))
     :review-status :unreviewed
     :unresolved-reasons (cond-> [:owner-review-required]
                           (nil? row) (conj :unparsed-source-line)
                           (and row (nil? (get-in row [:parsed :representation])))
                           (conj :representation-unresolved))}))

(defn- parse-page [page text]
  (let [lines (mapv (fn [i s] {:page page :line (inc i) :text s})
                    (range) (str/split text #"\n" -1))
        nonblank (vec (remove #(str/blank? (:text %)) lines))
        heading-line (first (filter #(heading (:text %)) nonblank))
        metadata (some-> heading-line :text heading)
        header-line (first (filter #(and (str/includes? (:text %) "Place")
                                         (str/includes? (:text %) "Athlete name")
                                         (str/includes? (:text %) "Card")) nonblank))
        supported (and (str/includes? text title) metadata header-line)
        rows (if supported
               (filterv #(and (> (:line %) (:line header-line))
                              (not (str/blank? (:text %)))) lines)
               [])
        candidates (mapv #(candidate % metadata heading-line) rows)
        parsed (count (filter #(= :parsed (:parse-status %)) candidates))
        candidate-lines (set (map :line rows))
        noncandidate-lines (mapv #(assoc % :classification
                                         (if supported :page-metadata :unsupported-page-line))
                                 (remove #(contains? candidate-lines (:line %)) nonblank))]
    {:page {:page page :text text :lines lines
            :status (cond (empty? nonblank) :needs-OCR supported :needs-review
                          :else :unsupported-needs-parser)}
     :candidates candidates
     :noncandidate-lines noncandidate-lines
     :reconciliation {:page page :supported? (boolean supported)
                      :event-date (:event-date metadata)
                      :candidate-count (when supported (count candidates))
                      :parsed-count (when supported parsed)
                      :unparsed-count (when supported (- (count candidates) parsed))
                      :unresolved-count (when supported (count candidates))
                      :nonblank-line-count (count nonblank)}}))

(defn parse-pages [pages]
  (let [processed (mapv (fn [i text] (parse-page (inc i) text)) (range) pages)
        candidates (vec (mapcat :candidates processed))
        unsupported (mapv #(get-in % [:page :page])
                          (remove #(= :needs-review (get-in % [:page :status])) processed))
        ocr (mapv #(get-in % [:page :page])
                  (filter #(= :needs-OCR (get-in % [:page :status])) processed))
        parsed (count (filter #(= :parsed (:parse-status %)) candidates))
        complete? (and (= 14 (count pages)) (empty? unsupported)
                       (= parsed (count candidates))
                       (every? #(pos? (get-in % [:reconciliation :candidate-count])) processed))]
    {:parser-version parser-version :schema-version 3
     :status (if complete? :needs-review :partial-unsupported-needs-parser)
     :pages (mapv :page processed) :candidates candidates
     :reconciliation {:page-count (count pages)
                      :coverage (if complete? :complete :partial)
                      :unsupported-pages unsupported :needs-ocr-pages ocr
                      :per-page (mapv :reconciliation processed)
                      :candidate-count (count candidates) :parsed-count parsed
                      :unparsed-count (- (count candidates) parsed)
                      :unresolved-count (count candidates)
                      :counts-scope :supported-pages-only
                      :nonblank-line-count (reduce + (map #(get-in % [:reconciliation :nonblank-line-count]) processed))
                      :noncandidate-lines (vec (mapcat :noncandidate-lines processed))
                      :status :unreviewed}
     :publication {:status :blocked
                   :reasons (cond-> [:owner-review-required :reconciliation-unreviewed]
                              (seq unsupported) (conj :unsupported-pages)
                              (seq ocr) (conj :needs-OCR))}}))
