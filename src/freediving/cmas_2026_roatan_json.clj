(ns freediving.cmas-2026-roatan-json
  "Source-bound transport rows from two official Roatan 2026 CWT men result APIs."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [freediving.archive :as archive])
  (:import [java.nio ByteBuffer]
           [java.nio.charset CodingErrorAction StandardCharsets]
           [java.security MessageDigest]
           [java.util HexFormat]))

(def parser-version "cmas-2026-roatan-json/1")

(def ^:private units
  {3551 {:event 661 :phase 594 :date "2026-08-17T00:00:00.000Z"}
   3559 {:event 655 :phase 588 :date "2026-08-21T00:00:00.000Z"}})
(def ^:private categories
  {"SENM" [213 "Seniors"] "M1M" [214 "Masters M1"]
   "M2M" [215 "Masters M2"] "M3M" [216 "Masters M3"]})

(defn- fail! [message] (throw (ex-info message {})))
(defn- source-text [bytes]
  (when-not (bytes? bytes) (fail! "Expected JSON source bytes"))
  (try
    (str (.decode (doto (.newDecoder StandardCharsets/UTF_8)
                    (.onMalformedInput CodingErrorAction/REPORT)
                    (.onUnmappableCharacter CodingErrorAction/REPORT))
                  (ByteBuffer/wrap bytes)))
    (catch Exception _ (fail! "Invalid UTF-8 JSON source"))))

(defn- route [view-url json-url]
  (let [unit (some (fn [unit]
                     (when (and (= view-url
                                   (str "https://cmas.microplustimingservices.com/#/event-detail/FRD/30/110/"
                                        (:event (units unit)) "/" (:phase (units unit)) "/" unit "/result"))
                                (= json-url
                                   (str "https://cmas-api.microplustimingservices.com/api/units/" unit "/results")))
                       unit))
                   (keys units))]
    (when-not unit (fail! "Unsupported or mismatched Roatan result URLs"))
    (assoc (units unit) :unit unit)))

(defn- source-row? [row {:keys [unit event phase date]}]
  (and (map? row)
       (= 30 (get row "DCCmpID"))
       (= "FRD" (get row "DCDisCODE"))
       (= "FRD" (get row "DisCODE"))
       (= unit (get row "UtID"))
       (= event (get row "EvID"))
       (= phase (get row "UtPhID"))
       (= 110 (get row "EvCatID"))
       (= 110 (get row "CatID"))
       (= "CWT" (get row "EvShortDescr"))
       (= "CONSTANT WEIGHT WITH FINS" (get row "EvLongDescr"))
       (= date (get row "EvStartDate"))
       (= "MEN" (get row "PhLongDescr"))
       (or (= 3559 unit) (= "SENM" (get row "AGCodeDescr")))
       (= (categories (get row "AGCodeDescr"))
          [(get row "ResAGID") (get row "AGLongDescr")])))

(defn- populated? [x] (and (string? x) (not (str/blank? x))))

(defn- result-row? [row]
  (and (integer? (get row "ResID"))
       (integer? (get row "ParID"))
       (populated? (get row "ParPrintName"))
       (populated? (get row "ParOrgCode"))
       (integer? (get row "ResAGID"))
       (populated? (get row "AGLongDescr"))
       (#{"POINT" "IRM"} (get row "ResResultType"))
       (string? (get row "ResResult"))
       (or (nil? (get row "ResResultFinal")) (string? (get row "ResResultFinal")))
       (or (nil? (get row "ResReasonCode")) (string? (get row "ResReasonCode")))))

(defn- candidate [view-url index row]
  {:coordinates {:row-index-zero-based index}
   :source-page-url view-url
   :raw row
   :parsed {:source-participant-token (get row "ParID")
            :source-result-token (get row "ResID")
            :source-name (get row "ParPrintName")
            :representation (get row "ParOrgCode")
            :category (get row "AGCodeDescr")
            :discipline (get row "EvShortDescr")
            :event-date (get row "EvStartDate")
            :declared-depth-token (get row "DECLLEN_STR")
            :result-token (get row "ResResult")
            :final-result-token (get row "ResResultFinal")
            :penalty-token (get row "ResPenality")
            :reason-token (get row "ResReasonCode")
            :result-type-token (get row "ResResultType")}
   :parse-status :parsed :review-status :unreviewed :selection-status :blocked
   :attempt-equivalence :unknown :source-finality :unknown :revision-status :unknown})

(defn parse-result
  "Parse only the two observed result routes. Rows are transport records, not proven attempts."
  [bytes {:keys [view-url json-url] :as provenance}]
  (when-not (= #{:view-url :json-url} (set (keys provenance)))
    (fail! "Expected view-url and json-url"))
  (let [source-route (route view-url json-url)
        source (source-text bytes)
        rows (try (json/read-str source) (catch Exception _ (fail! "Malformed JSON source")))]
    (when-not (and (vector? rows) (seq rows) (every? #(source-row? % source-route) rows))
      (fail! "Unsupported or mismatched Roatan result layout"))
    (let [indexed (map-indexed vector rows)
          valid (filter (fn [[_ row]] (result-row? row)) indexed)
          invalid (remove (fn [[_ row]] (result-row? row)) indexed)]
      {:parser-version parser-version :raw-json source
       :view-url view-url :source-page-url view-url :json-url json-url
       :source-route source-route :status :needs-review
       :source-finality :unknown :revision-status :unknown
       :candidates (mapv (fn [[index row]] (candidate view-url index row)) valid)
       :unparsed-rows (mapv (fn [[index row]]
                              {:coordinates {:row-index-zero-based index}
                               :reason :unsupported-result-row :raw row}) invalid)
       :reconciliation {:source-row-count (count rows)
                        :candidate-count (count valid)
                        :unparsed-count (count invalid)}})))

(def tool {:name "clojure.data.json" :version "2.5.1"
           :arguments ["strict-UTF-8" "Roatan CWT men visible rows"]})

(defn- digest [value]
  (letfn [(canonical [x]
            (cond (map? x) (into (sorted-map) (map (fn [[k v]] [k (canonical v)]) x))
                  (sequential? x) (mapv canonical x)
                  :else x))]
    (.formatHex (HexFormat/of)
                (.digest (MessageDigest/getInstance "SHA-256")
                         (.getBytes (pr-str (canonical value)) "UTF-8")))))

(defn- registered-source [root sha256]
  (let [source (archive/inspect root sha256)
        manifests (mapv :manifest (:acquisitions source))
        views (set (map #(get-in % [:provenance :source-page-url]) manifests))
        urls (set (map :final-url manifests))]
    (when-not (and (seq manifests) (= 1 (count views)) (= 1 (count urls))
                   (every? #(re-matches #"(?i)application/json(?:;.*)?" (:content-type %)) manifests))
      (fail! "Roatan JSON requires one result view and JSON response URL"))
    {:source source :bytes (archive/read-source-bytes (:artifact-path source))
     :view-url (first views) :json-url (first urls)}))

(defn- visible-evidence [root evidence-sha source-sha view-url json-url parsed]
  (when-not (and (string? evidence-sha) (re-matches #"[0-9a-f]{64}" evidence-sha)
                 (some #{evidence-sha} (archive/extraction-evidence root)))
    (fail! "Missing Roatan visible-row evidence"))
  (let [bytes (archive/read-source-bytes (str root "/evidence/" evidence-sha))
        evidence (try (json/read-str (source-text bytes))
                      (catch Exception _ (fail! "Malformed Roatan visible-row evidence")))
        route (some #(when (= json-url (get % "requested_url")) %) (get evidence "routes"))
        browser (some #(when (= view-url (get % "view_url")) %) (get evidence "browser_observations"))
        n (get-in parsed [:reconciliation :source-row-count])]
    (when-not (and (= "roatan-cwt-men-source-check/v1" (get evidence "schema"))
                   (= 1 (count (filter #(= json-url (get % "requested_url")) (get evidence "routes"))))
                   (= 1 (count (filter #(= view-url (get % "view_url")) (get evidence "browser_observations"))))
                   (= json-url (get route "final_url")) (= 200 (get route "status"))
                   (re-matches #"(?i)application/json(?:;.*)?" (get route "content_type"))
                   (= source-sha (get route "sha256")) (= n (get route "transport_rows"))
                   (= "OFFICIAL" (get browser "label")) (= n (get browser "rows"))
                   (str/includes? (get browser "row_key_check" "") (str "All " n " visible "))
                   (populated? (get browser "observed_window_utc")))
      (fail! "Roatan visible-row evidence does not match archived source"))
    browser))

(defn- cite [source-sha json-url view-url browser candidate]
  (let [raw (:raw candidate) index (get-in candidate [:coordinates :row-index-zero-based])]
    {:source-sha256 source-sha :unit (get raw "UtID")
     :row-index-zero-based index :json-url json-url :view-url view-url
     :view-label (get browser "label") :visible-row-match (get browser "row_key_check")
     :visible-tuple {:rank (or (get raw "ResRnk") (get raw "ResReasonCode"))
                     :raw-rank (get raw "ResRnk") :name (get raw "ParPrintName")
                     :representation (get raw "ParOrgCode")
                     :birth-year (get raw "ParYearBirthDate")}}))

(defn- replay [root source-sha evidence-sha]
  (let [{:keys [bytes view-url json-url]} (registered-source root source-sha)
        parsed (parse-result bytes {:view-url view-url :json-url json-url})
        browser (visible-evidence root evidence-sha source-sha view-url json-url parsed)]
    (when-not (and (= (get-in parsed [:reconciliation :source-row-count])
                      (count (:candidates parsed)))
                   (empty? (:unparsed-rows parsed)))
      (fail! "Roatan visible rows include unsupported source positions"))
    (update parsed :candidates
            (fn [candidates]
              (mapv #(assoc % :citation (cite source-sha json-url view-url browser %)) candidates)))))

(defn validate-artifact!
  "Replay every unreviewed visible-row citation from archived JSON and retained packet evidence."
  [root artifact]
  (when-not (and (= 5 (:schema-version artifact))
                 (= parser-version (:parser-version artifact)) (= tool (:tool artifact))
                 (= (:citation-evidence-sha256 artifact)
                    (get-in artifact [:config :citation-evidence-sha256])))
    (fail! "Unsupported Roatan JSON extraction contract"))
  (let [source (archive/inspect root (:source-sha256 artifact))
        expected (replay root (:source-sha256 artifact) (:citation-evidence-sha256 artifact))]
    (when-not (and (vector? (:acquisitions artifact)) (seq (:acquisitions artifact))
                   (= (count (:acquisitions artifact))
                      (count (set (map :acquisition-id (:acquisitions artifact)))))
                   (every? (set (:acquisitions source)) (:acquisitions artifact))
                   (some #{(:citation-evidence-sha256 artifact)} (:evidence-sha256 artifact))
                   (= expected (select-keys artifact (keys expected))))
      (fail! "Roatan JSON source or visible-row citation replay mismatch")))
  artifact)

(defn extract!
  "Derive immutable, blocked schema 5 observations from archived Roatan JSON."
  [root sha256 {:keys [actor config citation-evidence-sha256] :as options}]
  (when-not (and (= #{:actor :config :citation-evidence-sha256} (set (keys options)))
                 (populated? actor) (map? config))
    (fail! "Expected actor, config and visible-row evidence SHA-256"))
  (let [{:keys [source]} (registered-source root sha256)
        parsed (replay root sha256 citation-evidence-sha256)
        identity {:source-sha256 sha256 :acquisitions (:acquisitions source)
                  :evidence-sha256 (archive/extraction-evidence root)
                  :actor actor :config (assoc config :citation-evidence-sha256 citation-evidence-sha256)
                  :parser-version parser-version :schema-version 5 :tool tool}
        job-id (digest identity)]
    (archive/derive! root job-id
                     #(merge parsed identity
                             {:citation-evidence-sha256 citation-evidence-sha256
                              :job-id job-id :processed-at (str (java.time.Instant/now))
                              :publication {:status :blocked}}) nil)))
