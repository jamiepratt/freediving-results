(ns freediving.parser-adapters
  "Source-bound bridges from retained extractor inputs to parser-routing claims.
   The JSON bridge is limited to one receipt-bound Microplus result route.
   Scan packets without independent verification remain explicit gaps."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [freediving.aida-html :as aida-html]
            [freediving.retained-json :as retained-json]
            [freediving.retained-workbook :as retained-workbook]
            [freediving.vdst-neckar-2025 :as neckar]))

(defn- sha256 [^String source]
  (.formatHex (java.util.HexFormat/of)
              (.digest (java.security.MessageDigest/getInstance "SHA-256")
                       (.getBytes source "UTF-8"))))

(defn- citation [hash format coordinates]
  (str "sha256:" hash "#"
       (case format
         :pdf (str "page=" (:page coordinates) "&line=" (:line coordinates)
                   "&column=" (:column-start coordinates) "-" (:column-end coordinates))
         :html (str "table=" (:table coordinates) "&row=" (:row coordinates)))))

(defn- position-id [format coordinates]
  (case format
    :pdf (str "page=" (:page coordinates) "&line=" (:line coordinates)
              "&column=" (:column-start coordinates) "-" (:column-end coordinates))
    :html (str "table=" (:table coordinates) "&row=" (:row coordinates))))

(defn- cited-ids [document candidates]
  (let [hash (:source-sha256 document)
        format (:format document)
        inventory (set (map (juxt :id :citation :coordinates) (:positions document)))]
    (into #{}
          (keep (fn [candidate]
                  (let [coordinates (:coordinates candidate)
                        id (position-id format coordinates)]
                    (when (contains? inventory [id (citation hash format coordinates) coordinates])
                      id))))
          candidates)))

(defn- exact-pdf-line? [pages candidate]
  (let [{:keys [page line]} (:coordinates candidate)
        raw (get-in candidate [:raw :line])
        lines (when (and (integer? page) (<= 1 page (count pages)))
                (str/split (nth pages (dec page)) #"\n" -1))]
    (and (integer? line) (<= 1 line (count lines))
         (= raw (nth lines (dec line))))))

(defn- claim [document parser-id parser-version match-reason ids]
  {:parser-id parser-id :parser-version parser-version :match-reason match-reason
   :source-restriction {:sha256s #{(:source-sha256 document)}
                        :formats #{(:format document)}}
   :supported-positions ids :claimed-positions ids})

(defn- scan-position [position]
  {:id (:id position) :citation (:citation position)
   :coordinates {:page (get-in position [:coordinates :page])
                 :region_px (get-in position [:coordinates :region_px])
                 :section (get-in position [:coordinates :section])
                 :evidence-role (keyword (get-in position [:coordinates :evidence_role]))}
   :ambiguous? (:ambiguous position)})

(defn- scan-candidate [candidate]
  {:id (:id candidate) :citation (:citation candidate)
   :coordinates (:coordinates (scan-position candidate))
   :source-citations (:source_citations candidate)
   :verification-status (:verification_status candidate)
   :parsed {:source-reading (get-in candidate [:parsed :source_reading])}})

(defn- scan-result [manifest]
  (when-not (and (string? manifest) (.isFile (io/file manifest)))
    (throw (ex-info "Missing retained scan manifest" {})))
  (let [process (.start (ProcessBuilder. (into-array String
                                                     ["python3" "scripts/scan_verification.py"
                                                      "replay-source" manifest])))
        output (slurp (.getInputStream process))
        error (slurp (.getErrorStream process))
        exit (.waitFor process)]
    (when-not (zero? exit)
      (throw (ex-info "Scan verification failed" {:error error})))
    (json/read-str output :key-fn keyword)))

(defn- scan-document [result]
  {:source-sha256 (get-in result [:document :source_sha256])
   :format :image
   :positions (mapv scan-position (get-in result [:document :positions]))})

(defn retained-scan-entry
  "Build a registered-batch entry from a pinned, independently reviewed scan.
   This performs local verification and returns no authority beyond routing."
  [manifest]
  {:document (scan-document (scan-result manifest))
   :retained-input {:scan-manifest manifest}})

(defn- verified-scan [document retained-input]
  (try
    (let [result (scan-result (:scan-manifest retained-input))
          generated (scan-document result)
          _ (when-not (= document generated)
              (throw (ex-info "Scan position inventory differs from verification" {})))
          candidates (mapv scan-candidate (:candidates result))
          ids (set (map :id candidates))]
      {:claims (cond-> [] (seq ids)
                       (conj (claim document "retained-scan-review" (:parser_version result)
                                    "Retained independent blind passes and source review" ids)))
       :unsupported-reasons [] :unsupported-formats []
       :verified-source-path (:source_path result)
       :source-verification (:verification result)
       :extraction {:status :verified-partial :candidates candidates}})
    (catch Exception _
      {:claims [] :unsupported-reasons [:missing-or-invalid-independent-scan-verification]
       :unsupported-formats [:image] :source-verification :failed :extraction nil})))

(defn claims-for-document
  "Replay supported retained PDF/HTML input and produce route-document claims.
   PDF input is trusted upstream extraction evidence
   {:source-sha256 archived-PDF-hash :pages verified-pdftotext-pages
    :trusted-extraction? true}. This adapter cannot independently verify PDF
   bytes; the caller must verify source bytes and pdftotext provenance first.
   The source-specific parser checks the known hash and four-page signature.
   HTML input is {:html retained-UTF-8-source}; its bytes are SHA-256 checked.
   Inventory entries must have exact :id, :citation and :coordinates from the
   extractor. JSON requires original response bytes and its acquisition receipt.
   Image scan packets without independent verification have no checked bridge."
  [document retained-input]
  (let [{:keys [source-sha256 format]} document]
    (case format
      :json
      (retained-json/claims-for-document document retained-input)

      :workbook
      (retained-workbook/claims-for-document document retained-input)

      :html
      (if (and (string? (:html retained-input))
               (= source-sha256 (sha256 (:html retained-input))))
        (let [extraction (aida-html/parse-html (:html retained-input))
              grouped (group-by (juxt (comp :table :coordinates) :source-family)
                                (:candidates extraction))
              claims (->> grouped
                          (keep (fn [[[table family] candidates]]
                                  (let [ids (cited-ids document candidates)]
                                    (when (seq ids)
                                      (claim document "aida-html" aida-html/parser-version
                                             (str "AIDA " (name family) " table " table
                                                  " header and retained HTML row") ids)))))
                          (sort-by :match-reason) vec)]
          {:claims claims :unsupported-reasons [] :unsupported-formats []
           :source-verification :raw-bytes-sha256 :extraction extraction})
        {:claims [] :unsupported-reasons [:source-hash-mismatch]
         :unsupported-formats [] :source-verification :failed :extraction nil})

      :pdf
      (if (and (= source-sha256 neckar/source-sha256)
               (= source-sha256 (:source-sha256 retained-input))
               (true? (:trusted-extraction? retained-input))
               (vector? (:pages retained-input)))
        (let [extraction (neckar/parse-pages source-sha256 (:pages retained-input))
              ids (cited-ids document (filter #(exact-pdf-line? (:pages retained-input) %)
                                              (:candidates extraction)))]
          {:claims (cond-> [] (and (not= :unsupported-needs-parser (:status extraction))
                                   (seq ids))
                           (conj (claim document "vdst-neckar-2025" neckar/parser-version
                                        "Trusted upstream VDST Neckar PDF hash and four-page signature" ids)))
           :unsupported-reasons (if (= :unsupported-needs-parser (:status extraction))
                                  [:pdf-signature-mismatch] [])
           :unsupported-formats [] :source-verification :upstream-verified-required
           :extraction extraction})
        {:claims [] :unsupported-reasons [:unsupported-or-unverified-pdf-source]
         :unsupported-formats [] :source-verification :unverified :extraction nil})

      :image
      (if (:scan-manifest retained-input)
        (verified-scan document retained-input)
        {:claims [] :unsupported-reasons [:missing-independent-scan-verification]
         :unsupported-formats [:image] :source-verification :unverified :extraction nil})

      {:claims [] :unsupported-reasons [(keyword (str "no-checked-" (name format) "-replay-bridge"))]
       :unsupported-formats [format] :source-verification :unverified :extraction nil})))
