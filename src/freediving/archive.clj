(ns freediving.archive
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk])
  (:import [java.nio.file Files Paths StandardCopyOption StandardOpenOption LinkOption]
           [java.security MessageDigest]
           [java.nio.channels FileChannel]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]
           [java.net URI]
           [java.time OffsetDateTime LocalDate]
           [java.util HexFormat]))

(defn- path [s] (Paths/get (str s) (make-array String 0)))
(defn- sha256 [file]
  (let [digest (MessageDigest/getInstance "SHA-256") buffer (byte-array 65536)]
    (with-open [input (io/input-stream file)]
      (loop []
        (let [n (.read input buffer)]
          (when (pos? n)
            (.update digest buffer 0 n)
            (recur)))))
    (.formatHex (HexFormat/of) (.digest digest))))

(defn- canonical [manifest]
  (binding [*print-length* nil *print-level* nil]
    (pr-str (walk/postwalk #(if (map? %) (into (sorted-map) %) %) manifest))))
(defn- acquisition-id [manifest]
  (.formatHex (HexFormat/of)
              (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes (canonical manifest) "UTF-8"))))

(defn- fail! [message] (throw (ex-info message {})))
(defn- valid-hash? [v] (and (string? v) (re-matches #"[0-9a-f]{64}" v)))
(defn- text? [v] (and (string? v) (not (str/blank? v))))
(defn- timing-route? [^URI u]
  ;; Official CMAS archive redirects observed 2026-09-25. Only these public
  ;; schedule/result routes may retain a fragment.
  (and (= "https" (.getScheme u))
       (= -1 (.getPort u))
       (case (.getHost u)
         "results-ws.microplustimingservices.com"
         (and (= "/CMAS/Results/" (.getRawPath u))
              (re-matches #"/[0-9]+/schedule-bydate" (or (.getRawFragment u) "")))
         "cmas.microplustimingservices.com"
         (and (= "/" (.getRawPath u))
              (re-matches #"/(?:competition-schedule/[0-9]+|event-detail/FRD/[0-9]+/[0-9]+/[0-9]+/[0-9]+/[0-9]+/result)"
                          (or (.getRawFragment u) "")))
         false)))

(defn- aida-session-route? [^URI u]
  ;; Official EventPage/4350 links observed 2026-09-25. Do not decode or
  ;; normalize queries: only the literal public day selector is supported.
  (and (= "https" (.getScheme u))
       (= "www.aidainternational.org" (.getHost u))
       (= -1 (.getPort u))
       (re-matches #"/StartList/[0-9]+" (or (.getRawPath u) ""))
       (or (and (nil? (.getRawQuery u)) (= "start" (.getRawFragment u)))
           (and (nil? (.getRawFragment u))
                (re-matches #"day_index=[0-9]+" (or (.getRawQuery u) ""))))))

(defn- cmas-result-page? [v]
  (try
    (let [u (URI. v)]
      (and (= "https" (.getScheme u))
           (= "results.microplustimingservices.com" (.getHost u))
           (= -1 (.getPort u))
           (nil? (.getRawUserInfo u))
           (nil? (.getRawQuery u))
           (= "/CMAS/Results/" (.getRawPath u))
           (let [fragment (or (.getRawFragment u) "")]
             (or (re-matches #"/[12]/dynamic-result-json/[A-Z]{3}/[0-9]{3}/[0-9]{3}/[0-9]{3}"
                             fragment)
                 (re-matches #"/1/static-result-json/(?:JUF|JUM|MAF|MAM|SEF|SEM)/001/007/001"
                             fragment)
                 (re-matches #"/1/speed-result-json/(?:JUF|JUM|MAF|MAM|SEF|SEM)/004/007/001"
                             fragment)))))
    (catch Exception _ false)))

(defn- url? [v]
  (try (let [u (URI. v)]
         (and (#{"http" "https"} (.getScheme u)) (text? (.getHost u))
              (nil? (.getUserInfo u))
              (or (and (nil? (.getRawQuery u))
                       (or (nil? (.getRawFragment u)) (timing-route? u)))
                  (aida-session-route? u))))
       (catch Exception _ false)))
(defn- browser-state? [state]
  (and (map? state)
       (= #{:selected-date :filters :representation :rendered-sha256} (set (keys state)))
       (string? (:selected-date state))
       (re-matches #"[0-9]{4}-[0-9]{2}-[0-9]{2}" (:selected-date state))
       (try (LocalDate/parse (:selected-date state)) (catch Exception _ false))
       (= :rendered-dom (:representation state))
       (valid-hash? (:rendered-sha256 state))
       (map? (:filters state))
       (every? (fn [[k v]]
                 (case k
                   :discipline (contains? #{:all :sta :dyn :dynb :dnf :cwt :cwtb :cnf :fim} v)
                   :gender (contains? #{:all :men :women} v)
                   false))
               (:filters state))))

(defn- provenance? [p final-url]
  (and (map? p)
       (= #{:publisher-url :redirect-chain} (set (keys (dissoc p :browser-state :source-page-url))))
       (or (not (contains? p :browser-state)) (browser-state? (:browser-state p)))
       (or (not (contains? p :source-page-url)) (cmas-result-page? (:source-page-url p)))
       (url? (:publisher-url p))
       (vector? (:redirect-chain p))
       (seq (:redirect-chain p))
       (every? url? (:redirect-chain p))
       (= final-url (peek (:redirect-chain p)))))

(defn- validate! [manifest]
  (when-not (and (map? manifest)
                 (= #{:sha256 :discovery-url :final-url :acquisition-method :retrieved-at
                      :content-type :publisher :relationship :mirror-of}
                    (set (keys (dissoc manifest :provenance))))
                 (or (not (contains? manifest :provenance))
                     (provenance? (:provenance manifest) (:final-url manifest)))
                 (valid-hash? (:sha256 manifest))
                 (every? url? ((juxt :discovery-url :final-url) manifest))
                 (every? text? ((juxt :acquisition-method :publisher) manifest))
                 (string? (:content-type manifest))
                 (re-matches #"[^\s/;]+/[^\s/;]+(?:;.*)?" (:content-type manifest))
                 (try (OffsetDateTime/parse (:retrieved-at manifest))
                      (catch Exception _ false))
                 (#{:publisher :mirror :unknown} (:relationship manifest))
                 (if (= :mirror (:relationship manifest))
                   (text? (:mirror-of manifest))
                   (nil? (:mirror-of manifest))))
    (fail! "Malformed manifest: see canonical schema in README"))
  manifest)

(def ^:private process-lock (Object.))
(def ^:private nofollow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
(defn- exists? [p] (Files/exists (path p) nofollow))
(defn- attrs [mode]
  (into-array FileAttribute [(PosixFilePermissions/asFileAttribute
                              (PosixFilePermissions/fromString mode))]))

(defn- safe-path! [p]
  (let [p (.toAbsolutePath (path p))]
    (when (some #(= ".." (str %)) (iterator-seq (.iterator p)))
      (fail! "Parent traversal is not allowed"))
    (loop [current p]
      (when current
        (when (Files/isSymbolicLink current) (fail! "Symlinks are not allowed"))
        (recur (.getParent current))))
    (.normalize p)))

(defn- private! [p directory?]
  (safe-path! p)
  (when-not (if directory? (Files/isDirectory (path p) nofollow)
                (Files/isRegularFile (path p) nofollow))
    (fail! "Archive entry has an invalid file type"))
  (when (some #(re-find #"GROUP|OTHERS" (str %))
              (Files/getPosixFilePermissions (path p) nofollow))
    (fail! "Archive entries must be private: directories 0700, files 0600")))

(defn- directory! [p]
  (safe-path! p)
  (when-not (exists? p)
    (try (Files/createDirectory (path p) (attrs "rwx------"))
         (catch java.nio.file.FileAlreadyExistsException _ nil)))
  (private! p true))

(defn- with-archive [root create? action]
  (locking process-lock
    (let [root (str (safe-path! root))
          lock-path (path (io/file root ".lock"))]
      (when create? (directory! root))
      (private! root true)
      (safe-path! lock-path)
      (when (exists? lock-path) (private! lock-path false))
      (with-open [channel (FileChannel/open lock-path
                                            #{StandardOpenOption/CREATE StandardOpenOption/WRITE LinkOption/NOFOLLOW_LINKS}
                                            (attrs "rw-------"))
                  _lock (.lock channel)]
        (doseq [dir ["objects" "acquisitions" "tmp"]]
          (if create? (directory! (io/file root dir)) (private! (io/file root dir) true)))
        (action root)))))

(defn- sync! [p]
  (with-open [channel (FileChannel/open (path p)
                                        (into-array StandardOpenOption [StandardOpenOption/WRITE]))]
    (.force channel true)))

(defn- publish! [root target write! expected-hash]
  (safe-path! target)
  (let [temp (Files/createTempFile (path (io/file root "tmp")) "pending-" ".tmp" (attrs "rw-------"))]
    (try
      (write! (.toFile temp))
      (Files/setPosixFilePermissions temp (PosixFilePermissions/fromString "rw-------"))
      (when (and expected-hash (not= expected-hash (sha256 (.toFile temp))))
        (fail! "Source SHA-256 mismatch"))
      (sync! temp)
      (Files/move temp (path target)
                  (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE]))
      (finally (Files/deleteIfExists temp)))))

(defn- verified-object! [object digest]
  (safe-path! object)
  (when-not (exists? object) (fail! "Unknown artifact"))
  (private! object false)
  (when-not (= digest (sha256 object)) (fail! "Archived object SHA-256 mismatch")))

(defn- verified-browser-evidence! [root manifest]
  (when-let [digest (get-in manifest [:provenance :browser-state :rendered-sha256])]
    (private! (io/file root "evidence") true)
    (verified-object! (io/file root "evidence" digest) digest)))

(defn read-manifest
  "Read exactly one canonical EDN manifest, without evaluating code."
  [file]
  (with-open [reader (java.io.PushbackReader. (io/reader file :encoding "UTF-8"))]
    (let [eof (Object.)
          value (edn/read {:eof eof} reader)]
      (when-not (identical? eof (edn/read {:eof eof} reader)) (fail! "Expected one EDN manifest"))
      (validate! value))))

(defn- verified-record! [file]
  (private! file false)
  (when-not (re-matches #"[0-9a-f]{64}\.edn" (.getName file))
    (fail! "Invalid acquisition filename"))
  (let [manifest (read-manifest file)
        id (subs (.getName file) 0 64)]
    (when-not (= id (acquisition-id manifest)) (fail! "Acquisition record identity mismatch"))
    {:acquisition-id id :manifest manifest}))

(defn register!
  "Register existing bytes and explicit provenance. Optional :on-progress runs after
   the verified artifact is published, before acquisition publication; retry on interruption."
  ([root source manifest] (register! root source manifest {}))
  ([root source manifest {:keys [on-progress report-status]}]
   (validate! manifest)
   (safe-path! source)
   (when-not (Files/isRegularFile (path source) nofollow) (fail! "Source must be a regular file"))
   (when-not (= (:sha256 manifest) (sha256 source)) (fail! "Source SHA-256 mismatch"))
   (with-archive root true
     (fn [root]
       (let [digest (:sha256 manifest)
             object (io/file root "objects" digest)
             id (acquisition-id manifest)
             record (io/file root "acquisitions" (str id ".edn"))
             existed? (exists? record)]
         (verified-browser-evidence! root manifest)
         (safe-path! object)
         (safe-path! record)
         ;; Only recognizable private staging files may be removed after a terminated writer.
         (doseq [file (.listFiles (io/file root "tmp"))]
           (private! file false)
           (when-not (re-matches #"pending-.*\.tmp" (.getName file)) (fail! "Unexpected staging entry"))
           (Files/delete (path file)))
         (when-not (exists? object)
           (publish! root object
                     #(Files/copy (path source) (path %)
                                  (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
                     digest))
         (verified-object! object digest)
         (when on-progress (on-progress {:phase :artifact-ready :sha256 digest}))
         (when-not (exists? record)
           (publish! root record #(spit % (canonical manifest) :encoding "UTF-8") nil))
         (when-not (= manifest (:manifest (verified-record! record)))
           (fail! "Acquisition record conflict"))
         (cond-> {:sha256 digest :acquisition-id id}
           report-status (assoc :status (if existed? :skipped :imported))))))))

(defn inspect
  "Verify artifact bytes and return all acquisition records sharing this SHA-256."
  [root digest]
  (when-not (valid-hash? digest) (fail! "Invalid SHA-256"))
  (with-archive root false
    (fn [root]
      (let [object (io/file root "objects" digest)]
        (verified-object! object digest)
        {:sha256 digest
         :artifact-path (str object)
         :acquisitions (->> (.listFiles (io/file root "acquisitions"))
                            (map (fn [file]
                                   (let [record (verified-record! file)]
                                     (verified-browser-evidence! root (:manifest record))
                                     record)))
                            (filter #(= digest (get-in % [:manifest :sha256])))
                            (sort-by :acquisition-id)
                            vec)}))))

(defn -main [& args]
  (try
    (let [[command root value manifest-file] args]
      (prn (cond
             (and (= command "import") (= 4 (count args)))
             (register! root value (read-manifest manifest-file))
             (and (= command "inspect") (= 3 (count args)))
             (inspect root value)
             :else (fail! "Usage: import ARCHIVE SOURCE MANIFEST.edn | inspect ARCHIVE SHA256"))))
    (catch Exception error
      (binding [*out* *err*] (println "Archive command failed:" (.getMessage error)))
      (System/exit 1))))

(defn read-source-bytes
  "Read a regular, nonsymlink local evidence file. Does not change its permissions."
  [file]
  (let [p (safe-path! file)]
    (when-not (Files/isRegularFile p nofollow) (fail! "Evidence must be a regular file"))
    (Files/readAllBytes p)))

(defn retain-evidence!
  "Retain exact private evidence bytes, addressed by SHA-256, separately from source artifacts."
  [root bytes]
  (let [digest (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") bytes))]
    (with-archive root true
      (fn [root]
        (let [dir (io/file root "evidence") target (io/file dir digest)]
          (directory! dir)
          (when-not (exists? target)
            (publish! root target #(with-open [out (io/output-stream %)] (.write out bytes)) digest))
          (verified-object! target digest)
          {:sha256 digest :path (str target)})))))

(defn extraction-evidence
  "Return verified retained evidence hashes. Snapshot includes original legacy lineage,
   manifests and configs when present; no evidence bytes are copied into candidates."
  [root]
  (with-archive root false
    (fn [root]
      (let [dir (io/file root "evidence")]
        (if-not (exists? dir) []
                (do
                  (private! dir true)
                  (->> (.listFiles dir)
                       (map (fn [file]
                              (let [digest (.getName file)]
                                (when-not (valid-hash? digest) (fail! "Invalid evidence filename"))
                                (verified-object! file digest)
                                digest)))
                       sort vec)))))))

(defn- read-one-edn [file]
  (with-open [reader (java.io.PushbackReader. (io/reader file :encoding "UTF-8"))]
    (let [eof (Object.) value (edn/read {:eof eof} reader)]
      (when-not (identical? eof (edn/read {:eof eof} reader)) (fail! "Expected one EDN artifact"))
      value)))

(defn- complete-pr-str [value]
  (binding [*print-length* nil *print-level* nil] (pr-str value)))

(defn derive!
  "Atomically cache a private derived EDN artifact by caller's deterministic job hash.
   Pending receipts resume the same artifact after process interruption. Unreferenced
   derived objects and recognized staging files are reclaimed under the archive lock."
  [root job-id producer on-progress]
  (when-not (valid-hash? job-id) (fail! "Invalid derivation job hash"))
  (with-archive root true
    (fn [root]
      (let [dir (io/file root "derivations") artifacts (io/file root "derived-objects")
            record (io/file dir (str job-id ".edn"))
            pending (io/file dir (str job-id ".pending.edn"))]
        (directory! dir)
        (directory! artifacts)
        (safe-path! record)
        (safe-path! pending)
        (doseq [file (.listFiles (io/file root "tmp"))]
          (private! file false)
          (when-not (re-matches #"pending-.*\.tmp" (.getName file)) (fail! "Unexpected staging entry"))
          (Files/delete (path file)))
        (let [referenced (set (map (fn [file]
                                     (private! file false)
                                     (when-not (re-matches #"[0-9a-f]{64}(?:\.pending)?\.edn" (.getName file))
                                       (fail! "Unexpected derivation entry"))
                                     (let [receipt (read-one-edn file)]
                                       (when-not (valid-hash? (:artifact-sha256 receipt)) (fail! "Invalid derivation reference"))
                                       (:artifact-sha256 receipt))) (.listFiles dir)))]
          (doseq [file (.listFiles artifacts)]
            (when-not (valid-hash? (.getName file)) (fail! "Unexpected derived object"))
            (verified-object! file (.getName file))
            (when-not (contains? referenced (.getName file)) (Files/delete (path file)))))
        (let [existed? (exists? record)
              receipt (cond
                        existed? (do (private! record false) (read-one-edn record))
                        (exists? pending) (do (private! pending false) (read-one-edn pending))
                        :else
                        (let [value (producer)
                              bytes (.getBytes (complete-pr-str value) "UTF-8")
                              digest (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") bytes))
                              artifact (io/file artifacts digest)
                              receipt {:job-id job-id :artifact-sha256 digest}]
                          (if (exists? artifact) (verified-object! artifact digest)
                              (publish! root artifact #(with-open [out (io/output-stream %)] (.write out bytes)) digest))
                          (publish! root pending #(spit % (complete-pr-str receipt) :encoding "UTF-8") nil)
                          receipt))]
          (when-not (and (= job-id (:job-id receipt)) (valid-hash? (:artifact-sha256 receipt)))
            (fail! "Malformed derivation receipt"))
          (let [artifact (io/file artifacts (:artifact-sha256 receipt))]
            (verified-object! artifact (:artifact-sha256 receipt))
            (when-not (= job-id (:job-id (read-one-edn artifact))) (fail! "Derivation identity mismatch"))
            (when-not existed?
              (when on-progress (on-progress {:phase :extraction-artifact-ready :job-id job-id :artifact-sha256 (:artifact-sha256 receipt)}))
              (publish! root record #(spit % (complete-pr-str receipt) :encoding "UTF-8") nil))
            (when (exists? pending) (Files/delete (path pending)))
            (assoc receipt :artifact-path (str artifact) :run-status (if existed? :skipped :created))))))))
