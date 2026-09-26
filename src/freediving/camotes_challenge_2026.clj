(ns freediving.camotes-challenge-2026
  "Source-bound manual transcription of the four image-only 2026 result pages."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [freediving.archive :as archive])
  (:import [java.security MessageDigest]
           [java.util HexFormat]))

(def parser-version "cmas-camotes-challenge-2026-image/1")
(def source-sha256 "d10b8b9115fe873ad47def52b05176d528fbcf7981872f116f92ba14fb729a22")
(def printed-counts {1 18, 2 18, 3 18, 4 18})
(def printed-dates {1 "2026-04-24", 2 "2026-04-26", 3 "2026-04-28", 4 "2026-04-30"})
(def source-dates {1 "24/04/2026", 2 "26/04/2026", 3 "28/04/2026", 4 "30/04/2026"})
(def printed-days {2 "Day 2", 3 "Day 3", 4 "Day 4"})
(def cell-keys [:last-name :first-name :country :discipline :ap :top-time
                :dive-time :rp :card :record :points])
(def tsv-header (vec (concat ["page" "row"] (map name cell-keys))))

(defn read-ledger [path]
  (let [[header & lines] (str/split-lines (slurp (io/file path)))]
    (when-not (= tsv-header (str/split header #"\t" -1))
      (throw (ex-info "Unexpected Camotes 2026 ledger header" {})))
    {:source-sha256 source-sha256
     :rows (mapv (fn [line]
                   (let [values (str/split line #"\t" -1)]
                     (when-not (= (count tsv-header) (count values))
                       (throw (ex-info "Camotes 2026 ledger row has wrong cell count" {})))
                     {:page (parse-long (first values)) :row (parse-long (second values))
                      :cells (zipmap cell-keys (drop 2 values))}))
                 lines)}))

(defn- valid-cells? [cells]
  (and (= (set cell-keys) (set (keys cells)))
       (every? string? (vals cells))
       (every? #(not (str/blank? (get cells %)))
               [:last-name :first-name :country :discipline :ap :top-time :dive-time :rp :points])
       (contains? #{"FIM" "CWT" "CWTB" "CTWB" "CNF"} (:discipline cells))
       (every? #(re-matches #"\d+" (get cells %)) [:ap :rp :points])
       (every? #(re-matches #"\d{1,2}:\d{2}" (get cells %)) [:top-time :dive-time])))

(defn parse-row [{:keys [page row cells]}]
  (when-not (and (integer? page) (<= 1 page 4)
                 (integer? row) (<= 1 row (printed-counts page))
                 (valid-cells? cells))
    (throw (ex-info "Invalid Camotes 2026 printed row" {:page page :row row})))
  (let [line (str/join "\t" (map cells cell-keys))
        source-line {:page page :line row :text line}]
    {:coordinates {:page page :line row :row row}
     :source-lines [source-line]
     :raw {:line line :fields cells :transcription-method :manual-image-review}
     :metadata-evidence [{:page page :region :page-heading
                          :text (str "Camotes Freediving Challenge Philippines 2026; "
                                     (when-let [day (printed-days page)] (str day ": "))
                                     (source-dates page))}]
     :parse-status :parsed
     :parsed {:federation "CMAS" :event "Camotes Freediving Challenge 2026"
              :event-date (printed-dates page) :session-day (printed-days page)
              :source-name (str (:first-name cells) " " (:last-name cells))
              :country (:country cells) :discipline (:discipline cells)
              :category nil :gender nil
              :announced-depth (parse-long (:ap cells))
              :top-time (:top-time cells) :dive-time (:dive-time cells)
              :result (:rp cells) :distance (parse-long (:rp cells))
              :unit "m" :card (:card cells) :record (:record cells)
              :status (when (= "DNS" (:card cells)) "DNS")
              :points (parse-long (:points cells))}
     :review-status :unreviewed
     :unresolved-reasons [:owner-review-required]
     :publication {:status :blocked :reasons [:owner-review-required]}}))

(defn parse-ledger [{:keys [source-sha256 rows]}]
  (when-not (= freediving.camotes-challenge-2026/source-sha256 source-sha256)
    (throw (ex-info "Camotes 2026 ledger bound to another PDF" {})))
  (when-not (= (vec (for [page (range 1 5) row (range 1 19)] [page row]))
               (mapv (juxt :page :row) rows))
    (throw (ex-info "Camotes 2026 ledger omits or reorders printed rows" {})))
  (let [candidates (mapv parse-row rows)
        pages (mapv (fn [page]
                      (let [lines (mapv (comp first :source-lines)
                                        (filter #(= page (get-in % [:coordinates :page])) candidates))]
                        {:page page :text (str/join "\n" (map :text lines))
                         :lines lines :status :needs-review}))
                    (range 1 5))]
    {:parser-version parser-version :schema-version 3
     :source-sha256 source-sha256 :status :needs-review
     :pages pages :pdf-page-count 4 :raw-text (str/join "\f" (map :text pages))
     :candidates candidates
     :reconciliation {:page-count 4 :printed-count 72 :candidate-count 72
                      :parsed-count 72 :unparsed-count 0 :unresolved-count 72
                      :per-page (mapv (fn [page] {:page page :printed-count 18
                                                  :candidate-count 18 :parsed-count 18
                                                  :unresolved-count 18}) (range 1 5))
                      :status :unreviewed}
     :publication {:status :blocked :reasons [:owner-review-required]}}))

(defn- sha256-file [path]
  (let [bytes (java.nio.file.Files/readAllBytes (.toPath (io/file path)))
        digest (.digest (MessageDigest/getInstance "SHA-256") bytes)]
    (format "%064x" (java.math.BigInteger. 1 digest))))

(defn parse-source [pdf-path ledger-path]
  (when-not (= source-sha256 (sha256-file pdf-path))
    (throw (ex-info "Camotes 2026 source PDF changed" {})))
  (assoc (parse-ledger (read-ledger ledger-path))
         :transcription-ledger-sha256 (sha256-file ledger-path)))

(defn- command! [& args]
  (let [result (apply shell/sh args)]
    (when-not (zero? (:exit result))
      (throw (ex-info "Camotes 2026 source tool failed" {:tool (first args)})))
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
  ([root sha ledger-path options]
   (extract-reviewed-scan! root sha ledger-path options {}))
  ([root sha ledger-path {:keys [actor config] :as options} {:keys [on-progress]}]
   (when-not (and (= #{:actor :config} (set (keys options)))
                  (string? actor) (not (str/blank? actor))
                  (map? config) (not (contains? config :reviewed-ledger-sha256)))
     (throw (ex-info "Expected actor and config without reserved ledger key" {})))
   (when-not (= sha source-sha256)
     (throw (ex-info "Wrong Camotes 2026 source SHA" {})))
   (let [source (archive/inspect root sha)
         _ (when (empty? (:acquisitions source))
             (throw (ex-info "Camotes 2026 source lacks acquisition evidence" {})))
         retained (archive/retain-evidence! root (archive/read-source-bytes ledger-path))
         replay (parse-source (:artifact-path source) (:path retained))
         info (:out (command! "pdfinfo" (:artifact-path source)))
         page-count (some-> (re-find #"(?m)^Pages:\s+(\d+)\s*$" info) second parse-long)
         _ (when-not (= 4 page-count)
             (throw (ex-info "Camotes 2026 source PDF must have four pages" {})))
         identity {:source-sha256 sha :acquisitions (:acquisitions source)
                   :evidence-sha256 (archive/extraction-evidence root)
                   :actor actor :config (assoc config :reviewed-ledger-sha256 (:sha256 retained))
                   :parser-version parser-version :schema-version 3
                   :pdfinfo-version (str/trim (:err (command! "pdfinfo" "-v")))
                   :tool {:name "manual-image-row-ledger" :version parser-version
                          :arguments ["retained TSV" "source-image visual review"]}}
         job-id (digest identity)]
     (archive/derive! root job-id
                      (fn [] (merge replay identity
                                    {:job-id job-id :processed-at (str (java.time.Instant/now))
                                     :tool-stderr ""})) on-progress))))

(defn validate-artifact! [root artifact]
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
                   (= ["retained TSV" "source-image visual review"]
                      (get-in artifact [:tool :arguments]))
                   (= 4 page-count)
                   (= replay (select-keys artifact (keys replay))))
      (throw (ex-info "Camotes 2026 extraction differs from source and retained ledger replay" {})))
    artifact))
