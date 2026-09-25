(ns freediving.source-pages
  "Private, observation-bound PDF and retained HTML evidence."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [freediving.archive :as archive]
            [freediving.aida-html :as html]
            [freediving.html-evidence :as html-evidence]
            [freediving.observations :as observations])
  (:import [java.nio.file Files LinkOption Paths]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]
           [java.security MessageDigest]
           [java.util Base64 HexFormat]
           [java.util.concurrent Semaphore TimeUnit]
           [javax.imageio ImageIO]))

(def ^:private slots (Semaphore. 1))
(def ^:private nofollow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
(def ^:private max-bytes (* 12 1024 1024))
(defn- fail! [s] (throw (ex-info s {})))
(defn- sha [bytes] (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") bytes)))
(defn- canonical [v]
  (cond (map? v) (into (sorted-map) (map (fn [[k x]] [k (canonical x)]) v))
        (sequential? v) (mapv canonical v) :else v))
(defn- digest [v] (sha (.getBytes (binding [*print-length* nil *print-level* nil] (pr-str (canonical v))) "UTF-8")))
(defn- hash! [v] (when-not (and (string? v) (re-matches #"[a-f0-9]{64}" v)) (fail! "Invalid source identity")) v)
(defn- safe! [value]
  (let [p (.toAbsolutePath (Paths/get (str value) (make-array String 0)))]
    (when (some #(= ".." (str %)) (iterator-seq (.iterator p))) (fail! "Parent traversal denied"))
    (loop [q p]
      (when q (when (Files/isSymbolicLink q) (fail! "Symlink denied")) (recur (.getParent q)))) p))
(defn- private! [file dir?]
  (let [p (safe! file)]
    (when-not (if dir? (Files/isDirectory p nofollow) (Files/isRegularFile p nofollow)) (fail! "Missing private evidence"))
    (when (some #(re-find #"GROUP|OTHERS" (str %)) (Files/getPosixFilePermissions p nofollow)) (fail! "Evidence must be private")) p))
(defn- read-private [file]
  (let [p (private! file false)]
    (when (> (Files/size p) (* 100 1024 1024)) (fail! "Evidence size limit exceeded"))
    (Files/readAllBytes p)))
(defn- read-edn [bytes]
  (with-open [r (java.io.PushbackReader. (io/reader (java.io.ByteArrayInputStream. bytes)))]
    (let [end (Object.) v (edn/read {:eof end} r)]
      (when-not (identical? end (edn/read {:eof end} r)) (fail! "Invalid evidence EDN")) v)))
(defn- verified-artifact! [root row]
  (private! root true)
  (doseq [d ["objects" "derivations" "derived-objects"]] (private! (io/file root d) true))
  (let [job (hash! (:job-id row)) h (hash! (:artifact-sha256 row)) source (hash! (:source-sha256 row))
        receipt (read-edn (read-private (io/file root "derivations" (str job ".edn"))))
        bytes (read-private (io/file root "derived-objects" h)) a (read-edn bytes)]
    (when-not (and (= receipt {:job-id job :artifact-sha256 h}) (= h (sha bytes))
                   (= job (:job-id a)) (= job (digest (select-keys a (if (= 4 (:schema-version a)) html/identity-keys observations/identity-keys))))
                   (= source (:source-sha256 a))) (fail! "Extraction identity mismatch"))
    (let [file (io/file root "objects" source) b (read-private file)
          acquired (archive/inspect root source) evidence (set (archive/extraction-evidence root))]
      (when-not (and (seq (:acquisitions a))
                     (every? (set (:acquisitions acquired)) (:acquisitions a))
                     (every? evidence (:evidence-sha256 a))) (fail! "Extraction provenance missing"))
      (when-not (= source (sha b)) (fail! "Source SHA-256 mismatch"))
      (when-not (or (= 4 (:schema-version a)) (str/starts-with? (String. b 0 (min 8 (alength b)) "US-ASCII") "%PDF-")) (fail! "Source is not a PDF"))
      {:artifact (if (= 4 (:schema-version a)) (html/validate-artifact! root a) a) :artifact-sha256 h :source-bytes b})))
(defn- source-lines [artifact payload]
  (vec (or (:source-lines payload)
           (for [p (:pages artifact) :when (= (:page p) (get-in payload [:coordinates :page]))
                 l (:lines p) :when (= (:line l) (get-in payload [:coordinates :line]))]
             (assoc l :page (:page p))))))
(defn- bound-row! [{:keys [artifact artifact-sha256] :as verified} row]
  (let [ordinal (:ordinal row) payload (:payload row)
        positions (if (= 4 (:schema-version artifact)) [(select-keys (:coordinates payload) [:table :row])] (or (when (seq (:source-lines payload)) (mapv #(select-keys % [:page :line]) (:source-lines payload)))
                                                                                                                [(select-keys (:coordinates payload) [:page :line])]))]
    (when-not (and (= (:job-id row) (:job-id artifact)) (= artifact-sha256 (:artifact-sha256 row))
                   (= (:source-sha256 row) (:source-sha256 artifact))
                   (nat-int? ordinal) (< ordinal (count (:candidates artifact)))
                   (= payload (nth (:candidates artifact) ordinal))
                   (= (:candidate-id row) (digest [(:source-sha256 artifact) positions]))
                   (every? (fn [[k value]] (or (not (contains? row k)) (= (get row k) value)))
                           {:acquisitions (:acquisitions artifact) :evidence-sha256 (:evidence-sha256 artifact)
                            :source-lines (source-lines artifact payload)
                            :extraction-provenance (select-keys artifact [:config :actor :tool :processed-at :pdfinfo-version])}))
      (fail! "Observation is not bound to registered extraction"))
    verified))
(defn- bind-row! [root row] (bound-row! (verified-artifact! root row) row))
(defn inspect-html!
  "Return verified HTML row/context as data only. Never serve executable source HTML."
  [{:keys [archive-root] :as config} row]
  (when-not (= #{:archive-root :cache-root} (set (keys config))) (fail! "Invalid source configuration"))
  (let [{:keys [artifact]} (bind-row! archive-root row)
        _ (when-not (= 4 (:schema-version artifact)) (fail! "Source is not HTML"))
        context (html-evidence/bound-context! artifact (:payload row) (:ordinal row) (:source-sha256 row))
        identity (merge context (select-keys row [:job-id :ordinal :candidate-id :source-sha256 :artifact-sha256])
                        {:coordinate-system :html-table-rows :parser-version (:parser-version artifact) :schema-version (:schema-version artifact)})]
    (assoc identity :render-id (digest identity))))

(defn verify-corpus!
  "Verify all database rows against each registered extraction and retained source."
  [archive-root corpus]
  (when-not (seq corpus) (fail! "Real corpus must not be empty"))
  (doseq [[_ rows] (group-by :job-id corpus)]
    (let [verified (verified-artifact! archive-root (first rows))]
      (doseq [row rows] (bound-row! verified row))))
  true)

(def ^:private attributes (into-array FileAttribute [(PosixFilePermissions/asFileAttribute (PosixFilePermissions/fromString "rwx------"))]))
;; Static wrapper, no shell. Darwin does not support lowering address/data limits;
;; monitor RSS as well as enforcing CPU, file size and the outer wall timeout.
(def ^:private wrapper
  "import os,resource,sys,subprocess,time\nresource.setrlimit(resource.RLIMIT_CPU,(10,10))\nresource.setrlimit(resource.RLIMIT_FSIZE,(12582912,12582912))\nif sys.platform != 'darwin': resource.setrlimit(resource.RLIMIT_AS,(1073741824,1073741824))\nos.umask(0o077)\np=subprocess.Popen(sys.argv[1:])\ntry:\n while p.poll() is None:\n  q=subprocess.run(['/bin/ps','-o','rss=','-p',str(p.pid)],capture_output=True,text=True,timeout=1)\n  if q.stdout.strip() and int(q.stdout.strip()) > 524288: p.kill(); raise RuntimeError('memory limit')\n  time.sleep(0.05)\n sys.exit(p.returncode)\nfinally:\n if p.poll() is None: p.kill()\n p.wait()")
(defn- binary! [tool]
  (or (first (filter #(.canExecute (io/file %)) [(str "/opt/homebrew/bin/" tool) (str "/usr/bin/" tool)]))
      (fail! "Required PDF tool unavailable")))
(defn- tool-identity [tool]
  (let [file (.toFile (.toRealPath (.toPath (io/file (binary! tool))) (make-array LinkOption 0)))]
    {:path (str file) :sha256 (sha (Files/readAllBytes (.toPath file)))}))
(defn- font-profile! [temp]
  (let [font (or (first (filter #(.isFile (io/file %))
                                ["/System/Library/Fonts/Supplemental/Arial.ttf" "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"]))
                 (fail! "Controlled fallback font unavailable"))
        bytes (Files/readAllBytes (.toPath (io/file font)))
        dir (.resolve temp "fonts")]
    (Files/createDirectory dir attributes)
    (Files/write (.resolve dir "fallback.ttf") bytes (make-array java.nio.file.OpenOption 0))
    (spit (.toFile (.resolve temp "fonts.conf"))
          (str "<?xml version=\"1.0\"?><!DOCTYPE fontconfig SYSTEM \"urn:fontconfig:fonts.dtd\"><fontconfig><dir>" dir "</dir><cachedir>" (.resolve temp "font-cache") "</cachedir></fontconfig>"))
    {:profile :single-fallback-font-v1 :font-sha256 (sha bytes)}))
(defn- command! [dir tool & args]
  (let [out (.resolve dir "stdout") err (.resolve dir "stderr")
        binary (binary! tool)
        builder (ProcessBuilder. ^java.util.List (into ["/usr/bin/python3" "-c" wrapper binary] args))
        environment (.environment builder)
        _ (.clear environment)
        _ (.putAll environment {"PATH" "/usr/bin:/bin" "HOME" (str dir) "TMPDIR" (str dir) "LC_ALL" "C" "FONTCONFIG_FILE" (str (.resolve dir "fonts.conf"))})
        _ (.redirectOutput builder (.toFile out)) _ (.redirectError builder (.toFile err))
        process (.start builder)]
    (try
      (when-not (.waitFor process 15000 TimeUnit/MILLISECONDS) (fail! "PDF render timeout"))
      (when-not (zero? (.exitValue process)) (fail! "PDF tool failed or resource limit exceeded"))
      {:out (slurp (.toFile out)) :err (slurp (.toFile err))}
      (finally (when (.isAlive process)
                 (doseq [child (iterator-seq (.iterator (.descendants process)))] (.destroyForcibly child))
                 (.destroyForcibly process))))))
(defn- image-info [bytes]
  (when (> (alength bytes) max-bytes) (fail! "Rendered output exceeds limit"))
  (with-open [input (ImageIO/createImageInputStream (java.io.ByteArrayInputStream. bytes))]
    (let [readers (ImageIO/getImageReaders input)]
      (when-not (.hasNext readers) (fail! "Corrupt rendered image"))
      (let [reader (.next readers)]
        (try (.setInput reader input)
             (let [w (.getWidth reader 0) h (.getHeight reader 0)]
               (when-not (and (= "png" (str/lower-case (.getFormatName reader))) (pos? w) (pos? h) (<= w 1800) (<= h 1800)) (fail! "Invalid rendered dimensions"))
               {:width w :height h})
             (finally (.dispose reader)))))))
(defn verify-config!
  "Validate/create a dedicated private render cache, never inside the source archive."
  [{:keys [archive-root cache-root] :as config}]
  (when-not (and (= #{:archive-root :cache-root} (set (keys config)))
                 (string? archive-root) (string? cache-root)) (fail! "Invalid source page configuration"))
  (private! archive-root true)
  (let [cache (safe! cache-root) source (safe! archive-root)]
    (when (or (.startsWith cache source) (.startsWith source cache)) (fail! "Cache must be separate from source archive"))
    (when-not (Files/exists cache nofollow) (Files/createDirectory cache attributes))
    (private! cache true))
  config)

(defn render!
  "Render one page from the observation's registered source; no caller paths or tools.
   Fixed 1800px maximum edge, one concurrent render, 15s/tool, 512MiB RSS
   sampled every 50ms (1GiB address cap also on Linux), 10s CPU, 12MiB output. Cache root must be dedicated/private."
  [{:keys [archive-root cache-root] :as config} row page]
  (when-not (and (= #{:archive-root :cache-root} (set (keys config))) (integer? page) (pos? page)) (fail! "Invalid source page configuration"))
  (verify-config! config)
  (when-not (.tryAcquire slots) (fail! "Source page renderer busy"))
  (try
    (let [{:keys [artifact source-bytes]} (bind-row! archive-root row)
          page-count (:pdf-page-count artifact) cache (safe! cache-root)]
      (when-not (and (pos-int? page-count) (<= page page-count)) (fail! "Source page out of bounds"))
      (when (.startsWith cache (safe! archive-root)) (fail! "Cache must be separate from source archive"))
      (when-not (Files/exists cache nofollow) (Files/createDirectory cache attributes))
      (private! cache true)
      (when (> (reduce + 0 (for [f (file-seq (.toFile cache))]
                             (let [p (safe! f)] (if (Files/isDirectory p nofollow) 0 (Files/size p)))))
               (* 96 1024 1024)) (fail! "Cache size limit exceeded; clear dedicated cache while stopped"))
      (let [temp (Files/createTempDirectory cache "render-" attributes)]
        (try
          (let [source-file (str (.resolve temp "verified.pdf"))
                _ (Files/write (.resolve temp "verified.pdf") source-bytes (make-array java.nio.file.OpenOption 0))
                _ (Files/setPosixFilePermissions (.resolve temp "verified.pdf") (PosixFilePermissions/fromString "rw-------"))
                font-profile (font-profile! temp)
                version (str/trim (:err (command! temp "pdftoppm" "-v")))
                info (:out (command! temp "pdfinfo" source-file))
                actual (some-> (re-find #"(?m)^Pages:\s+(\d+)\s*$" info) second parse-long)
                _ (when-not (= actual page-count) (fail! "Source page count differs from extraction"))
                identity {:source-sha256 (:source-sha256 row) :page page :page-count actual
                          :tool {:name "pdftoppm" :version version :arguments ["-png" "-singlefile" "-scale-to" "1800"]}
                          :renderer-version 2 :font-profile font-profile
                          :executables {:pdftoppm (tool-identity "pdftoppm")
                                        :pdfinfo (assoc (tool-identity "pdfinfo") :version (str/trim (:err (command! temp "pdfinfo" "-v"))))}}
                id (digest identity)
                receipt (archive/derive! (str cache) id
                                         (fn []
                                           (let [prefix (str (.resolve temp "page"))]
                                             (command! temp "pdftoppm" "-f" (str page) "-l" (str page) "-png" "-singlefile" "-scale-to" "1800" source-file prefix)
                                             (let [bytes (read-private (str prefix ".png"))]
                                               {:job-id id :identity identity :dimensions (image-info bytes)
                                                :png-sha256 (sha bytes) :png (.encodeToString (Base64/getEncoder) bytes)}))) nil)
                value (read-edn (read-private (:artifact-path receipt)))
                bytes (.decode (Base64/getDecoder) ^String (:png value))
                dimensions (image-info bytes)]
            (when-not (and (= identity (:identity value)) (= id (:job-id value))
                           (= (:png-sha256 value) (sha bytes)) (= dimensions (:dimensions value))) (fail! "Render provenance mismatch"))
            {:bytes bytes :metadata (merge identity dimensions {:render-id id :png-sha256 (sha bytes)
                                                                :coordinate-system :extracted-text-lines
                                                                :source-lines (source-lines artifact (:payload row))})})
          (finally
            (doseq [f (reverse (file-seq (.toFile temp)))] (Files/deleteIfExists (.toPath f)))))))
    (finally (.release slots))))
