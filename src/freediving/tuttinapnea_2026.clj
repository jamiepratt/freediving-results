(ns freediving.tuttinapnea-2026
  "Source-bound transcription of the February 2026 TuttinApnea individual sheets."
  (:require [clojure.string :as str]))

(def parser-version "tuttinapnea-february-2026/1")
(def static-sha256 "33adbd7d14e5080c07be0bf4879b8547d43c29178f19e56abe90d1f46c0dfc8b")
(def dynamic-sha256 "d508b0bea2b8db42ff5eb8935550709f49a7ccd92f730579873ec94c6dddbaa5")
(def source-sha256s #{static-sha256 dynamic-sha256})
(def title "TUTTINAPNEA FEBBRAIO 2026")
(def row-pattern #"^\s*(.+?)\s{2,}([FM])\s{2,}(.*?)\s*$")
(def number-pattern #"\d+(?:,\d+)?")

(defn supported? [pages]
  (let [text (str/join "\n" pages)]
    (and (str/includes? text title)
         (str/includes? text "RESOCONTO GARA NAZIONALE")
         (or (str/includes? text "STATICA") (str/includes? text "DINAMICA")))))

(defn- cells [s]
  (->> (str/split (str/trim s) #"\s{2,}") (mapv str/trim)))

(defn- printed-number? [s]
  (boolean (and s (re-matches number-pattern s))))

(defn- location [prefix]
  (case (count prefix)
    4 (zipmap [:team :city :region :source-name] prefix)
    3 (zipmap [:team :region :source-name] prefix)
    nil))

(defn- performance [kind values]
  (let [[type & result] (if (= kind :dynamic) values (cons nil values))
        [performed second-value third-value] result
        no-result? (and (#{"X" "x"} performed)
                        (or (nil? second-value) (= "PN" second-value))
                        (nil? third-value))
        ordinary? (and (printed-number? performed)
                       (or (nil? third-value) (printed-number? second-value))
                       (printed-number? (or third-value second-value))
                       (<= 2 (count result) 3))
        yellow (when (= 3 (count result)) second-value)
        points (if no-result? second-value (or third-value second-value))]
    (when (and (if (= kind :dynamic) (#{"B" "M" "R"} type) (nil? type))
               (or ordinary? no-result?))
      {:type type :realised-performance (when ordinary? performed)
       :yellow-card yellow :red-card (when no-result? performed)
       :points points :status (if no-result? :null-result :result)})))

(defn- candidate [kind line metadata supported-page?]
  (let [[_ prefix gender tail] (re-matches row-pattern (:text line))
        location-fields (location (cells prefix))
        result-fields (performance kind (cells tail))
        parsed (when (and supported-page? location-fields result-fields)
                 (merge {:event-name "TuttinApnea Febbraio 2026"
                         :event-month "2026-02" :event-date nil
                         :discipline (if (= kind :static) "STA" "DYN")
                         :gender gender :unit (if (= kind :static) "min,sec" "m")
                         :rank nil :city nil}
                        location-fields result-fields))]
    {:coordinates {:page (:page line) :line (:line line)
                   :column-start 1 :column-end (inc (count (:text line)))}
     :source-lines [line]
     :metadata-evidence metadata
     :raw {:line (:text line)
           :fields {:location (cells prefix) :gender gender :result (cells tail)}}
     :parse-status (if parsed :parsed :unparsed)
     :parsed parsed
     :fields {:event-date {:status :unknown :value nil}
              :rank {:status :unknown :value nil}}
     :review-status :unreviewed
     :unresolved-reasons (cond-> [:owner-review-required]
                           (nil? parsed) (conj :unparsed-source-line))}))

(defn- page-result [kind page-index text]
  (let [lines (mapv (fn [i s] {:page (inc page-index) :line (inc i) :text s})
                    (range) (str/split text #"\n" -1))
        heading (if (= kind :static) "STATICA" "DINAMICA")
        supported-page? (and (str/includes? text "RESOCONTO GARA NAZIONALE")
                             (str/includes? text title)
                             (str/includes? text heading)
                             (str/includes? text "PUNTEGGIO"))
        metadata (vec (filter #(or (str/includes? (:text %) title)
                                   (str/includes? (:text %) heading)) lines))
        rows (filterv #(re-matches row-pattern (:text %)) lines)
        candidates (mapv #(candidate kind % metadata supported-page?) rows)
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
  (when-not (contains? source-sha256s sha256)
    (throw (ex-info "TuttinApnea parser is bound to different source PDFs" {})))
  (let [kind (if (= sha256 static-sha256) :static :dynamic)
        processed (mapv #(page-result kind %1 %2) (range) pages)
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
