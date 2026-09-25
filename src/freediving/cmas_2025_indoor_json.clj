(ns freediving.cmas-2025-indoor-json
  "Source-bound 2025 Athens CGR1 result views. No athlete or attempt identity is inferred."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [freediving.archive :as archive])
  (:import [java.net URI]
           [java.nio ByteBuffer]
           [java.nio.charset CodingErrorAction StandardCharsets]
           [java.security MessageDigest]
           [java.util HexFormat]))

(def parser-version "cmas-2025-indoor-json/8")
(def v7-parser-version "cmas-2025-indoor-json/7")
(def v6-parser-version "cmas-2025-indoor-json/6")
(def v5-parser-version "cmas-2025-indoor-json/5")
(def v4-parser-version "cmas-2025-indoor-json/4")
(def previous-parser-version "cmas-2025-indoor-json/3")
(def prior-parser-version "cmas-2025-indoor-json/2")
(def legacy-parser-version "cmas-2025-indoor-json/1")
(def result-categories #{"JUF" "JUM" "MAF" "MAM" "SEF" "SEM"})
(def ^:private supported-competitions
  {"001" {:name "Static Apnea" :date "23/05/2025" :sport "NU"
          :family "1" :kind "static" :round "007" :heat "001"}
   "002" {:name "Speed Apnea 2x50" :date "22/05/2025" :sport "NU"
          :family "1" :kind "speed" :round "007" :heat "001"}
   "004" {:name "Speed Apnea 8x50" :date "22/05/2025" :sport "NU"
          :family "1" :kind "speed" :round "007" :heat "001"}
   "011" {:name "Dynamic Apnea Without Fin" :date "20/05/2025" :sport "TF"
          :family "2" :kind "dynamic"}
   "016" {:name "Dynamic Apnea Bi Fins" :date "21/05/2025" :sport "TF"
          :family "2" :kind "dynamic"
          :round "007" :heat "001"}
   "026" {:name "Dynamic Apnea" :date "24/05/2025" :sport "TF"
          :family "2" :kind "dynamic"
          :round "007" :heat "001"}})
(def ^:private sef-source-sha256
  "84b1294ebab01c4c173cca7a2d49b9d9c9ccf3349c656f3d01962ec5ee76af84")
(def ^:private sef-invalid-byte-offset 12982)
(def ^:private sef-row-span [12789 13382])
(def ^:private sef-row-sha256
  "17cec89ba54cd1ca192ef0078446eb40ef7a12efa3a98a3b33b0e30967179df3")
(defn- fail! [message] (throw (ex-info message {})))
(defn- populated? [x] (and (string? x) (not (str/blank? x))))
(defn- utf8 [bytes]
  (when-not (bytes? bytes) (fail! "Expected source bytes"))
  (try
    (str (.decode (doto (.newDecoder StandardCharsets/UTF_8)
                    (.onMalformedInput CodingErrorAction/REPORT)
                    (.onUnmappableCharacter CodingErrorAction/REPORT))
                  (ByteBuffer/wrap bytes)))
    (catch Exception _ (fail! "Invalid UTF-8 source"))))
(defn- bytes-sha256 [bytes]
  (.formatHex (HexFormat/of)
              (.digest (MessageDigest/getInstance "SHA-256") bytes)))
(defn- source-text [bytes route]
  (when-not (bytes? bytes) (fail! "Expected source bytes"))
  (if (and (= "SEF" (:category route))
           (= sef-source-sha256 (bytes-sha256 bytes)))
    (do
      (when-not (and (= 31246 (alength bytes))
                     (= 0x98 (bit-and 0xff (aget bytes sef-invalid-byte-offset)))
                     (= sef-row-sha256
                        (bytes-sha256 (java.util.Arrays/copyOfRange
                                       bytes (first sef-row-span) (second sef-row-span)))))
        (fail! "Archived SEF byte exception does not match source"))
      ;; The exact archived bytes authorize this one transient ASCII sentinel.
      ;; Row 19 is removed before candidates or any decoded name are retained.
      (let [parse-bytes (aclone bytes)]
        (aset-byte parse-bytes sef-invalid-byte-offset (byte 0x3f))
        {:text (utf8 parse-bytes) :quarantine? true}))
    {:text (utf8 bytes) :quarantine? false}))
(defn- codes [view-url]
  (try
    (let [u (URI. view-url)
          [_ family kind category competition round heat]
          (re-matches #"/([12])/(dynamic|static|speed)-result-json/([A-Z]{3})/([0-9]{3})/([0-9]{3})/([0-9]{3})"
                      (or (.getRawFragment u) ""))]
      (when-not (and (= "https" (.getScheme u))
                     (= "results.microplustimingservices.com" (.getHost u))
                     (= -1 (.getPort u)) (= "/CMAS/Results/" (.getRawPath u))
                     (nil? (.getRawQuery u)) (nil? (.getUserInfo u))
                     (#{["2" "dynamic"] ["1" "static"] ["1" "speed"]} [family kind]))
        (fail! "Unsupported CMAS result page URL"))
      {:family family :kind kind :category category :competition competition :round round :heat heat})
    (catch java.net.URISyntaxException _ (fail! "Malformed CMAS result page URL"))))
(defn- response-url? [url family filename]
  (try
    (let [u (URI. url)]
      (and (= "https" (.getScheme u))
           (= "results.microplustimingservices.com" (.getHost u))
           (= -1 (.getPort u)) (nil? (.getRawQuery u)) (nil? (.getRawFragment u))
           (nil? (.getUserInfo u))
           (= (str "/CMAS/ExportPOST/export/CMAS_" family "/"
                   (str/replace filename " " "%20")) (.getRawPath u))))
    (catch Exception _ false)))
(defn- header-code [source key]
  (let [value (get-in source [key "Cod"])]
    (when-not (populated? value) (fail! (str "Missing " key " code")))
    value))
(defn- valid-row? [row static?]
  (and (map? row)
       (every? #(string? (get row %))
               ["PlaCod" "PlaName" "PlaSurname" "PlaNat" "PlaCat" "b"
                "PlaLane" "MemPrest" "MemPoint"])
       (every? #(populated? (get row %))
               ["PlaCod" "PlaName" "PlaSurname" "PlaNat" "b" "PlaLane"])
       (if static?
         (and (string? (get row "PlaCatEff"))
              (or (populated? (get row "PlaCat")) (populated? (get row "PlaCatEff"))))
         (populated? (get row "PlaCat")))))
(defn- speed-row? [row competition]
  (let [performance (get row "MemPrest")
        fields (get row "MemFields")
        two-by-fifty? (= "002" competition)
        finish (get-in row ["MemFields" (if two-by-fifty? 2 8) "V"])
        clock-pattern (if two-by-fifty?
                        #"(?:[0-9]+:)?[0-9]{1,2}\.[0-9]{2}"
                        #"[0-9]+:[0-9]{2}\.[0-9]{2}")
        clock? (and (string? performance) (boolean (re-matches clock-pattern performance)))]
    (and (valid-row? row true)
         (string? (get row "MemQual"))
         (= "" (get row "MemPoint"))
         (= "" (get row "MemQual"))
         (vector? fields) (= 9 (count fields))
         (every? #(and (map? %) (string? (get % "V"))) fields)
         (or (not two-by-fifty?)
             (and (= "" (get-in fields [0 "V"]))
                  (every? #(= "" (get-in fields [% "V"])) (range 3 9))))
         (or (and clock? (= performance finish))
             (and (= "DNS" performance) (= "" finish))
             (and (= "DSQ" performance)
                  (or (= "" finish)
                      (and two-by-fifty? (string? finish)
                           (re-matches clock-pattern finish))))))))
(defn parse-result
  "Parse source-bound CGR1 bytes with separately observed page and response URLs.
   The exact archived SEF anomaly quarantines row 19; other bytes require strict UTF-8."
  [bytes {:keys [view-url json-url] :as provenance}]
  (when-not (= #{:view-url :json-url} (set (keys provenance)))
    (fail! "Expected view-url and json-url"))
  (let [route (codes view-url)
        {:keys [text quarantine?]} (source-text bytes route)
        source (try (json/read-str text) (catch Exception _ (fail! "Malformed JSON")))
        headers (dissoc source "data")
        filename (get source "jsonfilename")
        rows (get source "data")]
    (when-not (and (map? source) (populated? filename)
                   (= "CGR1" (header-code source "Document"))
                   (= "Result" (get-in source ["Document" "Eng"]))
                   (= (:category route) (header-code source "Category"))
                   (= (:competition route) (header-code source "Competition"))
                   (= (:round route) (header-code source "Round"))
                   (= (:heat route) (header-code source "Heat"))
                   (let [{:keys [name date sport family kind round heat]}
                         (get supported-competitions (:competition route))]
                     (and name
                          (= sport (header-code source "Sport"))
                          (= family (:family route)) (= kind (:kind route))
                          (= name (get-in source ["Competition" "Eng"]))
                          (= date (get-in source ["Event" "Date"]))
                          (or (nil? round) (= round (:round route)))
                          (or (nil? heat) (= heat (:heat route)))
                          (or (not (#{"static" "speed"} kind))
                              (and (= "HEATS" (get-in source ["Round" "Eng"]))
                                   (= date (get-in source ["Heat" "UffDate"]))))))
                   (contains? result-categories (:category route))
                   (= (:competition route) (get source "tipologia"))
                   (= (str (header-code source "Sport") (:category route)
                           (:competition route) "CLAS" (subs (:round route) 1) " " (:heat route) ".JSON") filename)
                   (response-url? json-url (:family route) filename)
                   (vector? rows) (seq rows)
                   (or (= "speed" (:kind route))
                       (every? #(valid-row? % (= "static" (:kind route))) rows))
                   (or (not quarantine?) (= 50 (count rows))))
      (fail! "Unsupported or ambiguous CGR1 result structure"))
    (let [speed? (= "speed" (:kind route))
          invalid-speed-indices (if speed?
                                  (into #{} (keep-indexed (fn [index row]
                                                            (when-not (speed-row? row (:competition route)) index)) rows))
                                  #{})]
      {:parser-version parser-version :raw-json (when-not quarantine? text) :view-url view-url
       :source-page-url view-url :json-url json-url
       :headers headers :status :needs-review
       :candidates (mapv (fn [[index row]]
                           {:coordinates {:row-index-zero-based index}
                            :source-page-url view-url
                            :raw row
                            :parsed (cond-> {:source-pla-code (get row "PlaCod")
                                             :source-name (str (get row "PlaSurname") " " (get row "PlaName"))
                                             :representation (get row "PlaNat")
                                             :category (if (and (#{"static" "speed"} (:kind route))
                                                                (not (populated? (get row "PlaCat"))))
                                                         (get row "PlaCatEff") (get row "PlaCat"))
                                             :heat (get row "b") :lane (get row "PlaLane")
                                             :performance-token (get row "MemPrest")
                                             :points-token (get row "MemPoint")}
                                      (#{"static" "speed"} (:kind route))
                                      (assoc :performance-unit :unknown :points-unit :unknown
                                             :time-token (when (and (string? (get row "MemPrest"))
                                                                    (re-matches (if (and speed? (= "002" (:competition route)))
                                                                                  #"(?:[0-9]+:)?[0-9]{1,2}\.[0-9]{2}"
                                                                                  #"[0-9]+:[0-9]{2}\.[0-9]{2}")
                                                                                (get row "MemPrest")))
                                                           (get row "MemPrest"))
                                             :status-token (when ((if speed? #{"DSQ" "DNS"} #{"DSQ"})
                                                                  (get row "MemPrest"))
                                                             (get row "MemPrest"))))
                            :parse-status :parsed :review-status :unreviewed :selection-status :blocked})
                         (remove (fn [[index _]] (or (and quarantine? (= 19 index))
                                                     (contains? invalid-speed-indices index)))
                                 (map-indexed vector rows)))
       :unparsed-rows (if speed?
                        (mapv (fn [index] {:coordinates {:row-index-zero-based index}
                                           :reason :ambiguous-speed-result-row
                                           :raw (nth rows index)})
                              (sort invalid-speed-indices))
                        (if quarantine?
                          [{:coordinates {:row-index-zero-based 19}
                            :reason :invalid-utf8 :byte-offset sef-invalid-byte-offset
                            :raw-byte-hex "98"
                            :raw-row-byte-span {:start-inclusive (first sef-row-span)
                                                :end-exclusive (second sef-row-span)
                                                :sha256 sef-row-sha256}}]
                          []))
       :reconciliation {:source-row-count (count rows) :candidate-count (- (count rows) (+ (if quarantine? 1 0) (count invalid-speed-indices)))
                        :unparsed-count (+ (if quarantine? 1 0) (count invalid-speed-indices))
                        :unresolved-count (count rows) :status :unreviewed}
       :publication {:status :blocked :reasons [:owner-review-required :source-semantics-unresolved
                                                :coverage-not-established]}})))
(declare parse-prior-result)
(defn- parse-v7-result [bytes provenance]
  (let [result (parse-result bytes provenance)]
    (when (= "002" (:competition (codes (:view-url provenance))))
      (fail! "Version 7 parser cannot replay speed 2x50 source"))
    (assoc result :parser-version v7-parser-version)))
(defn- parse-v6-result [bytes provenance]
  (let [result (parse-v7-result bytes provenance)]
    (when (= "004" (:competition (codes (:view-url provenance))))
      (fail! "Version 6 parser cannot replay speed apnea source"))
    (assoc result :parser-version v6-parser-version)))
(defn- parse-v5-result [bytes provenance]
  (let [result (parse-v6-result bytes provenance)]
    (when (= "static" (:kind (codes (:view-url provenance))))
      (fail! "Version 5 parser cannot replay static apnea source"))
    (assoc result :parser-version v5-parser-version)))
(defn- parse-v4-result [bytes provenance]
  (let [result (parse-v5-result bytes provenance)]
    (when-not (#{"011" "016"} (:competition (codes (:view-url provenance))))
      (fail! "Version 4 parser cannot replay dynamic apnea source"))
    (assoc result :parser-version v4-parser-version)))
(defn- parse-previous-result [bytes provenance]
  (let [result (parse-v4-result bytes provenance)]
    (when-not (= "011" (:competition (codes (:view-url provenance))))
      (fail! "Previous parser cannot replay bi-fins source"))
    (assoc result :parser-version previous-parser-version)))
(defn- parse-legacy-result [bytes provenance]
  (-> (parse-prior-result bytes provenance)
      (assoc :parser-version legacy-parser-version)
      (update :candidates
              (fn [candidates]
                (mapv (fn [candidate]
                        (assoc-in candidate [:parsed :source-name]
                                  (str (get-in candidate [:raw "PlaName"]) " "
                                       (get-in candidate [:raw "PlaSurname"]))))
                      candidates)))))

(defn- parse-prior-result [bytes provenance]
  (let [result (parse-previous-result bytes provenance)]
    (when (seq (:unparsed-rows result))
      (fail! "Prior parser cannot replay quarantined source"))
    (-> result
        (assoc :parser-version prior-parser-version)
        (dissoc :unparsed-rows)
        (update :reconciliation dissoc :unparsed-count))))

(defn- canonical [value]
  (cond (map? value) (into (sorted-map) (map (fn [[k v]] [k (canonical v)]) value))
        (sequential? value) (mapv canonical value)
        :else value))
(defn- digest [value]
  (.formatHex (HexFormat/of)
              (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes (binding [*print-length* nil *print-level* nil]
                                    (pr-str (canonical value))) "UTF-8"))))
(def tool {:name "clojure.data.json" :version "2.5.1" :arguments ["strict-UTF-8" "CGR1"]})
(defn- registered-source [root sha256]
  (let [source (archive/inspect root sha256)
        acquisitions (:acquisitions source)
        manifests (mapv :manifest acquisitions)
        view-urls (set (map #(get-in % [:provenance :source-page-url]) manifests))
        json-urls (set (map :final-url manifests))]
    (when-not (and (seq manifests)
                   (every? #(re-matches #"(?i)application/json(?:;.*)?" (:content-type %)) manifests)
                   (= 1 (count view-urls)) (= 1 (count json-urls))
                   (populated? (first view-urls)))
      (fail! "JSON requires one source page URL and application/json acquisition evidence"))
    {:source source :bytes (archive/read-source-bytes (:artifact-path source))
     :view-url (first view-urls) :json-url (first json-urls)}))
(defn validate-artifact!
  "Replay source bytes and page binding from the registered archive."
  [root artifact]
  (when-not (and (= 5 (:schema-version artifact))
                 (#{legacy-parser-version prior-parser-version previous-parser-version
                    v4-parser-version v5-parser-version v6-parser-version v7-parser-version parser-version}
                  (:parser-version artifact))
                 (= tool (:tool artifact)))
    (fail! "Unsupported CMAS JSON extraction contract"))
  (let [{:keys [source bytes view-url json-url]} (registered-source root (:source-sha256 artifact))
        expected ((case (:parser-version artifact)
                    "cmas-2025-indoor-json/1" parse-legacy-result
                    "cmas-2025-indoor-json/2" parse-prior-result
                    "cmas-2025-indoor-json/3" parse-previous-result
                    "cmas-2025-indoor-json/4" parse-v4-result
                    "cmas-2025-indoor-json/5" parse-v5-result
                    "cmas-2025-indoor-json/6" parse-v6-result
                    "cmas-2025-indoor-json/7" parse-v7-result
                    parse-result)
                  bytes {:view-url view-url :json-url json-url})]
    (when-not (and (= (:acquisitions source) (:acquisitions artifact))
                   (= expected (select-keys artifact (keys expected))))
      (fail! "CMAS JSON source replay mismatch")))
  artifact)
(defn extract!
  "Derive immutable schema 5 unreviewed evidence from registered CGR1 JSON."
  [root sha256 {:keys [actor config] :as options}]
  (when-not (and (= #{:actor :config} (set (keys options)))
                 (populated? actor) (map? config))
    (fail! "Expected actor and config"))
  (let [{:keys [source bytes view-url json-url]} (registered-source root sha256)
        identity {:source-sha256 sha256 :acquisitions (:acquisitions source)
                  :evidence-sha256 (archive/extraction-evidence root)
                  :actor actor :config config :parser-version parser-version :schema-version 5
                  :tool tool}
        job-id (digest identity)]
    (archive/derive! root job-id
                     #(merge (parse-result bytes {:view-url view-url :json-url json-url}) identity
                             {:job-id job-id :processed-at (str (java.time.Instant/now))}) nil)))
