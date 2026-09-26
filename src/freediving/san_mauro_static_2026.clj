(ns freediving.san-mauro-static-2026
  "Source-bound transcription of the 2026 Trofeo San Mauro static rankings."
  (:require [clojure.string :as str]))

(def parser-version "san-mauro-static-2026/1")
(def source-sha256 "f96b5a28fb1c43b1994a0cfafecbad5d861449cd9655a0712a4b1c85541a32ec")
(def event-date "2026-03-01")
(def title "GIRO D'ITALIA IN APNEA - TROFEO SAN MAURO")
(def date-line "Pomigliano d'Arco 1 marzo 2026")
(def discipline-line "APNEA STATICA")
(def heading-pattern #"\s*Classifica (femminile|maschile)\s*")
(def row-pattern #"\s*(\d+)\s+(.+?)\s{2,}(.+?)\s{2,}([MF])\s+(\S+)\s+(\S+)\s*")

(defn supported? [pages]
  (let [s (str/join "\n" pages)]
    (every? #(str/includes? s %) [title date-line discipline-line
                                  "Classifica femminile" "Classifica maschile"
                                  "( MM,SS )"])))

(defn- field [value]
  {:status (if (nil? value) :unknown :parsed) :value value})

(defn- heading [source-line]
  (when-let [[_ section] (re-matches heading-pattern (:text source-line))]
    {:category section :gender (if (= section "femminile") "F" "M")}))

(defn- candidate [source-line context heading-line metadata]
  (let [line (:text source-line)
        [_ rank club name gender time points] (re-matches row-pattern line)
        raw-fields (when name {:rank rank :club club :source-name name
                               :gender gender :time time :points points})
        valid? (and context name (= gender (:gender context))
                    (re-matches #"\d+(?:,\d{1,2})?" time)
                    (re-matches #"\d+(?:,\d{2})?" points))
        parsed (when valid?
                 (merge context {:event-date event-date :discipline "STA"
                                 :rank (parse-long rank) :club club :source-name name
                                 :time time :points points :unit "min,sec"
                                 :result-status "ranked"}))
        shortened? (and valid? (or (not (str/includes? time ","))
                                   (= 1 (count (second (str/split time #","))))))]
    {:coordinates {:page (:page source-line) :line (:line source-line)
                   :column-start 1 :column-end (inc (count line))}
     :source-lines [source-line]
     :metadata-evidence (cond-> metadata heading-line (conj heading-line))
     :raw {:line line :fields raw-fields}
     :parse-status (if parsed :parsed :unparsed)
     :parsed parsed
     :fields (into {} (map (fn [[k v]] [k (field v)]) parsed))
     :review-status :unreviewed
     :unresolved-reasons (cond-> [:owner-review-required]
                           (not parsed) (conj :unparsed-source-line)
                           shortened? (conj (if (str/includes? time ",")
                                              :abbreviated-time-cell :bare-minute-cell)))}))

(defn parse-pages [sha256 pages]
  (when-not (= source-sha256 sha256)
    (throw (ex-info "San Mauro static parser is bound to a different source PDF" {})))
  (let [source-supported? (supported? pages)
        metadata (vec (for [[p text] (map-indexed vector pages)
                            [i s] (map-indexed vector (str/split text #"\n" -1))
                            :when (some #{(str/trim s)} [title date-line discipline-line "( MM,SS )"])]
                        {:page (inc p) :line (inc i) :text s}))
        processed (mapv
                   (fn [page-index text]
                     (let [lines (mapv (fn [i s] {:page (inc page-index) :line (inc i) :text s})
                                       (range) (str/split text #"\n" -1))
                           nonblank (filterv #(not (str/blank? (:text %))) lines)
                           result (reduce (fn [{:keys [context heading-line] :as acc} source-line]
                                            (if-let [section (heading source-line)]
                                              (-> acc (assoc :context section :heading-line source-line)
                                                  (update :noncandidate conj (assoc source-line :classification :heading)))
                                              (if (re-find #"^\s*\d+\s+" (:text source-line))
                                                (update acc :candidates conj
                                                        (candidate source-line context heading-line metadata))
                                                (update acc :noncandidate conj
                                                        (assoc source-line :classification :metadata)))))
                                          {:context nil :heading-line nil :candidates [] :noncandidate []}
                                          nonblank)
                           candidates (:candidates result)
                           parsed-count (count (filter #(= :parsed (:parse-status %)) candidates))
                           status (cond (empty? nonblank) :needs-OCR
                                        (not source-supported?) :unsupported-needs-parser
                                        :else :needs-review)]
                       {:page (inc page-index) :text text :lines lines :status status
                        :candidates candidates :noncandidate-lines (:noncandidate result)
                        :reconciliation {:page (inc page-index) :supported? (= :needs-review status)
                                         :candidate-count (count candidates)
                                         :parsed-count parsed-count
                                         :unparsed-count (- (count candidates) parsed-count)
                                         :unresolved-count (count candidates)
                                         :rank-shaped-line-count (count candidates)
                                         :nonblank-line-count (count nonblank)}}))
                   (range) pages)
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
                      :candidate-count (count candidates) :parsed-count parsed-count
                      :unparsed-count (- (count candidates) parsed-count)
                      :unresolved-count (count candidates)
                      :rank-shaped-line-count (count candidates)
                      :counts-scope :supported-pages-only
                      :nonblank-line-count (reduce + (map #(get-in % [:reconciliation :nonblank-line-count]) processed))
                      :noncandidate-lines (vec (mapcat :noncandidate-lines processed))
                      :status :unreviewed}
     :publication {:status :blocked
                   :reasons (cond-> [:owner-review-required :reconciliation-unreviewed]
                              (seq unsupported) (conj :unsupported-pages))}}))
