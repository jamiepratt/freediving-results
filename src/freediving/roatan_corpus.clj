(ns freediving.roatan-corpus
  "Private, isolated source-position corpus for the two observed Roatan CWT men views."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [freediving.cmas-2026-roatan-json :as parser])
  (:import [java.nio.file Files StandardCopyOption LinkOption]
           [java.nio.file.attribute PosixFilePermissions]
           [java.security MessageDigest]
           [java.util HexFormat]))

(defn- fail! [s] (throw (ex-info s {})))
(defn- sha [^bytes b] (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") b)))
(defn- file-bytes [f]
  (when (Files/isSymbolicLink (.toPath (io/file f))) (fail! "Symlink evidence is forbidden"))
  (Files/readAllBytes (.toPath (io/file f))))
(defn- keywordize-safe [x]
  (cond (map? x) (into {} (map (fn [[k v]] [(if (re-matches #"[a-z][a-z0-9_]*" k) (keyword k) k)
                                            (keywordize-safe v)]) x))
        (vector? x) (mapv keywordize-safe x)
        :else x))
(defn- read-json [^bytes b] (keywordize-safe (json/read-str (String. b "UTF-8"))))
(defn- required! [truth message] (when-not truth (fail! message)))
(defn- path [root section name] (io/file root section name))
(defn- mkdir! [f]
  (.mkdirs (io/file f))
  (Files/setPosixFilePermissions (.toPath (io/file f)) (PosixFilePermissions/fromString "rwx------")))
(defn- publish! [file ^bytes content]
  (let [target (.toPath (io/file file))]
    (if (Files/exists target (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
      (required! (= (seq content) (seq (file-bytes file))) "Immutable corpus object conflict")
      (let [temporary (Files/createTempFile (.getParent target) "pending-" ".tmp"
                                            (make-array java.nio.file.attribute.FileAttribute 0))]
        (try
          (Files/setPosixFilePermissions temporary (PosixFilePermissions/fromString "rw-------"))
          (Files/write temporary content (make-array java.nio.file.OpenOption 0))
          (Files/move temporary target (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE]))
          (finally (Files/deleteIfExists temporary)))))))
(defn- canonical [x]
  (cond (map? x) (into (sorted-map) (map (fn [[k v]] [k (canonical v)]) x))
        (sequential? x) (mapv canonical x)
        :else x))
(defn- encode [x] (.getBytes (pr-str (canonical x)) "UTF-8"))
(defn- unit-from [url]
  (some (fn [unit] (when (str/ends-with? url (str "/" unit "/results")) unit)) [3551 3559]))
(defn- route-and-browser! [manifest route]
  (let [json-url (:requested_url route)
        unit (unit-from json-url)
        browser (some #(when (str/ends-with? (:view_url %) (str "/" unit "/result")) %) (:browser_observations manifest))]
    (required! (and unit browser (= json-url (:final_url route)) (= [] (:redirects route))
                    (= 200 (:status route)) (= "application/json; charset=utf-8" (:content_type route))
                    (= (:transport_rows route) (:rows browser)) (= "OFFICIAL" (:label browser))
                    (str/includes? (:row_key_check browser) (str "All " (:rows browser) " visible "))
                    (string? (:observed_window_utc browser))
                    (string? (:discovery_url manifest)) (string? (:event_page_url manifest)))
               "Unsupported Roatan packet provenance")
    [unit browser]))
(defn- source-rows [parsed]
  (into {} (for [candidate (:candidates parsed)]
             [(get-in candidate [:coordinates :row-index-zero-based])
              {:status :imported :review-status :unreviewed :candidate candidate}])))
(defn- citation [source-sha route browser index raw]
  {:source-sha256 source-sha :unit (unit-from (:requested_url route))
   :row-index-zero-based index :json-url (:requested_url route)
   :view-url (:view_url browser) :view-label (:label browser)
   :visible-row-match (:row_key_check browser)
   :visible-tuple {:rank (or (get raw "ResRnk") (get raw "ResReasonCode"))
                   :raw-rank (get raw "ResRnk") :name (get raw "ParPrintName")
                   :representation (get raw "ParOrgCode") :birth-year (get raw "ParYearBirthDate")}})
(defn- entry [manifest manifest-sha route browser ^bytes source]
  (let [source-sha (sha source)
        parsed (parser/parse-result source {:view-url (:view_url browser) :json-url (:requested_url route)})
        valid (source-rows parsed)
        quarantined (into {} (for [row (:unparsed-rows parsed)]
                               [(get-in row [:coordinates :row-index-zero-based])
                                {:status :quarantined :review-status :unreviewed
                                 :reason (:reason row) :raw (:raw row)}]))
        _ (required! (= (:transport_rows route) (get-in parsed [:reconciliation :source-row-count]))
                     "Packet source-row count mismatch")
        rows (mapv (fn [index raw]
                     (let [item (or (get valid index) (get quarantined index))]
                       (required! item "Unreconciled source position")
                       (assoc item :citation (citation source-sha route browser index raw))))
                   (range (get-in parsed [:reconciliation :source-row-count]))
                   (json/read-str (:raw-json parsed)))]
    {:schema :roatan-cwt-men-isolated/v1 :parser-version parser/parser-version
     :manifest-sha256 manifest-sha :source-sha256 source-sha
     :unit (unit-from (:requested_url route))
     :discovery-url (:discovery_url manifest) :event-page-url (:event_page_url manifest)
     :publisher (:publisher manifest) :route route :browser-observation browser
     :source-row-count (count rows) :rows rows
     :reconciliation {:imported (count (:candidates parsed))
                      :quarantined (count (:unparsed-rows parsed))}}))
(defn- input! [packet]
  (let [manifest-bytes (file-bytes (io/file packet "manifest.json"))
        manifest (read-json manifest-bytes)
        routes (filter #(and (unit-from (:requested_url %))
                             (str/ends-with? (:requested_url %) "/results")) (:routes manifest))]
    (required! (and (= "roatan-cwt-men-source-check/v1" (:schema manifest))
                    (= #{3551 3559} (set (map #(unit-from (:requested_url %)) routes)))
                    (= 2 (count routes))) "Expected both Roatan result routes")
    {:manifest manifest :manifest-bytes manifest-bytes
     :entries (mapv (fn [route]
                      (let [[_ browser] (route-and-browser! manifest route)
                            digest (:sha256 route)
                            _ (required! (and (re-matches #"[0-9a-f]{64}" digest)
                                              (= (str "objects/" digest) (:object route)))
                                         "Invalid packet object reference")
                            source (file-bytes (io/file packet (:object route)))]
                        (required! (and (= digest (sha source)) (= (:byte_length route) (alength source)))
                                   "Packet source SHA-256 or length mismatch")
                        {:source source :value (entry manifest (sha manifest-bytes) route browser source)})) routes)}))
(declare replay)
(defn import-packet!
  "Copy exact packet bytes and replayable row citations into a separate private corpus."
  [packet root]
  (let [{:keys [manifest-bytes entries]} (input! packet)
        manifest-sha (sha manifest-bytes)
        entry-ids (into {} (for [{:keys [value]} entries]
                             [(:unit value) (sha (encode value))]))
        existing? (.exists (path root "packets" (str manifest-sha ".edn")))]
    (mkdir! root)
    (doseq [section ["objects" "manifests" "entries" "packets"]] (mkdir! (path root section "")))
    (publish! (path root "manifests" (str (sha manifest-bytes) ".json")) manifest-bytes)
    (doseq [{:keys [source value]} entries]
      (publish! (path root "objects" (:source-sha256 value)) source)
      (publish! (path root "entries" (str (sha (encode value)) ".edn")) (encode value)))
    (publish! (path root "packets" (str manifest-sha ".edn"))
              (encode {:manifest-sha256 manifest-sha :entries entry-ids}))
    (let [result (replay root)]
      {:status (if existing? :unchanged :created)
       :census {:imported (reduce + (map #(get-in % [:reconciliation :imported]) (:units result)))
                :quarantined (reduce + (map #(get-in % [:reconciliation :quarantined]) (:units result)))}})))
(defn replay
  "Verify immutable source, packet manifest, parser output and every cited row."
  [root]
  (let [packet-files (sort-by #(.getName %) (or (.listFiles (io/file root "packets")) []))
        _ (required! (seq packet-files) "No indexed Roatan packets")
        indexed (mapv (fn [f]
                        (let [record (edn/read-string (String. (file-bytes f) "UTF-8"))
                              manifest-sha (:manifest-sha256 record)]
                          (required! (and (= (.getName f) (str manifest-sha ".edn"))
                                          (= #{3551 3559} (set (keys (:entries record))))
                                          (every? #(re-matches #"[0-9a-f]{64}" %) (vals (:entries record)))
                                          (= manifest-sha (sha (file-bytes (path root "manifests" (str manifest-sha ".json"))))))
                                     "Incomplete Roatan packet index")
                          record)) packet-files)
        expected-files (set (for [record indexed id (vals (:entries record))] (str id ".edn")))
        files (sort-by #(.getName %) (or (.listFiles (io/file root "entries")) []))
        _ (required! (= expected-files (set (map #(.getName %) files))) "Missing or orphaned Roatan entry")
        units (mapv (fn [f]
                      (let [content (file-bytes f)
                            _ (required! (= (.getName f) (str (sha content) ".edn")) "Corpus entry hash mismatch")
                            value (edn/read-string (String. content "UTF-8"))
                            manifest-bytes (file-bytes (path root "manifests" (str (:manifest-sha256 value) ".json")))
                            _ (required! (= (:manifest-sha256 value) (sha manifest-bytes)) "Packet manifest hash mismatch")
                            manifest (read-json manifest-bytes)
                            route (:route value)
                            [_ browser] (route-and-browser! manifest route)
                            source (file-bytes (path root "objects" (:source-sha256 value)))
                            _ (required! (= (:source-sha256 value) (sha source)) "Corpus source SHA-256 mismatch")
                            expected (entry manifest (:manifest-sha256 value) route browser source)]
                        (required! (and (= route (some #(when (= (:requested_url %) (:requested_url route)) %) (:routes manifest)))
                                        (= browser (:browser-observation value))
                                        (= expected value)) "Corpus row citation conflict")
                        (required! (= (get-in (some #(when (= (:manifest-sha256 %) (:manifest-sha256 value)) %) indexed)
                                              [:entries (:unit value)])
                                      (subs (.getName f) 0 64)) "Packet entry identity mismatch")
                        value)) files)]
    {:units (vec (sort-by :unit units))}))

(defn -main [& args]
  (let [[command first-arg second-arg] args]
    (try
      (case command
        "import" (if (and first-arg second-arg) (prn (import-packet! first-arg second-arg))
                     (fail! "Usage: import PACKET CORPUS"))
        "replay" (if (and first-arg (nil? second-arg))
                   (let [units (:units (replay first-arg))]
                     (prn {:units (mapv #(select-keys % [:unit :source-sha256 :source-row-count :reconciliation]) units)
                           :imported (reduce + (map #(get-in % [:reconciliation :imported]) units))
                           :quarantined (reduce + (map #(get-in % [:reconciliation :quarantined]) units))}))
                   (fail! "Usage: replay CORPUS"))
        (fail! "Commands: import PACKET CORPUS | replay CORPUS"))
      (catch Exception e
        (binding [*out* *err*] (println "Roatan corpus failed:" (.getMessage e)))
        (System/exit 1)))))
