(ns freediving.camotes-world-cup-continuation
  "Source-bound manual review of pages two through four of the 2025 Camotes World Cup scan."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [freediving.archive :as archive])
  (:import [java.security MessageDigest]
           [java.util HexFormat]))

(def parser-version "cmas-camotes-world-cup-2025-continuation-image/1")
(def source-sha256 "298e408b4b71afe5e35c01a76784e5d06f8c7e217df171cd5d9e171682492736")
(def row-evidence-sha256 "f21b30695e112621d636dd473e3859ccfd3ebc0048dd93614885f870d6e15bff")
(def page-specs
  {2 {:counts [13 10 14] :dimensions [1216 1579]
      :sha "5efb7a6b2c630fc05324f33ea4bba4da5c42ccf10a41df3f28daf592816bac5a"}
   3 {:counts [13 11 13] :dimensions [1229 1582]
      :sha "49766bac6a4b73453593281a423ca7e1f2b437dfa987955b9571d28cb028d076"}
   4 {:counts [12 10 12] :dimensions [1188 1363]
      :sha "ff6e70d61eeec796d772ed202330da370b455d29bf5f9c2bb5175e50db64fee6"}})
(defn render-sha256 [page] (get-in page-specs [page :sha]))
(defn block-counts [page] (get-in page-specs [page :counts]))
(def cell-keys [:last-name :first-name :country :gender :discipline :ap :rp :card :record :points])
(def tsv-header (vec (concat ["page" "block" "line"] (map name cell-keys) ["note"])))
(def expected-positions
  (vec (for [page [2 3 4] [block n] (map-indexed (fn [i n] [(inc i) n]) (block-counts page))
             line (range 1 (inc n))] [page block line])))

(defn- fail! [message] (throw (ex-info message {})))
(defn- sha256-file [path]
  (let [bytes (java.nio.file.Files/readAllBytes (.toPath (io/file path)))]
    (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") bytes))))
(defn- check-sha! [path expected description]
  (when-not (= expected (sha256-file path)) (fail! (str description " changed"))))

(defn read-ledger [path]
  (let [[header & lines] (str/split-lines (slurp (io/file path)))]
    (when-not (= tsv-header (str/split (or header "") #"\t" -1))
      (fail! "Unexpected Camotes World Cup continuation TSV header"))
    (mapv (fn [line]
            (let [values (str/split line #"\t" -1)]
              (when-not (= (count tsv-header) (count values)) (fail! "Continuation TSV row has wrong cell count"))
              {:page (parse-long (nth values 0)) :block (parse-long (nth values 1))
               :line (parse-long (nth values 2))
               :cells (zipmap cell-keys (take (count cell-keys) (drop 3 values)))
               :note (last values)})) lines)))

(defn read-row-evidence [path]
  (let [raw (json/read-str (slurp (io/file path)) :key-fn keyword)
        pages (mapv (fn [page]
                      (let [p (first (filter #(= page (:page %)) (:pages raw)))
                            spec (page-specs page)]
                        (when-not (and p (nil? (:printed_date p))
                                       (= (:sha spec) (:render_sha256 p))
                                       (= (:dimensions spec) (:dimensions_px p))
                                       (= (:counts spec) (:block_row_counts p))
                                       (= (reduce + (:counts spec)) (:visual_printed_row_count p)))
                          (fail! "Unexpected continuation page evidence"))
                        {:page page :render-sha256 (:render_sha256 p)
                         :printed-date (:printed_date p) :block-row-counts (:block_row_counts p)})) [2 3 4])
        positions (mapv (fn [{:keys [page block line image_region_px]}]
                          {:page page :block block :line line
                           :region (mapv image_region_px [:x1 :y1 :x2 :y2])})
                        (filter #(contains? page-specs (:page %)) (:positions raw)))]
    (when-not (and (= source-sha256 (:source_sha256 raw)) (= 4 (:source_page_count raw))
                   (= expected-positions (mapv (juxt :page :block :line) positions)))
      (fail! "Unexpected continuation row evidence"))
    {:source-sha256 (:source_sha256 raw) :pages pages :positions positions}))

(defn- valid-region? [page region]
  (let [[x1 y1 x2 y2] region [width height] (get-in page-specs [page :dimensions])]
    (and (= 4 (count region)) (every? integer? region)
         (<= 0 x1) (< x1 x2) (<= x2 width)
         (<= 0 y1) (< y1 y2) (<= y2 height))))
(defn- numeric? [s] (boolean (re-matches #"[0-9]+" s)))
(defn- unresolved-key [note]
  (some-> (re-find #"(?:^|;)unresolved:([a-z-]+):[^;]+" note) second keyword))

(defn parse-row [{:keys [page block line cells note]} evidence]
  (let [position [page block line]
        region (:region evidence)
        ambiguous-key (when (string? note) (unresolved-key note))
        required [:last-name :first-name :country :gender :discipline :ap :points]
        rp-blank? (and (= "DNS" (:card cells)) (= "" (:rp cells)))
        ordinal (inc (count (take-while #(not= position %) (filter #(= page (first %)) expected-positions))))]
    (when-not (and (some #{position} expected-positions)
                   (= position ((juxt :page :block :line) evidence))
                   (valid-region? page region)
                   (= (set cell-keys) (set (keys cells))) (every? string? (vals cells))
                   (string? note)
                   (or (nil? ambiguous-key) (and (contains? (set cell-keys) ambiguous-key)
                                                 (str/blank? (get cells ambiguous-key))))
                   (every? #(or (= ambiguous-key %) (not (str/blank? (get cells %)))) required)
                   (or (= ambiguous-key :gender) (contains? #{"Male" "Female"} (:gender cells)))
                   (or (= ambiguous-key :discipline)
                       (contains? #{"CNF" "CWT" "CWTB" "FIM"} (:discipline cells)))
                   (every? #(or (= ambiguous-key %) (numeric? (get cells %))) [:ap :points])
                   (or (= ambiguous-key :rp) rp-blank? (numeric? (:rp cells))))
      (throw (ex-info "Invalid audited Camotes World Cup continuation row" {:position position})))
    (let [raw-line (str/join "\t" (map cells cell-keys))
          parsed (when-not ambiguous-key
                   {:federation "CMAS" :event "CMAS WORLD CUP PHILIPPINES 2025"
                    :event-year "2025" :event-date nil :session-day nil
                    :source-name (str (:first-name cells) " " (:last-name cells))
                    :country (:country cells) :gender (:gender cells)
                    :category nil :discipline (:discipline cells) :rank nil
                    :announced-depth (parse-long (:ap cells))
                    :result (:rp cells) :distance (when (numeric? (:rp cells)) (parse-long (:rp cells)))
                    :unit "m" :card (:card cells) :status (:card cells)
                    :record (:record cells) :points (parse-long (:points cells))})]
      {:coordinates {:page page :line ordinal :block block :block-line line :region region}
       :source-lines [{:page page :line ordinal :text raw-line}]
       :raw {:line raw-line :fields cells :note note :transcription-method :manual-image-review}
       :metadata-evidence [{:page page :region :page-heading :text "CMAS WORLD CUP PHILIPPINES 2025"}]
       :parse-status (if ambiguous-key :unparsed :parsed)
       :parsed parsed :review-status :unreviewed
       :unresolved-reasons (if ambiguous-key [:ambiguous-source-cell] [:owner-review-required])
       :publication {:status :blocked :reasons [:owner-review-required]}})))

(defn parse-ledger [{:keys [source-sha256 rows]} evidence]
  (when-not (and (= freediving.camotes-world-cup-continuation/source-sha256 source-sha256)
                 (= source-sha256 (:source-sha256 evidence))
                 (= expected-positions (mapv (juxt :page :block :line) rows))
                 (= expected-positions (mapv (juxt :page :block :line) (:positions evidence)))
                 (= (mapv #(select-keys % [:page :render-sha256 :printed-date :block-row-counts])
                          (:pages evidence))
                    (mapv (fn [page] {:page page :render-sha256 (render-sha256 page)
                                      :printed-date nil :block-row-counts (block-counts page)}) [2 3 4]))
                 (every? (fn [{:keys [page region]}] (valid-region? page region)) (:positions evidence)))
    (fail! "Camotes World Cup continuation evidence changed or omits printed positions"))
  (let [positions (mapv parse-row rows (:positions evidence))
        candidates (filterv #(= :parsed (:parse-status %)) positions)
        unresolved (filterv #(= :unparsed (:parse-status %)) positions)
        pages (mapv (fn [page]
                      (let [lines (mapv (comp first :source-lines)
                                        (filter #(= page (get-in % [:coordinates :page])) positions))]
                        {:page page :text (str/join "\n" (map :text lines))
                         :lines lines :status :needs-review})) (range 1 5))]
    {:parser-version parser-version :schema-version 3
     :source-sha256 source-sha256 :status :partial-unsupported-needs-review
     :pages pages :pdf-page-count 4 :raw-text (str/join "\f" (map :text pages))
     :candidates candidates :unresolved-positions unresolved
     :reconciliation {:page-count 4 :scoped-pages [2 3 4] :printed-count 108
                      :candidate-count (count candidates) :parsed-count (count candidates)
                      :unparsed-count 0 :unresolved-count (count unresolved)
                      :per-page (mapv (fn [page]
                                        {:page page :printed-count (reduce + (block-counts page))
                                         :candidate-count (count (filter #(= page (get-in % [:coordinates :page])) candidates))
                                         :parsed-count (count (filter #(= page (get-in % [:coordinates :page])) candidates))
                                         :unresolved-count (count (filter #(= page (get-in % [:coordinates :page])) unresolved))})
                                      [2 3 4])}
     :publication {:status :blocked :reasons [:owner-review-required :unresolved-source-cells]}}))

(defn parse-source [pdf-path ledger2 ledger3 ledger4 row-evidence-path render2 render3 render4]
  (check-sha! pdf-path source-sha256 "Camotes World Cup PDF")
  (check-sha! row-evidence-path row-evidence-sha256 "Camotes World Cup row evidence")
  (doseq [[page path] (map vector [2 3 4] [render2 render3 render4])]
    (check-sha! path (render-sha256 page) (str "Camotes World Cup page " page " render")))
  (let [rows (mapv read-ledger [ledger2 ledger3 ledger4])
        _ (when-not (every? true? (map (fn [page rs] (every? #(= page (:page %)) rs)) [2 3 4] rows))
            (fail! "Continuation ledger page mismatch"))
        ledgers (mapv sha256-file [ledger2 ledger3 ledger4])]
    (assoc (parse-ledger {:source-sha256 source-sha256 :rows (vec (mapcat identity rows))}
                         (read-row-evidence row-evidence-path))
           :transcription-ledger-sha256 ledgers
           :row-evidence-sha256 row-evidence-sha256
           :render-sha256 (mapv render-sha256 [2 3 4]))))

(defn- command! [& args]
  (let [result (apply shell/sh args)]
    (when-not (zero? (:exit result)) (fail! "Camotes World Cup source tool failed"))
    result))
(defn- canonical [value]
  (cond (map? value) (into (sorted-map) (map (fn [[k x]] [k (canonical x)]) value))
        (sequential? value) (mapv canonical value) :else value))
(defn- digest [value]
  (.formatHex (HexFormat/of)
              (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes (pr-str (canonical value)) "UTF-8"))))

(defn extract-reviewed-scan!
  ([root sha paths options] (extract-reviewed-scan! root sha paths options {}))
  ([root sha paths {:keys [actor config] :as options} {:keys [on-progress]}]
   (when-not (and (= #{:actor :config} (set (keys options)))
                  (string? actor) (not (str/blank? actor)) (map? config)
                  (= 7 (count paths))
                  (not-any? #(contains? config %)
                            [:reviewed-ledger-sha256 :row-evidence-sha256 :render-sha256]))
     (fail! "Expected three ledgers, row evidence, three renders, actor and config"))
   (when-not (= sha source-sha256) (fail! "Wrong Camotes World Cup source SHA"))
   (let [source (archive/inspect root sha)
         _ (when (empty? (:acquisitions source)) (fail! "Source lacks acquisition evidence"))
         retained (mapv #(archive/retain-evidence! root (archive/read-source-bytes %)) paths)
         replay (apply parse-source (:artifact-path source) (mapv :path retained))
         info (:out (command! "pdfinfo" (:artifact-path source)))
         page-count (some-> (re-find #"(?m)^Pages:\s+(\d+)\s*$" info) second parse-long)
         _ (when-not (= 4 page-count) (fail! "Camotes World Cup PDF must have four pages"))
         evidence-shas (mapv :sha256 retained)
         identity {:source-sha256 sha :acquisitions (:acquisitions source)
                   :evidence-sha256 (archive/extraction-evidence root)
                   :actor actor
                   :config (assoc config :reviewed-ledger-sha256 (subvec evidence-shas 0 3)
                                  :row-evidence-sha256 (nth evidence-shas 3)
                                  :render-sha256 (subvec evidence-shas 4 7))
                   :parser-version parser-version :schema-version 3
                   :pdfinfo-version (str/trim (:err (command! "pdfinfo" "-v")))
                   :tool {:name "manual-image-row-ledger" :version parser-version
                          :arguments ["retained page 2-4 TSVs" "retained b09 page regions"
                                      "retained 300 dpi page 2-4 PNGs"]}}
         job-id (digest identity)]
     (archive/derive! root job-id
                      (fn [] (merge replay identity
                                    {:job-id job-id :processed-at (str (java.time.Instant/now))
                                     :tool-stderr ""})) on-progress))))

(defn validate-artifact! [root artifact]
  (let [source (archive/inspect root (:source-sha256 artifact))
        config (:config artifact)
        path (fn [sha] (str (io/file root "evidence" sha)))
        paths (concat (map path (:reviewed-ledger-sha256 config))
                      [(path (:row-evidence-sha256 config))]
                      (map path (:render-sha256 config)))
        replay (apply parse-source (:artifact-path source) paths)
        info (:out (command! "pdfinfo" (:artifact-path source)))
        page-count (some-> (re-find #"(?m)^Pages:\s+(\d+)\s*$" info) second parse-long)]
    (when-not (and (= 3 (:schema-version artifact))
                   (= parser-version (:parser-version artifact))
                   (= source-sha256 (:source-sha256 artifact)) (= 4 page-count)
                   (= row-evidence-sha256 (:row-evidence-sha256 artifact))
                   (= (mapv render-sha256 [2 3 4]) (:render-sha256 artifact))
                   (every? (set (:evidence-sha256 artifact))
                           (concat (:reviewed-ledger-sha256 config)
                                   [(:row-evidence-sha256 config)] (:render-sha256 config)))
                   (= "manual-image-row-ledger" (get-in artifact [:tool :name]))
                   (= parser-version (get-in artifact [:tool :version]))
                   (= ["retained page 2-4 TSVs" "retained b09 page regions"
                       "retained 300 dpi page 2-4 PNGs"] (get-in artifact [:tool :arguments]))
                   (= replay (select-keys artifact (keys replay))))
      (fail! "Camotes World Cup continuation extraction differs from retained source replay"))
    artifact))
