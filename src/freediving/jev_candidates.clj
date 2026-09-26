(ns freediving.jev-candidates
  "Source-bound candidate pairs through durable Jev scoring and normalization."
  (:require [clojure.edn :as edn]
            [clojure.data.json :as json]
            [freediving.cmas-2025-indoor-json :as indoor-json]
            [freediving.html-evidence :as html-evidence]
            [freediving.candidates :as candidates]
            [freediving.evaluation :as evaluation]
            [freediving.evaluation-protocol :as protocol]
            [freediving.spelling-normalization :as spelling])
  (:import [java.sql DriverManager]
           [java.security MessageDigest]
           [java.util HexFormat]))

(defn- fail! [message] (throw (ex-info message {})))
(defn- digest [value]
  (.formatHex (HexFormat/of)
              (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes (pr-str value) "UTF-8"))))
(defn- row-ref [row] (select-keys row [:job-id :ordinal]))
(defn- artifact [url job]
  (with-open [c (DriverManager/getConnection url)
              statement (.prepareStatement c "SELECT artifact_bytes FROM freediving.extractions WHERE job_id=?")]
    (.setString statement 1 job)
    (with-open [result (.executeQuery statement)]
      (when-not (.next result) (fail! "Unknown extraction artifact"))
      (String. ^bytes (.getBytes result 1) "UTF-8"))))
(defn- archived-row [url row]
  (let [archived (artifact url (:job-id row))
        bytes (.getBytes ^String archived "UTF-8")
        hash (.formatHex (HexFormat/of)
                         (.digest (MessageDigest/getInstance "SHA-256") bytes))]
    (when-not (= hash (:artifact-sha256 row)) (fail! "Extraction artifact hash mismatch"))
    [archived (try (edn/read-string archived) (catch Exception _ nil))]))

(defn- json-row-snippet [source index]
  (when (and (string? source) (nat-int? index))
    (when-let [marker (re-find #"\"data\"\s*:\s*\[" source)]
      (let [start (+ (.indexOf ^String source ^String marker) (count marker))]
        (loop [i start depth 0 row-start nil rows [] quoted? false escaped? false]
          (when (< i (count source))
            (let [c (.charAt ^String source i)]
              (cond
                escaped? (recur (inc i) depth row-start rows quoted? false)
                (and quoted? (= c \\)) (recur (inc i) depth row-start rows quoted? true)
                (= c \") (recur (inc i) depth row-start rows (not quoted?) false)
                quoted? (recur (inc i) depth row-start rows true false)
                (= c \{) (recur (inc i) (inc depth) (or row-start i) rows false false)
                (= c \}) (if (= depth 1)
                           (let [rows (conj rows (subs source row-start (inc i)))]
                             (if (< index (count rows)) (nth rows index)
                                 (recur (inc i) 0 nil rows false false)))
                           (recur (inc i) (dec depth) row-start rows false false))
                (and (= c \]) (zero? depth)) nil
                :else (recur (inc i) depth row-start rows false false)))))))))

(defn- source-row-evidence [row archived archive reference]
  (case (:source-format row)
    :pdf
    (let [line (first (:source-lines row))]
      (when-not (and (pos-int? (:page line)) (pos-int? (:line line)))
        (fail! "Missing PDF source line"))
      (protocol/source-evidence (assoc reference :page (:page line)
                                       :lines [(:line line) (:line line)]) archived))
    :html
    (let [context (html-evidence/bound-context! archive (:payload row) (:ordinal row)
                                                (:source-sha256 row))
          coordinate (:coordinates context)
          snippet (:raw-row context)]
      (when-not (and (= 4 (:schema-version row))
                     (= (:parser-version row) (:parser-version archive))
                     (every? pos-int? ((juxt :table :row) coordinate))
                     (string? snippet) (<= (count snippet) 4096)
                     (not (seq (:context-errors context))))
        (fail! "Unsupported or ambiguous HTML source row"))
      (assoc reference :source-format :html :locator coordinate :exact-lines [snippet]))
    :json
    (let [index (get-in row [:payload :coordinates :row-index-zero-based])
          source (:raw-json archive)
          parsed (when (string? source)
                   (try (json/read-str source) (catch Exception _ nil)))
          raw (get-in row [:payload :raw])
          snippet (json-row-snippet source index)
          page-url (:source-page-url archive)]
      (when-not (and (= 5 (:schema-version row))
                     (= indoor-json/parser-version (:parser-version row) (:parser-version archive))
                     (= (:source-sha256 row) (:source-sha256 archive)
                        (when source (.formatHex (HexFormat/of)
                                                 (.digest (MessageDigest/getInstance "SHA-256")
                                                          (.getBytes ^String source "UTF-8")))))
                     (nat-int? index) (= (:payload row) (nth (:candidates archive) (:ordinal row) nil))
                     (= raw (get-in parsed ["data" index]))
                     (= page-url (:source-page-url row) (get-in row [:payload :source-page-url]))
                     (string? page-url) (re-matches #"https?://[^\s]+" page-url)
                     (string? snippet) (<= (count snippet) 4096)
                     (= raw (try (json/read-str snippet) (catch Exception _ nil))))
        (fail! "Unsupported or ambiguous JSON source row"))
      (assoc reference :source-format :json
             :locator {:row-index-zero-based index :source-page-url page-url}
             :exact-lines [snippet]))
    (fail! "Unsupported source format")))

(defn- source-record [url row]
  (let [name (get-in row [:payload :parsed :source-name])]
    (when-not (and (= "result-row" (:kind row))
                   (= :parsed (get-in row [:payload :parse-status]))
                   (string? name) (seq name))
      (fail! "Only parsed, source-backed result rows can be scored"))
    (let [reference {:evidence-id (digest [(:job-id row) (:ordinal row) (:candidate-id row)
                                           (:parser-version row) (:artifact-sha256 row)])
                     :source-sha256 (:source-sha256 row)
                     :artifact-sha256 (:artifact-sha256 row)
                     :observation-id (:candidate-id row)
                     :source-family-id (:source-sha256 row)}
          [archived archive] (archived-row url row)
          source (source-row-evidence row archived archive reference)
          parsed (get-in row [:payload :parsed])
          fields (into (sorted-map)
                       (for [field protocol/field-keys
                             :let [value (get parsed (if (= field :name) :source-name field))]]
                         [field {:value (when (some? value) (str value))
                                 :evidence-ids (if (some? value) [(:evidence-id reference)] [])}]))
          uncertainties (mapv (fn [value]
                                {:value (str value) :evidence-ids [(:evidence-id reference)]})
                              (concat (get-in row [:payload :uncertainties] [])
                                      (get-in row [:payload :flags] [])))]
      {:record-id (str "local-observation:" (:job-id row) ":" (:ordinal row))
       :fields fields :sources [source] :uncertainties uncertainties
       :publisher-identity nil})))

(defn candidate-case
  "Build one exact candidate pair. A source or parser change produces a new ID."
  [url corpus target-ref candidate-ref]
  (let [packet (candidates/packet corpus target-ref {})
        target (:target packet)
        candidate (some (fn [group]
                          (some #(when (= candidate-ref (row-ref %)) %) (:observations group)))
                        (:candidates packet))]
    (when-not candidate (fail! "Pair is not a retrieved source-bound candidate"))
    (let [left (source-record url target)
          right (source-record url candidate)
          input {:schema-version "freediving-source/1" :left left :right right}
          _ (protocol/validate-input! input)
          pair-id (digest [(:record-id left) (:record-id right)
                           (mapv #(select-keys % [:job-id :ordinal :candidate-id :source-sha256
                                                  :artifact-sha256 :parser-version :schema-version
                                                  :source-format :source-page-url :payload :source-lines])
                                 [target candidate])
                           left right])]
      {:case-id pair-id :split :held-out :input input
       :source-family-ids (vec (distinct [(:source-sha256 target) (:source-sha256 candidate)]))
       :person-group-ids [(str "unresolved-pair:" pair-id)]
       :evidence (mapv #(dissoc % :exact-lines :source-family-id)
                       (concat (:sources left) (:sources right)))
       :label nil})))

(defn score-and-normalize!
  "Score one retrieved PDF pair, then apply any clear spelling through the normal
  reversible audit. Runtime Jev credentials remain outside the durable run."
  [root reviewer-url target-ref candidate-ref config runtime]
  (when-not (and (= :jev (:provider config))
                 (= :freediving-compact-v3 (:identity-protocol config))
                 (= :credential-checks-passed-v1 (:jev-dispatch-gate runtime)))
    (fail! "Versioned Jev protocol and completed credential checks required"))
  (let [corpus (candidates/load-corpus reviewer-url {})
        case (candidate-case reviewer-url corpus target-ref candidate-ref)
        dataset {:schema-version 1 :dataset-id (:case-id case)
                 :rubric-version "source-bound-jev-candidate-v1"
                 :grouping {:attested-by "source-bound-candidate-builder"
                            :method "One exact retrieved pair per run"
                            :limitations "Opaque pair grouping is not an identity or nationality assertion"}
                 :cases [case]}
        receipt (evaluation/run! root dataset [config] runtime)]
    {:run-id (:run-id receipt) :case-id (:case-id case)
     :normalizations (spelling/apply-run! root (:run-id receipt) (:id config) reviewer-url)}))
