(ns freediving.extraction
  (:require [clojure.string :as str]
            [clojure.java.shell :as shell]
            [clojure.edn :as edn]
            [freediving.archive :as archive]
            [freediving.aida :as aida]))

(def parser-version "cmas-cwt-men/1")
(defn- number-value [s] (when s (parse-long s)))
(defn- field [value] {:status (if (nil? value) :unknown :parsed) :value value})
(defn- parse-line [line]
  (when-let [[_ rank name representation tail]
             (re-matches #"\s*(?:(\d+)\s+)?(.+?)\s+(CMAS1|AIN|[A-Z]{3})\s+Men Senior\s*(.*?)\s*" line)]
    (let [tokens (str/split tail #"\s+")
          nums (take-while #(re-matches #"\d+" %) tokens)
          rest-tokens (drop (count nums) tokens)
          status (when (#{"PEN" "DNS" "DSQ"} (first rest-tokens)) (first rest-tokens))
          notes (str/join " " (if status (rest rest-tokens) rest-tokens))
          normal? (and (#{2 3} (count nums)) (or (nil? status) (= "PEN" status)))
          valid? (or normal? (and (= "DNS" status) (empty? nums))
                     (and (= "DSQ" status) (= 1 (count nums))))]
      (when valid?
        {:rank (number-value rank) :source-name name :representation representation
         :category "Men Senior" :attempted-depth (number-value (first nums))
         :final-depth (when normal? (number-value (last nums)))
         :penalty (when (= 3 (count nums)) (number-value (second nums)))
         :status status :notes (when-not (str/blank? notes) notes) :unit nil
         ::raw {:rank rank :source-name name :representation representation :category "Men Senior"
                :attempted-depth (first nums) :final-depth (when normal? (last nums))
                :penalty (when (= 3 (count nums)) (second nums)) :status status
                :notes (when-not (str/blank? notes) notes)}}))))

(defn- parse-cmas-pages
  "Parse exact pdftotext layout page strings. Coordinates are 1-based text lines,
   not PDF geometry. Every nonblank line remains accounted for and review blocked."
  [pages]
  (let [whole (str/join "\n" pages)
        supported? (and (str/includes? whole "2025 CMAS World Championship Freediving Outdoor")
                        (str/includes? whole "CWT MEN SENIORS"))
        dates (distinct (map second (re-seq #"(?m)^\s*(\d{2}/\d{2}/\d{4})\s*$" whole)))
        event-date (when (= 1 (count dates))
                     (try (let [[d m y] (str/split (first dates) #"/")]
                            (str (java.time.LocalDate/of (parse-long y) (parse-long m) (parse-long d))))
                          (catch java.time.DateTimeException _ nil)))
        page-data (mapv (fn [idx text]
                          {:page (inc idx) :text text
                           :status (if (str/blank? text) :needs-OCR :text-extracted)
                           :lines (mapv (fn [n s] {:line (inc n) :text s})
                                        (range) (str/split text #"\n" -1))}) (range) pages)
        lines (for [page page-data line (:lines page) :when (not (str/blank? (:text line)))]
                {:page (:page page) :line (:line line) :text (:text line)})
        candidates (if supported?
                     (mapv (fn [{:keys [page line text]}]
                             (let [result (parse-line text) parsed (when result (dissoc result ::raw))]
                               {:coordinates {:page page :line line :column-start 1 :column-end (inc (count text))}
                                :raw {:line text :fields (::raw result)}
                                :parse-status (if parsed :parsed :unparsed)
                                :parsed (when parsed (assoc parsed :federation "CMAS" :event-date event-date :discipline "CWT"))
                                :fields (into {} (map (fn [[k v]] [k (field v)])
                                                      (assoc (or parsed {}) :unit nil :event-date event-date)))
                                :review-status :unreviewed}))
                           (remove #(re-find #"^\s*(?:2025 CMAS World Championship Freediving Outdoor|\d{2}/\d{2}/\d{4}|Result|CWT MEN SENIORS|FINAL|DEPTH|RANK\s+SURNAME.*|Report Created.*|Data Processing.*)\s*$" (:text %)) lines)) [])
        parsed-count (count (filter #(= :parsed (:parse-status %)) candidates))]
    {:parser-version parser-version
     :status (cond (some #(= :needs-OCR (:status %)) page-data) :needs-OCR
                   (not supported?) :unsupported-needs-parser
                   :else :needs-review)
     :pages page-data :candidates candidates
     :reconciliation {:page-count (count pages) :candidate-count (when supported? (count candidates))
                      :parsed-count (when supported? parsed-count) :unparsed-count (when supported? (- (count candidates) parsed-count))
                      :unresolved-count (when supported? (count candidates))
                      :nonblank-line-count (count lines)
                      :noncandidate-lines (vec (remove (fn [line] (some #(= (select-keys line [:page :line])
                                                                            (select-keys (:coordinates %) [:page :line])) candidates)) lines))
                      :status :unreviewed}
     :publication {:status :blocked :reasons [:owner-review-required :reconciliation-unreviewed :units-not-explicit]}}))

(defn parse-pages [pages]
  (if (aida/supported? pages) (aida/parse-pages pages) (parse-cmas-pages pages)))

(defn- canonical [value]
  (cond (map? value) (into (sorted-map) (map (fn [[k v]] [k (canonical v)]) value))
        (vector? value) (mapv canonical value)
        (sequential? value) (mapv canonical value)
        :else value))
(defn- digest [value]
  (.formatHex (java.util.HexFormat/of)
              (.digest (java.security.MessageDigest/getInstance "SHA-256")
                       (.getBytes (binding [*print-length* nil *print-level* nil] (pr-str (canonical value))) "UTF-8"))))
(defn- command! [& args]
  (let [result (apply shell/sh (concat args [:out-enc "UTF-8" :err-enc "UTF-8"]))]
    (when-not (zero? (:exit result))
      (throw (ex-info "PDF tool failed" {:tool (first args) :exit (:exit result) :stderr (:err result)})))
    result))

(defn extract!
  "Extract registered PDF to private versioned EDN. Config is retained verbatim as
   processing metadata; only layout UTF-8 extraction and this parser are implemented.
   All outputs require owner review and cannot authorize publication."
  ([root sha256 options] (extract! root sha256 options {}))
  ([root sha256 {:keys [actor config] :as options} {:keys [on-progress]}]
   (when-not (and (= #{:actor :config} (set (keys options)))
                  (string? actor) (not (str/blank? actor)) (map? config))
     (throw (ex-info "Expected {:actor nonblank-string :config map}" {})))
   (let [source (archive/inspect root sha256)
         _ (when (empty? (:acquisitions source)) (throw (ex-info "PDF lacks acquisition evidence" {})))
         evidence (archive/extraction-evidence root)
         tool-version (str/trim (:err (command! "pdftotext" "-v")))
         result (command! "pdftotext" "-layout" "-enc" "UTF-8" (:artifact-path source) "-")
         raw (:out result)
         segments (str/split raw #"\f" -1)
         pages (if (and (> (count segments) 1) (= "" (last segments))) (pop (vec segments)) (vec segments))
         aida? (aida/supported? pages)
         identity {:source-sha256 sha256 :acquisitions (:acquisitions source)
                   :evidence-sha256 evidence :actor actor :config config
                   :parser-version (if aida? aida/parser-version parser-version) :schema-version (if aida? 2 1)
                   :pdfinfo-version (str/trim (:err (command! "pdfinfo" "-v")))
                   :tool {:name "pdftotext" :version tool-version :arguments ["-layout" "-enc" "UTF-8"]}}
         job-id (digest identity)]
     (archive/derive! root job-id
                      (fn []
                        (let [info (:out (command! "pdfinfo" (:artifact-path source)))
                              page-count (some-> (re-find #"(?m)^Pages:\s+(\d+)\s*$" info) second parse-long)]
                          (when-not (= page-count (count pages))
                            (throw (ex-info "Extracted page count does not match PDF" {:expected page-count :actual (count pages)})))
                          (merge (parse-pages pages) identity
                                 {:job-id job-id :processed-at (str (java.time.Instant/now))
                                  :raw-text raw :tool-stderr (:err result)
                                  :pdf-page-count page-count}))) on-progress))))

(defn -main [& args]
  (try
    (when-not (= 3 (count args)) (throw (ex-info "Usage: ARCHIVE SHA256 OPTIONS.edn" {})))
    (let [[root sha256 file] args
          options (with-open [reader (java.io.PushbackReader. (java.io.StringReader. (String. (archive/read-source-bytes file) "UTF-8")))]
                    (let [eof (Object.) value (edn/read {:eof eof} reader)]
                      (when-not (identical? eof (edn/read {:eof eof} reader))
                        (throw (ex-info "Expected one EDN options form" {})))
                      value))]
      (prn (extract! root sha256 options))
      (shutdown-agents))
    (catch Exception error
      (binding [*out* *err*] (println "Extraction failed:" (.getMessage error)))
      (System/exit 1))))
