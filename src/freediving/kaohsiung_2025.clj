(ns freediving.kaohsiung-2025
  "Source-bound sporting attempts in the September 2025 Kaohsiung World Cup resultbook."
  (:require [clojure.string :as str]))

(def source-sha256 "1e58fefbe81475c620bf80a8ebec25c697129c389abd31495371500d3459a8e9")
(def parser-version "cmas-kaohsiung-world-cup-2025/1")

(defn supported? [pages]
  (and (some #(str/includes? % "2025 CMAS WORLD CUP FREEDIVING INDOOR, KAOHSIUNG") pages)
       (some #(str/includes? % "DNF FINAL RESULTS") pages)
       (some #(str/includes? % "DYNBF FINAL RESULTS") pages)))

(defn- row-fields [s discipline]
  (when-let [[_ rank name country tail]
             (re-matches #"\s*(?:(\d+)\s+)?(.+?)\s{2,}(AIN|[A-Z]{3})\s+(.+?)\s*" s)]
    (let [tokens (str/split (str/trim tail) #"\s+")
          marker? (and (#{"DYNBF" "DYN"} discipline) (#{"F" "M"} (first tokens)))
          marker (when marker? (first tokens))
          tokens (if marker? (vec (rest tokens)) tokens)
          status (last tokens)
          status (when (#{"DNS" "DQSP" "DQBO-SURFACE"} status) status)
          numeric (if status (butlast tokens) tokens)
          distance? #(boolean (re-matches #"\d+(?:\.\d+)?" %))
          valid? (and (every? distance? numeric)
                      (if marker? (<= 0 (count numeric) 1) (<= 1 (count numeric) 2))
                      (or status (seq numeric)))]
      (when valid?
        (let [realized (when-not marker? (first numeric))
              final (if marker? (first numeric) (second numeric))]
          {:raw {:rank rank :source-name name :representation country
                 :realized-distance (or marker realized) :final-distance final :status status}
           :parsed {:rank (some-> rank parse-long) :source-name name :representation country
                    :realized-distance (some-> realized bigdec) :final-distance (some-> final bigdec)
                    :status status :unit "m"}})))))

(defn- page-info [page text]
  (let [lines (mapv (fn [i s] {:page page :line (inc i) :text s})
                    (range) (str/split text #"\n" -1))
        section (re-find #"(?m)^\s*(DNF|DYNBF|DYN) FINAL RESULTS\s+SENIORS - (WOMEN|MEN)\s*$" text)
        date (some-> (re-find #"(?m)^\s*(10|11)-Sep-25\s*$" text) second)
        attempt? (and (<= page 6) section date (str/includes? text "Name & surname"))
        [discipline category] (rest section)
        event-date (when date (str "2025-09-" date))
        header-end (last (keep-indexed (fn [i l] (when (str/includes? (:text l) "Distance (m)") i)) lines))
        body (if (and attempt? header-end) (subvec lines (inc header-end)) [])
        row? (fn [line] (boolean (re-matches #"\s*(?:(?:\d+)\s+)?[A-Za-z].+?\s{2,}(?:AIN|[A-Z]{3})\s+.+" (:text line))))
        rows (filter row? body)
        candidates (mapv
                    (fn [line]
                      (let [note? (and (= page 6) (= discipline "DYN") (str/includes? (:text line) "Raymond KO"))
                            previous (when note? (some #(when (= (:line %) (dec (:line line))) %) lines))
                            next-line (when note? (some #(when (= (:line %) (inc (:line line))) %) lines))
                            notes (when (and previous next-line
                                             (str/includes? (:text previous) "RESULT 88M PENALTIES 3M")
                                             (str/includes? (:text next-line) "START"))
                                    (str (str/trim (:text previous)) " " (str/trim (:text next-line))))
                            source-lines (cond-> [line] notes (into [previous next-line]))
                            r (row-fields (:text line) discipline)
                            parsed (when r (cond-> (merge {:federation "CMAS" :event-date event-date
                                                           :discipline discipline :category (str "Seniors - " category)}
                                                          (:parsed r))
                                             notes (assoc :notes notes)))]
                        {:coordinates {:page page :line (:line line) :column-start 1
                                       :column-end (inc (count (:text line)))}
                         :source-lines source-lines
                         :raw {:lines (mapv :text source-lines) :fields (:raw r)}
                         :parse-status (if r :parsed :unparsed) :parsed parsed
                         :fields (into {} (map (fn [[k v]] [k {:status (if (nil? v) :unknown :parsed) :value v}]) parsed))
                         :review-status :unreviewed
                         :unresolved-reasons (cond-> [:owner-review-required] (nil? r) (conj :unparsed-source-line))}))
                    rows)
        classification (cond attempt? :attempt-results
                             (>= page 7) :supporting-ranking
                             (str/blank? text) :needs-OCR
                             :else :unsupported-page)
        used-lines (set (mapcat #(map :line (:source-lines %)) candidates))
        nonblank (remove #(str/blank? (:text %)) lines)]
    {:page {:page page :text text :lines lines :status (if attempt? :needs-review classification)}
     :candidates candidates
     :reconciliation {:page page :classification classification :candidate-count (count candidates)
                      :parsed-count (count (filter #(= :parsed (:parse-status %)) candidates))
                      :unparsed-count (count (filter #(= :unparsed (:parse-status %)) candidates))
                      :nonblank-line-count (count nonblank)}
     :noncandidate-lines (mapv #(assoc % :classification classification)
                               (remove #(contains? used-lines (:line %)) nonblank))}))

(defn parse-pages [pages]
  (let [processed (mapv (fn [i text] (page-info (inc i) text)) (range) pages)
        per-page (mapv :reconciliation processed)
        candidates (vec (mapcat :candidates processed))
        unparsed (count (filter #(= :unparsed (:parse-status %)) candidates))
        complete? (and (= 8 (count pages))
                       (= (vec (repeat 6 :attempt-results))
                          (mapv :classification (take 6 per-page)))
                       (= [:supporting-ranking :supporting-ranking]
                          (mapv :classification (drop 6 per-page)))
                       (= [14 14 12 13 13 15] (mapv :candidate-count (take 6 per-page)))
                       (zero? unparsed))]
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
