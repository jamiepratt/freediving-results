(ns freediving.evaluation-labels
  "Explicit pair labels. Database owner capability is authority; actor is audit text.
   Exports are private data. Trust requires live verification, never a file flag."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [freediving.reviews :as reviews])
  (:import [java.sql DriverManager Connection]
           [java.security MessageDigest]
           [java.util HexFormat UUID]))
(defn- fail! [message] (throw (ex-info message {:type :invalid-evaluation-label})))
(defn- text? [x] (and (string? x) (not (str/blank? x)) (<= (count x) 4096)))
(defn- canonical [x]
  (cond (map? x) (into (sorted-map) (map (fn [[k v]] [k (canonical v)]) x))
        (sequential? x) (mapv canonical x) :else x))
(defn- encode [x] (binding [*print-length* nil *print-level* nil] (pr-str (canonical x))))
(defn- digest [x]
  (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") (.getBytes (encode x) "UTF-8"))))
(defn- query [^Connection c sql & args]
  (with-open [s (.prepareStatement c sql)]
    (doseq [[i v] (map-indexed vector args)] (.setObject s (inc i) v))
    (with-open [r (.executeQuery s)]
      (let [m (.getMetaData r)]
        (loop [rows []]
          (if (.next r)
            (recur (conj rows (into {} (for [i (range 1 (inc (.getColumnCount m)))]
                                         [(keyword (.getColumnLabel m i)) (.getObject r i)])))) rows))))))
(defn- execute! [^Connection c sql & args]
  (with-open [s (.prepareStatement c sql)]
    (doseq [[i v] (map-indexed vector args)] (.setObject s (inc i) v)) (.executeUpdate s)))
(defn- transaction [url read-only? f]
  (with-open [c (DriverManager/getConnection url)]
    (.setTransactionIsolation c (if read-only? Connection/TRANSACTION_REPEATABLE_READ Connection/TRANSACTION_READ_COMMITTED))
    (.setReadOnly c read-only?) (.setAutoCommit c false)
    (execute! c "SET LOCAL statement_timeout = '10s'")
    (execute! c "SET LOCAL lock_timeout = '5s'")
    (try (let [v (f c)] (.commit c) v) (catch Exception e (.rollback c) (throw e)))))
(defn migrate! [admin-url reviewer-role corpus-mode]
  (when-not (and (string? reviewer-role) (re-matches #"[a-z_][a-z0-9_]*" reviewer-role)
                 (#{:synthetic :real} corpus-mode)) (fail! "Invalid reviewer role or corpus mode"))
  (transaction admin-url false
               (fn [c]
                 (query c "SELECT pg_advisory_xact_lock(781246914)")
                 (let [role (first (query c "SELECT rolsuper,rolcreaterole,rolcreatedb,rolbypassrls FROM pg_roles WHERE rolname=?" reviewer-role))]
                   (when (or (nil? role) (some true? (vals role))
                             (seq (query c "SELECT roleid FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname=?)" reviewer-role))
                             (seq (query c "SELECT oid FROM pg_class WHERE relnamespace=to_regnamespace('freediving') AND pg_has_role(?,relowner,'MEMBER')" reviewer-role))
                             (seq (query c "SELECT oid FROM pg_namespace WHERE nspname='freediving' AND pg_has_role(?,nspowner,'MEMBER')" reviewer-role))
                             (seq (query c "SELECT oid FROM pg_database WHERE datname=current_database() AND pg_has_role(?,datdba,'MEMBER')" reviewer-role)))
                     (fail! "Reviewer must be restricted without ownership or memberships")))
                 (let [sql (slurp (io/resource "migrations/006-evaluation-labels.sql")) checksum (digest sql)]
                   (if-let [old (first (query c "SELECT sha256 FROM freediving.schema_migrations WHERE version=6"))]
                     (when-not (= checksum (:sha256 old)) (fail! "Migration checksum conflict"))
                     (do (execute! c sql)
                         (execute! c "INSERT INTO freediving.evaluation_corpus(corpus_id,mode) VALUES(?,?)" (str (UUID/randomUUID)) (name corpus-mode))
                         (execute! c "INSERT INTO freediving.schema_migrations VALUES(6,?)" checksum))))
                 (when-not (= (name corpus-mode) (:mode (first (query c "SELECT mode FROM freediving.evaluation_corpus"))))
                   (fail! "Immutable corpus mode differs"))
                 (execute! c (str "REVOKE ALL ON freediving.evaluation_labels,freediving.evaluation_corpus FROM " reviewer-role))
                 (execute! c (str "GRANT SELECT ON freediving.evaluation_labels,freediving.evaluation_corpus TO " reviewer-role))
                 (execute! c (str "GRANT INSERT ON freediving.evaluation_labels TO " reviewer-role))
                 {:schema-version 6 :corpus-mode corpus-mode})))
(def reference-keys #{:job-id :ordinal :candidate-id :source-sha256 :artifact-sha256 :page :line})
(defn- evidence [c ref]
  (when-not (and (= reference-keys (set (keys ref)))
                 (every? text? ((juxt :job-id :candidate-id :source-sha256 :artifact-sha256) ref))
                 (nat-int? (:ordinal ref)) (every? pos-int? ((juxt :page :line) ref)))
    (fail! "Invalid exact evidence reference"))
  (let [size (first (query c "SELECT octet_length(e.artifact_bytes) AS artifact_size,octet_length(o.payload_edn) AS payload_size FROM freediving.observations o JOIN freediving.extractions e USING(job_id) WHERE o.job_id=? AND o.ordinal=?" (:job-id ref) (:ordinal ref)))
        _ (when (or (> (or (:artifact_size size) 0) 10000000) (> (or (:payload_size size) 0) 1000000)) (fail! "Evidence exceeds byte bound"))
        review-size (first (query c "SELECT coalesce(sum(octet_length(body_edn)),0) AS bytes,count(*) AS n FROM (SELECT body_edn FROM freediving.review_decisions WHERE job_id=? AND ordinal=? UNION ALL SELECT body_edn FROM freediving.review_proposals WHERE job_id=? AND ordinal=?) AS history" (:job-id ref) (:ordinal ref) (:job-id ref) (:ordinal ref)))
        _ (when (or (> (:bytes review-size) 1000000) (> (:n review-size) 1000)) (fail! "Review history exceeds bound"))
        o (first (query c "SELECT o.*,e.source_sha256,e.artifact_sha256,e.artifact_bytes FROM freediving.observations o JOIN freediving.extractions e USING(job_id) WHERE o.job_id=? AND o.ordinal=?" (:job-id ref) (:ordinal ref)))
        payload (when o (edn/read-string (:payload_edn o)))
        artifact (when o (edn/read-string (String. ^bytes (:artifact_bytes o) "UTF-8")))
        candidate-lines (conj (set (map #(select-keys % [:page :line]) (:source-lines payload)))
                              (select-keys (:coordinates payload) [:page :line]))]
    (when-not (and o (= "result-row" (:kind o))
                   (= (:candidate-id ref) (:candidate_id o))
                   (= (:source-sha256 ref) (:source_sha256 o))
                   (= (:artifact-sha256 ref) (:artifact_sha256 o))
                   (contains? candidate-lines (select-keys ref [:page :line]))
                   (some #(and (= (:page ref) (:page %)) (some (fn [line] (= (:line ref) (:line line))) (:lines %))) (:pages artifact)))
      (fail! "Exact evidence provenance or coordinates mismatch"))
    {:reference ref :parsed (:parsed payload)
     :identity (:identity (#'reviews/snapshot c ref))
     :review-revision (or (:revision (first (query c "SELECT max(revision) AS revision FROM freediving.review_decisions WHERE job_id=? AND ordinal=?" (:job-id ref) (:ordinal ref)))) 0)}))
(defn- pair-key [pair] (digest (sort-by encode (map #(select-keys % [:job-id :ordinal]) pair))))
(defn- body [row]
  (assoc (edn/read-string (:body_edn row)) :db-role (:db_role row)
         :recorded-at (str (.toInstant ^java.sql.Timestamp (:recorded_at row)))))
(defn decide!
  "Append explicit match/no-match/unknown/revoke for an exact pair, optimistic revision.
   Unknown and revoke remove eligibility. Existing generic identity reviews are not labels."
  [url request]
  (when-not (and (map? request)
                 (= #{:id :pair :base-revision :review-revisions :outcome :actor :reason :rubric-version} (set (keys request)))
                 (every? text? ((juxt :id :actor :reason :rubric-version) request))
                 (nat-int? (:base-revision request)) (#{:match :no-match :unknown :revoke} (:outcome request))
                 (vector? (:pair request)) (= 2 (count (:pair request)))
                 (vector? (:review-revisions request)) (= 2 (count (:review-revisions request)))
                 (every? nat-int? (:review-revisions request)))
    (fail! "Invalid explicit pair label request"))
  (transaction url false
               (fn [c]
                 (when-not (:allowed (first (query c "SELECT has_table_privilege(current_user,'freediving.evaluation_labels','INSERT') AS allowed")))
                   (fail! "Owner evaluation database capability required"))
                 (let [pair (vec (sort-by encode (:pair request))) key (pair-key pair)]
                   (when (= (select-keys (first pair) [:job-id :ordinal]) (select-keys (second pair) [:job-id :ordinal]))
                     (fail! "Distinct observations required"))
                   (query c "SELECT pg_advisory_xact_lock(hashtextextended(?, 19))" key)
                   (doseq [ref pair]
                     (query c "SELECT pg_advisory_xact_lock(hashtextextended(?, 11))" (str (:job-id ref) "/" (:ordinal ref))))
                   (if-let [old (first (query c "SELECT * FROM freediving.evaluation_labels WHERE id=?" (:id request)))]
                     (if (= request (:request (body old))) (body old) (fail! "Conflicting idempotency key"))
                     (let [current (first (query c "SELECT revision FROM freediving.evaluation_labels WHERE pair_key=? ORDER BY revision DESC LIMIT 1" key))
                           _ (when-not (= (:base-revision request) (or (:revision current) 0)) (fail! "Stale pair revision"))
                           refs (mapv #(evidence c %) pair)
                           expected (zipmap (:pair request) (:review-revisions request))
                           _ (when-not (= (mapv expected pair) (mapv :review-revision refs)) (fail! "Stale observation review revisions"))
                           record {:id (:id request) :pair-key key :revision (inc (:base-revision request))
                                   :outcome (:outcome request) :rubric-version (:rubric-version request)
                                   :actor (:actor request) :reason (:reason request) :pair pair
                                   :identity-target {:type :local-observation :reference (second pair)}
                                   :review-revisions (mapv :review-revision refs) :request request}]
                       (execute! c "INSERT INTO freediving.evaluation_labels(id,pair_key,revision,outcome,body_edn) VALUES(?,?,?,?,?)"
                                 (:id request) key (:revision record) (name (:outcome record)) (encode record))
                       (body (first (query c "SELECT * FROM freediving.evaluation_labels WHERE id=?" (:id request))))))))))
(defn- case-for [corpus row refs]
  (let [synthetic? (= "synthetic" (:mode corpus))
        es (mapv (fn [{:keys [reference]}]
                   {:evidence-id (digest reference) :source-sha256 (:source-sha256 reference)
                    :artifact-sha256 (:artifact-sha256 reference)
                    :observation-id (str (:job-id reference) ":" (:ordinal reference))
                    :page (:page reference) :lines [(:line reference) (:line reference)]}) refs)]
    {:case-id (:pair-key row) :split :held-out
     :source-family-ids [(str "corpus:" (:corpus_id corpus))]
     :person-group-ids [(str "corpus:" (:corpus_id corpus))]
     :input {:left (:parsed (first refs)) :right (:parsed (second refs))}
     :evidence es
     :authoritative-review row
     :label (merge {:outcome (:outcome row)}
                   (if synthetic? {:provenance :synthetic :fixture-id (:id row)}
                       {:provenance :owner :review-id (:id row) :reviewer (:actor row)
                        :reviewed-at (:recorded-at row) :review-artifact-sha256 (digest row)
                        :evidence-ids (mapv :evidence-id es)}))}))
(defn- normalized-name [x]
  (-> (java.text.Normalizer/normalize (str x) java.text.Normalizer$Form/NFD)
      (str/replace #"\p{M}" "") str/lower-case
      (str/split #"[^\p{L}\p{N}]+") sort vec))
(defn- connected-groups [items]
  (reduce (fn [groups item]
            (let [touches? #(some (:dedup item) (mapcat :dedup %))
                  hits (filter touches? groups)
                  merged (vec (concat [item] (mapcat identity hits)))]
              (conj (vec (remove touches? groups)) merged))) [] items))
(defn- conflicting-identity? [row refs]
  (and (= :no-match (:outcome row))
       (let [[a b] refs
             local-id (fn [r] (str "local-observation:" (get-in r [:reference :job-id]) ":" (get-in r [:reference :ordinal])))
             ai (:identity a) bi (:identity b)]
         (or (and (= :matched (:outcome ai)) (= (:identity-id ai) (local-id b)))
             (and (= :matched (:outcome bi)) (= (:identity-id bi) (local-id a)))
             (and (= :matched (:outcome ai)) (= :matched (:outcome bi))
                  (= (:identity-id ai) (:identity-id bi)))))))
(defn export
  "Read-only repeatable-read export. Conservative whole-corpus grouping prevents leakage.
   Source-copy/re-extraction/repeated-name pairs collapse to one label or conflict."
  ([url opts] (export url opts {}))
  ([url opts {:keys [on-snapshot]}]
   (when-not (and (map? opts) (every? #{:rubric-version :max-labels} (keys opts))
                  (text? (:rubric-version opts)) (integer? (get opts :max-labels 1000))
                  (<= 1 (get opts :max-labels 1000) 10000)) (fail! "Invalid export bounds or rubric"))
   (let [opts (assoc opts :max-labels (get opts :max-labels 1000))]
     (transaction url true
                  (fn [c]
                    (let [corpus (or (first (query c "SELECT * FROM freediving.evaluation_corpus")) (fail! "Missing corpus authority"))
                          size (first (query c "SELECT count(*) AS n,coalesce(sum(octet_length(body_edn)),0) AS bytes FROM freediving.evaluation_labels"))
                          _ (when (or (> (:n size) (:max-labels opts)) (> (:bytes size) 10000000)) (fail! "Label history exceeds export bound"))
                          raw (query c "SELECT * FROM freediving.evaluation_labels ORDER BY pair_key,revision LIMIT ?" (inc (:max-labels opts)))
                          _ (when (> (count raw) (:max-labels opts)) (fail! "Label history exceeds export bound"))
                          history (mapv body raw)
                          _ (when on-snapshot (on-snapshot))
                          _ (doseq [[r b] (map vector raw history)]
                              (when-not (= [(:id r) (:pair_key r) (:revision r) (:outcome r)]
                                           [(:id b) (pair-key (:pair b)) (:revision b) (name (:outcome b))])
                                (fail! "Label envelope mismatch"))
                              (when-not (and (every? text? ((juxt :id :actor :reason :rubric-version) b))
                                             (vector? (:pair b)) (= 2 (count (:pair b)))
                                             (= 2 (count (set (map #(select-keys % [:job-id :ordinal]) (:pair b)))))
                                             (vector? (:review-revisions b)) (= 2 (count (:review-revisions b)))
                                             (every? nat-int? (:review-revisions b))
                                             (= (:identity-target b) {:type :local-observation :reference (second (:pair b))})
                                             (= (:pair-key b) (:pair_key r))
                                             (= (select-keys b [:id :outcome :actor :reason :rubric-version])
                                                (select-keys (:request b) [:id :outcome :actor :reason :rubric-version]))
                                             (= (:pair b) (vec (sort-by encode (get-in b [:request :pair]))))
                                             (= (:revision b) (inc (get-in b [:request :base-revision])))
                                             (= (:review-revisions b) (mapv (zipmap (get-in b [:request :pair]) (get-in b [:request :review-revisions])) (:pair b))))
                                (fail! "Label request envelope mismatch")))
                          active (mapv last (vals (group-by :pair-key history)))
                          inspected (mapv (fn [row]
                                            (let [refs (mapv #(evidence c %) (:pair row))
                                                  reason (cond
                                                           (= :revoke (:outcome row)) :revoked
                                                           (= :unknown (:outcome row)) :unknown
                                                           (not= (:rubric-version opts) (:rubric-version row)) :rubric-mismatch
                                                           (not= (:review-revisions row) (mapv :review-revision refs)) :stale-review
                                                           (conflicting-identity? row refs) :conflicting-identity-review)]
                                              {:row row :refs refs :reason reason
                                   ;; Deliberately over-collapse homonyms rather than inflate independent reviews.
                                               :dedup #{[:names (digest (sort (map #(normalized-name (get-in % [:parsed :source-name])) refs)))]
                                                        [:positions (digest (sort-by encode (map #(select-keys (:reference %) [:source-sha256 :page :line]) refs)))]
                                                        [:candidates (digest (sort (map #(get-in % [:reference :candidate-id]) refs)))]}})) active)
                          grouped (connected-groups inspected)
                          chosen (mapv (fn [group]
                                         (let [eligible (filter #(nil? (:reason %)) group)
                                               outcomes (set (map #(get-in % [:row :outcome]) eligible))]
                                           (if (or (> (count outcomes) 1) (and (> (count group) 1) (some :reason group)))
                                             {:keep nil :exclude (mapv #(assoc % :reason (or (:reason %) (if (> (count outcomes) 1) :conflicting-pair-labels :ambiguous-duplicate-review))) group)}
                                             (let [winner (first (sort-by #(get-in % [:row :id]) eligible))]
                                               {:keep winner :exclude (mapv #(assoc % :reason (or (:reason %) :duplicate-pair)) (remove #{winner} group))})))) grouped)
                          cases (->> chosen (keep :keep) (map #(case-for corpus (:row %) (:refs %))) (sort-by :case-id) vec)
                          excluded (->> chosen (mapcat :exclude)
                                        (map #(hash-map :label-id (get-in % [:row :id]) :reason (:reason %)))
                                        (concat (for [row history :when (not (some #(= (:id row) (:id %)) active))]
                                                  {:label-id (:id row) :reason :superseded}))
                                        (sort-by :label-id) vec)
                          snapshot {:corpus-id (:corpus_id corpus) :corpus-mode (keyword (:mode corpus))
                                    :history-sha256 (digest history)
                                    :review-state-sha256 (digest (mapv #(select-keys % [:row :refs]) (sort-by #(get-in % [:row :id]) inspected)))}
                          receipt {:receipt-version 1 :options opts :snapshot snapshot :exclusions excluded
                                   :status (if (seq cases) :ready :not-evaluable)
                                   :dataset {:schema-version 1 :dataset-id (str "review-export:" (digest [snapshot opts]))
                                             :rubric-version (:rubric-version opts)
                                             :grouping {:attested-by "authoritative-db-export-v1"
                                                        :method "Whole corpus held-out; connected source-position, candidate, normalized name-pair deduplication"
                                                        :limitations "Conservative grouping can collapse homonyms. Unknown identity and source duplication remain possible; no independent development split."}
                                             :cases cases}}]
                      (when (> (alength (.getBytes (encode receipt) "UTF-8")) 10000000) (fail! "Export exceeds byte bound"))
                      (assoc receipt :receipt-id (digest receipt))))))))
(defn verify! [url receipt]
  (when-not (and (map? receipt) (= 1 (:receipt-version receipt))) (fail! "Invalid export receipt"))
  (let [current (export url (:options receipt))]
    (when-not (= current receipt) (fail! "Export is stale or differs from authoritative database snapshot"))
    {:verified? true :label-source (if (= :synthetic (get-in current [:snapshot :corpus-mode])) :synthetic-fixture :verified-owner-review)
     :dataset (:dataset current) :receipt-id (:receipt-id current) :status (:status current)}))
(defn history
  "Read exact pair history plus observation review revisions for a prepared request."
  [url pair]
  (when-not (and (vector? pair) (= 2 (count pair))) (fail! "Expected exact pair"))
  (transaction url true
               (fn [c]
                 (let [refs (mapv #(evidence c %) pair)
                       size (first (query c "SELECT coalesce(sum(octet_length(body_edn)),0) AS bytes FROM freediving.evaluation_labels WHERE pair_key=?" (pair-key pair)))
                       _ (when (> (:bytes size) 10000000) (fail! "Pair history exceeds byte bound"))
                       rows (query c "SELECT * FROM freediving.evaluation_labels WHERE pair_key=? ORDER BY revision LIMIT 10001" (pair-key pair))]
                   (when (> (count rows) 10000) (fail! "Pair history exceeds bound"))
                   {:pair pair :review-revisions (mapv :review-revision refs)
                    :base-revision (or (:revision (last rows)) 0) :events (mapv body rows)}))))
(defn- read-file [path]
  (with-open [stream (io/input-stream path)]
    (let [bytes (.readNBytes stream 10000001)]
      (when (> (alength bytes) 10000000) (fail! "Input exceeds byte bound"))
      (with-open [r (java.io.PushbackReader. (java.io.StringReader. (String. bytes "UTF-8")))]
        (let [eof (Object.) v (edn/read {:eof eof} r)]
          (when (or (identical? eof v) (not (identical? eof (edn/read {:eof eof} r)))) (fail! "Expected one EDN value")) v)))))
(defn write-receipt!
  "Publish immutable content-addressed receipt in a private directory, 0700/0600.
   Trusted local owner controls directory ancestry; this is not an adversarial store."
  [directory receipt]
  (let [dir (.normalize (.toAbsolutePath (.toPath (io/file directory))))
        perms (java.nio.file.attribute.PosixFilePermissions/fromString "rwx------")
        file-perms (java.nio.file.attribute.PosixFilePermissions/asFileAttribute (java.nio.file.attribute.PosixFilePermissions/fromString "rw-------"))
        nofollow (into-array java.nio.file.LinkOption [java.nio.file.LinkOption/NOFOLLOW_LINKS])]
    (loop [ancestor dir]
      (when ancestor
        (when (java.nio.file.Files/isSymbolicLink ancestor) (fail! "Private receipt ancestry cannot contain symlinks"))
        (recur (.getParent ancestor))))
    (java.nio.file.Files/createDirectories dir (into-array java.nio.file.attribute.FileAttribute [(java.nio.file.attribute.PosixFilePermissions/asFileAttribute perms)]))
    (when-not (= perms (java.nio.file.Files/getPosixFilePermissions dir nofollow)) (fail! "Receipt directory must have 0700 permissions"))
    (when-not (= (:receipt-id receipt) (digest (dissoc receipt :receipt-id))) (fail! "Invalid receipt content identity"))
    (let [path (.resolve dir (str (:receipt-id receipt) ".edn")) bytes (.getBytes (encode receipt) "UTF-8")]
      (when (> (alength bytes) 10000000) (fail! "Receipt exceeds byte bound"))
      (when (java.nio.file.Files/isSymbolicLink path) (fail! "Receipt cannot be a symlink"))
      (if (java.nio.file.Files/exists path nofollow)
        (when-not (= receipt (read-file (str path))) (fail! "Immutable receipt conflict"))
        (let [temporary (java.nio.file.Files/createTempFile dir ".receipt-" ".tmp" (into-array java.nio.file.attribute.FileAttribute [file-perms]))]
          (try
            (java.nio.file.Files/write temporary bytes (make-array java.nio.file.OpenOption 0))
            (try (java.nio.file.Files/createLink path temporary)
                 (catch java.nio.file.FileAlreadyExistsException _
                   (when (or (java.nio.file.Files/isSymbolicLink path)
                             (not= receipt (read-file (str path))))
                     (fail! "Immutable receipt conflict"))))
            (finally (java.nio.file.Files/deleteIfExists temporary)))))
      (when-not (= (java.nio.file.attribute.PosixFilePermissions/fromString "rw-------") (java.nio.file.Files/getPosixFilePermissions path nofollow))
        (fail! "Receipt must have 0600 permissions"))
      (str (.toAbsolutePath path)))))
(defn -main [& [command & args]]
  (try
    (let [url (System/getenv "FREEDIVING_DATABASE_URL")]
      (case command
        "migrate" (if (= 2 (count args)) (println (migrate! url (first args) (keyword (second args)))) (fail! "migrate REVIEWER-ROLE synthetic|real"))
        "decide" (if (= 1 (count args)) (let [r (decide! url (read-file (first args)))] (println (select-keys r [:id :revision :outcome]))) (fail! "decide REQUEST.edn"))
        "history" (if (= 2 (count args))
                    (let [result (history url (read-file (first args))) receipt {:history result :receipt-version 1}]
                      (println {:path (write-receipt! (second args) (assoc receipt :receipt-id (digest receipt)))}))
                    (fail! "history PAIR.edn PRIVATE-DIRECTORY"))
        "export" (if (= 2 (count args)) (let [r (export url (read-file (first args)))] (println {:status (:status r) :receipt-id (:receipt-id r) :path (write-receipt! (second args) r)})) (fail! "export OPTIONS.edn PRIVATE-DIRECTORY"))
        "verify" (if (= 1 (count args)) (println (dissoc (verify! url (read-file (first args))) :dataset)) (fail! "verify RECEIPT.edn"))
        (fail! "Commands: migrate|decide|history|export|verify")))
    (catch Exception _ (binding [*out* *err*] (println "Evaluation label operation failed; private input or database verification required.")) (System/exit 1))))
