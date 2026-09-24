(ns freediving.novi-sad
  (:require [clojure.string :as str]))

(def parser-version "cmas-novi-sad-dnf-juniors/1")
(def title "2026 CMAS WORLD CHAMPIONSHIP FREEDIVING INDOOR")
(defn supported? [pages] (boolean (some #(str/includes? % title) pages)))
(defn- field [v] {:status (if (nil? v) :unknown :parsed) :value v})
(defn- decimal [s] (when s (bigdec (str/replace s "," "."))))
(defn- metadata-kind [text]
  (let [s (str/trim text)]
    (cond
      (= title s) :event-title
      (re-matches #"NOVI SAD, SERBIA\s+JUNE, 11, 2026" s) :event-date
      (= "DNF" s) :discipline
      (re-matches #"JUNIORS [\u2013\u2014] (?:MEN|WOMEN)" s) :category
      (re-matches #"Realized\s+Final\s+Notes" s) :result-header
      (re-matches #"#\s+Name & surname\s+Country" s) :name-header
      (re-matches #"Distance \(m\)\s+Distance \(m\)" s) :units)))

(defn- dsq-row [text evidence]
  (let [m (re-matcher #"\s*([^\d]+?)\s+(CMAS1|AIN|[A-Z]{3})\s+(\d+(?:,\d+)?)\s+(DSQ (?:SP|SURFACE BO))\s*" text)
        header (some #(when (= :result-header (metadata-kind (:text %))) (:text %)) evidence)]
    ;; A single distance is realized only when its actual column establishes that.
    (when (and header (.matches m)
               (<= (.indexOf header "Realized") (.start m 3))
               (<= (.end m 3) (.indexOf header "Final")))
      [text nil (.group m 1) (.group m 2) (.group m 3) nil (.group m 4)])))

(defn- row [text context evidence]
  (when-let [[_ rank name representation realized final notes]
             (or (re-matches #"\s*(?:(\d+)\s+)?([^\d]+?)\s+(CMAS1|AIN|[A-Z]{3})\s+(\d+(?:,\d+)?)\s+(\d+(?:,\d+)?)(?:\s+((?:GOLD|SILVER|BRONZE) MEDAL))?\s*" text)
                 (dsq-row text evidence))]
    (let [status (when (and notes (str/starts-with? notes "DSQ ")) "DSQ")]
      {:raw-fields {:rank rank :source-name name :representation representation
                    :realized-distance realized :final-distance final :notes notes :status status}
       :parsed (merge context {:federation "CMAS" :rank (some-> rank parse-long)
                               :source-name name :representation representation
                               :realized-distance (decimal realized) :final-distance (decimal final)
                               :notes notes :status status :penalty nil})})))

(defn- candidate [{:keys [page line text] :as source} context evidence]
  (let [r (row text context evidence)]
    {:coordinates {:page page :line line :column-start 1 :column-end (inc (count text))}
     :raw {:line text :fields (:raw-fields r)} :source-lines [source]
     :metadata-evidence evidence :parse-status (if r :parsed :unparsed)
     :parsed (:parsed r) :fields (into {} (map (fn [[k v]] [k (field v)]) (:parsed r)))
     :review-status :unreviewed
     :unresolved-reasons (cond-> [:owner-review-required]
                           (nil? r) (conj :unparsed-source-line)
                           (and r (nil? (get-in r [:parsed :status]))) (conj :source-status-not-explicit))}))

(defn parse-pages [pages]
  (let [processed
        (mapv (fn [i text]
                (let [page (inc i)
                      lines (mapv (fn [j s] {:page page :line (inc j) :text s})
                                  (range) (str/split text #"\n" -1))
                      nonblank (remove #(str/blank? (:text %)) lines)
                      metadata (filter #(metadata-kind (:text %)) nonblank)
                      by-kind (group-by #(metadata-kind (:text %)) metadata)
                      malformed? (some #(and (re-find #"^(?:\d{4} CMAS|NOVI SAD|JUNIORS|SENIORS|MASTERS|DNF|DYN|STA|SPEED|JUNE)" (str/trim (:text %)))
                                             (nil? (metadata-kind (:text %)))) nonblank)
                      supported (and (not malformed?) (every? #(= 1 (count (get by-kind %)))
                                                              [:event-title :event-date :discipline :category :result-header :name-header :units]))
                      context {:event-date "2026-06-11" :discipline "DNF" :unit "m"
                               :category (some-> by-kind :category first :text str/trim)}
                      candidates (if supported (mapv #(candidate % context (vec metadata))
                                                     (remove #(metadata-kind (:text %)) nonblank)) [])
                      parsed (count (filter #(= :parsed (:parse-status %)) candidates))]
                  {:page page :text text :lines lines
                   :status (cond supported :needs-review (empty? nonblank) :needs-OCR :else :unsupported-needs-parser)
                   :candidates candidates
                   :noncandidate-lines (mapv #(assoc % :classification (if supported (metadata-kind (:text %)) :unsupported-page-line))
                                             (if supported metadata nonblank))
                   :reconciliation {:page page :supported? supported :candidate-count (when supported (count candidates))
                                    :parsed-count (when supported parsed) :unparsed-count (when supported (- (count candidates) parsed))
                                    :unresolved-count (when supported (count candidates)) :nonblank-line-count (count nonblank)}}))
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
                      :candidate-count (count candidates) :parsed-count parsed :unparsed-count (- (count candidates) parsed)
                      :unresolved-count (count candidates) :counts-scope :supported-pages-only
                      :nonblank-line-count (reduce + (map #(get-in % [:reconciliation :nonblank-line-count]) processed))
                      :noncandidate-lines (vec (mapcat :noncandidate-lines processed)) :status :unreviewed}
     :publication {:status :blocked :reasons (cond-> [:owner-review-required :reconciliation-unreviewed]
                                               (seq unsupported) (conj :unsupported-pages)
                                               (seq ocr) (conj :needs-OCR))}}))
