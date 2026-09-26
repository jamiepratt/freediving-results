(ns freediving.world-games-2025
  "Source-bound layout parser for the 10 August 2025 World Games results PDF."
  (:require [clojure.string :as str]))

(def source-sha256 "98a00b10053ba285a87c8e624597787e4f77bc2ed0f7b4ddfba3bdef5ddff747")
(def parser-version "cmas-world-games-2025-08-10/1")
(def title "2025 THE WORLD GAMES CHEGDU, CHINA")
(def categories #{"SENIORS — WOMEN" "SENIORS — MEN"
                  "PARAFREEDIVING — MEN — FFS1-FFS2"
                  "PARAFREEDIVING — MEN — FFS3-FFS4"
                  "PARAFREEDIVING — WOMEN — FFS1-FFS2"})

(defn supported? [pages]
  (boolean (some #(str/includes? % title) pages)))

(defn- metadata-kind [text]
  (let [s (str/trim text)]
    (cond
      (= s title) :title
      (= s "AUGUST, 10, 2025") :event-date
      (re-matches #"(?:DNF|DYN)\s+FINAL RESULTS" s) :discipline
      (contains? categories s) :category
      (re-matches #"Realized\s+Final\s+Notes" s) :columns
      (re-matches #"#\s+Name & surname\s+Country" s) :columns
      (re-matches #"Distance \(m\)\s+Distance \(m\)" s) :units
      (= s "fi") :detached-fragment)))

(defn- field [value] {:status (if (nil? value) :unknown :parsed) :value value})
(defn- decimal [token] (bigdec (str/replace token "," ".")))
(defn- row [text context]
  (when-let [[_ rank name representation realized final notes]
             (re-matches #"\s*(?:(\d+)\s+)?(.+?)\s{2,}(CMAS1|[A-Z]{3})\s+(\d+,\d)\s+(\d+,\d|0)(?:\s+(.*?))?\s*" text)]
    (let [notes (not-empty (some-> notes str/trim))
          status (when (and notes (re-find #"^DQ(?:\s|$)" notes)) "DQ")]
      {:raw {:rank rank :source-name name :representation representation
             :realized-distance realized :final-distance final :notes notes}
       :parsed (merge context {:federation "CMAS" :source-name name
                               :representation representation :rank (some-> rank parse-long)
                               :realized-distance (decimal realized) :final-distance (decimal final)
                               :unit "m" :status status :notes notes})})))

(defn- candidate [source-lines context evidence]
  (let [first-line (first source-lines)
        result (row (:text first-line) context)
        continuation (when (and result (> (count source-lines) 1))
                       (str/join " " (map (comp str/trim :text) (rest source-lines))))
        parsed (cond-> (:parsed result)
                 continuation (update :notes #(str % " " continuation)))]
    {:coordinates {:page (:page first-line) :line (:line first-line)
                   :column-start 1 :column-end (inc (count (:text first-line)))}
     :source-lines source-lines
     :raw {:line (str/join "\n" (map :text source-lines)) :fields (:raw result)}
     :metadata-evidence evidence
     :parse-status (if result :parsed :unparsed) :parsed parsed
     :fields (into {} (map (fn [[k v]] [k (field v)]) parsed))
     :review-status :unreviewed
     :unresolved-reasons (cond-> [:owner-review-required]
                           (nil? result) (conj :unparsed-source-line))}))

(defn- parse-page [index text]
  (let [page (inc index)
        lines (mapv (fn [i s] {:page page :line (inc i) :text s})
                    (range) (str/split text #"\n" -1))
        nonblank (vec (remove #(str/blank? (:text %)) lines))
        metadata (vec (filter #(metadata-kind (:text %)) nonblank))
        kinds (group-by #(metadata-kind (:text %)) metadata)
        discipline-line (first (get kinds :discipline))
        discipline (some-> discipline-line :text str/trim (str/split #"\s+") first)
        category (some-> (get kinds :category) first :text str/trim)
        supported (and (= 1 (count (get kinds :title)))
                       (= 1 (count (get kinds :event-date)))
                       (= 1 (count (get kinds :discipline)))
                       (= 1 (count (get kinds :category)))
                       (= 1 (count (get kinds :units)))
                       (= 2 (count (get kinds :columns)))
                       (contains? #{["DNF" "SENIORS — WOMEN"] ["DNF" "SENIORS — MEN"]
                                    ["DNF" "PARAFREEDIVING — MEN — FFS1-FFS2"]
                                    ["DNF" "PARAFREEDIVING — MEN — FFS3-FFS4"]
                                    ["DYN" "PARAFREEDIVING — WOMEN — FFS1-FFS2"]}
                                  [discipline category]))
        result-lines (remove #(metadata-kind (:text %)) nonblank)
        groups (reduce (fn [groups line]
                         (if (and (seq groups)
                                  (re-matches #"\s+WORLD RECORD (?:SENIORS|FFS[1-4])\s*" (:text line))
                                  (row (:text (first (peek groups))) {}))
                           (update groups (dec (count groups)) conj line)
                           (conj groups [line]))) [] result-lines)
        context {:event-date "2025-08-10" :discipline discipline :category category}
        fragments (count (get kinds :detached-fragment))
        candidates (if supported
                     (mapv (fn [i group]
                             (cond-> (candidate group context metadata)
                               (and (= page 1) (pos? fragments) (= i 1))
                               (assoc-in [:parsed :source-name-integrity] :uncertain)
                               (and (= page 1) (pos? fragments) (= i 1))
                               (update :unresolved-reasons conj :detached-name-fragment)))
                           (range) groups) [])
        parsed (count (filter #(= :parsed (:parse-status %)) candidates))]
    {:page page :text text :lines lines
     :status (cond (empty? nonblank) :needs-OCR supported :needs-review :else :unsupported-needs-parser)
     :candidates candidates
     :noncandidate-lines (mapv #(assoc % :classification (if supported (metadata-kind (:text %)) :unsupported-page-line))
                               (if supported metadata nonblank))
     :reconciliation {:page page :supported? (boolean supported)
                      :candidate-count (when supported (count candidates))
                      :parsed-count (when supported parsed)
                      :unparsed-count (when supported (- (count candidates) parsed))
                      :unresolved-fragment-count (when supported fragments)
                      :nonblank-line-count (count nonblank)}}))

(defn parse-pages [pages]
  (let [processed (mapv parse-page (range) pages)
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
                      :unparsed-count (- (count candidates) parsed)
                      :unresolved-count (count candidates)
                      :unresolved-fragment-count (reduce + (map #(or (get-in % [:reconciliation :unresolved-fragment-count]) 0) processed))
                      :counts-scope :supported-pages-only
                      :nonblank-line-count (reduce + (map #(get-in % [:reconciliation :nonblank-line-count]) processed))
                      :noncandidate-lines (vec (mapcat :noncandidate-lines processed))
                      :status :unreviewed}
     :publication {:status :blocked
                   :reasons (cond-> [:owner-review-required :reconciliation-unreviewed]
                              (seq unsupported) (conj :unsupported-pages)
                              (seq ocr) (conj :needs-OCR))}}))
