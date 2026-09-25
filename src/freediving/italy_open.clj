(ns freediving.italy-open
  "Source-bound, review-only text evidence for the 2026 Italian Open Outdoor PDF."
  (:require [clojure.string :as str]))

(def parser-version "italian-open-outdoor-2026/1")
(def source-sha256 "b29ee59117eedefd4784840be9386b9b7e5cfa86bbb89c18825220b6e2fb5d88")
(def title "Campionati Italiani Open di Apnea Outdoor")
(def heading-pattern #"\s*Classiﬁca (M[123][FM]|OPEN [FM]|PRO [FM]) - (CNF|CWT|CWTB|FIM) (OPEN|PRO) International\s*")
(defn supported? [pages]
  (boolean (some #(str/includes? % title) pages)))

(defn- heading [s]
  (when-let [[_ category discipline class] (re-matches heading-pattern s)]
    (when (= class (if (str/starts-with? category "PRO") "PRO" "OPEN"))
      {:category category :discipline discipline :class class})))

(defn- row-shaped? [s]
  (boolean (re-find #"^\s*(?:\d+\s+)?\S.+?\s+(?:19|20)\d{2}\s+\d+:\d{2}\.\d{2}\s+" s)))

(defn- parse-row [s context]
  (when (and context (row-shaped? s))
    (when-let [[_ left birth right]
               (re-matches #"\s*(.*?)\s{2,}((?:19|20)\d{2})\s{2,}(.+?)\s*" s)]
      (let [cells (str/split (str/trim left) #"\s{2,}")
            rank? (re-matches #"\d+" (first cells))
            cells (if rank? (subvec (vec cells) 1) (vec cells))
            values (str/split (str/trim right) #"\s{2,}")]
        (when (and (= 3 (count cells)) (>= (count values) 4)
                   (re-matches #"\d+:\d{2}\.\d{2}" (first values)))
          {:raw {:rank (when rank? (first (str/split (str/trim left) #"\s{2,}")))
                 :surname (nth cells 0) :given-name (nth cells 1)
                 :club (nth cells 2) :birth-year birth :result-cells values}
           :parsed (merge context {:source-name (str (nth cells 0) " " (nth cells 1))
                                   :club (nth cells 2) :birth-year (parse-long birth)
                                   :rank (when rank? (parse-long (first (str/split (str/trim left) #"\s{2,}"))))})})))))

(defn- other-kind [s]
  (let [x (str/trim s)]
    (cond
      (= x title) :title
      (heading s) :heading
      (or (str/starts-with? x "Posizione")
          (re-find #"^(?:Anno di|nascita|Tempo|dichiarato|realizzato|omologato|Profondità|Differenza)\b" x)) :column-header
      (str/starts-with? x "DQ: Squaliﬁca (vedi note) Penalità:") :legend
      (re-matches #"Giudice Capo: Frosini Andrea\s+Ora:" x) :footer
      (#{"PG" "BO" "DQ" "NP" "(DQ)" "Early turn" "Superficie" "World Record" "World record"} x) :row-annotation)))

(defn- field [v] {:status (if (nil? v) :unknown :parsed) :value v})
(defn- annotation-evidence [nonblank index]
  (let [before (when (pos? index) (nth nonblank (dec index)))
        after (when (< (inc index) (count nonblank)) (nth nonblank (inc index)))]
    (cond-> []
      (and before (#{"PG" "BO" "DQ"} (str/trim (:text before)))) (conj before)
      (and after (#{"Early turn" "Superficie" "NP" "(DQ)" "World Record" "World record"}
                  (str/trim (:text after)))) (conj after))))

(defn- candidate [line context heading-evidence annotations]
  (let [r (parse-row (:text line) context)
        parsed (:parsed r)]
    {:coordinates {:page (:page line) :line (:line line)
                   :column-start 1 :column-end (inc (count (:text line)))}
     :raw {:line (:text line) :fields (:raw r) :annotation-evidence annotations}
     :source-lines [line] :metadata-evidence (if heading-evidence [heading-evidence] [])
     :parse-status (if r :parsed :unparsed) :parsed parsed
     :fields (into {} (map (fn [[k v]] [k (field v)]) parsed))
     :review-status :unreviewed
     :unresolved-reasons (cond-> [:owner-review-required]
                           (nil? r) (conj :unparsed-source-line))}))

(defn parse-pages [pages]
  (let [processed
        (loop [idx 0 context nil heading-evidence nil out []]
          (if (= idx (count pages)) out
              (let [text (nth pages idx) page (inc idx)
                    lines (mapv (fn [i s] {:page page :line (inc i) :text s})
                                (range) (str/split text #"\n" -1))
                    nonblank (vec (remove #(str/blank? (:text %)) lines))
                    headings (vec (filter #(heading (:text %)) nonblank))
                    invalid-heading? (some #(and (str/includes? (:text %) "Classiﬁca")
                                                 (not (heading (:text %)))) nonblank)
                    page-context (if (= 1 (count headings)) (heading (:text (first headings))) context)
                    page-heading (if (= 1 (count headings)) (first headings) heading-evidence)
                    supported (and page-context (not invalid-heading?) (<= (count headings) 1)
                                   (or (some #(= title (str/trim (:text %))) nonblank)
                                       (seq headings) context))
                    metadata (vec (filter #(other-kind (:text %)) nonblank))
                    candidates (if supported
                                 (vec (keep-indexed
                                       (fn [i line]
                                         (when-not (other-kind (:text line))
                                           (candidate line page-context page-heading
                                                      (annotation-evidence nonblank i))))
                                       nonblank)) [])
                    noncandidate (if supported metadata nonblank)
                    parsed (count (filter #(= :parsed (:parse-status %)) candidates))
                    rank-shaped (count (filter #(re-find #"^\s*\d+\s+" (:text %)) nonblank))
                    entry {:page page :text text :lines lines
                           :status (cond (empty? nonblank) :needs-OCR supported :needs-review :else :unsupported-needs-parser)
                           :candidates candidates
                           :noncandidate-lines (mapv #(assoc % :classification (if supported
                                                                                 (other-kind (:text %))
                                                                                 :unsupported-page-line)) noncandidate)
                           :reconciliation {:page page :supported? (boolean supported)
                                            :candidate-count (when supported (count candidates))
                                            :parsed-count (when supported parsed)
                                            :unparsed-count (when supported (- (count candidates) parsed))
                                            :unresolved-count (when supported (count candidates))
                                            :rank-shaped-line-count rank-shaped
                                            :nonblank-line-count (count nonblank)}}]
                (recur (inc idx) (when supported page-context) (when supported page-heading) (conj out entry)))))
        candidates (vec (mapcat :candidates processed))
        unsupported (mapv :page (remove #(= :needs-review (:status %)) processed))
        ocr (mapv :page (filter #(= :needs-OCR (:status %)) processed))
        parsed (count (filter #(= :parsed (:parse-status %)) candidates))]
    {:parser-version parser-version :schema-version 3
     :status (if (seq unsupported) :partial-unsupported-needs-parser :needs-review)
     :pages (mapv #(dissoc % :candidates :noncandidate-lines :reconciliation) processed)
     :candidates candidates
     :reconciliation {:page-count (count pages) :coverage (if (seq unsupported) :partial :complete)
                      :unsupported-pages unsupported :needs-ocr-pages ocr
                      :per-page (mapv :reconciliation processed)
                      :candidate-count (count candidates) :parsed-count parsed
                      :unparsed-count (- (count candidates) parsed) :unresolved-count (count candidates)
                      :rank-shaped-line-count (reduce + (map #(get-in % [:reconciliation :rank-shaped-line-count]) processed))
                      :counts-scope :supported-pages-only
                      :nonblank-line-count (reduce + (map #(get-in % [:reconciliation :nonblank-line-count]) processed))
                      :noncandidate-lines (vec (mapcat :noncandidate-lines processed)) :status :unreviewed}
     :publication {:status :blocked :reasons (cond-> [:owner-review-required :reconciliation-unreviewed]
                                               (seq unsupported) (conj :unsupported-pages)
                                               (seq ocr) (conj :needs-OCR))}}))
