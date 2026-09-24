(ns freediving.depth
  "Bounded 2025 CMAS women depth layout. Text coordinates are not PDF geometry."
  (:require [clojure.string :as str]))

(def parser-version "cmas-women-depth/1")
(def title-pattern #"2025 CMAS World Championship Freediving (?:Outdoor|Depth)")
(def section-pattern #"(CWT|FIM|CNF) Women SENIORS")
(defn supported? [pages]
  (boolean (some #(and (re-find title-pattern %) (re-find section-pattern %)) pages)))
(defn- nonblank [s] (when-not (str/blank? s) s))
(defn- field [v] {:status (if (nil? v) :unknown :parsed) :value v})
(defn- date-value [s]
  (when s
    (try (let [[d m y] (str/split s #"/")]
           (str (java.time.LocalDate/of (parse-long y) (parse-long m) (parse-long d))))
         (catch java.time.DateTimeException _ nil))))
(defn- only-value [xs] (when (= 1 (count xs)) (first xs)))
(defn- context [lines]
  (let [texts (map (comp str/trim :text) lines)
        discipline (only-value (distinct (keep #(second (re-matches section-pattern %)) texts)))
        header (only-value (filter #(re-matches #"RANK SURNAME & NAME\s+NAT\s+DEPTH\s+PEN\.\s+STATUS\s+NOTES" (str/trim (:text %))) lines))
        upper (only-value (filter #(re-matches #"DEC\.\s+FINAL" (str/trim (:text %))) lines))
        lower (only-value (filter #(re-matches #"DEPTH\s+DEPTH" (str/trim (:text %))) lines))
        h (:text header) u (:text upper)
        centers (when (and h u)
                  [(+ (str/index-of h "NAT") 1) (+ (str/index-of u "DEC.") 2)
                   (+ (str/index-of h "DEPTH") 2) (+ (str/index-of h "PEN.") 2)
                   (+ (str/index-of u "FINAL") 2) (+ (str/index-of h "STATUS") 3)])
        valid? (and discipline header upper lower
                    (some #(re-matches title-pattern %) texts)
                    (< (:line upper) (:line header) (:line lower))
                    (apply < centers))]
    {:discipline discipline
     :event-date (date-value (only-value (distinct (filter #(re-matches #"\d{2}/\d{2}/\d{4}" %) texts))))
     :valid? valid? :header-line (:line lower)
     :cuts (when valid? (conj (mapv #(quot (+ %1 %2) 2) centers (rest centers)) (str/index-of h "NOTES")))
     :metadata-evidence (vec (filter #(or (re-matches title-pattern (str/trim (:text %)))
                                          (re-matches section-pattern (str/trim (:text %)))
                                          (re-matches #"\d{2}/\d{2}/\d{4}" (str/trim (:text %)))
                                          (contains? (set [header upper lower]) %)) lines))}))
(defn- cells [text cuts]
  (mapv (fn [start end] (nonblank (str/trim (subs text (min start (count text)) (min end (count text))))))
        (cons 0 cuts) (conj cuts (count text))))
(def numeric-keys [:declared-depth :attempted-depth :penalty :final-depth])
(defn- parse-row [text ctx]
  (when (:valid? ctx)
    (let [[prefix declared depth penalty final status notes] (cells text (:cuts ctx))
          [_ rank name representation] (when prefix (re-matches #"\s*(?:(\d+)\s+)?(.+?)\s+(CMAS1|AIN|[A-Z]{3})\s*" prefix))
          numeric [declared depth penalty final]
          valid-numbers? (every? #(or (nil? %) (and (re-matches #"\d+" %) (some? (parse-long %)))) numeric)
          raw (when name {:rank rank :source-name name :representation representation
                          :declared-depth declared :attempted-depth depth :penalty penalty :final-depth final :status status :notes notes})
          valid? (and name
                      (every? (fn [cut] (or (>= cut (count text))
                                            (Character/isWhitespace (.charAt text (dec cut)))
                                            (Character/isWhitespace (.charAt text cut)))) (:cuts ctx))
                      valid-numbers? (or (nil? rank) (some? (parse-long rank)))
                      (case status
                        "DNS" (and (nil? rank) declared (nil? depth) (nil? penalty) (nil? final))
                        "DSQ" (and (nil? rank) declared depth (nil? penalty) (nil? final))
                        "PEN" (and rank declared depth penalty final)
                        nil (and rank declared depth (nil? penalty) final)
                        false))]
      {:raw raw
       :parsed (when valid? (merge raw (zipmap numeric-keys (map #(when % (parse-long %)) numeric))
                                   {:rank (when rank (parse-long rank)) :unit nil :federation "CMAS"
                                    :category "Women SENIORS" :discipline (:discipline ctx) :event-date (:event-date ctx)}))})))
(defn- classified? [line]
  (let [s (str/trim (:text line))]
    (boolean (or (re-matches title-pattern s) (re-matches section-pattern s)
                 (re-matches #"\d{2}/\d{2}/\d{4}" s)
                 (re-matches #"Result|DEC\.\s+FINAL|DEPTH\s+DEPTH|RANK SURNAME & NAME\s+NAT\s+DEPTH\s+PEN\.\s+STATUS\s+NOTES" s)
                 (re-matches #"Report Created \d{2}/\d{2}/\d{4} \d{2}:\d{2}\s+\d+ of \d+" s)
                 (= s "Data Processing and Timing by Microplus - www.microplustiming.com")))))
(defn- candidate [line ctx]
  (let [{:keys [raw parsed]} (when (> (:line line) (or (:header-line ctx) 0)) (parse-row (:text line) ctx))]
    {:coordinates (assoc (select-keys line [:page :line]) :column-start 1 :column-end (inc (count (:text line))))
     :source-lines [line] :metadata-evidence (:metadata-evidence ctx)
     :raw {:line (:text line) :fields raw}
     :parse-status (if parsed :parsed :unparsed) :parsed parsed
     :fields (into {} (map (fn [[k v]] [k (field v)]))
                   (merge (zipmap [:rank :source-name :representation :declared-depth :attempted-depth :penalty :final-depth :status :notes :unit :event-date :discipline :category] (repeat nil)) parsed))
     :unresolved-reasons (if parsed [:units-not-explicit] [:malformed-or-unsupported-depth-row])
     :review-status :unreviewed}))
(defn- page-candidates [lines ctx]
  (reduce (fn [out line]
            (let [previous (peek out) text (:text line) note (str/trim text)
                  note-column (last (:cuts ctx))
                  continuation? (and note-column (= :parsed (:parse-status previous))
                                     (= (inc (:line (last (:source-lines previous)))) (:line line))
                                     (>= (- (count text) (count (str/triml text))) note-column)
                                     (#{"GOLD MEDAL" "SILVER MEDAL" "BRONZE MEDAL"} note)
                                     (= "EARLY TURN" (get-in previous [:parsed :notes])))]
              (if continuation?
                (let [fragments [(get-in previous [:parsed :notes]) note]
                      joined (str/join " " fragments)
                      source-lines (conj (:source-lines previous) line)]
                  (conj (pop out) (-> previous
                                      (assoc :source-lines source-lines
                                             :repairs [{:operation :join-note-lines :reason :adjacent-aligned-note-continuation}])
                                      (assoc-in [:raw :line] (str/join "\n" (map :text source-lines)))
                                      (assoc-in [:raw :fields :note-fragments] fragments)
                                      (assoc-in [:raw :fields :notes] joined)
                                      (assoc-in [:parsed :notes] joined)
                                      (assoc-in [:fields :notes] (field joined)))))
                (conj out (candidate line ctx))))) [] (remove classified? lines)))

(defn parse-pages [pages]
  (let [page-data (mapv (fn [i text] {:page (inc i) :text text :status (if (str/blank? text) :needs-OCR :text-extracted)
                                      :lines (mapv (fn [n s] {:line (inc n) :text s}) (range) (str/split text #"\n" -1))}) (range) pages)
        lines (vec (for [p page-data l (:lines p) :when (not (str/blank? (:text l)))] (assoc l :page (:page p))))
        candidates (vec (mapcat (fn [p] (let [ls (filterv #(= (:page p) (:page %)) lines) ctx (context ls)]
                                          (page-candidates ls ctx))) page-data))
        parsed (count (filter #(= :parsed (:parse-status %)) candidates))]
    {:parser-version parser-version :status (if (some #(= :needs-OCR (:status %)) page-data) :needs-OCR :needs-review)
     :pages page-data :candidates candidates
     :reconciliation {:page-count (count pages) :candidate-count (count candidates) :parsed-count parsed
                      :unparsed-count (- (count candidates) parsed) :unresolved-count (count candidates)
                      :nonblank-line-count (count lines) :noncandidate-lines (filterv classified? lines) :status :unreviewed}
     :publication {:status :blocked :reasons [:owner-review-required :reconciliation-unreviewed :units-not-explicit]}}))
