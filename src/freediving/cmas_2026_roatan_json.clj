(ns freediving.cmas-2026-roatan-json
  "Source-bound transport rows from two official Roatan 2026 CWT men result APIs."
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.nio ByteBuffer]
           [java.nio.charset CodingErrorAction StandardCharsets]))

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
