(ns freediving.athens
  (:require [clojure.string :as str]))

(def parser-version "cmas-athens-pool/4")
(def title "2025 CMAS WORLD CHAMPIONSHIP FREEDIVING INDOOR, GREECE")
(defn supported? [pages] (boolean (some #(str/includes? % title) pages)))
(defn- field [v] {:status (if (nil? v) :unknown :parsed) :value v})
(defn- decimal [s] (when s (bigdec (str/replace s "," "."))))
(defn- metadata-kind [s]
  (cond
    (= s title) :event-title
    (re-matches #"MAY, \d{1,2}, \d{4}" s) :event-date
    (re-matches #"(?:DNF|DYNBF|DYN|STA)\s+FINAL RESULTS" s) :discipline
    (re-matches #"(?:JUNIORS|SENIORS|MASTERS M[123]) – (?:WOMEN|MEN)" s) :category
    (or (re-matches #"#\s+Name & surname\s+Country\s+Final Result\s+Notes" s)
        (re-matches #"#\s+Name & surname\s+Country(?:\s+Realized\s+Final\s+Notes)?" s)
        (re-matches #"Realized\s+Final\s+Notes" s)
        (re-matches #"Distance \(m\)\s+Distance \(m\)" s)) :column-header))
(defn- section-line? [text]
  (boolean (re-find #"^(?:.*FINAL RESULTS|2025 CMAS|MAY,|JUNIORS|SENIORS|MASTERS)" (str/trim text))))

(defn- context-for [lines previous]
  (let [by-kind (group-by #(metadata-kind (str/trim (:text %))) lines)
        fresh? (some #(section-line? (:text %)) lines)
        malformed? (some #(and (section-line? (:text %))
                               (nil? (metadata-kind (str/trim (:text %))))) lines)
        sta? (some #(re-matches #"STA\s+FINAL RESULTS" (str/trim (:text %))) lines)
        time-header? (some #(re-matches #"#\s+Name & surname\s+Country\s+Final Result\s+Notes" (str/trim (:text %))) lines)
        distance-columns? (some #(re-find #"Realized\s+Final\s+Notes" (:text %)) lines)
        unit? (some #(re-matches #"Distance \(m\)\s+Distance \(m\)" (str/trim (:text %))) lines)
        table? (some #(re-find #"^\s*#\s+Name & surname\s+Country" (:text %)) lines)]
    (if fresh?
      (when (and (not malformed?) (= 1 (count (:event-title by-kind))) (= 1 (count (:discipline by-kind)))
                 (= 1 (count (:event-date by-kind))) (= 1 (count (:category by-kind))) (if sta? (and time-header? (not unit?) (not distance-columns?)) (and unit? (not time-header?))) table?)
        (let [date-line (first (:event-date by-kind))
              [_ day year] (re-matches #"MAY, (\d{1,2}), (\d{4})" (str/trim (:text date-line)))
              date (try (str (java.time.LocalDate/of (parse-long year) 5 (parse-long day)))
                        (catch java.time.DateTimeException _ nil))]
          (when date
            {:category (str/trim (:text (first (:category by-kind)))) :discipline (first (str/split (str/trim (:text (first (:discipline by-kind)))) #"\s+"))
             :event-date date :unit (when-not sta? "m")
             :evidence (vec (mapcat #(get by-kind %) [:event-title :event-date :discipline :category :column-header]))})))
      (when (and previous (if (= "STA" (:discipline previous)) (and time-header? (not unit?) (not distance-columns?)) (and unit? (not time-header?))) table?)
        (update previous :evidence into (:column-header by-kind))))))
(defn- positioning-header [context]
  (let [latest-page (reduce max 0 (map :page (:evidence context)))]
    (some #(when (and (= latest-page (:page %))
                      (re-find (if (= "STA" (:discipline context)) #"Final Result\s+Notes" #"Realized\s+Final\s+Notes") (:text %))) (:text %))
          (reverse (:evidence context)))))

(defn- final-only-dns [text context]
  (let [matcher (re-matcher #"\s*(?:(\d+)\s+)?(.+?)\s+(CMAS1|AIN|[A-Z]{3})\s+(\d+(?:,\d+)?)\s+(DNS)\s*" text)
        header (positioning-header context)]
    (when (and (= "DYNBF" (:discipline context)) header (.matches matcher)
               (<= (.indexOf header "Final") (.start matcher 4))
               (< (.start matcher 4) (.indexOf header "Notes")))
      [text (.group matcher 1) (.group matcher 2) (.group matcher 3) nil (.group matcher 4) nil "DNS"])))

(defn- dyn-realized-only [text context]
  (let [matcher (re-matcher #"\s*(?:(\d+)\s+)?(.+?)\s+(CMAS1|AIN|[A-Z]{3})\s+(\d+(?:,\d+)?)\s+(DQ .+?)\s*" text)
        header (positioning-header context)]
    (when (and (= "DYN" (:discipline context)) header (.matches matcher)
               (<= (.indexOf header "Realized") (.start matcher 4))
               (< (.start matcher 4) (.indexOf header "Final")))
      [text (.group matcher 1) (.group matcher 2) (.group matcher 3) (.group matcher 4) nil (.group matcher 5) nil])))

(defn- distance-row [text context]
  (when-let [[_ rank name representation realized final notes dns]
             (or (re-matches #"\s*(?:(\d+)\s+)?(.+?)\s+(CMAS1|AIN|[A-Z]{3})\s+(?:(\d+(?:,\d+)?)\s+(\d+(?:,\d+)?)(?:\s+(.*?))?|(DNS))\s*" text)
                 (final-only-dns text context)
                 (dyn-realized-only text context))]
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
                     (and (= "DYN" (:discipline context))
                          (re-matches #"(?:GOLD MEDAL,|WORLD RECORD SENIORS|DQ SP OK DIR|DQ EQUIPMENT)" notes))
                     (re-matches #"(?:PANAMERICAN RECORD|DOLPHIN KICK|WALL AT START|DNS|DQ (?:SP(?: CHIN| NO OK)?|SURFACE BO|UW BO)(?:, DQ (?:SP(?: CHIN| NO OK)?|SURFACE BO|UW BO))*|(?:GOLD|SILVER|BRONZE) MEDAL(?:, WORLD RECORD(?: SENIORS| MASTERS M[123])?)?|WORLD RECORD MASTERS M[123])" notes)))
        {:raw-fields raw :parsed parsed}))))

(defn- time-components [token]
  ;; A colon alone does not establish minutes/seconds. Preserve syntax only.
  (when-let [[_ a b fraction] (re-matches #"(\d{2}):(\d{2})(?:[.,](\d+))?" (or token ""))]
    {:components [(parse-long a) (parse-long b)] :fraction fraction
     :fraction-digits (count fraction) :notation :colon-separated}))

(defn- sta-row [text context]
  (when-let [[_ rank name representation final notes]
             (re-matches #"\s*(?:(\d+)\s+)?(.+)\s+(CMAS1|AIN|[A-Z]{3})\s+(\S+)(?:\s+(.*?))?\s*" text)]
    (when (and (not (re-find #"\d" name))
               (or (nil? notes)
                   (re-matches #"(?:(?:GOLD|SILVER|BRONZE) MEDAL(?:, (?:WORLD RECORD MASTERS M[123]|PANAMERICAN RECORD))?|WORLD RECORD MASTERS M[123]|DQ(?: (?:TOUCH|SP(?: CHIN)?|SURFACE BO|UW BO|ASSIST))?)" notes)))
      (let [time (time-components final)
            status (second (re-find #"^(DQ)(?:\s|$)" (or notes "")))]
        {:raw-fields {:rank rank :source-name name :representation representation
                      :final-time final :realized-time nil :penalty nil :notes notes :status status}
         :parsed (when time
                   (merge (dissoc context :evidence)
                          {:federation "CMAS" :rank (when rank (parse-long rank))
                           :source-name name :representation representation :final-time time
                           :final-duration nil :realized-time nil :penalty nil :notes notes :status status}))}))))

(defn- row [text context]
  (if (= "STA" (:discipline context)) (sta-row text context) (distance-row text context)))

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
            wrapped? (and (#{"DYNBF" "DYN" "STA"} (:discipline context)) next-row
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
        r (when (and (not wrapped?) (:parsed evidence-row)) evidence-row)
        invalid-time? (and (= "STA" (:discipline context))
                           (get-in evidence-row [:raw-fields :final-time])
                           (nil? (time-components (get-in evidence-row [:raw-fields :final-time]))))]
    (cond-> {:coordinates {:page page :line line :column-start 1 :column-end (inc (count text))}
             :raw {:line (str/join "\n" (map :text source-lines)) :fields (cond-> (:raw-fields evidence-row) wrapped? (dissoc :source-name :rank))}
             :metadata-evidence (:evidence context)
             :parse-status (if r :parsed :unparsed) :parsed (:parsed r)
             :fields (into {} (map (fn [[k v]] [k (field v)]) (:parsed r)))
             :review-status :unreviewed
             :unresolved-reasons (cond-> [:owner-review-required]
                                   (nil? r) (conj :unparsed-source-line)
                                   invalid-time? (conj :invalid-time-syntax)
                                   wrapped? (conj :ambiguous-wrapped-name)
                                   (and r (nil? (get-in r [:parsed :status]))) (conj :source-status-not-explicit))}
      invalid-time? (assoc-in [:fields :final-time] {:status :invalid :value nil :reason :invalid-time-syntax})
      (or wrapped? (= "STA" (:discipline context))) (assoc :source-lines source-lines)
      (= "STA" (:discipline context))
      (-> (update :unresolved-reasons conj :time-unit-not-explicit)
          (assoc-in [:fields :unit] {:status :ambiguous :value nil :reason :time-unit-not-explicit})
          (assoc-in [:fields :final-duration] {:status :unknown :value nil :reason :time-unit-not-explicit})))))

(defn- dyn-candidates [lines context]
  (loop [remaining (seq lines) candidates [] noncandidate []]
    (if-let [a (first remaining)]
      (let [[_ b c] remaining
            ra (row (:text a) context)
            rc (when c (row (:text c) context))
            shared? (and ra b rc (nil? (get-in ra [:parsed :rank]))
                         (nil? (get-in rc [:parsed :rank]))
                         (re-matches #"\s*\d+\s*" (:text b))
                         (= (inc (:line a)) (:line b)) (= (inc (:line b)) (:line c)))
            joined-note (when (and a c) (str (str/trim (:text a)) " " (str/trim (:text c))))
            multiline? (and b c
                            (if (= "STA" (:discipline context))
                              (#{"SILVER MEDAL, PANAMERICAN RECORD" "GOLD MEDAL, WORLD RECORD MASTERS M2"} joined-note)
                              (= "GOLD MEDAL, WORLD RECORD SENIORS" joined-note))
                            (:parsed (row (:text b) context))
                            (nil? (get-in (row (:text b) context) [:parsed :notes]))
                            (when-let [header (positioning-header context)]
                              (every? #(>= (count (take-while (fn [ch] (= ch \space)) (:text %)))
                                           (.indexOf header "Notes")) [a c]))
                            (= (inc (:line a)) (:line b)) (= (inc (:line b)) (:line c)))]
        (cond
          shared?
          (let [evidence {:kind :ambiguous-merged-cells :source-lines [a b c]}
                uncertain (fn [line]
                            (-> (candidate [line] context)
                                (update-in [:raw :fields] (fn [fields] (-> fields (assoc :note-fragment (:notes fields)) (dissoc :notes :status))))
                                (assoc :parse-status :unparsed :parsed nil
                                       :group-evidence evidence
                                       :unresolved-reasons (cond-> [:owner-review-required :unparsed-source-line :ambiguous-merged-cells]
                                                             (= "STA" (:discipline context)) (conj :time-unit-not-explicit)))
                                (update :fields #(if (= "STA" (:discipline context)) (select-keys % [:unit :final-duration]) {}))))]
            (recur (drop 3 remaining) (into candidates [(uncertain a) (uncertain c)])
                   (conj noncandidate (assoc b :classification :ambiguous-group-evidence))))
          multiline?
          (let [r (candidate [b] context)]
            (recur (drop 3 remaining)
                   (conj candidates (-> r
                                        (assoc :source-lines [a b c]
                                               :coordinates {:page (:page a) :line (:line a) :column-start 1 :column-end (inc (count (:text a)))}
                                               :repairs [{:operation :join-note-lines :field :notes
                                                          :source-lines [a c] :separator " "}])
                                        (assoc-in [:parsed :notes] joined-note)
                                        (assoc-in [:fields :notes] (field joined-note))
                                        (assoc-in [:raw :line] (str/join "\n" (map :text [a b c])))
                                        (assoc-in [:raw :note-lines] [a c]))) noncandidate))
          :else
          (let [group (first (source-groups remaining context))]
            (recur (drop (count group) remaining) (conj candidates (candidate group context)) noncandidate))))
      {:candidates candidates :noncandidate noncandidate})))

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
                                   dyn (when (and supported (#{"DYN" "STA"} (:discipline context)))
                                         (dyn-candidates (remove classification lines) context))
                                   metadata (into metadata (:noncandidate dyn))
                                   candidates (if dyn (:candidates dyn) (if supported
                                                                          (mapv #(candidate % context)
                                                                                (source-groups (remove classification lines) context)) []))
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
