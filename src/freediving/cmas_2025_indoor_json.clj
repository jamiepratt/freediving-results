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

(def parser-version "cmas-2025-indoor-json/4")
(def previous-parser-version "cmas-2025-indoor-json/3")
(def prior-parser-version "cmas-2025-indoor-json/2")
(def legacy-parser-version "cmas-2025-indoor-json/1")
(def dnf-categories #{"JUF" "JUM" "MAF" "MAM" "SEF" "SEM"})
(def ^:private supported-competitions
  {"011" {:name "Dynamic Apnea Without Fin" :date "20/05/2025"}
   "016" {:name "Dynamic Apnea Bi Fins" :date "21/05/2025"
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
          (re-matches #"/([2])/(dynamic)-result-json/([A-Z]{3})/([0-9]{3})/([0-9]{3})/([0-9]{3})"
                      (or (.getRawFragment u) ""))]
      (when-not (and (= "https" (.getScheme u))
                     (= "results.microplustimingservices.com" (.getHost u))
                     (= -1 (.getPort u)) (= "/CMAS/Results/" (.getRawPath u))
                     (nil? (.getRawQuery u)) (nil? (.getUserInfo u))
                     (= family "2") (= kind "dynamic"))
        (fail! "Unsupported CMAS result page URL"))
      {:family family :category category :competition competition :round round :heat heat})
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
(defn- valid-row? [row]
  (and (map? row)
       (every? #(string? (get row %))
               ["PlaCod" "PlaName" "PlaSurname" "PlaNat" "PlaCat" "b"
                "PlaLane" "MemPrest" "MemPoint"])
       (every? #(populated? (get row %))
               ["PlaCod" "PlaName" "PlaSurname" "PlaNat" "PlaCat" "b" "PlaLane"])))
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
                   (= "TF" (header-code source "Sport"))
                   (let [{:keys [name date round heat]} (get supported-competitions (:competition route))]
                     (and name
                          (= name (get-in source ["Competition" "Eng"]))
                          (= date (get-in source ["Event" "Date"]))
                          (or (nil? round) (= round (:round route)))
                          (or (nil? heat) (= heat (:heat route)))))
                   (contains? dnf-categories (:category route))
                   (= (:competition route) (get source "tipologia"))
                   (= (str (header-code source "Sport") (:category route)
                           (:competition route) "CLAS" (subs (:round route) 1) " " (:heat route) ".JSON") filename)
                   (response-url? json-url (:family route) filename)
                   (vector? rows) (seq rows) (every? valid-row? rows)
                   (or (not quarantine?) (= 50 (count rows))))
      (fail! "Unsupported or ambiguous CGR1 result structure"))
    {:parser-version parser-version :raw-json (when-not quarantine? text) :view-url view-url
     :source-page-url view-url :json-url json-url
     :headers headers :status :needs-review
     :candidates (mapv (fn [[index row]]
                         {:coordinates {:row-index-zero-based index}
                          :source-page-url view-url
                          :raw row
                          :parsed {:source-pla-code (get row "PlaCod")
                                   :source-name (str (get row "PlaSurname") " " (get row "PlaName"))
                                   :representation (get row "PlaNat")
                                   :category (get row "PlaCat")
                                   :heat (get row "b") :lane (get row "PlaLane")
                                   :performance-token (get row "MemPrest")
                                   :points-token (get row "MemPoint")}
                          :parse-status :parsed :review-status :unreviewed :selection-status :blocked})
                       (remove (fn [[index _]] (and quarantine? (= 19 index)))
                               (map-indexed vector rows)))
     :unparsed-rows (if quarantine?
                      [{:coordinates {:row-index-zero-based 19}
                        :reason :invalid-utf8 :byte-offset sef-invalid-byte-offset
                        :raw-byte-hex "98"
                        :raw-row-byte-span {:start-inclusive (first sef-row-span)
                                            :end-exclusive (second sef-row-span)
                                            :sha256 sef-row-sha256}}]
                      [])
     :reconciliation {:source-row-count (count rows) :candidate-count (- (count rows) (if quarantine? 1 0))
                      :unparsed-count (if quarantine? 1 0)
                      :unresolved-count (count rows) :status :unreviewed}
     :publication {:status :blocked :reasons [:owner-review-required :source-semantics-unresolved
                                              :coverage-not-established]}}))
(declare parse-prior-result)
(defn- parse-previous-result [bytes provenance]
  (let [result (parse-result bytes provenance)]
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
                 (#{legacy-parser-version prior-parser-version previous-parser-version parser-version}
                  (:parser-version artifact))
                 (= tool (:tool artifact)))
    (fail! "Unsupported CMAS JSON extraction contract"))
  (let [{:keys [source bytes view-url json-url]} (registered-source root (:source-sha256 artifact))
        expected ((case (:parser-version artifact)
                    "cmas-2025-indoor-json/1" parse-legacy-result
                    "cmas-2025-indoor-json/2" parse-prior-result
                    "cmas-2025-indoor-json/3" parse-previous-result
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
