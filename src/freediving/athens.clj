(ns freediving.athens
  (:require [clojure.string :as str]))

(def parser-version "cmas-athens-distance/2")
(def title "2025 CMAS WORLD CHAMPIONSHIP FREEDIVING INDOOR, GREECE")
(defn supported? [pages] (boolean (some #(str/includes? % title) pages)))
(defn- field [v] {:status (if (nil? v) :unknown :parsed) :value v})
(defn- decimal [s] (when s (bigdec (str/replace s "," "."))))
(defn- metadata-kind [s]
  (cond
    (= s title) :event-title
    (re-matches #"MAY, \d{1,2}, \d{4}" s) :event-date
    (re-matches #"(?:DNF|DYNBF)\s+FINAL RESULTS" s) :discipline
    (re-matches #"(?:JUNIORS|SENIORS|MASTERS M[123]) – (?:WOMEN|MEN)" s) :category
    (or (re-matches #"#\s+Name & surname\s+Country(?:\s+Realized\s+Final\s+Notes)?" s)
        (re-matches #"Realized\s+Final\s+Notes" s)
        (re-matches #"Distance \(m\)\s+Distance \(m\)" s)) :column-header))
(defn- section-line? [text]
  (boolean (re-find #"^(?:.*FINAL RESULTS|2025 CMAS|MAY,|JUNIORS|SENIORS|MASTERS)" (str/trim text))))

(defn- context-for [lines previous]
  (let [by-kind (group-by #(metadata-kind (str/trim (:text %))) lines)
        fresh? (some #(section-line? (:text %)) lines)
        malformed? (some #(and (section-line? (:text %))
                               (nil? (metadata-kind (str/trim (:text %))))) lines)
        unit? (some #(re-matches #"Distance \(m\)\s+Distance \(m\)" (str/trim (:text %))) lines)
        table? (some #(re-find #"^\s*#\s+Name & surname\s+Country" (:text %)) lines)]
    (if fresh?
      (when (and (not malformed?) (= 1 (count (:event-title by-kind))) (= 1 (count (:discipline by-kind)))
                 (= 1 (count (:event-date by-kind))) (= 1 (count (:category by-kind))) unit? table?)
        (let [date-line (first (:event-date by-kind))
              [_ day year] (re-matches #"MAY, (\d{1,2}), (\d{4})" (str/trim (:text date-line)))
              date (try (str (java.time.LocalDate/of (parse-long year) 5 (parse-long day)))
                        (catch java.time.DateTimeException _ nil))]
          (when date
            {:category (str/trim (:text (first (:category by-kind)))) :discipline (first (str/split (str/trim (:text (first (:discipline by-kind)))) #"\s+"))
             :event-date date :unit "m"
             :evidence (vec (mapcat #(get by-kind %) [:event-title :event-date :discipline :category :column-header]))})))
      (when (and previous unit? table?)
        (update previous :evidence into (:column-header by-kind))))))
(defn- positioning-header [context]
  (let [latest-page (reduce max 0 (map :page (:evidence context)))]
    (some #(when (and (= latest-page (:page %))
                      (re-find #"Realized\s+Final\s+Notes" (:text %))) (:text %))
          (reverse (:evidence context)))))

(defn- final-only-dns [text context]
  (let [matcher (re-matcher #"\s*(?:(\d+)\s+)?(.+?)\s+(CMAS1|AIN|[A-Z]{3})\s+(\d+(?:,\d+)?)\s+(DNS)\s*" text)
        header (positioning-header context)]
    (when (and (= "DYNBF" (:discipline context)) header (.matches matcher)
               (<= (.indexOf header "Final") (.start matcher 4))
               (< (.start matcher 4) (.indexOf header "Notes")))
      [text (.group matcher 1) (.group matcher 2) (.group matcher 3) nil (.group matcher 4) nil "DNS"])))

(defn- row [text context]
  (when-let [[_ rank name representation realized final notes dns]
             (or (re-matches #"\s*(?:(\d+)\s+)?(.+?)\s+(CMAS1|AIN|[A-Z]{3})\s+(?:(\d+(?:,\d+)?)\s+(\d+(?:,\d+)?)(?:\s+(.*?))?|(DNS))\s*" text)
                 (final-only-dns text context))]
    (let [notes (or dns notes)
          status (second (re-find #"^(DQ|DNS)(?:\s|$)" (or notes "")))
          raw {:rank rank :source-name name :representation representation
               :realized-distance realized :final-distance final :notes notes :status status}
          parsed (merge (dissoc context :evidence)
                        {:federation "CMAS" :rank (when rank (parse-long rank))
                         :source-name name :representation representation
                         :realized-distance (decimal realized) :final-distance (decimal final)
                         :notes notes :status status :penalty nil})]
      (when (and (not (re-find #"\d" name))
                 (or (nil? notes)
                     (re-matches #"(?:PANAMERICAN RECORD|DOLPHIN KICK|WALL AT START|DNS|DQ (?:SP(?: CHIN| NO OK)?|SURFACE BO|UW BO)(?:, DQ (?:SP(?: CHIN| NO OK)?|SURFACE BO|UW BO))*|(?:GOLD|SILVER|BRONZE) MEDAL(?:, WORLD RECORD(?: SENIORS| MASTERS M[123])?)?|WORLD RECORD MASTERS M[123])" notes)))
        {:raw-fields raw :parsed parsed}))))

(defn- footer? [line lines context]
  (let [header (positioning-header context)
        previous (last (take-while #(not= (:line %) (:line line)) lines))
        column (count (take-while #(= \space %) (:text line)))]
    (and (= "DYNBF" (:discipline context)) header previous
         (= line (last lines)) (re-matches #"\s+\d+\s*" (:text line))
         (>= (- (:line line) (:line previous)) 3)
         (<= (.indexOf header "Realized") column) (< column (.indexOf header "Notes")))))

(defn- source-groups [lines context]
  (loop [remaining (seq lines) groups []]
    (if-let [line (first remaining)]
      (let [next-line (second remaining)
            next-row (when next-line (row (:text next-line) context))
            wrapped? (and (= "DYNBF" (:discipline context)) next-row
                          (= (inc (:line line)) (:line next-line))
                          (nil? (get-in next-row [:parsed :rank]))
                          (re-matches #"\s*\d+\s+[^\d]+" (:text line))
                          (nil? (row (:text line) context)))]
        (recur (if wrapped? (nnext remaining) (next remaining))
               (conj groups (if wrapped? [line next-line] [line]))))
      groups)))

(defn- candidate [source-lines context]
  (let [{:keys [text page line]} (first source-lines)
        wrapped? (> (count source-lines) 1)
        evidence-row (row (:text (last source-lines)) context)
        r (when-not wrapped? evidence-row)]
    (cond-> {:coordinates {:page page :line line :column-start 1 :column-end (inc (count text))}
             :raw {:line (str/join "\n" (map :text source-lines)) :fields (cond-> (:raw-fields evidence-row) wrapped? (dissoc :source-name :rank))}
             :metadata-evidence (:evidence context)
             :parse-status (if r :parsed :unparsed) :parsed (:parsed r)
             :fields (into {} (map (fn [[k v]] [k (field v)]) (:parsed r)))
             :review-status :unreviewed
             :unresolved-reasons (cond-> [:owner-review-required]
                                   (nil? r) (conj :unparsed-source-line)
                                   wrapped? (conj :ambiguous-wrapped-name)
                                   (and r (nil? (get-in r [:parsed :status]))) (conj :source-status-not-explicit))}
      wrapped? (assoc :source-lines source-lines))))

(defn parse-pages [pages]
  (let [page-data (mapv (fn [i text] {:page (inc i) :text text
                                      :lines (mapv (fn [j s] {:page (inc i) :line (inc j) :text s})
                                                   (range) (str/split text #"\n" -1))}) (range) pages)
        processed (:results
                   (reduce (fn [{:keys [context results]} page]
                             (let [lines (vec (remove #(str/blank? (:text %)) (:lines page)))
                                   context (context-for lines context)
                                   supported (some? context)
                                   classification #(or (metadata-kind (str/trim (:text %)))
                                                       (when (footer? % lines context) :page-footer))
                                   metadata (if supported
                                              (mapv #(assoc % :classification (classification %))
                                                    (filter classification lines))
                                              (mapv #(assoc % :classification :unsupported-page-line) lines))
                                   candidates (if supported
                                                (mapv #(candidate % context)
                                                      (source-groups (remove classification lines) context)) [])
                                   parsed (count (filter #(= :parsed (:parse-status %)) candidates))]
                               {:context context :results (conj results
                                                                (assoc page :status (cond supported :needs-review (empty? lines) :needs-OCR :else :unsupported-needs-parser)
                                                                       :candidates candidates :noncandidate-lines metadata
                                                                       :reconciliation {:page (:page page) :supported? supported
                                                                                        :candidate-count (when supported (count candidates))
                                                                                        :parsed-count (when supported parsed)
                                                                                        :unparsed-count (when supported (- (count candidates) parsed))
                                                                                        :unresolved-count (when supported (count candidates))
                                                                                        :nonblank-line-count (count lines)}))}))
                           {:context nil :results []} page-data))
        candidates (vec (mapcat :candidates processed))
        unsupported (mapv :page (remove #(= :needs-review (:status %)) processed))
        parsed (count (filter #(= :parsed (:parse-status %)) candidates))]
    {:parser-version parser-version :schema-version 3 :status (if (seq unsupported) :partial-unsupported-needs-parser :needs-review)
     :pages (mapv #(dissoc % :candidates :noncandidate-lines :reconciliation) processed)
     :candidates candidates
     :reconciliation {:page-count (count pages) :coverage (if (seq unsupported) :partial :complete)
                      :unsupported-pages unsupported
                      :needs-ocr-pages (mapv :page (filter #(= :needs-OCR (:status %)) processed)) :per-page (mapv :reconciliation processed)
                      :candidate-count (count candidates) :parsed-count parsed
                      :unparsed-count (- (count candidates) parsed) :unresolved-count (count candidates)
                      :counts-scope :supported-pages-only
                      :nonblank-line-count (reduce + (map #(get-in % [:reconciliation :nonblank-line-count]) processed))
                      :noncandidate-lines (vec (mapcat :noncandidate-lines processed)) :status :unreviewed}
     :publication {:status :blocked :reasons (cond-> [:owner-review-required :reconciliation-unreviewed]
                                               (seq unsupported) (conj :unsupported-pages)
                                               (some #(= :needs-OCR (:status %)) processed) (conj :needs-OCR))}}))
