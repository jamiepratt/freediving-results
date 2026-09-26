(ns freediving.camotes-challenge
  "Review-only, source-bound transcription of the four Camotes Challenge scans.
   The page-three date and one clipped given name remain unresolved."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def parser-version "cmas-camotes-challenge-2025-image/1")
(def source-sha256 "4853df5a88f889f579a6e99988e27794da6c4003ce5c74ae4b806899a38017d6")
(def printed-counts {1 17, 2 17, 3 17, 4 15})
(def printed-dates {1 "2025-04-19", 2 "2025-04-22", 4 "2025-04-26"})
(def printed-days {1 "Day 1", 2 "Day 2", 4 "Day 4"})
(def cell-keys [:last-name :first-name :country :discipline :ap :top-time
                :dive-time :rp :card :record :points])
(def tsv-header (vec (concat ["page" "row"] (map name cell-keys) ["note"])))

(defn- row-region [page row]
  ;; Pixel coordinates in the retained 400 dpi pdftoppm renders. Each box
  ;; covers the full printed table row, including the card and record cells.
  (let [[width y0 h] (case page
                       1 [1721 (if (<= row 9) 782 1117) 33]
                       2 [2742 1171 54]
                       3 [2688 (if (<= row 8) 1013 1482) 52]
                       4 [3100 1113 71])
        offset (case page 1 (if (<= row 9) (dec row) (- row 10))
                     3 (if (<= row 8) (dec row) (- row 9))
                     (dec row))
        top (+ y0 (* h offset))]
    [10 top (- width 10) (+ top h)]))

(defn read-ledger
  "Read an independently image-checked TSV. This is a private evidence input,
   never OCR output or a replacement for retaining the original PDF."
  [path]
  (let [lines (str/split-lines (slurp (io/file path)))
        header (str/split (first lines) #"\t" -1)]
    (when-not (= tsv-header header)
      (throw (ex-info "Unexpected Camotes Challenge ledger header" {:header header})))
    {:source-sha256 source-sha256
     :rows (mapv (fn [line]
                   (let [values (str/split line #"\t" -1)]
                     (when-not (= (count header) (count values))
                       (throw (ex-info "Ledger row has wrong cell count" {:line line})))
                     (let [[page row] (mapv parse-long (take 2 values))]
                       {:page page :row row :region (row-region page row)
                        :cells (zipmap cell-keys (take (count cell-keys) (drop 2 values)))
                        :note (last values)})))
                 (rest lines))}))

(defn- valid-cells? [cells]
  (and (= (set cell-keys) (set (keys cells)))
       (every? string? (vals cells))
       (every? #(not (str/blank? (get cells %)))
               [:last-name :first-name :country :discipline :ap :top-time :dive-time :points])
       (contains? #{"FIM" "CWT" "CWTB" "CNF"} (:discipline cells))
       (re-matches #"\d+" (:ap cells))
       (re-matches #"\d+" (:points cells))
       (or (str/blank? (:rp cells)) (= "DNS" (:rp cells))
           (re-matches #"\d+" (:rp cells)))
       (re-matches #"\d{1,2}:\d{2}" (:top-time cells))
       (re-matches #"\d{1,2}:\d{2}" (:dive-time cells))))

(defn parse-row [{:keys [page row region cells note]}]
  (when-not (and (integer? page) (<= 1 page 4)
                 (integer? row) (<= 1 row (printed-counts page))
                 (vector? region) (= 4 (count region))
                 (valid-cells? cells) (#{"" "clipped-first-name"} (or note "")))
    (throw (ex-info "Invalid audited Camotes Challenge row" {:page page :row row})))
  (let [reasons (cond-> [:owner-review-required]
                  (nil? (printed-dates page)) (conj :missing-printed-date)
                  (= note "clipped-first-name") (conj :clipped-source-cell))
        supported? (= [:owner-review-required] reasons)
        rp (:rp cells)
        parsed {:federation "CMAS" :event "Camotes Freediving Challenge 2025"
                :event-date (printed-dates page) :session-day (printed-days page)
                :source-name (str (:first-name cells) " " (:last-name cells))
                :country (:country cells) :discipline (:discipline cells)
                :announced-depth (parse-long (:ap cells))
                :top-time (:top-time cells) :dive-time (:dive-time cells)
                :result rp :distance (when (re-matches #"\d+" rp) (parse-long rp))
                :unit "m" :card (:card cells) :record (:record cells)
                :points (parse-long (:points cells))}]
    {:coordinates {:page page :line row :row row :region region}
     :raw {:fields cells :transcription-method :manual-image-review}
     :metadata-evidence [{:page page :region :page-heading
                          :text (str "Camotes Freediving Challenge 2025; "
                                     (or (printed-days page) "day not printed") "; "
                                     (or (printed-dates page) "date not printed"))}]
     :parse-status (if supported? :parsed :unparsed)
     :parsed parsed :review-status :unreviewed
     :unresolved-reasons reasons
     :publication {:status :blocked :reasons [:owner-review-required]}}))

(defn parse-ledger [{:keys [source-sha256 rows]}]
  (when-not (= freediving.camotes-challenge/source-sha256 source-sha256)
    (throw (ex-info "Camotes Challenge ledger bound to another PDF" {})))
  (when-not (= (for [page (range 1 5) row (range 1 (inc (printed-counts page)))]
                 [page row])
               (map (juxt :page :row) rows))
    (throw (ex-info "Camotes Challenge ledger omits or reorders printed rows" {})))
  (let [candidates (mapv parse-row rows)
        parsed (count (filter #(= :parsed (:parse-status %)) candidates))]
    {:parser-version parser-version :schema-version 3
     :source-sha256 source-sha256 :status :partial-unsupported-needs-review
     :candidates candidates
     :reconciliation {:page-count 4 :printed-count (count candidates)
                      :candidate-count (count candidates) :parsed-count parsed
                      :unparsed-count (- (count candidates) parsed)
                      :per-page (mapv (fn [page]
                                        (let [xs (filter #(= page (get-in % [:coordinates :page])) candidates)]
                                          {:page page :printed-count (printed-counts page)
                                           :candidate-count (count xs)
                                           :parsed-count (count (filter #(= :parsed (:parse-status %)) xs))
                                           :unparsed-count (count (filter #(= :unparsed (:parse-status %)) xs))}))
                                      (range 1 5))}
     :publication {:status :blocked :reasons [:owner-review-required :unresolved-printed-rows]}}))

(defn- sha256-file [path]
  (let [bytes (java.nio.file.Files/readAllBytes (.toPath (io/file path)))
        digest (.digest (java.security.MessageDigest/getInstance "SHA-256") bytes)]
    (format "%064x" (java.math.BigInteger. 1 digest))))

(defn parse-source
  "Replay the reviewed private ledger only against the exact original PDF bytes.
   The source PDF and rendered page images must remain alongside this ledger."
  [pdf-path ledger-path]
  (when-not (= source-sha256 (sha256-file pdf-path))
    (throw (ex-info "Camotes Challenge source PDF changed" {})))
  (assoc (parse-ledger (read-ledger ledger-path))
         :transcription-ledger-sha256 (sha256-file ledger-path)))
