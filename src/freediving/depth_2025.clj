(ns freediving.depth-2025
  "Additional bounded 2025 CMAS depth layouts. Text coordinates are not PDF geometry."
  (:require [clojure.string :as str]))

(def parser-version "cmas-2025-depth/1")
(def title-pattern #"2025 CMAS World Championship Freediving Depth")
(def section-pattern #"(CWT|FIM|CNF|CWT-BF) ((?:Men|MEN|Women) (?:SENIORS|MASTERS M[123](?: \+ M[123])?))")
(def subsection-pattern #"(?:Masters|MASTERS) M[123] - (?:Men|Women)")
(defn supported? [pages]
  (boolean (some (fn [page]
                   (and (re-find title-pattern page)
                        (some (fn [[_ discipline category]]
                                (not (and (= category "Women SENIORS") (#{"CWT" "FIM" "CNF"} discipline))))
                              (re-seq section-pattern page)))) pages)))
(defn- nonblank [s] (when-not (str/blank? s) s))
(defn- field [v] {:status (if (nil? v) :unknown :parsed) :value v})
(defn- date-value [s]
  (when s
    (try (let [[d m y] (str/split s #"/")]
           (str (java.time.LocalDate/of (parse-long y) (parse-long m) (parse-long d))))
         (catch java.time.DateTimeException _ nil))))
(defn- only-value [xs] (when (= 1 (count xs)) (first xs)))
(def header-pattern #"RANK\s+SURNAME & NAME\s+NAT(?:\s+CATEGORY)?(?:\s+DEPTH(?:\s+PEN\.\s+STATUS\s+NOTES)?)?")
(defn- context [lines]
  (let [texts (map (comp str/trim :text) lines)
        section (only-value (distinct (keep #(re-matches section-pattern %) texts)))
        [_ discipline document-category] section
        subsection (only-value (filter #(re-matches subsection-pattern %) texts))
        category (or subsection document-category)
        h (only-value (filter #(re-matches header-pattern (str/trim (:text %))) lines))
        u (only-value (filter #(re-matches #"DEC\.\s+FINAL" (str/trim (:text %))) lines))
        header-lines (when h (filter #(<= (:line h) (:line %) (+ (:line h) 3)) lines))
        full? (and h (str/includes? (:text h) "STATUS"))
        lower (only-value (filter #(re-matches #"DEPTH\s+DEPTH" (str/trim (:text %))) header-lines))
        mid (only-value (filter #(re-matches #"DEPTH\s+PEN\." (str/trim (:text %))) header-lines))
        bottom (only-value (filter #(re-matches #"DEPTH" (str/trim (:text %))) header-lines))
        tail (only-value (filter #(re-matches #"STATUS\s+NOTES" (str/trim (:text %))) header-lines))
        columns (if full? (:text h) (when (and mid tail (<= (count (:text mid)) (count (:text tail)))) (str (:text mid) (subs (:text tail) (count (:text mid))))))
        centers (when (and h u columns (every? #(str/includes? columns %) ["DEPTH" "PEN." "STATUS" "NOTES"]))
                  [(if (str/includes? (:text h) "CATEGORY")
                     (- (str/index-of (:text u) "DEC.") 8)
                     (+ (str/index-of (:text h) "NAT") 1)) (+ (str/index-of (:text u) "DEC.") 2)
                   (+ (str/index-of columns "DEPTH") 2) (+ (str/index-of columns "PEN.") 2)
                   (+ (str/index-of (:text u) "FINAL") 2) (+ (str/index-of columns "STATUS") 3)])
        compatible? (and category document-category
                         (if subsection
                           (let [[_ age gender] (re-matches #"(?:Masters|MASTERS) (M[123]) - (Men|Women)" subsection)]
                             (and (str/includes? document-category "MASTERS")
                                  (str/includes? document-category age)
                                  (str/starts-with? (str/lower-case document-category) (str/lower-case gender))))
                           (not (str/includes? document-category "+"))))
        valid? (and discipline compatible? h u
                    (= 1 (count (filter #(re-matches title-pattern %) texts)))
                    (if full? (and lower (< (:line u) (:line h) (:line lower)))
                        (and mid bottom tail (< (:line u) (:line h) (:line mid) (:line bottom) (:line tail))))
                    centers (apply < centers))]
    {:discipline discipline :category category :document-category document-category
     :event-date (date-value (only-value (distinct (filter #(re-matches #"\d{2}/\d{2}/\d{4}" %) texts))))
     :valid? valid? :header-line (:line (if full? lower tail))
     :inline-category? (and h (str/includes? (:text h) "CATEGORY"))
     :cuts (when valid? (conj (mapv #(quot (+ %1 %2) 2) centers (rest centers)) (if (str/includes? (:text h) "CATEGORY")
                                                                                  (+ (str/index-of columns "STATUS") 7)
                                                                                  (str/index-of columns "NOTES"))))
     :metadata-evidence (vec (filter #(or (re-matches title-pattern (str/trim (:text %)))
                                          (re-matches section-pattern (str/trim (:text %)))
                                          (re-matches subsection-pattern (str/trim (:text %)))
                                          (re-matches #"\d{2}/\d{2}/\d{4}" (str/trim (:text %)))
                                          (contains? (set [u h lower mid bottom tail]) %)) lines))}))
(defn- cells [text cuts]
  (mapv (fn [start end] (nonblank (str/trim (subs text (min start (count text)) (min end (count text))))))
        (cons 0 cuts) (conj cuts (count text))))
(def numeric-keys [:declared-depth :attempted-depth :penalty :final-depth])
(defn- parse-row [text ctx]
  (when (:valid? ctx)
    (let [[prefix declared depth penalty final status notes] (cells text (:cuts ctx))
          [_ rank name representation inline-category] (when prefix
                                                         (re-matches (if (:inline-category? ctx)
                                                                       #"\s*(?:(\d+)\s+)?(.+?)\s+(CMAS1|AIN|[A-Z]{3})\s+(Men Senior|Men Master M3)\s*"
                                                                       #"\s*(?:(\d+)\s+)?(.+?)\s+(CMAS1|AIN|[A-Z]{3})\s*") prefix))
          category (or inline-category (:category ctx))
          numeric [declared depth penalty final]
          valid-numbers? (every? #(or (nil? %) (and (re-matches #"\d+" %) (some? (parse-long %)))) numeric)
          raw (when name {:rank rank :source-name name :representation representation :category category
                          :declared-depth declared :attempted-depth depth :penalty penalty :final-depth final :status status :notes notes})
          valid? (and name
                      (or (not (:inline-category? ctx))
                          (= [(:discipline ctx) (:document-category ctx) inline-category]
                             (case inline-category
                               "Men Senior" ["CWT" "MEN SENIORS" "Men Senior"]
                               "Men Master M3" ["CWT" "MEN MASTERS M3" "Men Master M3"]
                               nil)))
                      (every? (fn [cut] (or (>= cut (count text))
                                            (Character/isWhitespace (.charAt text (dec cut)))
                                            (Character/isWhitespace (.charAt text cut)))) (:cuts ctx))
                      valid-numbers? (or (nil? rank) (some? (parse-long rank)))
                      (case status
                        "DNS" (and (nil? rank) declared (or (nil? depth) (= 0 (parse-long depth))) (nil? penalty) (nil? final))
                        "DSQ" (and (nil? rank) declared depth (nil? final))
                        "PEN" (and rank declared depth penalty final)
                        "WR MM3" (and (= "Men Master M3" category) rank declared depth (nil? penalty) final)
                        nil (and rank declared depth (nil? penalty) final)
                        false))]
      {:raw raw
       :parsed (when valid? (merge raw (zipmap numeric-keys (map #(when % (parse-long %)) numeric))
                                   {:rank (when rank (parse-long rank)) :unit nil :federation "CMAS"
                                    :category category :discipline (:discipline ctx) :event-date (:event-date ctx)}))})))
(defn- classified? [line]
  (let [s (str/trim (:text line))]
    (boolean (or (re-matches title-pattern s) (re-matches section-pattern s) (re-matches subsection-pattern s)
                 (re-matches #"\d{2}/\d{2}/\d{4}" s)
                 (re-matches header-pattern s)
                 (re-matches #"Result|DEC\.\s+FINAL|DEPTH\s+DEPTH|DEPTH\s+PEN\.|DEPTH|STATUS\s+NOTES" s)
                 (re-matches #"(?:Report Created \d{2}/\d{2}/\d{4} \d{2}:\d{2}(?:\s+\d+ of \d+)?|\d+ of \d+)" s)
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
                                     (some? (get-in previous [:parsed :notes])))]
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

(defn- section-candidates [lines]
  (let [starts (keep-indexed #(when (re-matches subsection-pattern (str/trim (:text %2))) %1) lines)]
    (if (seq starts)
      (let [prefix (subvec lines 0 (first starts))
            prefix (filterv #(not (or (re-matches header-pattern (str/trim (:text %)))
                                      (re-matches #"DEC\.\s+FINAL|DEPTH\s+DEPTH" (str/trim (:text %))))) prefix)]
        (into (page-candidates prefix (assoc (context prefix) :valid? false)) (mapcat (fn [start end]
                                                                                        (let [section (into prefix (subvec lines start end))]
                                                                                          (page-candidates (subvec lines start end) (context section))))
                                                                                      starts (concat (rest starts) [(count lines)]))))
      (page-candidates lines (context lines)))))

(defn parse-pages [pages]
  (let [page-data (mapv (fn [i text] {:page (inc i) :text text :status (if (str/blank? text) :needs-OCR :text-extracted)
                                      :lines (mapv (fn [n s] {:line (inc n) :text s}) (range) (str/split text #"\n" -1))}) (range) pages)
        lines (vec (for [p page-data l (:lines p) :when (not (str/blank? (:text l)))] (assoc l :page (:page p))))
        page-lines (mapv (fn [p] (filterv #(= (:page p) (:page %)) lines)) page-data)
        candidates (vec (mapcat section-candidates page-lines))
        parsed (count (filter #(= :parsed (:parse-status %)) candidates))]
    {:parser-version parser-version :status (if (some #(= :needs-OCR (:status %)) page-data) :needs-OCR :needs-review)
     :pages page-data :candidates candidates
     :reconciliation {:page-count (count pages) :candidate-count (count candidates) :parsed-count parsed
                      :unparsed-count (- (count candidates) parsed) :unresolved-count (count candidates)
                      :nonblank-line-count (count lines) :noncandidate-lines (filterv classified? lines) :status :unreviewed}
     :publication {:status :blocked :reasons [:owner-review-required :reconciliation-unreviewed :units-not-explicit]}}))
