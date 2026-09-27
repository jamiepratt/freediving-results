(ns freediving.fedas-indoor
  "Source-bound standings from the official 2025 FEDAS indoor results PDF."
  (:require [clojure.string :as str]))

(def source-2025-sha256 "51162be4915b3ea09a33386ae4ef651a3c4f208147327b4336dd69db20a179ed")
(def parser-version "fedas-indoor-2025/1")

(def ^:private heading #"CLASIFICACIÓN (MASCULINA|FEMENINA) (STA|DNF|DYN-BF|DYN)\b")
(def ^:private ranked #"^\s*\d{1,3}\s{2,}\p{L}.*$")
(def ^:private unranked #"^\s+\p{L}.*\s{2,}(?:DQ-[A-Za-z]+|BO-Ant|DNS)\s*$")
(def ^:private decimal #"\d+(?:,\d+)?")
(def ^:private time-result #"\d+:\d{2}")

(defn- performance [s discipline]
  (when (and s (re-matches (if (= discipline "STA") time-result decimal) s))
    (if (= discipline "STA") s (bigdec (str/replace s "," ".")))))

(defn- row-values [text {:keys [gender discipline]}]
  (let [chunks (str/split (str/trim text) #"\s{2,}")
        numbered? (boolean (re-matches #"\d{1,3}" (first chunks)))
        [rank given surname representation result status points]
        (if numbered?
          [(nth chunks 0 nil) (nth chunks 1 nil) (nth chunks 2 nil)
           (nth chunks 3 nil) (nth chunks 4 nil) (nth chunks 5 nil) (nth chunks 6 nil)]
          [nil (nth chunks 0 nil) (nth chunks 1 nil) (nth chunks 2 nil)
           nil (nth chunks 3 nil) nil])
        final-value (performance result discipline)
        status-only? (contains? #{"DQ-NPS" "DQ-Coach" "DQ-BO" "DQ-Vias" "BO-Ant" "DNS"} status)
        valid? (and gender discipline (seq given) (seq surname) (seq representation)
                    (if numbered?
                      (and (= 7 (count chunks)) final-value (= "OK" status)
                           (re-matches decimal (or points "")))
                      (and (= 4 (count chunks)) status-only?)))
        raw {:rank rank :given-name given :surname surname :representation representation
             :final-performance result :status status :points points}]
    {:raw raw
     :parsed (when valid?
               {:federation "FEDAS" :event-date "2025-04-26" :event-date-end "2025-04-27"
                :source-name (str given " " surname) :representation representation
                :gender gender :category (if (= gender "M") "Masculina" "Femenina")
                :discipline discipline :rank (some-> rank parse-long)
                :final-performance final-value :status status
                :points (some-> points (str/replace "," ".") bigdec)
                :unit (if (= discipline "STA") "mm:ss" "m")})}))

(defn- candidate [line section]
  (let [{:keys [raw parsed]} (row-values (:text line) section)]
    {:coordinates {:page (:page line) :line (:line line) :column-start 1
                   :column-end (inc (count (:text line)))}
     :raw {:line (:text line) :fields raw}
     :parse-status (if parsed :parsed :unparsed)
     :parsed parsed
     :fields (into {} (map (fn [[key v]] [key {:status (if (nil? v) :unknown :parsed) :value v}]) parsed))
     :review-status :unreviewed
     :unresolved-reasons (cond-> [:owner-review-required]
                           (nil? parsed) (conj :unparsed-source-line))}))

(defn parse-pages
  "Parse exact pdftotext -layout page strings. Every ranked or unranked
   result row keeps its original text and 1-based page/line coordinates."
  [source-sha256 pages]
  (let [page-data (mapv (fn [idx text]
                          {:page (inc idx) :text text
                           :lines (mapv (fn [n s] {:page (inc idx) :line (inc n) :text s})
                                        (range) (str/split text #"\n" -1))})
                        (range) pages)
        all-lines (mapcat :lines page-data)
        whole (str/join "\n" pages)
        supported? (and (= source-sha256 source-2025-sha256)
                        (str/includes? whole "RESULTADOS DEL CAMPEONATO DE ESPAÑA DE APNEA INDOOR")
                        (str/includes? whole "Vitoria 26-27 de Abril de 2025"))
        section (atom nil)
        candidates (->> all-lines
                        (keep (fn [line]
                                (let [match (re-find heading (:text line))]
                                  (cond match (do (reset! section
                                                          {:gender (if (= "MASCULINA" (nth match 1)) "M" "F")
                                                           :discipline (nth match 2)}) nil)
                                        (or (re-matches ranked (:text line))
                                            (re-matches unranked (:text line)))
                                        (candidate line @section)
                                        :else nil))))
                        vec)
        parsed-count (count (filter #(= :parsed (:parse-status %)) candidates))
        complete? (and supported? (= parsed-count (count candidates)))]
    {:schema-version 3 :parser-version parser-version :source-sha256 source-sha256
     :status (if complete? :needs-review :partial-unsupported-needs-parser)
     :pages (mapv #(assoc % :status (if complete? :needs-review :unsupported-page)) page-data)
     :candidates candidates
     :reconciliation {:page-count (count pages) :coverage (if complete? :complete :partial)
                      :candidate-count (count candidates) :parsed-count parsed-count
                      :unparsed-count (- (count candidates) parsed-count)
                      :unresolved-count (count candidates)
                      :section-counts (frequencies
                                       (map (fn [c] (select-keys (:parsed c) [:gender :discipline]))
                                            (filter :parsed candidates)))
                      :unique-performance-count nil :status :unreviewed}
     :publication {:status :blocked :reasons [:owner-review-required :reconciliation-unreviewed]}}))
