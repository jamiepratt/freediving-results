(ns freediving.retained-pdf
  "Verify archived PDF bytes and derive exact pdftotext layout pages for routing."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [freediving.archive :as archive])
  (:import [java.nio.file Files Paths LinkOption]
           [java.security MessageDigest]
           [java.util HexFormat]))

(def ^:private pdftotext-arguments ["-layout" "-enc" "UTF-8"])

(defn- sha256 [^bytes bytes]
  (.formatHex (HexFormat/of)
              (.digest (MessageDigest/getInstance "SHA-256") bytes)))

(defn- fail! [reason message]
  (throw (ex-info message {:reason reason})))

(defn- run-command! [& args]
  (let [{:keys [exit out err]} (try (apply shell/sh args)
                                    (catch java.io.IOException error
                                      (fail! :pdftotext-unavailable (.getMessage error))))]
    (when-not (zero? exit)
      (fail! :pdftotext-failed (str "pdftotext exited " exit ": " (str/trim err))))
    {:out out :err err}))

(defn verified-input
  "Return trusted adapter input derived from one verified archived PDF.

   `archive-root` is the private archive root used by freediving.archive.
   `document` must provide its expected :source-sha256 and :format :pdf.
   This performs local pdftotext extraction on each call and does no network or
   model work. The returned pages and tool receipt are immutable evidence for
   the current replay; callers must pass only this result to PDF adapters."
  [archive-root document]
  (let [hash (:source-sha256 document)]
    (when-not (and (= :pdf (:format document))
                   (string? hash) (re-matches #"[0-9a-f]{64}" hash))
      (fail! :invalid-pdf-document "Expected a PDF document with a SHA-256 source"))
    (let [object (Paths/get (str archive-root) (into-array String ["objects" hash]))]
      (when-not (Files/isRegularFile object (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
        (fail! :missing-source "Archived PDF source object is absent"))
      (let [source (try (archive/inspect archive-root hash)
                        (catch clojure.lang.ExceptionInfo error
                          (fail! :source-hash-mismatch (.getMessage error))))
            bytes (Files/readAllBytes object)]
        (when-not (= hash (sha256 bytes))
          (fail! :source-hash-mismatch "Archived PDF bytes differ from source SHA-256"))
        (when-not (and (<= 5 (alength bytes))
                       (= "%PDF-" (String. bytes 0 5 "US-ASCII")))
          (fail! :not-pdf "Archived source does not have a PDF header"))
        (when (empty? (:acquisitions source))
          (fail! :missing-acquisition "Archived PDF lacks acquisition evidence"))
        (let [version (str/trim (:err (run-command! "pdftotext" "-v")))
              raw (:out (apply run-command! (concat ["pdftotext"] pdftotext-arguments
                                                    [(:artifact-path source) "-"])))
              segments (vec (str/split raw #"\f" -1))
              pages (if (and (> (count segments) 1) (empty? (last segments)))
                      (pop segments) segments)]
          (when-not (= hash (sha256 (Files/readAllBytes object)))
            (fail! :source-hash-mismatch "Archived PDF changed during text extraction"))
          (when (or (not (str/starts-with? version "pdftotext version "))
                    (empty? pages) (every? str/blank? pages))
            (fail! :pdftotext-failed "pdftotext did not return verifiable pages"))
          {:source-sha256 hash
           :source-verification :archive-object-sha256
           :verified-byte-count (alength bytes)
           :pages pages
           :trusted-extraction? true
           :pdftotext {:name "pdftotext" :version version
                       :arguments pdftotext-arguments
                       :output-sha256 (sha256 (.getBytes raw "UTF-8"))
                       :page-sha256s (mapv #(sha256 (.getBytes ^String % "UTF-8")) pages)}})))))
