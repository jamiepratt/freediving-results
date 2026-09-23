(ns freediving.packets
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.java.io :as io]
            [freediving.candidates :as candidates])
  (:import [java.nio.file Files Paths LinkOption StandardOpenOption]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]))

(def rubric
  [{:category :extraction-repair
    :requires "Exact source page/line demonstrating a glyph, spacing, or column extraction error; preserve original text."}
   {:category :name-normalization
    :requires "Explicit reversible formatting rule, original value and normalized value; no identity or nationality inference."}
   {:category :identity-matching
    :requires "Meaningful local observation anchor, exact cross-source references and supporting context beyond matching names. Mirrors and repeated rows are not independent corroboration."}
   {:category :substantive-correction
    :requires "Source-backed reason for changing a substantive value, original and proposed values, and unresolved contradictions."}
   {:category :abstain-no-match
    :requires "Record missing, ambiguous or conflicting evidence; no-match means no supported candidate in this bounded corpus, not proof of distinct identity."}])

(defn- escape-html [x]
  (str/escape (str x) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&#39;"}))

(defn- pretty [x]
  (binding [*print-length* nil *print-level* nil]
    (with-out-str (pprint/pprint x))))
(defn- source-name [row]
  (let [value (get-in row [:payload :parsed :source-name])]
    (if (and (string? value) (not (str/blank? value))) value "[missing parsed source name]")))
(defn- render-case [i packet]
  (str "<article><h2>" (inc i) ". " (escape-html (source-name (:target packet))) "</h2>"
       "<p>Outcome: <strong>" (escape-html (:outcome packet)) "</strong>. Unreviewed candidate signals only.</p>"
       "<p>Grouped target listings: " (count (:target-observations packet))
       ". Candidate groups: " (count (:candidates packet))
       ". Distinct source documents: " (escape-html (:distinct-source-document-count packet))
       ". Independent corroboration: not established.</p>"
       "<p>Target source hash: " (escape-html (get-in packet [:target :source-sha256])) "</p>"
       "<p>Packet ID: " (escape-html (:packet-id packet)) "</p>"
       "<h3>Candidate groups</h3>"
       (if (seq (:candidates packet))
         (apply str (for [candidate (:candidates packet)]
                      (str "<section><h4>" (escape-html (str/join " / " (distinct (map source-name (:observations candidate))))) "</h4>"
                           "<p>Signals: " (escape-html (pr-str (:signals candidate)))
                           ". Grouped listings: " (count (:observations candidate)) "</p>"
                           "<p>Local anchor: " (escape-html (get-in candidate [:local-identity-anchor :identity-id])) "</p></section>")))
         "<p>No supported candidate retrieved. Missing parsed fields require abstention.</p>")
       "<h3>Known uncertainties</h3><pre>" (escape-html (pretty (:uncertainties packet))) "</pre>"
       "<details><summary>Exact source evidence, original values, acquisitions, versions and configuration</summary><pre>"
       (escape-html (pretty packet)) "</pre></details></article>"))

(defn render-html
  "Offline candidate packet view. Every evidence value is displayed as escaped text."
  [result]
  (str "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>Private candidate review</title>"
       "<style>body{font:17px system-ui;max-width:1000px;margin:2rem auto;padding:1rem}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#f2f2f2;padding:1rem}article{border-top:2px solid #888;margin-top:2rem}h1,h2{line-height:1.2}</style>"
       "<h1>Private review packets</h1><p><strong>Candidate only. Awaiting owner review.</strong> Retrieval does not approve, merge, or establish an identity. No calibrated confidence is claimed.</p>"
       "<p>Owner choices: approve, reject, no-match, needs-more-evidence. These are review prompts, not recorded decisions. Inspect exact source references and uncertainties before proposing a review.</p>"
       "<h2>Review rubric</h2><p>Implementation guidance awaiting owner review, not owner labels. CMAS1/AIN and matching names do not establish nationality or identity. Distinct documents are not automatically independent truth.</p>"
       (apply str (for [{:keys [category requires]} rubric]
                    (str "<p><strong>" (escape-html (name category)) "</strong>: " (escape-html requires) "</p>")))
       "<h2>Retrieval metadata</h2><pre>" (escape-html (pretty (dissoc result :packets))) "</pre>"
       (when (:has-more? result) "<p><strong>More packets remain. This export is a bounded page, not the complete candidate set.</strong></p>")
       (apply str (map-indexed render-case (:packets result)))
       "</html>"))

(def ^:private nofollow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
(defn- fail! [message] (throw (ex-info message {})))
(defn- safe-path! [value]
  (let [p (.toAbsolutePath (Paths/get (str value) (make-array String 0)))]
    (when (some #(= ".." (str %)) (iterator-seq (.iterator p)))
      (fail! "Parent traversal is not allowed"))
    (loop [current p]
      (when current
        (when (Files/isSymbolicLink current) (fail! "Symlinks are not allowed"))
        (recur (.getParent current))))
    (.normalize p)))
(defn- attrs [mode]
  (into-array FileAttribute [(PosixFilePermissions/asFileAttribute (PosixFilePermissions/fromString mode))]))
(defn- private-entry! [p directory?]
  (safe-path! p)
  (when-not (if directory? (Files/isDirectory p nofollow) (Files/isRegularFile p nofollow))
    (fail! "Invalid output file type"))
  (when-not (= (PosixFilePermissions/fromString (if directory? "rwx------" "rw-------"))
               (Files/getPosixFilePermissions p nofollow))
    (fail! "Output must be private: directory 0700, files 0600")))

(defn export!
  "Write private EDN and offline HTML, refusing conflicting reruns and symlinks. Parent must exist."
  [result output-directory]
  (let [directory (safe-path! output-directory)
        outputs [[(.resolve directory "packets.edn") (binding [*print-length* nil *print-level* nil] (str (pr-str result) "\n"))]
                 [(.resolve directory "packets.html") (render-html result)]]]
    (when-not (Files/exists directory nofollow)
      (Files/createDirectory directory (attrs "rwx------")))
    (private-entry! directory true)
    ;; Validate both existing outputs before writing either, including interrupted reruns.
    (doseq [[p content] outputs]
      (safe-path! p)
      (when (Files/exists p nofollow)
        (private-entry! p false)
        (when-not (= content (slurp (.toFile p))) (fail! "Conflicting packet export; choose a fresh directory"))))
    (doseq [[p content] outputs]
      (when-not (Files/exists p nofollow)
        (with-open [channel (Files/newByteChannel p
                                                  (java.util.HashSet. [StandardOpenOption/CREATE_NEW StandardOpenOption/WRITE LinkOption/NOFOLLOW_LINKS])
                                                  (attrs "rw-------"))]
          (let [buffer (java.nio.ByteBuffer/wrap (.getBytes content "UTF-8"))]
            (while (.hasRemaining buffer) (.write channel buffer)))))
      (private-entry! p false))
    {:edn (str (ffirst outputs)) :html (str (first (second outputs)))}))

(defn read-request [path]
  (with-open [reader (java.io.PushbackReader. (io/reader path))]
    (let [eof (Object.) request (edn/read {:eof eof} reader)]
      (when (or (identical? eof request) (not (identical? eof (edn/read {:eof eof} reader))))
        (fail! "Expected exactly one EDN request"))
      (when-not (and (map? request)
                     (every? #{:offset :limit :max-observations :comparison-version} (keys request)))
        (fail! "Unexpected packet request fields"))
      ;; Validate all paging/configuration bounds before opening a database connection.
      (candidates/packets [] request)
      request)))

(defn -main [& args]
  (try
    (when-not (and (= 3 (count args)) (= "export" (first args)))
      (fail! "Usage: export REQUEST.edn OUTPUT-DIRECTORY"))
    (let [request (read-request (second args))
          url (System/getenv "FREEDIVING_DATABASE_URL")]
      (when (str/blank? url) (fail! "FREEDIVING_DATABASE_URL is required"))
      (println (pr-str (export! (candidates/packets (candidates/load-corpus url request) request) (nth args 2)))))
    (catch Exception e
      (binding [*out* *err*] (println "Packet export failed:" (.getMessage e)))
      (System/exit 1))))
