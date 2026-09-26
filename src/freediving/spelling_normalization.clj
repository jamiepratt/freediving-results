(ns freediving.spelling-normalization
  "Conservative, reversible spelling selection from a source-bound Jev pair."
  (:require [clojure.string :as str]
            [freediving.candidates :as candidates]
            [freediving.evaluation :as evaluation]
            [freediving.reviews :as reviews])
  (:import [java.security MessageDigest]
           [java.util HexFormat]))

(def ^:private threshold 0.95)
(def ^:private margin 0.20)

(defn- clear-choice? [answer choice]
  (let [probabilities (:probabilities answer)
        selected (get probabilities choice)
        others (vals (dissoc probabilities choice))]
    (and (= choice (:outcome answer))
         (number? selected) (<= threshold selected 1)
         (number? (:confidence answer)) (<= threshold (:confidence answer) 1)
         (seq others) (every? number? others)
         (>= (- selected (apply max others)) margin))))

(defn recommendation
  "Return only a printed spelling with a clear identity and spelling result.
  A result is advice until bound to current source observations and recorded in
  the normal review audit. Provider probabilities are thresholds, not accuracy."
  [input result]
  (let [choice (get-in result [:spelling :outcome])
        chosen (get-in input [choice :fields :name :value])
        other (get-in input [(if (= choice :left) :right :left) :fields :name :value])]
    (when (and (clear-choice? result :match)
               (#{:left :right} choice)
               (clear-choice? (:spelling result) choice)
               (string? chosen) (not (str/blank? chosen))
               (string? other) (not (str/blank? other))
               (not= chosen other))
      {:spelling chosen :source-side choice :target-side (if (= choice :left) :right :left)
       :identity-probability (get-in result [:probabilities :match])
       :spelling-probability (get-in result [:spelling :probabilities choice])
       :gate-version :jev-spelling-normalization-v1})))

(defn- record-target [record]
  (when-let [[_ job ordinal] (re-matches #"local-observation:([^:]+):([0-9]+)" (:record-id record))]
    {:job-id job :ordinal (parse-long ordinal)}))

(defn- bound-row [rows record]
  (let [target (record-target record)
        row (some #(when (= target (select-keys % [:job-id :ordinal])) %) rows)
        name (get-in record [:fields :name])
        cited (filter #(some #{(:evidence-id %)} (:evidence-ids name)) (:sources record))]
    (when (and row (= "result-row" (:kind row))
               (= (:value name) (get-in row [:payload :parsed :source-name]))
               (or (nil? (:source-version record))
                   (= (:source-version record)
                      (select-keys row [:parser-version :schema-version :source-format])))
               (some #(and (= (:source-sha256 %) (:source-sha256 row))
                           (= (:artifact-sha256 %) (:artifact-sha256 row))
                           (= (:observation-id %) (:candidate-id row))) cited))
      row)))

(defn- evidence [row]
  (case (:source-format row)
    :html [(select-keys (get-in row [:payload :coordinates]) [:table :row])]
    :pdf (when-let [line (first (:source-lines row))]
           [(select-keys line [:page :line])])
    nil))

(defn- registered-reference [row]
  (when row
    (merge (select-keys row [:job-id :ordinal :candidate-id :source-sha256 :artifact-sha256])
           (case (:source-format row)
             :pdf (select-keys (first (:source-lines row)) [:page :line])
             :html (select-keys (get-in row [:payload :coordinates]) [:table :row])
             :json (merge (select-keys row [:parser-version :source-page-url])
                          (select-keys (get-in row [:payload :coordinates]) [:row-index-zero-based]))
             {}))))

(defn- id [parts]
  (.formatHex (HexFormat/of)
              (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes (pr-str parts) "UTF-8"))))

(defn- provider-receipt? [report result]
  (let [batch (get (:batches report) (:batch-index result))
        receipt (:attempt batch)
        response (:result receipt)]
    (and (= :dispatched (:dispatch-status batch))
         (= :complete (:outcome response))
         (integer? (:http-status response))
         (<= 200 (:http-status response) 299)
         (= (:request-hash result) (:request-hash receipt))
         (= (:trace-hash result) (:trace-hash batch))
         (= (:model-version result) (:model-version response)))))

(defn- score-view-from-run [run run-id provider-id target rows current-case-ids]
  (let [report (get-in run [:report :providers provider-id])
        results (into {} (map (juxt :case-id identity) (:results report)))
        requests (get-in run [:input :requests])
        index (first (keep-indexed (fn [i config] (when (= provider-id (:id config)) i))
                                   (get-in run [:input :configurations])))]
    (when-not (and index report
                   (every? #(and (= :jev (:provider %)) (= "shadow-adapters/14" (:adapter-version %))
                                 (= :freediving-compact-v3 (get-in % [:config :identity-protocol])))
                           (get requests index)))
      (throw (ex-info "Stored v3 Jev score required" {})))
    (mapv (fn [case]
            (let [input (:input case)
                  result (results (:case-id case))
                  batch (get (:batches report) (:batch-index result))
                  left (bound-row rows (:left input))
                  right (bound-row rows (:right input))
                  current? (and left right
                                (or (nil? current-case-ids)
                                    (contains? current-case-ids (:case-id case))))
                  complete? (provider-receipt? report result)
                  status (cond (not current?) :stale
                               (not-every? #(#{:pdf :html :json} (:source-format %)) [left right]) :unsupported
                               (nil? result) :missing
                               complete? :complete
                               :else :failed)]
              {:case-id (:case-id case)
               :run-id run-id
               :provider-id provider-id
               :score-status status
               :target-reference (registered-reference left)
               :candidate-reference (registered-reference right)
               :pair-records (select-keys input [:left :right])
               :source-dependence
               (let [a (:sources (:left input)) b (:sources (:right input))]
                 {:left-source-families (vec (distinct (map :source-family-id a)))
                  :right-source-families (vec (distinct (map :source-family-id b)))
                  :shared-family? (boolean (some (set (map :source-family-id a)) (map :source-family-id b)))
                  :shared-source-sha256? (boolean (some (set (map :source-sha256 a)) (map :source-sha256 b)))
                  :shared-artifact-sha256? (boolean (some (set (map :artifact-sha256 a)) (map :artifact-sha256 b)))
                  :left-acquisitions (mapv #(select-keys (:manifest %) [:publisher :relationship :mirror-of
                                                                        :discovery-url :final-url :provenance])
                                           (:acquisitions left))
                  :right-acquisitions (mapv #(select-keys (:manifest %) [:publisher :relationship :mirror-of
                                                                         :discovery-url :final-url :provenance])
                                            (:acquisitions right))})
               :model-version (:model-version result)
               :protocol-version :freediving-compact-v3
               :adapter-version "shadow-adapters/14"
               :provider-configuration (get-in run [:input :requests index 0 :config])
               :pair-references (mapv #(select-keys % [:record-id :sources])
                                      [(:left input) (:right input)])
               :extraction-versions (mapv :source-version
                                          [(:left input) (:right input)])
               :request-hash (:request-hash result)
               :result-hash (:result-hash batch)
               :completed-at (get-in batch [:attempt :completed-at])
               :source-references (mapv :sources [(:left input) (:right input)])
               :left-name (get-in input [:left :fields :name :value])
               :right-name (get-in input [:right :fields :name :value])
               :identity (when complete? (select-keys result [:outcome :confidence :probabilities]))
               :spelling (when complete? (:spelling result))
               :automatic-recommendation (when (and current? complete?)
                                           (recommendation input result))}))
          (filter (fn [case]
                    (some #(= target (record-target %))
                          (map (get case :input) [:left :right])))
                  (filter #(= :held-out (:split %)) (get-in run [:input :dataset :cases]))))))

(defn score-view
  "Return private review metadata for source-bound or stale Jev scores involving one observation."
  ([root run-id provider-id target rows] (score-view root run-id provider-id target rows nil))
  ([root run-id provider-id target rows current-case-ids]
   (score-view-from-run (evaluation/inspect-run root run-id) run-id provider-id target rows current-case-ids)))

(defn score-views-for-targets
  "Inspect each stored run once for a private queue of target references and current pairs.
  When run-id is supplied, show only scores from that run, as the detail view does."
  ([root provider-id rows targets] (score-views-for-targets root provider-id rows targets nil))
  ([root provider-id rows targets run-id]
   (let [runs (mapv (fn [id] [id (evaluation/inspect-run root id)])
                    (if run-id [run-id] (evaluation/list-runs root)))
         runs (if run-id runs
                  (filterv (fn [[_ run]]
                             (let [index (first (keep-indexed (fn [i config]
                                                                (when (= provider-id (:id config)) i))
                                                              (get-in run [:input :configurations])))
                                   request (get-in run [:input :requests index 0])]
                               (and (= :jev (:provider request))
                                    (= :freediving-compact-v3 (get-in request [:config :identity-protocol])))))
                           runs))]
     (into {}
           (for [[target current-cases] targets]
             (let [current-ids (when (some? current-cases)
                                 (set (map :case-id (remove :score-status current-cases))))
                   historical (mapcat (fn [[id run]]
                                        (score-view-from-run run id provider-id target rows current-ids)) runs)
                   indexed (set (map :case-id historical))
                   missing (when-not run-id
                             (for [case current-cases :when (not (contains? indexed (:case-id case)))]
                               (merge {:case-id (:case-id case) :run-id nil
                                       :score-status (or (:score-status case) :missing)
                                       :pair-references (mapv #(select-keys % [:record-id :sources])
                                                              [(get-in case [:input :left]) (get-in case [:input :right])])}
                                      (select-keys case [:target-reference :candidate-reference]))))]
               [target (->> (concat historical missing) (sort-by (juxt :case-id :run-id)) vec)]))))))

(defn score-views
  "Inspect bounded historical v3 runs; never silently substitute an old run for a current pair."
  ([root provider-id target rows] (score-views root provider-id target rows nil))
  ([root provider-id target rows current-cases]
   (get (score-views-for-targets root provider-id rows {target current-cases}) target)))

(defn apply-run!
  "Apply clear spelling choices from one stored v3 Jev run through reversible
  review decisions. Both records must bind to current PDF/HTML observations.
  The caller supplies the restricted reviewer DB capability; no Jev call occurs."
  [root run-id provider-id reviewer-url]
  (let [run (evaluation/inspect-run root run-id)
        requests (get-in run [:input :requests])
        provider-index (first (keep-indexed (fn [i config] (when (= provider-id (:id config)) i))
                                            (get-in run [:input :configurations])))
        prepared (get requests provider-index)
        report (get-in run [:report :providers provider-id])
        cases (filterv #(= :held-out (:split %)) (get-in run [:input :dataset :cases]))
        results (into {} (map (juxt :case-id identity) (:results report)))]
    (when-not (and provider-index report (= (set (map :case-id cases)) (set (keys results)))
                   (every? #(and (= :jev (:provider %)) (= "shadow-adapters/14" (:adapter-version %))
                                 (= :freediving-compact-v3 (get-in % [:config :identity-protocol]))) prepared))
      (throw (ex-info "A complete stored v3 Jev run is required" {})))
    (let [rows (candidates/load-corpus reviewer-url {})
          work (mapv (fn [case]
                       (let [input (:input case)
                             result (results (:case-id case))
                             recommendation (when (provider-receipt? report result)
                                              (recommendation input result))
                             left (bound-row rows (:left input))
                             right (bound-row rows (:right input))]
                         (when-not (and left right)
                           (throw (ex-info "Jev pair is not bound to current source observations" {:case-id (:case-id case)})))
                         {:case-id (:case-id case) :recommendation recommendation
                          :target (when recommendation
                                    (if (= :left (:target-side recommendation)) left right))})) cases)
          conflicts (->> work (filter :recommendation)
                         (group-by #(select-keys (:target %) [:job-id :ordinal]))
                         (keep (fn [[target items]]
                                 (when (> (count (set (map (comp :spelling :recommendation) items))) 1)
                                   target))) set)]
      (mapv (fn [{:keys [case-id recommendation target] :as item}]
              (if-not (:recommendation item)
                {:case-id case-id :status :no-clear-choice}
                (let [t (select-keys target [:job-id :ordinal])
                      state (reviews/effective reviewer-url t)
                      before (get-in state [:fields :source-name])
                      after (:spelling recommendation)
                      ref (evidence target)
                      proposal-id (id [run-id provider-id case-id t :proposal])
                      pending-owner? (let [history (reviews/history reviewer-url t)
                                           decided (set (keep :proposal-id history))]
                                       (some #(and (= :propose (:action %))
                                                   (= :name-normalization (:category %))
                                                   (not= proposal-id (:id %))
                                                   (not (contains? decided (:id %)))) history))]
                  (cond
                    (contains? conflicts t) {:case-id case-id :status :conflicting-recommendations}
                    (get-in state [:active :source-name]) {:case-id case-id :status :existing-normalization}
                    pending-owner? {:case-id case-id :status :pending-owner-choice}
                    (= before after) {:case-id case-id :status :already-spelled}
                    (not (seq ref)) {:case-id case-id :status :unsupported-source-format}
                    :else
                    (let [base (:revision state)
                          approval-id (id [run-id provider-id case-id t :approval])
                          reason (str "Jev spelling normalization " (name (:gate-version recommendation))
                                      "; run " run-id "; case " case-id
                                      "; match " (:identity-probability recommendation)
                                      "; spelling " (:spelling-probability recommendation))]
                      (reviews/propose! reviewer-url (merge t {:id proposal-id :base-revision base
                                                               :category :name-normalization :field :source-name
                                                               :before before :after after :evidence ref
                                                               :actor "jev-automatic-v1" :reason reason}))
                      (let [approved (reviews/decide! reviewer-url
                                                      {:id approval-id :proposal-id proposal-id :action :approve
                                                       :base-revision base :actor "jev-automatic-v1" :reason reason})]
                        {:case-id case-id :status :applied :proposal-id proposal-id
                         :approval-id (:id approved) :job-id (:job-id t) :ordinal (:ordinal t)
                         :before before :after after})))))) work))))

(defn -main [& [root run-id provider-id]]
  (try
    (when-not (and root run-id provider-id)
      (throw (ex-info "Run root, run ID and provider ID required" {})))
    (let [url (System/getenv "FREEDIVING_OWNER_DATABASE_URL")
          _ (when (str/blank? url) (throw (ex-info "Reviewer database URL required" {})))
          results (apply-run! root run-id provider-id url)]
      (println (pr-str {:case-count (count results) :statuses (frequencies (map :status results))})))
    (catch Exception _
      (binding [*out* *err*]
        (println "Jev spelling normalization failed; inspect the private run, source binding and reviewer capability."))
      (System/exit 1))))
