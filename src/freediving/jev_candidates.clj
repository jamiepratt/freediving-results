(ns freediving.jev-candidates
  "One source-bound PDF candidate pair through durable Jev scoring and normalization."
  (:require [freediving.candidates :as candidates]
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
(defn- source-record [url row]
  (let [line (first (:source-lines row))
        name (get-in row [:payload :parsed :source-name])]
    (when-not (and (= :pdf (:source-format row)) (= "result-row" (:kind row))
                   (= :parsed (get-in row [:payload :parse-status]))
                   (string? name) (seq name)
                   (pos-int? (:page line)) (pos-int? (:line line)))
      (fail! "Only parsed, source-backed PDF result rows can be scored"))
    (let [reference {:evidence-id (digest [(:job-id row) (:ordinal row) (:candidate-id row)])
                     :source-sha256 (:source-sha256 row)
                     :artifact-sha256 (:artifact-sha256 row)
                     :observation-id (:candidate-id row)
                     :page (:page line) :lines [(:line line) (:line line)]
                     :source-family-id (:source-sha256 row)}
          source (protocol/source-evidence reference (artifact url (:job-id row)))
          parsed (get-in row [:payload :parsed])
          fields (into (sorted-map)
                       (for [field protocol/field-keys
                             :let [value (get parsed (if (= field :name) :source-name field))]]
                         [field {:value (when (some? value) (str value))
                                 :evidence-ids (if (some? value) [(:evidence-id reference)] [])}]))
          uncertainties (mapv (fn [value]
                                {:value (str value) :evidence-ids [(:evidence-id reference)]})
                              (get-in row [:payload :uncertainties] []))]
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
                           (mapv #(select-keys % [:source-sha256 :artifact-sha256 :evidence-id])
                                 (concat (:sources left) (:sources right)))
                           (:fields left) (:fields right)])]
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
