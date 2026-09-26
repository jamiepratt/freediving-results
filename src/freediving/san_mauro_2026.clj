(ns freediving.san-mauro-2026
  "Source-bound Trofeo San Mauro 2026 result-sheet transcription."
  (:require [clojure.string :as str]))

(def parser-version "san-mauro-dynamic-2026/1")
(def male-sha256 "7641234fbddbeba295e08fc7fbea974a56915bdac12ad38bf5dade28e862e2db")
(def female-sha256 "c3af0173bb25424c7c142cdf4e8660d5d020eefe50a237c6259b609f4bcef7fd")
(def source-sha256s #{male-sha256 female-sha256})
(def event-date "2026-03-01")
(def title "TROFEO SAN MAURO")
(def date-line "POMIGLIANO D'ARCO 1 MARZO 2026")
(def heading-pattern #"\s*Classifica (maschile|femminile) (bipinne|monopinna|rana)\s*")
(def row-pattern #"\s*(\d+)\s+(.+?)\s{2,}(.+?)\s{2,}([MF])\s+([BMR])\s+(\d+(?:,\d+)?)\s+(\d+(?:,\d+)?)\s+(\d+%)\s+(\d+(?:,\d+)?)\s*")

(defn supported? [pages]
  (let [text (str/join "\n" pages)]
    (and (str/includes? text title) (str/includes? text date-line)
         (str/includes? text "Classifica"))))

(defn- heading [line]
  (when-let [[_ category discipline] (re-matches heading-pattern line)]
    {:category category :discipline discipline :gender (if (= category "maschile") "M" "F")}))

(defn- field [value] {:status (if (nil? value) :unknown :parsed) :value value})

(defn- candidate [source-line context heading-line]
  (let [line (:text source-line)
        match (re-matches row-pattern line)
        [_ rank club name gender type distance bonus uncertain-exit points] match
        parsed (when (and match context (= gender (:gender context))
                          (= type ({"bipinne" "B" "monopinna" "M" "rana" "R"} (:discipline context))))
                 (merge context {:event-date event-date :source-name name :club club
                                 :rank (parse-long rank) :type type :distance distance
                                 :bonus bonus :uncertain-exit uncertain-exit :points points
                                 :unit "m"}))]
    {:coordinates {:page (:page source-line) :line (:line source-line)
                   :column-start 1 :column-end (inc (count line))}
     :source-lines [source-line]
     :metadata-evidence (if heading-line [heading-line] [])
     :raw {:line line :fields (when match {:rank rank :club club :source-name name
                                           :gender gender :type type :distance distance
                                           :bonus bonus :uncertain-exit uncertain-exit :points points})}
     :parse-status (if parsed :parsed :unparsed)
     :parsed parsed
     :fields (into {} (map (fn [[k v]] [k (field v)]) parsed))
     :review-status :unreviewed
     :unresolved-reasons (cond-> [:owner-review-required]
                           (nil? parsed) (conj :unparsed-source-line))}))

(defn parse-pages [sha256 pages]
  (when-not (contains? source-sha256s sha256)
    (throw (ex-info "San Mauro parser is bound to different source PDFs" {})))
  (let [expected-gender (if (= sha256 male-sha256) "M" "F")
        processed (mapv
                   (fn [page-index text]
                     (let [lines (mapv (fn [i s] {:page (inc page-index) :line (inc i) :text s})
                                       (range) (str/split text #"\n" -1))
                           nonblank (filterv #(not (str/blank? (:text %))) lines)
                           result (reduce
                                   (fn [{:keys [context heading-line] :as acc} line]
                                     (let [new-heading (heading (:text line))]
                                       (cond
                                         new-heading (-> acc (assoc :context new-heading :heading-line line)
                                                         (update :noncandidate conj (assoc line :classification :heading)))
                                         (re-find #"^\s*\d+\s+" (:text line))
                                         (update acc :candidates conj (candidate line context heading-line))
                                         :else (update acc :noncandidate conj
                                                       (assoc line :classification :metadata)))))
                                   {:context nil :heading-line nil :candidates [] :noncandidate []}
                                   nonblank)
                           candidates (:candidates result)
                           parsed-count (count (filter #(= :parsed (:parse-status %)) candidates))
                           unexpected? (or (not (supported? [text]))
                                           (some #(not= expected-gender (:gender %))
                                                 (keep (comp heading :text) nonblank)))
                           status (cond (empty? nonblank) :needs-OCR
                                        unexpected? :unsupported-needs-parser
                                        :else :needs-review)]
                       {:page (inc page-index) :text text :lines lines :status status
                        :candidates candidates :noncandidate-lines (:noncandidate result)
                        :reconciliation {:page (inc page-index)
                                         :supported? (= :needs-review status)
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
     :reconciliation {:page-count (count pages) :coverage (if (seq unsupported) :partial :complete)
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
