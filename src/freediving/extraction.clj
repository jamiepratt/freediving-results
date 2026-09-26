(ns freediving.extraction
  (:require [clojure.string :as str]
            [clojure.java.shell :as shell]
            [clojure.edn :as edn]
            [freediving.archive :as archive]
            [freediving.aida :as aida]
            [freediving.athens :as athens]
            [freediving.athens-geometry :as athens-geometry]
            [freediving.novi-sad :as novi-sad]
            [freediving.croatia-open :as croatia-open]
            [freediving.italy-open :as italy-open]
            [freediving.world-games-2025 :as world-games]
            [freediving.world-games-series-2025 :as world-games-series]
            [freediving.kaohsiung-2025 :as kaohsiung]
            [freediving.lodz-2025 :as lodz]
            [freediving.unu-tampa-2025 :as unu-tampa]
            [freediving.noxy-2025 :as noxy]
            [freediving.deep-dominica-2025 :as deep-dominica]
            [freediving.vertical-blue-2025 :as vertical-blue]
            [freediving.indoor-2026 :as indoor-2026]
            [freediving.indoor-time-2026 :as indoor-time]
            [freediving.depth-2025 :as depth-2025]
            [freediving.depth-2026 :as depth-2026]
            [freediving.depth :as depth]))

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
  (cond (depth/supported? pages) (depth/parse-pages pages)
        (depth-2025/supported? pages) (depth-2025/parse-pages pages)
        (aida/supported? pages) (aida/parse-pages pages)
        (athens/supported? pages) (athens/parse-pages pages)
        (novi-sad/supported? pages) (novi-sad/parse-pages pages)
        (croatia-open/supported? pages) (croatia-open/parse-pages pages)
        (italy-open/supported? pages) (italy-open/parse-pages pages)
        (world-games/supported? pages) (world-games/parse-pages pages)
        (world-games-series/supported? pages) (world-games-series/parse-pages pages)
        (kaohsiung/supported? pages) (kaohsiung/parse-pages pages)
        (lodz/supported? pages) (lodz/parse-pages pages)
        (unu-tampa/supported? pages) (unu-tampa/parse-pages pages)
        (noxy/supported? pages) (noxy/parse-pages pages)
        (deep-dominica/supported? pages) (deep-dominica/parse-pages pages)
        (vertical-blue/supported? pages) (vertical-blue/parse-pages pages)
        (depth-2026/supported? pages) (depth-2026/parse-pages-with-geometry pages "")
        :else (parse-cmas-pages pages)))

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

(defn- depth-2026-selected? [pages]
  (and (depth-2026/supported? pages)
       (not-any? #(% pages) [depth/supported? depth-2025/supported? aida/supported? athens/supported? novi-sad/supported?])))

(defn legacy-novi-artifact? [artifact]
  (and (= 3 (:schema-version artifact)) (= novi-sad/parser-version (:parser-version artifact))))

(defn croatia-open-artifact? [artifact]
  (and (= 3 (:schema-version artifact)) (= croatia-open/parser-version (:parser-version artifact))))

(defn italy-open-artifact? [artifact]
  (and (= 3 (:schema-version artifact)) (= italy-open/parser-version (:parser-version artifact))))

(defn world-games-artifact? [artifact]
  (and (= 3 (:schema-version artifact)) (= world-games/parser-version (:parser-version artifact))))

(defn world-games-series-artifact? [artifact]
  (and (= 3 (:schema-version artifact)) (= world-games-series/parser-version (:parser-version artifact))))

(defn kaohsiung-artifact? [artifact]
  (and (= 3 (:schema-version artifact)) (= kaohsiung/parser-version (:parser-version artifact))))

(defn lodz-artifact? [artifact]
  (and (= 3 (:schema-version artifact)) (= lodz/parser-version (:parser-version artifact))))

(defn unu-tampa-artifact? [artifact]
  (and (= 3 (:schema-version artifact)) (= unu-tampa/parser-version (:parser-version artifact))))

(defn noxy-artifact? [artifact]
  (and (= 3 (:schema-version artifact)) (= noxy/parser-version (:parser-version artifact))))

(defn deep-dominica-artifact? [artifact]
  (and (= 3 (:schema-version artifact)) (= deep-dominica/parser-version (:parser-version artifact))))

(defn vertical-blue-artifact? [artifact]
  (and (= 3 (:schema-version artifact)) (= vertical-blue/parser-version (:parser-version artifact))))

(defn- athens-selected? [pages]
  (and (athens/supported? pages)
       (not-any? #(% pages) [depth/supported? depth-2025/supported? aida/supported?])))

(defn legacy-athens-artifact? [artifact]
  (and (= 3 (:schema-version artifact)) (= athens/parser-version (:parser-version artifact))))

(defn- indoor-selected? [pages]
  (and (indoor-2026/supported? pages)
       (not-any? #(% pages) [depth/supported? depth-2025/supported? aida/supported? athens/supported?])))

(defn requires-geometry-validation?
  "Recognize geometry artifacts even after identity or page-evidence downgrades.
   The archive-aware arity is required at the import trust boundary."
  ([artifact]
   (or (#{athens-geometry/parser-version depth-2025/geometry-parser-version depth-2026/parser-version indoor-2026/parser-version indoor-time/parser-version indoor-time/legacy-parser-version} (:parser-version artifact))
       (contains? artifact :geometry-xml) (contains? (:tool artifact) :geometry-arguments)
       (and (seq (:candidates artifact))
            (or (depth-2026-selected? (map :text (:pages artifact)))
                (and (indoor-selected? (map :text (:pages artifact))) (not (legacy-novi-artifact? artifact)))))))
  ([root artifact]
   (or (requires-geometry-validation? artifact)
       (when (and (not (legacy-novi-artifact? artifact)) (not (legacy-athens-artifact? artifact))
                  (not (croatia-open-artifact? artifact)) (not (italy-open-artifact? artifact))
                  (not (world-games-artifact? artifact))
                  (not (world-games-series-artifact? artifact))
                  (not (kaohsiung-artifact? artifact))
                  (not (lodz-artifact? artifact))
                  (not (unu-tampa-artifact? artifact))
                  (not (noxy-artifact? artifact))
                  (not (deep-dominica-artifact? artifact))
                  (not (vertical-blue-artifact? artifact)))
         (let [source (archive/inspect root (:source-sha256 artifact))
               raw (:out (command! "pdftotext" "-layout" "-enc" "UTF-8" (:artifact-path source) "-"))
               pages (str/split raw #"\f" -1)]
           (or (athens-selected? pages) (indoor-selected? pages) (depth-2026-selected? pages)))))))

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
         depth? (depth/supported? pages)
         depth-2025? (and (not depth?) (depth-2025/supported? pages))
         aida? (aida/supported? pages)
         athens? (athens-selected? pages)
         indoor? (and (not (or depth? depth-2025? aida? athens?)) (indoor-2026/supported? pages))
         indoor-time? (and indoor? (indoor-time/supported? pages))
         novi? (novi-sad/supported? pages)
         croatia? (croatia-open/supported? pages)
         italy? (italy-open/supported? pages)
         world-games? (world-games/supported? pages)
         world-games-series? (world-games-series/supported? pages)
         kaohsiung? (kaohsiung/supported? pages)
         lodz? (lodz/supported? pages)
         unu-tampa? (unu-tampa/supported? pages)
         noxy? (noxy/supported? pages)
         deep-dominica? (deep-dominica/supported? pages)
         vertical-blue? (vertical-blue/supported? pages)
         _ (when (and italy? (not= sha256 italy-open/source-sha256))
             (throw (ex-info "Italian Open parser is bound to a different source PDF" {})))
         _ (when (and world-games? (not= sha256 world-games/source-sha256))
             (throw (ex-info "World Games parser is bound to a different source PDF" {})))
         _ (when (and world-games-series? (not= sha256 world-games-series/source-sha256))
             (throw (ex-info "World Games Series parser is bound to a different source PDF" {})))
         _ (when (and kaohsiung? (not= sha256 kaohsiung/source-sha256))
             (throw (ex-info "Kaohsiung parser is bound to a different source PDF" {})))
         _ (when (and lodz? (not= sha256 lodz/source-sha256))
             (throw (ex-info "Łódź parser is bound to a different source PDF" {})))
         _ (when (and unu-tampa? (not= sha256 unu-tampa/source-sha256))
             (throw (ex-info "UNU Tampa parser is bound to a different source PDF" {})))
         _ (when (and noxy? (not= sha256 noxy/source-sha256))
             (throw (ex-info "nOxyCup parser is bound to a different source PDF" {})))
         _ (when (and deep-dominica? (not= sha256 deep-dominica/source-sha256))
             (throw (ex-info "Deep Dominica parser is bound to a different source PDF" {})))
         _ (when (and vertical-blue? (not= sha256 vertical-blue/source-sha256))
             (throw (ex-info "Vertical Blue parser is bound to a different source PDF" {})))
         depth-2026? (depth-2026-selected? pages)
         identity {:source-sha256 sha256 :acquisitions (:acquisitions source)
                   :evidence-sha256 evidence :actor actor :config config
                   :parser-version (cond indoor-time? indoor-time/parser-version indoor? indoor-2026/parser-version depth-2026? depth-2026/parser-version depth? depth/parser-version depth-2025? depth-2025/geometry-parser-version aida? aida/parser-version athens? athens-geometry/parser-version novi? novi-sad/parser-version croatia? croatia-open/parser-version italy? italy-open/parser-version world-games? world-games/parser-version world-games-series? world-games-series/parser-version kaohsiung? kaohsiung/parser-version lodz? lodz/parser-version unu-tampa? unu-tampa/parser-version noxy? noxy/parser-version deep-dominica? deep-dominica/parser-version vertical-blue? vertical-blue/parser-version :else parser-version)
                   :schema-version (cond indoor? 2 depth-2026? 2 depth? 2 depth-2025? 2 aida? 2 athens? 3 novi? 3 croatia? 3 italy? 3 world-games? 3 world-games-series? 3 kaohsiung? 3 lodz? 3 unu-tampa? 3 noxy? 3 deep-dominica? 3 vertical-blue? 3 :else 1)
                   :pdfinfo-version (str/trim (:err (command! "pdfinfo" "-v")))
                   :tool (cond-> {:name "pdftotext" :version tool-version :arguments ["-layout" "-enc" "UTF-8"]}
                           (or athens? indoor? depth-2025? depth-2026?) (assoc :geometry-arguments ["-bbox-layout" "-enc" "UTF-8"]))}
         job-id (digest identity)]
     (archive/derive! root job-id
                      (fn []
                        (let [info (:out (command! "pdfinfo" (:artifact-path source)))
                              page-count (some-> (re-find #"(?m)^Pages:\s+(\d+)\s*$" info) second parse-long)]
                          (when-not (= page-count (count pages))
                            (throw (ex-info "Extracted page count does not match PDF" {:expected page-count :actual (count pages)})))
                          (merge (if (or athens? indoor? depth-2025? depth-2026?)
                                   ((cond athens? athens-geometry/parse-pages-with-geometry indoor-time? indoor-time/parse-pages-with-geometry indoor? indoor-2026/parse-pages-with-geometry depth-2026? depth-2026/parse-pages-with-geometry :else depth-2025/parse-pages-with-geometry) pages (:out (command! "pdftotext" "-bbox-layout" "-enc" "UTF-8" (:artifact-path source) "-")))
                                   (parse-pages pages)) identity
                                 {:job-id job-id :processed-at (str (java.time.Instant/now))
                                  :raw-text raw :tool-stderr (:err result)
                                  :pdf-page-count page-count}))) on-progress))))

(defn validate-geometry-artifact!
  "Replay versioned geometry and layout from the hash-verified archived PDF before import.
   Legacy PDF contracts are deliberately not reinterpreted by this validator."
  [root artifact]
  (when-not (and (= (if (= athens-geometry/parser-version (:parser-version artifact)) 3 2) (:schema-version artifact))
                 (#{athens-geometry/parser-version depth-2025/geometry-parser-version depth-2026/parser-version indoor-2026/parser-version indoor-time/parser-version indoor-time/legacy-parser-version} (:parser-version artifact))
                 (= "pdftotext" (get-in artifact [:tool :name]))
                 (= ["-layout" "-enc" "UTF-8"] (get-in artifact [:tool :arguments]))
                 (= ["-bbox-layout" "-enc" "UTF-8"] (get-in artifact [:tool :geometry-arguments])))
    (throw (ex-info "Invalid geometry extraction contract" {})))
  (let [source (archive/inspect root (:source-sha256 artifact))
        version (str/trim (:err (command! "pdftotext" "-v")))
        raw (:out (command! "pdftotext" "-layout" "-enc" "UTF-8" (:artifact-path source) "-"))
        xml (:out (command! "pdftotext" "-bbox-layout" "-enc" "UTF-8" (:artifact-path source) "-"))
        segments (vec (str/split raw #"\f" -1))
        pages (if (= "" (last segments)) (pop segments) segments)
        replay ((cond (= athens-geometry/parser-version (:parser-version artifact)) athens-geometry/parse-pages-with-geometry (= indoor-time/legacy-parser-version (:parser-version artifact)) indoor-time/parse-legacy-pages-with-geometry (= indoor-time/parser-version (:parser-version artifact)) indoor-time/parse-pages-with-geometry (= indoor-2026/parser-version (:parser-version artifact)) indoor-2026/parse-pages-with-geometry (= depth-2026/parser-version (:parser-version artifact)) depth-2026/parse-pages-with-geometry :else depth-2025/parse-pages-with-geometry) pages xml)]
    (when-not (and (= version (get-in artifact [:tool :version]))
                   (= raw (:raw-text artifact))
                   (= replay (select-keys artifact (keys replay))))
      (throw (ex-info "Geometry extraction differs from archived source replay" {})))
    artifact))

(defn validate-legacy-athens-artifact!
  "Replay the preserved /6 parser. Earlier stored bytes remain immutable, but
   versions without an executable historical parser cannot bypass /7 replay."
  [root artifact]
  (let [source (archive/inspect root (:source-sha256 artifact))
        raw (:out (command! "pdftotext" "-layout" "-enc" "UTF-8" (:artifact-path source) "-"))
        segments (vec (str/split raw #"\f" -1))
        pages (if (= "" (last segments)) (pop segments) segments)
        replay (athens/parse-pages pages)]
    (when-not (and (legacy-athens-artifact? artifact) (= raw (:raw-text artifact))
                   (= replay (select-keys artifact (keys replay))))
      (throw (ex-info "Legacy Athens extraction differs from archived source replay" {})))
    artifact))

(defn validate-legacy-novi-artifact!
  "Keep the immutable junior-only parser usable while rejecting identity downgrades."
  [root artifact]
  (let [source (archive/inspect root (:source-sha256 artifact))
        raw (:out (command! "pdftotext" "-layout" "-enc" "UTF-8" (:artifact-path source) "-"))
        segments (vec (str/split raw #"\f" -1))
        pages (if (= "" (last segments)) (pop segments) segments)
        replay (novi-sad/parse-pages pages)]
    (when-not (and (legacy-novi-artifact? artifact)
                   (= raw (:raw-text artifact))
                   (= replay (select-keys artifact (keys replay))))
      (throw (ex-info "Legacy Novi Sad extraction differs from archived source replay" {})))
    artifact))

(defn validate-croatia-open-artifact!
  "Replay this source-bound parser against the registered PDF before import."
  [root artifact]
  (let [source (archive/inspect root (:source-sha256 artifact))
        raw (:out (command! "pdftotext" "-layout" "-enc" "UTF-8" (:artifact-path source) "-"))
        segments (vec (str/split raw #"\f" -1))
        pages (if (= "" (last segments)) (pop segments) segments)
        replay (croatia-open/parse-pages pages)]
    (when-not (and (croatia-open-artifact? artifact)
                   (= raw (:raw-text artifact))
                   (= replay (select-keys artifact (keys replay))))
      (throw (ex-info "Croatian Open extraction differs from archived source replay" {})))
    artifact))

(defn validate-italy-open-artifact!
  "Replay source-bound text and reconciliation against the registered PDF before import."
  [root artifact]
  (let [source (archive/inspect root (:source-sha256 artifact))
        raw (:out (command! "pdftotext" "-layout" "-enc" "UTF-8" (:artifact-path source) "-"))
        segments (vec (str/split raw #"\f" -1))
        pages (if (= "" (last segments)) (pop segments) segments)
        replay (italy-open/parse-pages pages)]
    (when-not (and (italy-open-artifact? artifact)
                   (= italy-open/source-sha256 (:source-sha256 artifact))
                   (= "pdftotext" (get-in artifact [:tool :name]))
                   (= ["-layout" "-enc" "UTF-8"] (get-in artifact [:tool :arguments]))
                   (= (str/trim (:err (command! "pdftotext" "-v"))) (get-in artifact [:tool :version]))
                   (= raw (:raw-text artifact))
                   (= replay (select-keys artifact (keys replay))))
      (throw (ex-info "Italian Open extraction differs from archived source replay" {})))
    artifact))

(defn validate-world-games-artifact!
  "Replay the source-bound World Games layout before import."
  [root artifact]
  (let [source (archive/inspect root (:source-sha256 artifact))
        raw (:out (command! "pdftotext" "-layout" "-enc" "UTF-8" (:artifact-path source) "-"))
        segments (vec (str/split raw #"\f" -1))
        pages (if (= "" (last segments)) (pop segments) segments)
        replay (world-games/parse-pages pages)]
    (when-not (and (world-games-artifact? artifact)
                   (= world-games/source-sha256 (:source-sha256 artifact))
                   (= "pdftotext" (get-in artifact [:tool :name]))
                   (= ["-layout" "-enc" "UTF-8"] (get-in artifact [:tool :arguments]))
                   (= (str/trim (:err (command! "pdftotext" "-v"))) (get-in artifact [:tool :version]))
                   (= raw (:raw-text artifact))
                   (= replay (select-keys artifact (keys replay))))
      (throw (ex-info "World Games extraction differs from archived source replay" {})))
    artifact))

(defn validate-world-games-series-artifact!
  "Replay the source-bound Series heat rows before import."
  [root artifact]
  (let [source (archive/inspect root (:source-sha256 artifact))
        raw (:out (command! "pdftotext" "-layout" "-enc" "UTF-8" (:artifact-path source) "-"))
        segments (vec (str/split raw #"\f" -1))
        pages (if (= "" (last segments)) (pop segments) segments)
        replay (world-games-series/parse-pages pages)]
    (when-not (and (world-games-series-artifact? artifact)
                   (= world-games-series/source-sha256 (:source-sha256 artifact))
                   (= "pdftotext" (get-in artifact [:tool :name]))
                   (= ["-layout" "-enc" "UTF-8"] (get-in artifact [:tool :arguments]))
                   (= (str/trim (:err (command! "pdftotext" "-v"))) (get-in artifact [:tool :version]))
                   (= raw (:raw-text artifact))
                   (= replay (select-keys artifact (keys replay))))
      (throw (ex-info "World Games Series extraction differs from archived source replay" {})))
    artifact))

(defn validate-kaohsiung-artifact!
  "Replay the source-bound Kaohsiung attempt rows before import."
  [root artifact]
  (let [source (archive/inspect root (:source-sha256 artifact))
        raw (:out (command! "pdftotext" "-layout" "-enc" "UTF-8" (:artifact-path source) "-"))
        segments (vec (str/split raw #"\f" -1))
        pages (if (= "" (last segments)) (pop segments) segments)
        replay (kaohsiung/parse-pages pages)]
    (when-not (and (kaohsiung-artifact? artifact)
                   (= kaohsiung/source-sha256 (:source-sha256 artifact))
                   (= "pdftotext" (get-in artifact [:tool :name]))
                   (= ["-layout" "-enc" "UTF-8"] (get-in artifact [:tool :arguments]))
                   (= (str/trim (:err (command! "pdftotext" "-v"))) (get-in artifact [:tool :version]))
                   (= raw (:raw-text artifact))
                   (= replay (select-keys artifact (keys replay))))
      (throw (ex-info "Kaohsiung extraction differs from archived source replay" {})))
    artifact))

(defn validate-lodz-artifact!
  "Replay the source-bound Łódź attempt rows before import."
  [root artifact]
  (let [source (archive/inspect root (:source-sha256 artifact))
        raw (:out (command! "pdftotext" "-layout" "-enc" "UTF-8" (:artifact-path source) "-"))
        segments (vec (str/split raw #"\f" -1))
        pages (if (= "" (last segments)) (pop segments) segments)
        replay (lodz/parse-pages pages)]
    (when-not (and (lodz-artifact? artifact)
                   (= lodz/source-sha256 (:source-sha256 artifact))
                   (= "pdftotext" (get-in artifact [:tool :name]))
                   (= ["-layout" "-enc" "UTF-8"] (get-in artifact [:tool :arguments]))
                   (= (str/trim (:err (command! "pdftotext" "-v"))) (get-in artifact [:tool :version]))
                   (= raw (:raw-text artifact))
                   (= replay (select-keys artifact (keys replay))))
      (throw (ex-info "Łódź extraction differs from archived source replay" {})))
    artifact))

(defn validate-unu-tampa-artifact!
  "Replay source-bound UNU Tampa results against the registered PDF."
  [root artifact]
  (let [source (archive/inspect root (:source-sha256 artifact))
        raw (:out (command! "pdftotext" "-layout" "-enc" "UTF-8" (:artifact-path source) "-"))
        segments (vec (str/split raw #"\f" -1))
        pages (if (= "" (last segments)) (pop segments) segments)
        replay (unu-tampa/parse-pages pages)]
    (when-not (and (unu-tampa-artifact? artifact)
                   (= unu-tampa/source-sha256 (:source-sha256 artifact))
                   (= "pdftotext" (get-in artifact [:tool :name]))
                   (= ["-layout" "-enc" "UTF-8"] (get-in artifact [:tool :arguments]))
                   (= (str/trim (:err (command! "pdftotext" "-v"))) (get-in artifact [:tool :version]))
                   (= raw (:raw-text artifact))
                   (= replay (select-keys artifact (keys replay))))
      (throw (ex-info "UNU Tampa extraction differs from archived source replay" {})))
    artifact))

(defn validate-noxy-artifact!
  "Replay source-bound nOxyCup rows against the registered PDF."
  [root artifact]
  (let [source (archive/inspect root (:source-sha256 artifact))
        raw (:out (command! "pdftotext" "-layout" "-enc" "UTF-8" (:artifact-path source) "-"))
        segments (vec (str/split raw #"\f" -1))
        pages (if (= "" (last segments)) (pop segments) segments)
        replay (noxy/parse-pages pages)]
    (when-not (and (noxy-artifact? artifact)
                   (= noxy/source-sha256 (:source-sha256 artifact))
                   (= "pdftotext" (get-in artifact [:tool :name]))
                   (= ["-layout" "-enc" "UTF-8"] (get-in artifact [:tool :arguments]))
                   (= (str/trim (:err (command! "pdftotext" "-v"))) (get-in artifact [:tool :version]))
                   (= raw (:raw-text artifact))
                   (= replay (select-keys artifact (keys replay))))
      (throw (ex-info "nOxyCup extraction differs from archived source replay" {})))
    artifact))

(defn validate-deep-dominica-artifact!
  "Replay source-bound Deep Dominica rows against the registered PDF."
  [root artifact]
  (let [source (archive/inspect root (:source-sha256 artifact))
        raw (:out (command! "pdftotext" "-layout" "-enc" "UTF-8" (:artifact-path source) "-"))
        segments (vec (str/split raw #"\f" -1))
        pages (if (= "" (last segments)) (pop segments) segments)
        replay (deep-dominica/parse-pages pages)]
    (when-not (and (deep-dominica-artifact? artifact)
                   (= deep-dominica/source-sha256 (:source-sha256 artifact))
                   (= "pdftotext" (get-in artifact [:tool :name]))
                   (= ["-layout" "-enc" "UTF-8"] (get-in artifact [:tool :arguments]))
                   (= (str/trim (:err (command! "pdftotext" "-v"))) (get-in artifact [:tool :version]))
                   (= raw (:raw-text artifact))
                   (= replay (select-keys artifact (keys replay))))
      (throw (ex-info "Deep Dominica extraction differs from archived source replay" {})))
    artifact))

(defn validate-vertical-blue-artifact!
  "Replay source-bound Vertical Blue rows against the registered PDF."
  [root artifact]
  (let [source (archive/inspect root (:source-sha256 artifact))
        raw (:out (command! "pdftotext" "-layout" "-enc" "UTF-8" (:artifact-path source) "-"))
        segments (vec (str/split raw #"\f" -1))
        pages (if (= "" (last segments)) (pop segments) segments)
        replay (vertical-blue/parse-pages pages)]
    (when-not (and (vertical-blue-artifact? artifact)
                   (= vertical-blue/source-sha256 (:source-sha256 artifact))
                   (= "pdftotext" (get-in artifact [:tool :name]))
                   (= ["-layout" "-enc" "UTF-8"] (get-in artifact [:tool :arguments]))
                   (= (str/trim (:err (command! "pdftotext" "-v"))) (get-in artifact [:tool :version]))
                   (= raw (:raw-text artifact))
                   (= replay (select-keys artifact (keys replay))))
      (throw (ex-info "Vertical Blue extraction differs from archived source replay" {})))
    artifact))

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
