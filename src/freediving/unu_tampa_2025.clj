(ns freediving.unu-tampa-2025
  "Source-bound results in the December 2025 UNU Tampa Bay CMAS PDF."
  (:require [clojure.string :as str]))

(def source-sha256 "61831ff0715a2130e2c2d35aa6caac30d92fc9f795a7cee3c290782710949781")
(def parser-version "cmas-unu-tampa-bay-2025/1")
(def ^:private static-title "UNU Tampa Bay Freediving Challenge 2025 - STATIC 06.12.2025")
(def ^:private dynamic-title "UNU Tampa Bay Freediving Challenge 2025 - DYNAMIC DISCIPLINES - RESULTS")

(defn supported? [pages]
  (and (= 3 (count pages))
       (str/includes? (first pages) static-title)
       (str/includes? (second pages) dynamic-title)
       (every? #(str/includes? % "Given Name") pages)))

(defn- duration-seconds [value]
  (when-let [[_ minutes seconds] (and value (re-matches #"(\d{2}):(\d{2})" value))]
    (when (< (parse-long seconds) 60)
      (+ (* 60 (parse-long minutes)) (parse-long seconds)))))

(defn- result-row [text discipline category]
  (when-let [[_ rank given family gender country printed-discipline tail]
             (re-matches #"\s*(?:(\d+)\s+)?(.+?)\s{2,}(.+?)\s{2,}([FM])\s+([A-Z]{3})\s+(?:(DNF|DYNBF|DYN)\s+)?(.*?)\s*" text)]
    (let [parts (str/split (str/trim tail) #"\s+")
          static? (= discipline "STA")
          match? (= printed-discipline (when-not static? discipline))
          realized (when (re-matches (if static? #"\d{2}:\d{2}" #"\d+") (first parts)) (first parts))
          after-realized (if realized (rest parts) parts)
          final-value (when (re-matches (if static? #"(?:\d{2}:\d{2}|0)" #"\d+") (first after-realized))
                        (first after-realized))
          remarks (str/join " " (if final-value (rest after-realized) after-realized))
          disqualified? (str/starts-with? remarks "DSQ")]
      (when (and match? (= gender (if (= category "WOMEN") "F" "M"))
                 (or (and realized final-value (not (str/blank? remarks)))
                     (and (= "DNS" remarks) (nil? realized) (nil? final-value))
                     (and disqualified? realized (or (nil? final-value) (= "0" final-value)))))
        {:raw {:rank rank :given-name given :family-name family :gender gender :representation country
               :discipline discipline :category category :realized-performance realized
               :final-performance final-value :remarks remarks}
         :parsed {:rank (some-> rank parse-long) :source-name (str given " " family)
                  :given-name given :family-name family :gender gender :representation country
                  :discipline discipline :category category :event-date nil
                  :printed-date (when static? "06.12.2025")
                  :federation "CMAS" :realized-performance realized
                  :final-performance final-value
                  :realized-duration-seconds (when static? (duration-seconds realized))
                  :final-duration-seconds (when (and static? (not disqualified?)) (duration-seconds final-value))
                  :realized-distance (when (and (not static?) realized) (bigdec realized))
                  :final-distance (when (and (not static?) (not disqualified?) final-value) (bigdec final-value))
                  :unit (if static? "s" "m")
                  :unit-evidence (if static? :clock-format-inferred :discipline-convention-inferred)
                  :status remarks}}))))

(defn- page-info [page text]
  (let [lines (mapv (fn [i s] {:page page :line (inc i) :text s})
                    (range) (str/split text #"\n" -1))
        sections (reductions
                  (fn [section {:keys [text]}]
                    (if-let [[_ discipline category]
                             (re-matches #"\s*(?:(DNF|DYNBF|DYN)\s+)?(WOMEN|MEN)\s*" text)]
                      [(or discipline "STA") category]
                      section)) nil lines)
        sections (rest sections)
        row? (fn [line]
               (boolean (re-matches #"\s*(?:(?:\d+)\s+)?[A-Za-z].+?\s{2,}.+?\s{2,}[FM]\s+[A-Z]{3}\s+.*" (:text line))))
        candidates (->> (map vector lines sections)
                        (filter (fn [[line section]] (and section (row? line))))
                        (mapv (fn [[line [discipline category]]]
                                (let [r (result-row (:text line) discipline category)
                                      parsed (:parsed r)]
                                  {:coordinates {:page page :line (:line line) :column-start 1
                                                 :column-end (inc (count (:text line)))}
                                   :source-lines [line] :raw {:line (:text line) :fields (:raw r)}
                                   :parse-status (if r :parsed :unparsed) :parsed parsed
                                   :fields (into {} (map (fn [[k v]]
                                                           [k {:status (if (nil? v) :unknown :parsed) :value v}]) parsed))
                                   :review-status :unreviewed
                                   :unresolved-reasons (cond-> [:owner-review-required]
                                                         (nil? r) (conj :unparsed-source-line))}))))
        nonblank (remove #(str/blank? (:text %)) lines)
        candidate-lines (set (map #(get-in % [:coordinates :line]) candidates))
        classification (cond (and (= page 1) (str/includes? text static-title)) :attempt-results
                             (and (= page 2) (str/includes? text dynamic-title)) :attempt-results
                             (and (= page 3) (str/includes? text "DYNBF MEN")) :attempt-results
                             (str/blank? text) :needs-OCR
                             :else :unsupported-page)]
    {:page {:page page :text text :lines lines
            :status (if (= :attempt-results classification) :needs-review classification)}
     :candidates candidates
     :reconciliation {:page page :classification classification :candidate-count (count candidates)
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
        complete? (and (supported? pages) (zero? unparsed)
                       (= [8 15 13] (mapv :candidate-count per-page))
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
