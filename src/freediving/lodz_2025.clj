(ns freediving.lodz-2025
  "Source-bound printed results in the March 2025 Łódź Indoor World Cup PDF."
  (:require [clojure.string :as str]))

(def source-sha256 "b1104d5d3e6f1bfee1c627cc2134949a9766fea18273a2c9dbbd96423f6f3a97")
(def parser-version "cmas-lodz-indoor-world-cup-2025/1")
(def title "CMAS World Cup Indoor Series - Łódź, Poland 2025")

(defn supported? [pages]
  (and (some #(str/includes? % title) pages)
       (some #(str/includes? % "Athlete name") pages)
       (some #(str/includes? % "March 2025") pages)))

(defn- printed-row [text]
  (let [parts (str/split (str/trim text) #"\s{2,}")]
    (when (<= 8 (count parts) 9)
      (let [[name gender country discipline category card result points remarks] parts
            distance (second (re-matches #"(\d+(?:\.\d+)?) m" result))
            valid? (and (not (str/blank? name)) (#{"Men" "Women"} gender)
                        (#{"DYN" "DYNB" "DNF"} discipline)
                        (#{"WHITE" "RED"} card)
                        (re-matches #"\d+(?:\.\d+)?" points)
                        (or (and (= "WHITE" card) distance)
                            (and (= "RED" card) (= "-" result) (not (str/blank? remarks)))))]
        (when valid?
          {:raw {:source-name name :gender gender :representation country
                 :discipline discipline :category category :card card
                 :result result :points points :remarks remarks}
           :parsed {:source-name name :gender gender :representation country
                    :discipline discipline :category category :card card
                    :final-distance (some-> distance bigdec) :unit "m"
                    :points (bigdec points) :status (when (= "RED" card) remarks)
                    :notes remarks}})))))

(defn- page-info [page text]
  (let [lines (mapv (fn [index line] {:page page :line (inc index) :text line})
                    (range) (str/split text #"\n" -1))
        day (some-> (re-find #"(?m)^\s*(?:Results - )?(?:Friday|Saturday|Sunday)?\s*-?\s*(14th|15th|16th) March 2025\s*$" text) second)
        event-date (when day (str "2025-03-" (subs day 0 2)))
        header-index (last (keep-indexed
                            (fn [i line]
                              (when (and (str/includes? (:text line) "Athlete name")
                                         (str/includes? (:text line) "Remarks")) i)) lines))
        result-page? (and (str/includes? text title) event-date header-index)
        rows (if result-page?
               (vec (remove #(str/blank? (:text %)) (subvec lines (inc header-index)))) [])
        candidates (mapv
                    (fn [line]
                      (let [r (printed-row (:text line))
                            parsed (when r (assoc (:parsed r) :federation "CMAS"
                                                  :event-date event-date))]
                        {:coordinates {:page page :line (:line line) :column-start 1
                                       :column-end (inc (count (:text line)))}
                         :source-lines [line]
                         :raw {:line (:text line) :fields (:raw r)}
                         :parse-status (if r :parsed :unparsed) :parsed parsed
                         :fields (into {} (map (fn [[k v]]
                                                 [k {:status (if (nil? v) :unknown :parsed) :value v}]) parsed))
                         :review-status :unreviewed
                         :unresolved-reasons (cond-> [:owner-review-required]
                                               (nil? r) (conj :unparsed-source-line))})) rows)
        classification (cond result-page? :attempt-results
                             (str/blank? text) :needs-OCR
                             :else :unsupported-page)
        nonblank (vec (remove #(str/blank? (:text %)) lines))
        candidate-lines (set (map #(get-in % [:coordinates :line]) candidates))]
    {:page {:page page :text text :lines lines
            :status (if result-page? :needs-review classification)}
     :candidates candidates
     :reconciliation {:page page :classification classification :event-date event-date
                      :candidate-count (count candidates)
                      :parsed-count (count (filter #(= :parsed (:parse-status %)) candidates))
                      :unparsed-count (count (filter #(= :unparsed (:parse-status %)) candidates))
                      :nonblank-line-count (count nonblank)}
     :noncandidate-lines (mapv #(assoc % :classification classification)
                               (remove #(contains? candidate-lines (:line %)) nonblank))}))

(defn parse-pages [pages]
  (let [processed (mapv (fn [i text] (page-info (inc i) text)) (range) pages)
        candidates (vec (mapcat :candidates processed))
        per-page (mapv :reconciliation processed)
        unparsed (count (filter #(= :unparsed (:parse-status %)) candidates))
        complete? (and (= 3 (count pages))
                       (= ["2025-03-14" "2025-03-15" "2025-03-16"]
                          (mapv :event-date per-page))
                       (every? #(= :attempt-results (:classification %)) per-page)
                       (every? #(pos? (:candidate-count %)) per-page)
                       (zero? unparsed))]
    {:parser-version parser-version :schema-version 3
     :status (if complete? :needs-review :partial-unsupported-needs-parser)
     :pages (mapv :page processed) :candidates candidates
     :reconciliation {:page-count (count pages)
                      :coverage (if complete? :complete :partial)
                      :candidate-count (count candidates)
                      :parsed-count (- (count candidates) unparsed)
                      :unparsed-count unparsed :unresolved-count (count candidates)
                      :per-page per-page
                      :nonblank-line-count (reduce + (map :nonblank-line-count per-page))
                      :noncandidate-lines (vec (mapcat :noncandidate-lines processed))
                      :status :unreviewed}
     :publication {:status :blocked :reasons [:owner-review-required :reconciliation-unreviewed]}}))
