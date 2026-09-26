(ns freediving.tuttinapnea-2025-dynamic
  "Source-bound January 2025 TuttinApnea dynamic ranking transcription."
  (:require [clojure.string :as str]))

(def parser-version "tuttinapnea-january-2025-dynamic/1")
(def source-sha256 "aede835f6af33149cf6c5dd4b6498b1d0cb34c2d3ec5a5cd95e325265ed65fcf")
(def title "TUTTINAPNEA GENNAIO 2025")
(def row-pattern #"^\s*(.+?)\s{2,}([FM])\s{2,}([BMR])\s{2,}(.*?)\s*$")
(def number-pattern #"\d+(?:,\d+)?")

(defn supported? [pages]
  (let [text (str/join "\n" pages)]
    (and (str/includes? text "RESOCONTO GARA NAZIONALE")
         (str/includes? text title)
         (str/includes? text "DINAMICA")
         (str/includes? text "PUNTEGGIO"))))

(defn- cells [text]
  (mapv str/trim (str/split (str/trim text) #"\s{2,}")))

(defn- printed-number? [value]
  (boolean (and value (re-matches number-pattern value))))

(defn- performance [values]
  (let [[performed second-value] values
        null-result? (= ["x" "PN"] values)
        ordinary? (and (<= 2 (count values) 3)
                       (printed-number? performed)
                       (printed-number? (last values))
                       (or (= 2 (count values)) (printed-number? second-value)))]
    (when (or ordinary? null-result?)
      {:realised-performance (when ordinary? performed)
       :yellow-card (when (= 3 (count values)) second-value)
       :red-card (when null-result? performed)
       :points (if null-result? second-value (last values))
       :status (if null-result? :null-result :result)})))

(defn- candidate [line metadata supported-page?]
  (let [[_ prefix gender type tail] (re-matches row-pattern (:text line))
        location (cells prefix)
        values (cells tail)
        result (performance values)
        parsed (when (and supported-page? (= 2 (count location)) result)
                 (merge {:event-name "TuttinApnea Gennaio 2025"
                         :event-month "2025-01" :event-date nil
                         :discipline "DYN" :unit "m" :rank nil
                         :team (first location) :source-name (second location)
                         :gender gender :type type}
                        result))]
    {:coordinates {:page (:page line) :line (:line line)
                   :column-start 1 :column-end (inc (count (:text line)))}
     :source-lines [line]
     :metadata-evidence metadata
     :raw {:line (:text line)
           :fields {:location location :gender gender :type type :result values}}
     :parse-status (if parsed :parsed :unparsed)
     :parsed parsed
     :fields {:event-date {:status :unknown :value nil}
              :rank {:status :unknown :value nil}}
     :review-status :unreviewed
     :unresolved-reasons (cond-> [:owner-review-required]
                           (nil? parsed) (conj :unparsed-source-line))}))

(defn- page-result [page-index text]
  (let [lines (mapv (fn [index value]
                      {:page (inc page-index) :line (inc index) :text value})
                    (range) (str/split text #"\n" -1))
        supported-page? (supported? [text])
        metadata (filterv #(or (str/includes? (:text %) title)
                               (str/includes? (:text %) "DINAMICA")) lines)
        rows (filterv #(re-matches row-pattern (:text %)) lines)
        candidates (mapv #(candidate % metadata supported-page?) rows)
        parsed-count (count (filter #(= :parsed (:parse-status %)) candidates))]
    {:page (inc page-index) :text text :lines lines
     :status (cond (str/blank? text) :needs-OCR
                   (not supported-page?) :unsupported-needs-parser
                   :else :needs-review)
     :candidates candidates
     :noncandidate-lines (vec (remove (set rows) (remove #(str/blank? (:text %)) lines)))
     :reconciliation {:page (inc page-index) :candidate-count (count candidates)
                      :parsed-count parsed-count
                      :unparsed-count (- (count candidates) parsed-count)
                      :unresolved-count (count candidates)}}))

(defn parse-pages [sha256 pages]
  (when-not (= source-sha256 sha256)
    (throw (ex-info "TuttinApnea January dynamic parser is bound to a different source PDF" {})))
  (let [processed (mapv #(page-result %1 %2) (range) pages)
        candidates (vec (mapcat :candidates processed))
        parsed-count (count (filter #(= :parsed (:parse-status %)) candidates))
        unsupported (mapv :page (remove #(= :needs-review (:status %)) processed))]
    {:parser-version parser-version :schema-version 3
     :status (if (seq unsupported) :partial-unsupported-needs-parser :needs-review)
     :pages (mapv #(dissoc % :candidates :noncandidate-lines :reconciliation) processed)
     :candidates candidates
     :reconciliation {:page-count (count pages)
                      :coverage (if (seq unsupported) :partial :complete)
                      :unsupported-pages unsupported
                      :needs-ocr-pages (mapv :page (filter #(= :needs-OCR (:status %)) processed))
                      :per-page (mapv :reconciliation processed)
                      :candidate-count (count candidates)
                      :parsed-count parsed-count
                      :unparsed-count (- (count candidates) parsed-count)
                      :unresolved-count (count candidates)
                      :noncandidate-lines (vec (mapcat :noncandidate-lines processed))
                      :status :unreviewed}
     :publication {:status :blocked
                   :reasons (cond-> [:owner-review-required :reconciliation-unreviewed]
                              (seq unsupported) (conj :unsupported-pages))}}))
