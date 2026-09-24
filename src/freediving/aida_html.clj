(ns freediving.aida-html
  "Bounded AIDA table extraction. Source text, not inferred sporting facts, is authoritative."
  (:require [clojure.string :as str]
            [freediving.archive :as archive])
  (:import [org.jsoup.parser Parser]
           [org.jsoup.nodes Element]))

(def parser-version "aida-html/1")
(def identity-keys [:source-sha256 :acquisitions :evidence-sha256 :actor :config :parser-version :schema-version :tool])
(defn digest [value]
  (letfn [(canonical [v]
            (cond (map? v) (into (sorted-map) (map (fn [[k x]] [k (canonical x)]) v))
                  (sequential? v) (mapv canonical v) :else v))]
    (.formatHex (java.util.HexFormat/of)
                (.digest (java.security.MessageDigest/getInstance "SHA-256")
                         (.getBytes (binding [*print-length* nil *print-level* nil] (pr-str (canonical value))) "UTF-8")))))
(def attempt-headers ["Start" "Diver" "Nationality" "Gender" "Discipline" "OT" "AP" "RP" "Card" "Points" "Remarks"])
(def legacy-headers ["Start" "Diver" "Nationality" "Gender" "Discipline" "Line" "Official Top" "AP" "RP" "Card" "Points" "Remarks"])
(def ranking-headers ["Medals" "#" "Name" "Nationality" "Result" "Announced" "Points" "Penalties"])
(defn- text [^Element e] (when e (.wholeText e)))
(defn- value [s] (when-not (str/blank? s) (str/trim s)))
(defn- exact-html [source ^Element e]
  (let [start (.. e sourceRange start pos) end (.. e endSourceRange end pos)]
    (when (and (<= 0 start) (<= start end (count source))) (subs source start end))))
(defn- metadata [doc]
  (let [dates (mapv #(str/trim (text %)) (.select doc "li.active .days"))
        date (when (= 1 (count dates))
               (try (str (java.time.LocalDate/parse (first dates))) (catch Exception _ nil)))
        selected (fn [selector] (let [xs (.select doc selector)] (when (= 1 (count xs)) (value (text (first xs))))))]
    {:event-name (some-> (.selectFirst doc "title") text value)
     :event-date date
     :selected-discipline (selected "select#discipline option[selected]")
     :selected-gender (selected "select#gender option[selected]")}))
(defn- children [^Element row tag]
  (filter #(= tag (.tagName ^Element %)) (.children row)))
(defn- performance [s]
  (cond
    (nil? s) {:status :unknown}
    :else
    (if-let [[_ number unit badge] (re-matches #"([0-9]+(?:[.][0-9]+)?)\s+(m)(?:\s+(NR|CR|WR))?" s)]
      {:status :parsed :value (bigdec number) :unit unit :badge badge}
      (if-let [[_ minutes seconds badge] (re-matches #"([0-9]+):([0-5][0-9])(?:\s+(NR|CR|WR))?" s)]
        {:status :parsed :time {:components [(parse-long minutes) (parse-long seconds)]
                                :fraction nil :fraction-digits 0 :notation :colon-separated}
         :badge badge}
        {:status :invalid}))))
(defn- candidate [source table-index row-index ^Element row headers family context]
  (let [elements (vec (children row "td"))
        cells (mapv text elements)
        raw-fields (zipmap headers cells)
        complete? (and (= (count headers) (count cells))
                       (string? (exact-html source row))
                       (not (.. row endSourceRange isImplicit))
                       (every? #(and (string? (exact-html source %))
                                     (not (.. ^Element % endSourceRange isImplicit))
                                     (empty? (.select ^Element % "table"))
                                     (not (.hasAttr % "colspan")) (not (.hasAttr % "rowspan"))) elements))
        getv #(value (get raw-fields %))
        announced (performance (getv (if (= family :attempts) "AP" "Announced")))
        realised (performance (getv (if (= family :attempts) "RP" "Result")))
        parsed (when (and complete? (getv (if (= family :attempts) "Diver" "Name")))
                 {:federation "AIDA" :source-name (getv (if (= family :attempts) "Diver" "Name"))
                  :representation (getv "Nationality") :category nil
                  :gender (if (= family :attempts) (getv "Gender") (:selected-gender context))
                  :discipline (if (= family :attempts) (getv "Discipline") (:selected-discipline context))
                  :event-name (:event-name context) :event-date (:event-date context)
                  :announced-performance (getv (if (= family :attempts) "AP" "Announced"))
                  :realised-performance (getv (if (= family :attempts) "RP" "Result"))
                  :performance (:value realised) :unit (:unit realised)
                  :announced (:value announced) :announced-unit (:unit announced)
                  :realized-time (:time realised) :announced-time (:time announced)
                  :record-badge (:badge realised) :announced-record-badge (:badge announced)
                  :card (getv "Card") :points (getv "Points") :penalty (getv "Penalties")
                  :remarks (getv "Remarks") :start (getv "Start") :line (getv "Line") :official-top (or (getv "OT") (getv "Official Top"))
                  :rank (getv "#")})
        contradiction? (and (= "WHITE" (some-> (getv "Card") str/trim str/upper-case))
                            (some->> (getv "Remarks") (re-find #"(?i)\bdq\w*")))]
    {:coordinates {:table (inc table-index) :row (inc row-index)}
     :source-family family
     :raw {:html (exact-html source row) :cells cells :cell-html (mapv #(exact-html source %) elements) :fields raw-fields}
     :parse-status (if parsed :parsed :unparsed) :parsed parsed
     :fields (cond-> (into {} (map (fn [[k v]] [k {:status (if (nil? v) :unknown :parsed) :value v}]) parsed))
               (= :invalid (:status realised)) (assoc :performance {:status :invalid :value nil :reason :unrecognized-performance})
               (= :invalid (:status announced)) (assoc :announced {:status :invalid :value nil :reason :unrecognized-performance}))
     :flags (cond-> [] contradiction? (conj :white-card-with-disqualification-remark))
     :review-status :unreviewed}))
(defn parse-html
  "Extract supported table families. Every data row stays accounted for; no names, numbers, units or cards are repaired."
  [source]
  (let [doc (.parseInput (.setTrackPosition (Parser/htmlParser) true) source "")
        context (metadata doc)
        tables (vec (.select doc "table"))
        table-data
        (mapv (fn [i table]
                (let [rows (vec (filter #(identical? table (.closest ^Element % "table")) (.select ^Element table "tr")))
                      header-row (first (keep-indexed (fn [j row] (when (seq (children row "th")) j)) rows))
                      headers (when header-row (mapv #(str/trim (text %)) (children (nth rows header-row) "th")))
                      family (cond (#{attempt-headers legacy-headers} headers) :attempts (= headers ranking-headers) :ranking :else nil)
                      data-rows (keep-indexed (fn [j row] (when (seq (children row "td")) [j row])) rows)]
                  {:table (inc i) :headers headers :family family :data-row-count (count data-rows)
                   :candidates (if family (mapv (fn [[j row]] (candidate source i j row headers family context)) data-rows) [])}))
              (range) tables)
        candidates (vec (mapcat :candidates table-data))
        supported? (some :family table-data)
        parsed (count (filter #(= :parsed (:parse-status %)) candidates))]
    {:parser-version parser-version :raw-html source :context context
     :status (if supported? :needs-review :unsupported-needs-parser)
     :tables (mapv #(dissoc % :candidates) table-data) :candidates candidates
     :reconciliation {:table-count (count tables) :candidate-count (count candidates) :parsed-count parsed
                      :unparsed-count (- (count candidates) parsed) :unresolved-count (count candidates)
                      :unsupported-table-count (count (remove :family table-data)) :status :unreviewed}
     :publication {:status :blocked :reasons [:owner-review-required :html-review-not-supported :coverage-not-established]}}))

(defn- source-text [root hash]
  (let [source (archive/inspect root hash)
        bytes (archive/read-source-bytes (:artifact-path source))
        decoder (doto (.newDecoder java.nio.charset.StandardCharsets/UTF_8)
                  (.onMalformedInput java.nio.charset.CodingErrorAction/REPORT)
                  (.onUnmappableCharacter java.nio.charset.CodingErrorAction/REPORT))]
    (when-not (and (seq (:acquisitions source))
                   (every? #(re-matches #"(?i)text/html(?:;.*)?" (get-in % [:manifest :content-type])) (:acquisitions source)))
      (throw (ex-info "HTML requires text/html acquisition evidence" {})))
    (str (.decode decoder (java.nio.ByteBuffer/wrap bytes)))))

(defn validate-artifact!
  "Validate HTML observations by deterministic replay from verified archive bytes."
  [root artifact]
  (when-not (and (= 4 (:schema-version artifact)) (= parser-version (:parser-version artifact))
                 (= {:name "jsoup" :version "1.21.2" :arguments ["UTF-8" "track-position"]} (:tool artifact)))
    (throw (ex-info "Unsupported HTML extraction contract" {})))
  (let [expected (parse-html (source-text root (:source-sha256 artifact)))]
    (when-not (= expected (select-keys artifact (keys expected)))
      (throw (ex-info "HTML source replay mismatch" {}))))
  artifact)

(defn extract!
  "Derive immutable schema 4 HTML evidence from a registered UTF-8 representation.
   Parses explicit metres; time notation retains unknown unit. No HTTP requests,
   identity decisions or publication."
  [root sha256 {:keys [actor config] :as options}]
  (when-not (and (= #{:actor :config} (set (keys options)))
                 (string? actor) (not (str/blank? actor)) (map? config))
    (throw (ex-info "Expected {:actor nonblank-string :config map}" {})))
  (let [source (archive/inspect root sha256)
        text (source-text root sha256)
        identity {:source-sha256 sha256 :acquisitions (:acquisitions source)
                  :evidence-sha256 (archive/extraction-evidence root)
                  :actor actor :config config :parser-version parser-version :schema-version 4
                  :tool {:name "jsoup" :version "1.21.2" :arguments ["UTF-8" "track-position"]}}
        job-id (digest identity)]
    (archive/derive! root job-id
                     #(merge (parse-html text) identity
                             {:job-id job-id :processed-at (str (java.time.Instant/now))}) nil)))
