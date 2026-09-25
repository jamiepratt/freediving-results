(ns freediving.croatia-open
  "Source-bound 2026 Croatian Open National result-table extraction."
  (:require [clojure.string :as str]))

(def parser-version "cmas-croatia-open-2026/1")
(def title "18th Submania CUP /")
(defn supported? [pages]
  (boolean (some #(and (str/includes? % title)
                       (str/includes? % "Croatian Open National")
                       (str/includes? % "Freediving Championship")) pages)))

(defn- field [value] {:status (if (nil? value) :unknown :parsed) :value value})
(defn- metadata-kind [text]
  (let [s (str/trim text)]
    (cond
      (re-matches #"18th Submania CUP /\s+\d{2}/\d{2}/2026" s) :event-date
      (= s "Croatian Open National") :title
      (= s "Freediving Championship") :championship
      (#{"STA" "DYN" "DNF" "DYNBF"} s) :discipline
      (re-matches #"Rank\s+Name\s+Gender\s+Nationality\s+Discipline\s+Result\s+Card" s) :header)))

(defn- row [text context]
  (when-let [[_ rank name gender nationality discipline result card]
             (re-matches #"\s*(\d+|-)\s+(.+?)\s+([MF])\s+([A-Z]{3})\s+(STA|DYN|DNF|DYNBF)\s+(\d+:\d{2}|\d+\.\d{2}m)\s+(WHITE|RED)\s*" text)]
    (when (and (= discipline (:discipline context))
               (if (= discipline "STA") (re-matches #"\d+:\d{2}" result)
                   (re-matches #"\d+\.\d{2}m" result)))
      {:raw {:rank rank :source-name name :gender gender :nationality nationality
             :discipline discipline :result result :card card}
       :parsed (merge context
                      {:federation "CMAS" :source-name name :gender gender
                       :nationality nationality :rank (when-not (= rank "-") (parse-long rank))
                       :result result :card card :unit (when-not (= discipline "STA") "m")}
                      (when-not (= discipline "STA")
                        {:distance (bigdec (subs result 0 (dec (count result))))}))})))

(defn- candidate [{:keys [page line text] :as source} context evidence]
  (let [r (row text context)]
    {:coordinates {:page page :line line :column-start 1 :column-end (inc (count text))}
     :raw {:line text :fields (:raw r)} :source-lines [source]
     :metadata-evidence evidence :parse-status (if r :parsed :unparsed)
     :parsed (:parsed r) :fields (into {} (map (fn [[k v]] [k (field v)]) (:parsed r)))
     :review-status :unreviewed
     :unresolved-reasons (cond-> [:owner-review-required]
                           (nil? r) (conj :unparsed-source-line))}))

(defn parse-pages [pages]
  (let [processed
        (mapv (fn [i text]
                (let [page (inc i)
                      lines (mapv (fn [j s] {:page page :line (inc j) :text s})
                                  (range) (str/split text #"\n" -1))
                      nonblank (vec (remove #(str/blank? (:text %)) lines))
                      metadata (vec (filter #(metadata-kind (:text %)) nonblank))
                      by-kind (group-by #(metadata-kind (:text %)) metadata)
                      date-text (some-> (get by-kind :event-date) first :text)
                      date (when date-text (re-find #"\d{2}/\d{2}/2026" date-text))
                      discipline (some-> by-kind :discipline first :text str/trim)
                      supported (and (every? #(= 1 (count (get by-kind %)))
                                             [:event-date :title :championship :discipline])
                                     (<= (count (get by-kind :header)) 1)
                                     (not-any? #(and (re-find #"(?i)(?:championship|submania|national|rank|discipline|result)" (:text %))
                                                     (nil? (metadata-kind (:text %)))) nonblank)
                                     (= date (get {"STA" "27/03/2026" "DYN" "28/03/2026"
                                                   "DNF" "28/03/2026" "DYNBF" "29/03/2026"}
                                                  discipline)))
                      context {:event-date (when date (let [[d m y] (str/split date #"/")] (str y "-" m "-" d)))
                               :discipline discipline}
                      candidates (if supported
                                   (mapv #(candidate % context metadata)
                                         (remove #(metadata-kind (:text %)) nonblank)) [])
                      parsed (count (filter #(= :parsed (:parse-status %)) candidates))]
                  {:page page :text text :lines lines
                   :status (cond supported :needs-review (empty? nonblank) :needs-OCR :else :unsupported-needs-parser)
                   :candidates candidates
                   :noncandidate-lines (mapv #(assoc % :classification (if supported (metadata-kind (:text %)) :unsupported-page-line))
                                             (if supported metadata nonblank))
                   :reconciliation {:page page :supported? (boolean supported)
                                    :candidate-count (when supported (count candidates))
                                    :parsed-count (when supported parsed)
                                    :unparsed-count (when supported (- (count candidates) parsed))
                                    :unresolved-count (when supported (count candidates))
                                    :nonblank-line-count (count nonblank)}}))
              (range) pages)
        candidates (vec (mapcat :candidates processed))
        unsupported (mapv :page (remove #(= :needs-review (:status %)) processed))
        ocr (mapv :page (filter #(= :needs-OCR (:status %)) processed))
        parsed (count (filter #(= :parsed (:parse-status %)) candidates))]
    {:parser-version parser-version :schema-version 3
     :status (if (seq unsupported) :partial-unsupported-needs-parser :needs-review)
     :pages (mapv #(dissoc % :candidates :noncandidate-lines :reconciliation) processed)
     :candidates candidates
     :reconciliation {:page-count (count pages) :coverage (if (seq unsupported) :partial :complete)
                      :unsupported-pages unsupported :needs-ocr-pages ocr :per-page (mapv :reconciliation processed)
                      :candidate-count (count candidates) :parsed-count parsed
                      :unparsed-count (- (count candidates) parsed)
                      :unresolved-count (count candidates) :counts-scope :supported-pages-only
                      :nonblank-line-count (reduce + (map #(get-in % [:reconciliation :nonblank-line-count]) processed))
                      :noncandidate-lines (vec (mapcat :noncandidate-lines processed)) :status :unreviewed}
     :publication {:status :blocked :reasons (cond-> [:owner-review-required :reconciliation-unreviewed]
                                               (seq unsupported) (conj :unsupported-pages)
                                               (seq ocr) (conj :needs-OCR))}}))
