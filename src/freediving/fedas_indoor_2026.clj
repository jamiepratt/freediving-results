(ns freediving.fedas-indoor-2026
  "Source-bound individual standings from the retained 2026 FEDAS indoor PDF."
  (:require [clojure.string :as str]))

(def source-2026-sha256 "badfaa52cf56b77f4613e8ab15609ddef1011dd0904c46131220e39d70841e52")
(def parser-version "fedas-indoor-2026/1")

(def ^:private heading #"CLASIFICACIÓN (MASCULINA|FEMENINA) (STA|DYN-BF|DYN|DNF)\b")
(def ^:private federation-code
  #"\b(?:EHUIF|FASCV|FBDAS|FAAS|FMDAS|FECDAS|FARAS|FEGAS|FASRM|FCDAS|FEDECAS)\s*-\s*[^\d]+?(?=\s{2,})")
(def ^:private status-only #{"DQ-Vias" "DQ-BO" "DQ-Coach" "DQ-NPS" "DQ-Carril" "DNS"})
(def ^:private decimal #"\d+(?:,\d+)?")
(def ^:private time-value #"\d+:\d{2}")

(defn- performance [value discipline]
  (when (and value (re-matches (if (= discipline "STA") time-value decimal) value))
    (if (= discipline "STA") value (bigdec (str/replace value "," ".")))))

(defn- decimal-value [value]
  (when (and value (re-matches decimal value))
    (bigdec (str/replace value "," "."))))

(defn- row-values [text {:keys [gender discipline]}]
  (let [federation (re-find federation-code text)
        start (when federation (str/index-of text federation))
        left (when start (subs text 0 start))
        right (when start (subs text (+ start (count federation))))
        [_ rank name] (or (some->> left (re-matches #"\s*(\d{1,3})\s+(.+?)\s*"))
                          [nil nil (some-> left str/trim)])
        source-name (some-> name (str/replace #"\s+" " ") str/trim)
        name-parts (when name (str/split (str/trim name) #"\s{2,}"))
        values (when right (str/split (str/trim right) #"\s+"))
        [announced realized penalty obtained status points]
        (case (count values)
          2 [(nth values 0) nil nil nil (nth values 1) nil]
          5 [(nth values 0) (nth values 1) nil (nth values 2)
             (nth values 3) (nth values 4)]
          6 [(nth values 0) (nth values 1) (nth values 2) (nth values 3)
             (nth values 4) (nth values 5)]
          [nil nil nil nil nil nil])
        announced-value (performance announced discipline)
        realized-value (performance realized discipline)
        obtained-value (performance obtained discipline)
        penalty-value (decimal-value penalty)
        points-value (decimal-value points)
        ranked? (some? rank)
        valid? (and gender discipline (seq source-name) (seq federation)
                    announced-value
                    (if ranked?
                      (and (#{5 6} (count values)) realized-value obtained-value
                           (or (= status "OK") (= status "PEN")) points-value
                           (if (= status "PEN") penalty-value (nil? penalty)))
                      (and (= 2 (count values)) (contains? status-only status))))
        raw {:rank rank :source-name source-name
             :given-name (when (> (count name-parts) 1) (first name-parts))
             :surname (when (> (count name-parts) 1) (str/join " " (rest name-parts)))
             :representation federation :announced-performance announced
             :realized-performance realized :penalty penalty
             :final-performance obtained :status status :points points}]
    {:raw raw
     :parsed (when valid?
               {:federation "FEDAS" :event-year nil
                :source-name source-name :representation federation
                :gender gender :category (if (= gender "M") "Masculina" "Femenina")
                :discipline discipline :rank (some-> rank parse-long)
                :announced-performance announced-value
                :realized-performance realized-value :penalty penalty-value
                :final-performance obtained-value :status status :points points-value
                :unit nil})}))

(defn- candidate [line section]
  (let [{:keys [raw parsed]} (row-values (:text line) section)]
    {:coordinates {:page (:page line) :line (:line line) :column-start 1
                   :column-end (inc (count (:text line)))}
     :raw {:line (:text line) :fields raw}
     :parse-status (if parsed :parsed :unparsed)
     :parsed parsed
     :fields (into {} (map (fn [[key value]]
                             [key {:status (if (nil? value) :unknown :parsed) :value value}])
                           parsed))
     :review-status :unreviewed
     :unresolved-reasons (cond-> [:owner-review-required]
                           (nil? parsed) (conj :unparsed-source-line))}))

(defn parse-pages
  "Parse exact pdftotext -layout page strings. Individual positions retain
   exact text with 1-based page and line citations. Other standings are excluded."
  [source-sha256 pages]
  (let [page-data (mapv (fn [idx text]
                          {:page (inc idx) :text text
                           :lines (mapv (fn [n s] {:page (inc idx) :line (inc n) :text s})
                                        (range) (str/split text #"\n" -1))})
                        (range) pages)
        whole (str/join "\n" pages)
        supported? (and (= source-sha256 source-2026-sha256)
                        (str/includes? whole "CLASIFICACIÓN FINAL POR PRUEBAS"))
        section (atom nil)
        candidates (->> page-data
                        (mapcat :lines)
                        (keep (fn [line]
                                (let [s (:text line)
                                      match (re-find heading s)]
                                  (cond
                                    (re-find #"CLASIFICACIÓN (?:POR COMUNIDADES|MASTER)" s)
                                    (do (reset! section nil) nil)
                                    match
                                    (do (reset! section {:gender (if (= "MASCULINA" (nth match 1)) "M" "F")
                                                         :discipline (nth match 2)}) nil)
                                    (and @section (re-find federation-code s)
                                         (re-find #"(?:OK|PEN)\s+\d+,\d+\s*$|(?:DQ-[A-Za-z]+|DNS)\s*$" s))
                                    (candidate line @section)
                                    :else nil))))
                        vec)
        parsed-count (count (filter #(= :parsed (:parse-status %)) candidates))
        complete? (and supported? (= parsed-count (count candidates)))]
    {:schema-version 3 :parser-version parser-version :source-sha256 source-sha256
     :source-context {:event-year "2026" :basis :acquisition-route-not-pdf}
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
