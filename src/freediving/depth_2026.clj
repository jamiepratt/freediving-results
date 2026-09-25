(ns freediving.depth-2026
  "Bounded 2026 CMAS depth tables. Every page supplies its own category and headers."
  (:require [clojure.string :as str]
            [freediving.depth-2025 :as geometry]))

(def parser-version "cmas-2026-depth/1")
(def disciplines {"CONSTANT WEIGHT WITH FINS" "CWT" "FREE IMMERSION" "FIM"
                  "CONSTANT WEIGHT WITHOUT FINS" "CNF" "CONSTANT WEIGHT WITH BI-FINS" "CWT-BF"})
(def category-pattern #"(?:SENIORS|MASTERS M[123]) (?:MEN|WOMEN)")
(def event-pattern #"(CONSTANT WEIGHT WITH FINS|FREE IMMERSION|CONSTANT WEIGHT WITHOUT FINS|CONSTANT WEIGHT WITH BI-FINS)\s+(\d{2}/\d{2}/\d{4})")
(def row-pattern #"\s*(?:(\d+)\s+)?(.+?)\s+(CMAS1|AIN|[A-Z]{3})(?:\s+.*)?")
(defn supported? [pages]
  (boolean (some #(and (str/includes? % "2026 CMAS World Championship")
                       (str/includes? % "Freediving Depth Seniors Masters")) pages)))
(defn- only [xs] (when (= 1 (count xs)) (first xs)))
(defn- tokens [s] (str/split (str/trim s) #"\s+"))
(defn- center [w] (/ (+ (:x-min w) (:x-max w)) 2))
(defn- date-value [s]
  (try (let [[d m y] (map parse-long (str/split s #"/"))]
         (str (java.time.LocalDate/of y m d))) (catch Exception _ nil)))
(defn- classified? [line]
  (let [s (str/trim (:text line))]
    (boolean (or (#{"2026 CMAS World Championship" "Freediving Depth Seniors Masters" "Results"
                    "Data Processing and Timing by Microplus - www.microplustiming.com"} s)
                 (re-matches #"_+" s) (re-matches category-pattern s) (re-matches event-pattern s)
                 (re-matches #"\d{2}/\d{2}/\d{4} - \d{2}/\d{2}/\d{4}" s)
                 (re-matches #"SURNAME &\s+DEC\.\s+FINAL|RANK\s+NAT\s+RESULT\s+PEN\.\s+STATUS\s+NOTES\s+MEDAL\s+RECORD|NAME\s+DEPTH\s+RESULT" s)
                 (re-matches #"Report Created \d{2}/\d{2}/\d{4} \d{2}:\d{2}\s+Page \d+ of \d+" s)))))
(defn- page-context [lines page]
  (let [texts (map (comp str/trim :text) lines)
        [_ event date] (only (keep #(re-matches event-pattern %) texts))
        rank (when (:valid? page) (only (filter #(= "RANK" (:text %)) (:words page))))
        headers (when rank (filterv #(<= (abs (- (:y-min %) (:y-min rank))) 10) (:words page)))
        word (fn [s] (only (filter #(= s (:text %)) headers)))
        expected ["RANK" "SURNAME" "&" "NAME" "NAT" "DEC." "DEPTH" "RESULT" "PEN." "FINAL" "RESULT" "STATUS" "NOTES" "MEDAL" "RECORD"]
        achieved (first (sort-by :x-min (filter #(= "RESULT" (:text %)) headers)))
        headings [(word "DEC.") achieved (word "PEN.") (word "FINAL") (word "STATUS") (word "NOTES") (word "MEDAL") (word "RECORD")]
        centers (when (every? some? headings) (mapv center headings))
        cuts (when (and centers (word "NAT"))
               (into [(/ (+ (:x-max (word "NAT")) (first centers)) 2)]
                     (map #(/ (+ %1 %2) 2) centers (rest centers))))
        valid? (and (:valid? page) event (date-value date) cuts (apply < cuts)
                    (= (frequencies expected) (frequencies (map :text headers)))
                    (= 1 (count (filter #{"2026 CMAS World Championship"} texts)))
                    (= 1 (count (filter #{"Freediving Depth Seniors Masters"} texts))))]
    {:valid? (boolean valid?) :discipline (disciplines event) :event-label event :event-date (date-value date)
     :cuts cuts :header-words headers
     :metadata-evidence (filterv classified? lines)}))
(def field-keys [:rank :source-name :representation :declared-depth :attempted-depth :penalty :final-depth :status :notes :medal :record :unit :event-date :discipline :category])
(defn- candidate [source-lines ctx page]
  (let [line (last source-lines)
        [_ rank name nation] (re-matches row-pattern (:text line))
        prefix (remove nil? [rank name nation])
        prefix-tokens (frequencies (mapcat tokens prefix))
        cuts (:cuts ctx)
        anchors (when cuts
                  (filter (fn [anchor]
                            (let [prefix-words (filter (fn [w]
                                                         (and (< (center w) (first cuts))
                                                              (< (abs (- (:y-min w) (:y-min anchor))) 2)))
                                                       (:words page))]
                              (and (= nation (:text anchor)) (< (center anchor) (first cuts))
                                   (= prefix-tokens (frequencies (map :text prefix-words))))))
                          (:words page)))
        anchor (only anchors)
        words (when anchor (filterv #(<= (abs (- (:y-min %) (:y-min anchor))) 8) (:words page)))
        matching? (= (frequencies (mapcat #(tokens (:text %)) source-lines)) (frequencies (map :text words)))
        values (when (and words cuts)
                 (mapv (fn [col]
                         (let [ws (filter #(= col (count (take-while (fn [cut] (<= cut (center %))) cuts))) words)]
                           (when (seq ws) (str/join " " (map :text (sort-by (juxt :y-min :x-min) ws)))))) (range 1 9)))
        raw (when name (merge {:rank rank :source-name name :representation nation :category (:category ctx)}
                              (zipmap [:declared-depth :attempted-depth :penalty :final-depth :status :notes :medal :record] values)))
        numbers (map #(get raw %) [:rank :declared-depth :attempted-depth :penalty :final-depth])
        valid? (and (:valid? ctx) (:category ctx) name matching? (:declared-depth raw)
                    (every? (fn [w] (not-any? #(< (:x-min w) % (:x-max w)) (take 5 cuts))) words)
                    (every? #(or (nil? %) (and (re-matches #"\d+" %) (parse-long %))) numbers)
                    (case (:status raw) nil (some? rank) "DSQ" (nil? rank) "DNS" (and (nil? rank) (nil? (:final-depth raw))) false)
                    (or (nil? (:medal raw)) (#{"GOLD MEDAL" "SILVER MEDAL" "BRONZE MEDAL"} (:medal raw))))
        parsed (when valid? (merge raw (zipmap [:rank :declared-depth :attempted-depth :penalty :final-depth] (map #(when % (parse-long %)) numbers))
                                   (select-keys ctx [:discipline :event-date]) {:unit nil :federation "CMAS"}))]
    {:coordinates (assoc (select-keys (first source-lines) [:page :line]) :column-start 1 :column-end (inc (count (:text (first source-lines)))))
     :source-lines source-lines :metadata-evidence (:metadata-evidence ctx)
     :geometry-evidence words :column-geometry (select-keys ctx [:cuts :header-words])
     :raw {:line (str/join "\n" (map :text source-lines)) :fields raw}
     :parsed parsed :parse-status (if parsed :parsed :unparsed)
     :fields (into {} (map (fn [[k v]] [k {:status (if (nil? v) :unknown :parsed) :value v}])) (merge (zipmap field-keys (repeat nil)) parsed))
     :unresolved-reasons (if parsed [:units-not-explicit] [:malformed-or-unsupported-depth-row]) :review-status :unreviewed}))
(defn- page-candidates [lines page]
  (let [base (page-context lines page) ctx base]
    (loop [[line & more] lines ctx ctx pending [] out []]
      (if-not line
        (into out (map #(candidate [%] (assoc ctx :valid? false) page) pending))
        (let [category (re-matches category-pattern (str/trim (:text line)))]
          (cond
            category (recur more (assoc base :category category
                                        :valid? (and (:valid? base) (or (nil? (:category ctx)) (:saw-row? ctx)))
                                        :saw-row? false)
                            [] (into out (map #(candidate [%] (assoc ctx :valid? false) page) pending)))
            (classified? line) (recur more ctx pending out)
            (re-matches row-pattern (:text line))
            (let [joined (candidate (conj pending line) ctx page)]
              (if (= :parsed (:parse-status joined))
                (recur more (assoc ctx :saw-row? true) [] (conj out joined))
                (let [invalid (assoc ctx :valid? false :saw-row? true)]
                  (recur more invalid [] (into out (map #(candidate [%] invalid page) (conj pending line)))))))
            :else (recur more ctx (conj pending line) out)))))))
(defn parse-pages-with-geometry [pages xml]
  (let [page-data (mapv (fn [i text]
                          {:page (inc i) :text text
                           :status (if (str/blank? text) :needs-OCR :text-extracted)
                           :lines (mapv (fn [n s] {:line (inc n) :text s}) (range) (str/split text #"\n" -1))})
                        (range) pages)
        boxes (geometry/geometry-pages xml)
        lines (mapv (fn [p] (mapv #(assoc % :page (:page p)) (remove #(str/blank? (:text %)) (:lines p)))) page-data)
        boxes (if (= (count pages) (count boxes)) boxes (repeat (count pages) {:valid? false :words []}))
        candidates (vec (mapcat page-candidates lines boxes))
        parsed (count (filter #(= :parsed (:parse-status %)) candidates))]
    {:parser-version parser-version :status (if (some #(= :needs-OCR (:status %)) page-data) :needs-OCR :needs-review)
     :pages page-data :geometry-xml xml :candidates candidates
     :reconciliation {:page-count (count pages) :candidate-count (count candidates) :parsed-count parsed :unparsed-count (- (count candidates) parsed)
                      :unresolved-count (count candidates) :nonblank-line-count (count (mapcat identity lines))
                      :noncandidate-lines (filterv classified? (mapcat identity lines)) :status :unreviewed}
     :publication {:status :blocked :reasons [:owner-review-required :reconciliation-unreviewed :units-not-explicit]}}))
