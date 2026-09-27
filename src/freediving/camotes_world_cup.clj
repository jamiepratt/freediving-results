(ns freediving.camotes-world-cup
  "Source-bound manual review of page one of the 2025 Camotes World Cup scan."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [freediving.archive :as archive])
  (:import [java.security MessageDigest]
           [java.util HexFormat]))

(def parser-version "cmas-camotes-world-cup-2025-page-one-image/1")
(def source-sha256 "298e408b4b71afe5e35c01a76784e5d06f8c7e217df171cd5d9e171682492736")
(def row-evidence-sha256 "f21b30695e112621d636dd473e3859ccfd3ebc0048dd93614885f870d6e15bff")
(def render-sha256 "b92aa2581d84f73649893541ed19d6f16e3bfa168e320c80fe3d8884ec255052")
(def block-counts [13 12 13])
(def cell-keys [:last-name :first-name :country :gender :discipline :ap :rp :card :record :points])
(def tsv-header (vec (concat ["page" "block" "line"] (map name cell-keys) ["note"])))
(def expected-positions
  (vec (for [block (range 1 4) line (range 1 (inc (nth block-counts (dec block))))]
         [1 block line])))

(defn- sha256-file [path]
  (let [bytes (java.nio.file.Files/readAllBytes (.toPath (io/file path)))]
    (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") bytes))))

(defn- check-sha! [path expected description]
  (when-not (= expected (sha256-file path))
    (throw (ex-info (str description " changed") {}))))

(defn read-ledger [path]
  (let [[header & lines] (str/split-lines (slurp (io/file path)))]
    (when-not (= tsv-header (str/split (or header "") #"\t" -1))
      (throw (ex-info "Unexpected Camotes World Cup TSV header" {})))
    {:source-sha256 source-sha256
     :rows (mapv (fn [line]
                   (let [values (str/split line #"\t" -1)]
                     (when-not (= (count tsv-header) (count values))
                       (throw (ex-info "Camotes World Cup TSV row has wrong cell count" {})))
                     {:page (parse-long (nth values 0))
                      :block (parse-long (nth values 1))
                      :line (parse-long (nth values 2))
                      :cells (zipmap cell-keys (take (count cell-keys) (drop 3 values)))
                      :note (last values)}))
                 lines)}))

(defn read-row-evidence [path]
  (let [raw (json/read-str (slurp (io/file path)) :key-fn keyword)
        page-one (first (:pages raw))]
    (when-not (and (= source-sha256 (:source_sha256 raw))
                   (= 4 (:source_page_count raw))
                   (= 1 (:page page-one))
                   (= "18/05/2025" (:printed_date page-one))
                   (= [1200 1607] (:dimensions_px page-one))
                   (= render-sha256 (:render_sha256 page-one))
                   (= 38 (:visual_printed_row_count page-one))
                   (= block-counts (:block_row_counts page-one)))
      (throw (ex-info "Unexpected Camotes World Cup row evidence" {})))
    {:source-sha256 (:source_sha256 raw)
     :render-sha256 (:render_sha256 page-one)
     :positions (mapv (fn [{:keys [page block line image_region_px]}]
                        {:page page :block block :line line
                         :region (mapv image_region_px [:x1 :y1 :x2 :y2])})
                      (filter #(= 1 (:page %)) (:positions raw)))}))

(defn- valid-region? [region]
  (let [[x1 y1 x2 y2] region]
    (and (= 4 (count region)) (every? integer? region)
         (<= 0 x1) (< x1 x2) (<= x2 1200)
         (<= 0 y1) (< y1 y2) (<= y2 1607))))

(defn- numeric? [s] (boolean (re-matches #"[0-9]+" s)))

(defn parse-row [{:keys [page block line cells note]} evidence]
  (let [region (:region evidence)
        position [page block line]
        note-match (when (string? note) (re-matches #"unresolved:([a-z-]+):(.+)" note))
        ambiguous-key (some-> note-match second keyword)
        unresolved? (boolean note-match)
        required [:last-name :first-name :country :gender :discipline :ap :rp :points]]
    (when-not (and (some #{position} expected-positions)
                   (= position ((juxt :page :block :line) evidence))
                   (valid-region? region)
                   (= (set cell-keys) (set (keys cells)))
                   (every? string? (vals cells))
                   (or (= "" note)
                       (and unresolved? (contains? (set cell-keys) ambiguous-key)
                            (str/blank? (get cells ambiguous-key))))
                   (every? #(or (= ambiguous-key %)
                                (not (str/blank? (get cells %)))) required)
                   (or (= ambiguous-key :gender)
                       (contains? #{"Male" "Female"} (:gender cells)))
                   (or (= ambiguous-key :discipline)
                       (contains? #{"CNF" "CWT" "CWTB" "FIM"} (:discipline cells)))
                   (every? #(or (= ambiguous-key %)
                                (numeric? (get cells %))) [:ap :rp :points]))
      (throw (ex-info "Invalid audited Camotes World Cup row" {:position position})))
    (let [ordinal (inc (.indexOf expected-positions position))
          raw-line (str/join "\t" (map cells cell-keys))
          parsed (when-not unresolved?
                   {:federation "CMAS" :event "CMAS WORLD CUP PHILIPPINES 2025"
                    :event-date "2025-05-18" :session-day nil
                    :source-name (str (:first-name cells) " " (:last-name cells))
                    :country (:country cells) :gender (:gender cells)
                    :category nil :discipline (:discipline cells) :rank nil
                    :announced-depth (parse-long (:ap cells))
                    :result (:rp cells) :distance (parse-long (:rp cells))
                    :unit "m" :card (:card cells) :status (:card cells)
                    :record (:record cells)
                    :points (parse-long (:points cells))})]
      {:coordinates {:page 1 :line ordinal :block block :block-line line :region region}
       :source-lines [{:page 1 :line ordinal :text raw-line}]
       :raw {:line raw-line :fields cells :note note
             :transcription-method :manual-image-review}
       :metadata-evidence [{:page 1 :region :page-heading
                            :text "CMAS WORLD CUP PHILIPPINES 2025; 18/05/2025"}]
       :parse-status (if unresolved? :unparsed :parsed)
       :parsed parsed :review-status :unreviewed
       :unresolved-reasons (if unresolved? [:ambiguous-source-cell] [:owner-review-required])
       :publication {:status :blocked :reasons [:owner-review-required]}})))

(defn parse-ledger [{:keys [source-sha256 rows]} evidence]
  (when-not (and (= freediving.camotes-world-cup/source-sha256 source-sha256)
                 (= freediving.camotes-world-cup/source-sha256 (:source-sha256 evidence))
                 (= render-sha256 (:render-sha256 evidence))
                 (= expected-positions (mapv (juxt :page :block :line) rows))
                 (= expected-positions (mapv (juxt :page :block :line) (:positions evidence)))
                 (every? (comp valid-region? :region) (:positions evidence)))
    (throw (ex-info "Camotes World Cup page-one evidence changed or omits printed positions" {})))
  (let [positions (mapv parse-row rows (:positions evidence))
        candidates (filterv #(= :parsed (:parse-status %)) positions)
        unresolved (filterv #(= :unparsed (:parse-status %)) positions)
        lines (mapv (comp first :source-lines) positions)
        pages (mapv (fn [page]
                      (let [xs (if (= 1 page) lines [])]
                        {:page page :text (str/join "\n" (map :text xs))
                         :lines xs :status :needs-review})) (range 1 5))]
    {:parser-version parser-version :schema-version 3
     :source-sha256 source-sha256 :status :partial-unsupported-needs-review
     :pages pages :pdf-page-count 4 :raw-text (str/join "\f" (map :text pages))
     :candidates candidates :unresolved-positions unresolved
     :reconciliation {:page-count 4 :scoped-pages [1] :printed-count 38
                      :candidate-count (count candidates) :parsed-count (count candidates)
                      :unparsed-count 0 :unresolved-count (count unresolved)
                      :per-page [{:page 1 :printed-count 38
                                  :candidate-count (count candidates)
                                  :parsed-count (count candidates)
                                  :unresolved-count (count unresolved)}]}
     :publication {:status :blocked :reasons [:owner-review-required :remaining-scanned-pages]}}))

(defn parse-source [pdf-path ledger-path row-evidence-path render-path]
  (check-sha! pdf-path source-sha256 "Camotes World Cup PDF")
  (check-sha! row-evidence-path row-evidence-sha256 "Camotes World Cup row evidence")
  (check-sha! render-path render-sha256 "Camotes World Cup page-one render")
  (assoc (parse-ledger (read-ledger ledger-path) (read-row-evidence row-evidence-path))
         :transcription-ledger-sha256 (sha256-file ledger-path)
         :row-evidence-sha256 row-evidence-sha256
         :render-sha256 render-sha256))

(defn- command! [& args]
  (let [result (apply shell/sh args)]
    (when-not (zero? (:exit result))
      (throw (ex-info "Camotes World Cup source tool failed" {:tool (first args)})))
    result))

(defn- canonical [value]
  (cond (map? value) (into (sorted-map) (map (fn [[k x]] [k (canonical x)]) value))
        (sequential? value) (mapv canonical value)
        :else value))

(defn- digest [value]
  (.formatHex (HexFormat/of)
              (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes (pr-str (canonical value)) "UTF-8"))))

(defn extract-reviewed-scan!
  ([root sha ledger-path row-evidence-path render-path options]
   (extract-reviewed-scan! root sha ledger-path row-evidence-path render-path options {}))
  ([root sha ledger-path row-evidence-path render-path {:keys [actor config] :as options}
    {:keys [on-progress]}]
   (when-not (and (= #{:actor :config} (set (keys options)))
                  (string? actor) (not (str/blank? actor))
                  (map? config)
                  (not-any? #(contains? config %)
                            [:reviewed-ledger-sha256 :row-evidence-sha256 :render-sha256]))
     (throw (ex-info "Expected actor and config without reserved evidence keys" {})))
   (when-not (= sha source-sha256)
     (throw (ex-info "Wrong Camotes World Cup source SHA" {})))
   (let [source (archive/inspect root sha)
         _ (when (empty? (:acquisitions source))
             (throw (ex-info "Camotes World Cup source lacks acquisition evidence" {})))
         retained-ledger (archive/retain-evidence! root (archive/read-source-bytes ledger-path))
         retained-rows (archive/retain-evidence! root (archive/read-source-bytes row-evidence-path))
         retained-render (archive/retain-evidence! root (archive/read-source-bytes render-path))
         replay (parse-source (:artifact-path source) (:path retained-ledger)
                              (:path retained-rows) (:path retained-render))
         info (:out (command! "pdfinfo" (:artifact-path source)))
         page-count (some-> (re-find #"(?m)^Pages:\s+(\d+)\s*$" info) second parse-long)
         _ (when-not (= 4 page-count)
             (throw (ex-info "Camotes World Cup PDF must have four pages" {})))
         identity {:source-sha256 sha :acquisitions (:acquisitions source)
                   :evidence-sha256 (archive/extraction-evidence root)
                   :actor actor
                   :config (assoc config
                                  :reviewed-ledger-sha256 (:sha256 retained-ledger)
                                  :row-evidence-sha256 (:sha256 retained-rows)
                                  :render-sha256 (:sha256 retained-render))
                   :parser-version parser-version :schema-version 3
                   :pdfinfo-version (str/trim (:err (command! "pdfinfo" "-v")))
                   :tool {:name "manual-image-row-ledger" :version parser-version
                          :arguments ["retained TSV" "retained b09 page regions"
                                      "retained 300 dpi page-one PNG"]}}
         job-id (digest identity)]
     (archive/derive! root job-id
                      (fn [] (merge replay identity
                                    {:job-id job-id :processed-at (str (java.time.Instant/now))
                                     :tool-stderr ""})) on-progress))))

(defn validate-artifact! [root artifact]
  (let [source (archive/inspect root (:source-sha256 artifact))
        config (:config artifact)
        path (fn [k] (str (io/file root "evidence" (get config k))))
        replay (parse-source (:artifact-path source)
                             (path :reviewed-ledger-sha256)
                             (path :row-evidence-sha256)
                             (path :render-sha256))
        info (:out (command! "pdfinfo" (:artifact-path source)))
        page-count (some-> (re-find #"(?m)^Pages:\s+(\d+)\s*$" info) second parse-long)]
    (when-not (and (= 3 (:schema-version artifact))
                   (= parser-version (:parser-version artifact))
                   (= source-sha256 (:source-sha256 artifact))
                   (= 4 page-count)
                   (= row-evidence-sha256 (:row-evidence-sha256 artifact))
                   (= render-sha256 (:render-sha256 artifact))
                   (every? (set (:evidence-sha256 artifact))
                           (map config [:reviewed-ledger-sha256
                                        :row-evidence-sha256 :render-sha256]))
                   (= "manual-image-row-ledger" (get-in artifact [:tool :name]))
                   (= parser-version (get-in artifact [:tool :version]))
                   (= ["retained TSV" "retained b09 page regions"
                       "retained 300 dpi page-one PNG"]
                      (get-in artifact [:tool :arguments]))
                   (= replay (select-keys artifact (keys replay))))
      (throw (ex-info "Camotes World Cup extraction differs from retained source replay" {})))
    artifact))
