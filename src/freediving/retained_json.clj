(ns freediving.retained-json
  "A source-bound replay bridge for one retained Microplus unit-results route.
   JSON rows are source positions, not established sporting attempts."
  (:require [clojure.data.json :as json]))

(def parser-version "cmas-microplus-unit-3533/1")
(def unit-results-url
  "https://cmas-api.microplustimingservices.com/api/units/3533/results")
(def retained-source-sha256
  "1fb994cd08c19eebfc9ffe1e757d8ee3ef7ccf0d1e94d4a2fbd797acdb94adf0")

(defn- field [value key]
  (or (get value key) (get value (name key))))

(defn- sha256 [bytes]
  (.formatHex (java.util.HexFormat/of)
              (.digest (java.security.MessageDigest/getInstance "SHA-256") bytes)))

(defn- source-text [bytes]
  (let [decoder (doto (.newDecoder java.nio.charset.StandardCharsets/UTF_8)
                  (.onMalformedInput java.nio.charset.CodingErrorAction/REPORT)
                  (.onUnmappableCharacter java.nio.charset.CodingErrorAction/REPORT))]
    (str (.decode decoder (java.nio.ByteBuffer/wrap bytes)))))

(defn- result-row? [row]
  (and (map? row)
       (= 3533 (get row "UtID"))
       (= 28 (get row "DCCmpID"))
       (= 558 (get row "EvID"))
       (integer? (get row "ResID"))
       (contains? row "ResResult")
       (contains? row "ResResultFinal")))

(defn- positions [hash rows]
  (mapv (fn [index row]
          (let [pointer (str "/" index)]
            {:id (str "json-pointer=" pointer)
             :citation (str "sha256:" hash "#json-pointer=" pointer)
             :coordinates {:json-pointer pointer}
             :raw row}))
        (range (count rows)) rows))

(defn- checked-rows [bytes]
  (try
    (let [rows (json/read-str (source-text bytes))]
      (when (and (vector? rows) (seq rows)
                 (every? result-row? rows)
                 (= (count rows) (count (set (map #(get % "ResID") rows)))))
        rows))
    (catch Exception _ nil)))

(defn- exact-inventory? [document candidates]
  (let [inventory (set (map #(select-keys % [:id :citation :coordinates])
                            (:positions document)))]
    (every? #(contains? inventory (select-keys % [:id :citation :coordinates]))
            candidates)))

(defn claims-for-document
  "Check raw UTF-8 JSON bytes, acquisition receipt and an exact position inventory.

   Input: document with :format :json, :source-sha256 and positions citing
   `sha256:<hash>#json-pointer=/<index>`; retained input with :bytes and :receipt.
   Receipt must carry :status 200, :bytes, :sha256 and exact :final_url. This
   route is limited to Microplus competition 28, event 558, unit 3533 results.
   A new acquisition with changed bytes is a distinct source version only when
   its own receipt and document hash agree. No network or model calls occur."
  [document retained-input]
  (let [bytes (:bytes retained-input)
        receipt (:receipt retained-input)
        hash (when (bytes? bytes) (sha256 bytes))
        verified? (and (bytes? bytes)
                       (= :json (:format document))
                       (= unit-results-url (field receipt :final_url))
                       (= 200 (field receipt :status))
                       (= (alength bytes) (field receipt :bytes))
                       (= hash (field receipt :sha256))
                       (= hash (:source-sha256 document)))
        rows (when verified? (checked-rows bytes))
        candidates (when rows (positions hash rows))
        inventory? (and candidates (exact-inventory? document candidates))]
    (if inventory?
      {:claims [{:parser-id "cmas-microplus-unit-3533"
                 :parser-version parser-version
                 :match-reason "Verified Microplus unit 3533 Results route, receipt, row schema and JSON pointers"
                 :source-restriction {:sha256s #{hash} :formats #{:json}}
                 :supported-positions (set (map :id candidates))
                 :claimed-positions (set (map :id candidates))}]
       :unsupported-reasons [] :unsupported-formats []
       :source-verification :raw-bytes-and-receipt-sha256
       :extraction {:status :needs-review :source-sha256 hash
                    :source-url unit-results-url :candidates candidates}}
      {:claims []
       :unsupported-reasons [(cond
                               (not verified?) :source-or-receipt-mismatch
                               (nil? rows) :unit-results-schema-mismatch
                               :else :json-position-inventory-mismatch)]
       :unsupported-formats [] :source-verification :failed :extraction nil})))
