(ns freediving.owner-decision-export
  "Fail-closed mapping from immutable observations and flow events to private owner proposals."
  (:require [clojure.string :as str]
            [freediving.reconciliation-jev :as jev]
            [freediving.reconciliation-flow :as flow])
  (:import [java.sql DriverManager]))

(defn- invalid! [message data]
  (throw (ex-info message data)))

(defn- sha? [value]
  (and (string? value) (boolean (re-matches #"[0-9a-f]{64}" value))))

(defn- nonempty? [value]
  (and (string? value) (not (str/blank? value))))

(defn load-observation-revisions!
  "Read exact immutable PostgreSQL observation/extraction revisions by job and ordinal.
   Missing or duplicate requested positions abort the entire export. The URL must
   be a read-only credential at the call site."
  [jdbc-url positions]
  (when-not (and (nonempty? jdbc-url) (vector? positions)
                 (= (count positions) (count (set positions)))
                 (every? (fn [[job ordinal]] (and (sha? job) (nat-int? ordinal))) positions))
    (invalid! "Invalid observation lookup" {:positions positions}))
  (with-open [connection (DriverManager/getConnection jdbc-url)]
    (.setReadOnly connection true)
    (with-open [statement (.prepareStatement connection
                                             (str "SELECT o.job_id,o.ordinal,o.candidate_id,e.artifact_sha256,"
                                                  "e.source_sha256,e.parser_version "
                                                  "FROM freediving.observations o JOIN freediving.extractions e "
                                                  "ON e.job_id=o.job_id WHERE o.job_id=? AND o.ordinal=?"))]
      (into {}
            (for [[job ordinal :as position] positions]
              (do
                (.setString statement 1 job)
                (.setInt statement 2 ordinal)
                (with-open [rows (.executeQuery statement)]
                  (when-not (.next rows)
                    (invalid! "Missing immutable observation" {:position position}))
                  (let [revision {:job_id (.getString rows "job_id")
                                  :ordinal (.getInt rows "ordinal")
                                  :candidate_id (.getString rows "candidate_id")
                                  :artifact_sha256 (.getString rows "artifact_sha256")
                                  :source_sha256 (.getString rows "source_sha256")
                                  :parser_version (.getString rows "parser_version")}]
                    (when (.next rows)
                      (invalid! "Ambiguous immutable observation" {:position position}))
                    [position revision]))))))))

(defn- observation-for [evidence mapping revisions]
  (let [position [(:job-id mapping) (:ordinal mapping)]
        revision (get revisions position)
        cited-sha (get-in evidence [:citation :source-sha256])]
    (when-not (and (sha? (:job-id mapping)) (nat-int? (:ordinal mapping))
                   (map? revision) (= (:job-id mapping) (:job_id revision))
                   (= (:ordinal mapping) (:ordinal revision))
                   (sha? (:artifact_sha256 revision))
                   (= cited-sha (:source_sha256 revision))
                   (nonempty? (:candidate_id revision))
                   (nonempty? (:parser_version revision)))
      (invalid! "Evidence does not match immutable observation revision"
                {:evidence-id (:evidence-id evidence) :position position}))
    revision))

(defn- metadata [decision selected records]
  (let [sources (vec (distinct (map :source-name records)))
        source-name (when (= 1 (count sources)) (first sources))
        originals (case (:family decision)
                    :identity (mapv :athlete-name records)
                    (:same-attempt :source-revision :row-semantics
                                   :category :representation :category-representation)
                    (mapv :source-value records)
                    nil)
        original-key (if (= :identity (:family decision)) :athlete_names :source_values)]
    (when-not (and (nonempty? source-name) (seq originals)
                   (every? nonempty? originals) (keyword? selected))
      (invalid! "Cannot derive proposal values from verified records"
                {:decision-id (:id decision)}))
    {:subject_id (or (get-in decision [:subject :id])
                     (get-in decision [:subject :target-id]))
     :source_name source-name
     :original {original-key originals}
     :proposed {:action (str/replace (name selected) "-" "_")
                :subject (select-keys (:subject decision) [:pair :target-id :source-positions])}
     :competing_options (mapv #(str/replace (name %) "-" "_")
                              (remove #{selected} (:choices decision)))
     :supporting_evidence [] :conflicting_evidence []
     :groups (vec (get-in decision [:subject :groups] []))}))

(defn- identity-position [id]
  (when-let [[_ job ordinal] (and (string? id)
                                  (re-matches #"local-observation:([0-9a-f]{64}):([0-9]+)" id))]
    [job (Long/parseLong ordinal)]))

(defn- verify-identity-subject! [decision bound]
  (when (= :identity (:family decision))
    (let [subject (:subject decision)
          pair (:pair subject)
          versions (:observation-versions subject)]
      (when pair
        (when-not (and (vector? pair) (= 2 (count pair))
                       (every? identity-position pair)
                       (= (set (map identity-position pair))
                          (set (map (fn [item]
                                      (let [r (:observation-revision item)]
                                        [(:job_id r) (:ordinal r)])) bound)))
                       (every? (fn [id]
                                 (let [cited (get versions id)
                                       revision (some (fn [item]
                                                        (let [r (:observation-revision item)]
                                                          (when (= (identity-position id)
                                                                   [(:job_id r) (:ordinal r)]) r))) bound)]
                                   (and (= (:source-sha256 cited) (:source_sha256 revision))
                                        (= (:artifact-sha256 cited) (:artifact_sha256 revision)))))
                               pair))
          (invalid! "Identity subject differs from exact observation revisions"
                    {:decision-id (:id decision)}))))))

(defn- proposal [decision event run-revision opts]
  (let [id (:id decision)
        evidence (:evidence decision)
        mappings (:evidence-bindings opts)
        verified (:verified-snapshot-records opts)
        bound (mapv (fn [item]
                      (let [evidence-id (:evidence-id item)
                            mapping (get mappings evidence-id)
                            record-id (:snapshot-record-id mapping)
                            observation (observation-for item mapping (:observation-revisions opts))
                            record (get verified record-id)]
                        (when-not (and (nonempty? evidence-id) (= evidence-id (:evidence-id mapping))
                                       (sha? record-id) (= record-id (:record-id record))
                                       (= (:job-id mapping) (:job-id record))
                                       (= (:ordinal mapping) (:ordinal record))
                                       (= (:candidate_id observation) (:candidate-id record))
                                       (= (:source_sha256 observation) (:source-sha256 record))
                                       (= (:artifact_sha256 observation) (:artifact-sha256 record))
                                       (= (:parser_version observation) (:parser-version record))
                                       (or (string? (:citation item))
                                           (and (map? (:citation item)) (seq (:citation item)))))
                          (invalid! "Evidence is not bound to verified snapshot record"
                                    {:decision-id id :evidence-id evidence-id}))
                        {:evidence-id evidence-id :snapshot-record-id record-id
                         :observation-revision observation :record record :source item}))
                    evidence)
        _ (verify-identity-subject! decision bound)
        answer (:answer event)
        selected (or (:action event) (:action decision))
        source (metadata decision selected (mapv :record bound))
        model-origin? (#{:jev :retained :cached-jev :deterministic} (:origin event))
        approved? (= :approved (:status event))
        rule-version (or (:rule-version event) (:template-version event))]
    (when-not (and (nonempty? id) (re-matches #"[A-Za-z0-9_-]{1,128}" id)
                   (= id (:decision-id event)) (= evidence (:evidence event))
                   (nonempty? (:id event)) (seq evidence)
                   (= (count evidence) (count (set (map :evidence-id evidence))))
                   (= (count bound) (count (set (map :snapshot-record-id bound))))
                   (map? source) (nonempty? (:subject_id source))
                   (nonempty? (:source_name source)) (nonempty? rule-version)
                   (nonempty? (:policy-version event))
                   (vector? (:dependencies decision))
                   (every? nonempty? (:dependencies decision))
                   (every? vector? (map source [:competing_options :supporting_evidence
                                                :conflicting_evidence :groups]))
                   (contains? source :original) (contains? source :proposed)
                   (keyword? selected) (some #{selected} (:choices decision)) model-origin?
                   (not (:stale? decision))
                   (or (= :deterministic (:origin event))
                       (= jev/template-version (:template-version event))))
      (invalid! "Incomplete owner proposal provenance" {:decision-id id}))
    (let [bindings (mapv (fn [{:keys [evidence-id snapshot-record-id observation-revision]}]
                           {:evidence_id evidence-id :snapshot_record_id snapshot-record-id
                            :observation_revision observation-revision}) bound)
          observations (mapv :observation-revision bound)
          confidence (when answer (:confidence answer))
          probability (when answer (get (:probabilities answer) selected))]
      (when-not (and (or (nil? confidence) (and (number? confidence) (<= 0 confidence 1)))
                     (or (nil? probability) (and (number? probability) (<= 0 probability 1))))
        (invalid! "Invalid provider probability" {:decision-id id}))
      {:id id :type (str/replace (name (:family decision)) "-" "_") :subject_id (:subject_id source)
       :source_name (:source_name source) :original (:original source)
       :proposed (:proposed source) :selected_option (str/replace (name selected) "-" "_")
       :competing_options (:competing_options source)
       :evidence (mapv (fn [{:keys [evidence-id snapshot-record-id observation-revision source]}]
                         {:id snapshot-record-id
                          :citation {:evidence_id evidence-id :source_citation (:citation source)
                                     :source_excerpt (:exact-excerpt source)
                                     :observation_revision observation-revision}
                          :version observation-revision}) bound)
       :supporting_evidence (:supporting_evidence source)
       :conflicting_evidence (:conflicting_evidence source)
       :depends_on (:dependencies decision) :groups (:groups source)
       :score probability :provider_confidence confidence
       :rule_version rule-version :model_version (get answer :model-version)
       :policy_version (:policy-version event)
       :status (if approved? "automatic_approved" "pending")
       :canonical_binding {:decision_id id :reconciliation_run_revision run-revision
                           :reconciliation_event_id (:id event)
                           :observation_revisions observations
                           :evidence_bindings bindings}})))

(defn export-proposals
  "Export store-compatible proposals only with verified snapshot IDs and exact
   PostgreSQL observation revisions. Caller supplies records read from a verified
   SnapshotQuery, including source values, and revisions read through
   load-observation-revisions!. Nothing here verifies the snapshot itself or
   mutates either decision store."
  [ledger decisions {:keys [snapshot-sha256 binding-revision] :as opts}]
  (when-not (and (= flow/ledger-version (:version ledger)) (vector? (:events ledger))
                 (vector? decisions) (sha? snapshot-sha256)
                 (pos-int? binding-revision)
                 (map? (:evidence-bindings opts)) (map? (:observation-revisions opts))
                 (map? (:verified-snapshot-records opts))
                 (= (count decisions) (count (set (map :id decisions)))))
    (invalid! "Invalid verified export inputs" {}))
  (let [run-revision (count (:events ledger))]
    {:snapshot_sha256 snapshot-sha256 :binding_revision binding-revision
     :reconciliation_run_revision run-revision
     :proposals
     (mapv (fn [decision]
             (let [event (last (filter #(= (:id decision) (:decision-id %))
                                       (:events ledger)))]
               (when-not event
                 (invalid! "Decision has no reconciliation event" {:decision-id (:id decision)}))
               (proposal decision event run-revision opts)))
           decisions)}))
