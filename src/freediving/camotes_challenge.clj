(ns freediving.camotes-challenge
  "Review-only, source-bound transcription of the four Camotes Challenge scans.
   The page-three date and one clipped given name remain unresolved."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [freediving.archive :as archive])
  (:import [java.security MessageDigest]
           [java.util HexFormat]))

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

(defn- in-page-region? [page region]
  (let [[width height] ({1 [1721 1392], 2 [2742 2109],
                         3 [2688 1963], 4 [3100 2184]} page)
        [x1 y1 x2 y2] region]
    (and (= 4 (count region))
         (every? integer? region)
         (<= 0 x1) (< x1 x2) (<= x2 width)
         (<= 0 y1) (< y1 y2) (<= y2 height))))

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
                 (vector? region) (in-page-region? page region)
                 (valid-cells? cells) (#{"" "clipped-first-name"} (or note "")))
    (throw (ex-info "Invalid audited Camotes Challenge row" {:page page :row row})))
  (let [line (str/join "\t" (map cells cell-keys))
        source-line {:page page :line row :text line}
        reasons (cond-> [:owner-review-required]
                  (nil? (printed-dates page)) (conj :missing-printed-date)
                  (= note "clipped-first-name") (conj :clipped-source-cell))
        supported? (= [:owner-review-required] reasons)
        rp (:rp cells)
        parsed {:federation "CMAS" :event "Camotes Freediving Challenge 2025"
                :event-date (printed-dates page) :session-day (printed-days page)
                :source-name (str (:first-name cells) " " (:last-name cells))
                :country (:country cells) :discipline (:discipline cells)
                :category nil :gender nil
                :announced-depth (parse-long (:ap cells))
                :top-time (:top-time cells) :dive-time (:dive-time cells)
                :result rp :distance (when (re-matches #"\d+" rp) (parse-long rp))
                :unit "m" :card (:card cells) :record (:record cells)
                :points (parse-long (:points cells))}]
    {:coordinates {:page page :line row :row row :region region}
     :source-lines [source-line]
     :raw {:line line :fields cells :transcription-method :manual-image-review}
     :metadata-evidence [{:page page :region :page-heading
                          :text (str "Camotes Freediving Challenge 2025; "
                                     (or (printed-days page) "day not printed") "; "
                                     (or (printed-dates page) "date not printed"))}]
     :parse-status (if supported? :parsed :unparsed)
     :parsed (when supported? parsed) :review-status :unreviewed
     :unresolved-reasons reasons
     :publication {:status :blocked :reasons [:owner-review-required]}}))

(defn parse-ledger [{:keys [source-sha256 rows]}]
  (when-not (= freediving.camotes-challenge/source-sha256 source-sha256)
    (throw (ex-info "Camotes Challenge ledger bound to another PDF" {})))
  (when-not (= (for [page (range 1 5) row (range 1 (inc (printed-counts page)))]
                 [page row])
               (map (juxt :page :row) rows))
    (throw (ex-info "Camotes Challenge ledger omits or reorders printed rows" {})))
  (let [positions (mapv parse-row rows)
        candidates (filterv #(= :parsed (:parse-status %)) positions)
        unresolved (filterv #(= :unparsed (:parse-status %)) positions)
        pages (mapv (fn [page]
                      (let [xs (filterv #(= page (get-in % [:coordinates :page])) positions)
                            lines (mapv (comp first :source-lines) xs)]
                        {:page page :text (str/join "\n" (map :text lines))
                         :lines lines :status :needs-review}))
                    (range 1 5))
        parsed (count (filter #(= :parsed (:parse-status %)) candidates))]
    {:parser-version parser-version :schema-version 3
     :source-sha256 source-sha256 :status :partial-unsupported-needs-review
     :pages pages :pdf-page-count 4 :raw-text (str/join "\f" (map :text pages))
     :candidates candidates :unresolved-positions unresolved
     :reconciliation {:page-count 4 :printed-count (count positions)
                      :candidate-count (count candidates) :parsed-count parsed
                      :unparsed-count 0 :unresolved-count (count unresolved)
                      :per-page (mapv (fn [page]
                                        (let [xs (filter #(= page (get-in % [:coordinates :page])) positions)]
                                          {:page page :printed-count (printed-counts page)
                                           :candidate-count (count (filter #(= :parsed (:parse-status %)) xs))
                                           :parsed-count (count (filter #(= :parsed (:parse-status %)) xs))
                                           :unresolved-count (count (filter #(= :unparsed (:parse-status %)) xs))}))
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

(defn- command! [& args]
  (let [result (apply shell/sh args)]
    (when-not (zero? (:exit result))
      (throw (ex-info "Camotes Challenge source tool failed" {:tool (first args)})))
    result))

(defn- canonical [value]
  (cond (map? value) (into (sorted-map) (map (fn [[k x]] [k (canonical x)]) value))
        (sequential? value) (mapv canonical value)
        :else value))

(defn- digest [value]
  (let [bytes (.getBytes (pr-str (canonical value)) "UTF-8")]
    (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") bytes))))

(defn extract-reviewed-scan!
  "Create an immutable archive derivation from reviewed TSV evidence and exact PDF.
   The TSV is retained by content hash; its text is explicitly a manual image
   transcription, not pdftotext or accepted OCR."
  ([root sha ledger-path options]
   (extract-reviewed-scan! root sha ledger-path options {}))
  ([root sha ledger-path {:keys [actor config] :as options} {:keys [on-progress]}]
   (when-not (and (= #{:actor :config} (set (keys options)))
                  (string? actor) (not (str/blank? actor))
                  (map? config) (not (contains? config :reviewed-ledger-sha256)))
     (throw (ex-info "Expected actor and config without reserved ledger key" {})))
   (when-not (= sha source-sha256)
     (throw (ex-info "Wrong Camotes Challenge source SHA" {})))
   (let [source (archive/inspect root sha)
         _ (when (empty? (:acquisitions source))
             (throw (ex-info "Challenge source lacks acquisition evidence" {})))
         retained (archive/retain-evidence! root (archive/read-source-bytes ledger-path))
         replay (parse-source (:artifact-path source) (:path retained))
         info (:out (command! "pdfinfo" (:artifact-path source)))
         page-count (some-> (re-find #"(?m)^Pages:\s+(\d+)\s*$" info) second parse-long)
         _ (when-not (= 4 page-count)
             (throw (ex-info "Challenge source PDF must have four pages" {})))
         identity {:source-sha256 sha :acquisitions (:acquisitions source)
                   :evidence-sha256 (archive/extraction-evidence root)
                   :actor actor :config (assoc config :reviewed-ledger-sha256 (:sha256 retained))
                   :parser-version parser-version :schema-version 3
                   :pdfinfo-version (str/trim (:err (command! "pdfinfo" "-v")))
                   :tool {:name "manual-image-row-ledger" :version parser-version
                          :arguments ["retained TSV" "400 dpi visual review"]}}
         job-id (digest identity)]
     (archive/derive! root job-id
                      (fn [] (merge replay identity
                                    {:job-id job-id :processed-at (str (java.time.Instant/now))
                                     :tool-stderr ""})) on-progress))))

(defn validate-artifact!
  "Replay every imported candidate from the archived PDF and retained TSV."
  [root artifact]
  (let [source (archive/inspect root (:source-sha256 artifact))
        ledger-hash (:transcription-ledger-sha256 artifact)
        ledger-path (str (io/file root "evidence" ledger-hash))
        replay (parse-source (:artifact-path source) ledger-path)
        info (:out (command! "pdfinfo" (:artifact-path source)))
        page-count (some-> (re-find #"(?m)^Pages:\s+(\d+)\s*$" info) second parse-long)]
    (when-not (and (= 3 (:schema-version artifact))
                   (= parser-version (:parser-version artifact))
                   (= source-sha256 (:source-sha256 artifact))
                   (= ledger-hash (get-in artifact [:config :reviewed-ledger-sha256]))
                   (some #{ledger-hash} (:evidence-sha256 artifact))
                   (= "manual-image-row-ledger" (get-in artifact [:tool :name]))
                   (= parser-version (get-in artifact [:tool :version]))
                   (= ["retained TSV" "400 dpi visual review"]
                      (get-in artifact [:tool :arguments]))
                   (= 4 page-count)
                   (= replay (select-keys artifact (keys replay))))
      (throw (ex-info "Camotes Challenge extraction differs from source and retained ledger replay" {})))
    artifact))
