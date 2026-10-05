(ns freediving.reconciliation-flow
  "Private, append-only reconciliation dispatch. It records proposed links, not publication approval."
  (:refer-clojure :exclude [run!])
  (:require [clojure.edn :as edn]
            [freediving.reconciliation-budget :as budget]
            [freediving.reconciliation-jev :as jev]
            [freediving.reconciliation-policy :as policy])
  (:import [java.security MessageDigest]
           [java.util HexFormat]
           [java.nio.channels FileChannel]
           [java.nio.file Files LinkOption Path Paths StandardCopyOption StandardOpenOption]
           [java.nio.file.attribute PosixFilePermissions]))

(def ledger-version "reconciliation-flow/1")
(def source-identity-rule-version "source-identity/1")

(defn- canonical [value]
  (cond
    (map? value) (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
                       (map (fn [[key item]] [key (canonical item)]) value))
    (vector? value) (mapv canonical value)
    (set? value) (vec (sort-by pr-str (map canonical value)))
    (sequential? value) (mapv canonical value)
    :else value))

(defn- digest [value]
  (.formatHex (HexFormat/of)
              (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes (pr-str (canonical value)) "UTF-8"))))

(defn empty-ledger [] {:version ledger-version :events []})

(defn append-human-event
  "Record a durable owner correction, including rejection or reversal."
  [ledger {:keys [id decision-id status action] :as event}]
  (when-not (and (= ledger-version (:version ledger)) (string? id) (string? decision-id)
                 (#{:approved :rejected :reversed} status)
                 (or (not= :approved status) (keyword? action))
                 (not-any? #(= id (:id %)) (:events ledger)))
    (throw (ex-info "Invalid human reconciliation event" {:event event})))
  (update ledger :events conj (assoc event :origin :human)))

(defn- evidence-key [decision]
  (digest (select-keys decision [:id :family :action :choices :subject :evidence
                                 :candidates :uncertainties :contradictions
                                 :field-binding :dependencies :conflicts :stale?
                                 :evidence-adequate?])))

(defn- decision-key [decision config]
  (digest [(evidence-key decision) config jev/template-version]))

(defn- human-event [ledger decision-id]
  (last (filter #(and (= :human (:origin %)) (= decision-id (:decision-id %))) (:events ledger))))

(defn- current-event [ledger decision config]
  (let [key (decision-key decision config)
        version (:version config)]
    (last (filter #(and (= (:id decision) (:decision-id %))
                        (= key (:decision-key %))
                        (= version (:config-version %))) (:events ledger)))))

(defn- result-view [decision event]
  {:status (:status event) :origin (:origin event) :family (:family decision)
   :action (:action event) :answer (:answer event)
   :alternatives (when-let [probs (get-in event [:answer :probabilities])]
                   (dissoc probs (get-in event [:answer :outcome])))
   :evidence (:evidence decision) :candidates (:candidates decision)
   :dependencies (:dependencies decision) :policy (:assessment event)
   :policy-version (:policy-version event) :rule-version (:rule-version event)
   :reason (:reason event)
   :request-hash (:request-hash event) :result-hash (:result-hash event)
   :receipt (:receipt event)})

(defn inspect
  "Private current view keyed by decision ID. Pass config to detect changed model/config."
  ([ledger decisions] (inspect ledger decisions nil))
  ([ledger decisions config]
   (into {}
         (for [decision decisions
               :let [event (or (human-event ledger (:id decision))
                               (when config (current-event ledger decision config))
                               (when-not config
                                 (last (filter #(and (= (:id decision) (:decision-id %))
                                                     (= (evidence-key decision) (:evidence-key %)))
                                               (:events ledger)))))]]
           [(:id decision) (if event (result-view decision event)
                               {:status :unresolved :origin nil :evidence (:evidence decision)})]))))

(defn- append-result [ledger decision config payload]
  (let [event (merge {:id (digest [(:id decision) (decision-key decision config)
                                   (:policy-version payload) (:status payload) (:result-hash payload)
                                   (:rule-version payload) (:reason payload)])
                      :decision-id (:id decision) :family (:family decision)
                      :action (:action decision) :origin :jev
                      :evidence-key (evidence-key decision)
                      :decision-key (decision-key decision config)
                      :config-version (:version config)
                      :evidence (:evidence decision) :candidates (:candidates decision)
                      :dependencies (:dependencies decision)} payload)]
    (if (some #(= (:id event) (:id %)) (:events ledger)) ledger
        (update ledger :events conj event))))

(defn- classify-answer [decision answer policy-config]
  (let [chosen-action (:outcome answer)
        assessment (policy/assess policy-config (assoc decision :action chosen-action) answer)]
    {:action chosen-action
     :status (if (= :approve (:status assessment)) :approved :unresolved)
     :reason (:reason assessment) :assessment assessment
     :policy-version (:policy-version assessment) :answer answer
     :result-hash (:result-hash answer) :receipt (:receipt answer)}))

(defn- dispatch-batch [ledger decisions request config policy-config execute! checkpoint!
                       provider-budget-path provider-pricing]
  (when (and (string? (:endpoint config))
             (.startsWith ^String (:endpoint config) "https://")
             (nil? provider-budget-path))
    (throw (ex-info "Remote Jev dispatch requires a durable provider budget"
                    {:reason :missing-budget-baseline})))
  (let [reservation (when provider-budget-path
                      (budget/reserve! provider-budget-path request provider-pricing))
        ledger (reduce (fn [ledger decision]
                         (append-result ledger decision config
                                        {:status :unknown-external-outcome
                                         :reason :dispatch-started
                                         :budget-reservation-id (:id reservation)
                                         :policy-version (:version policy-config)
                                         :request-hash (:request-hash request)
                                         :template-version (:template-version request)}))
                       ledger decisions)
        _ (when checkpoint! (checkpoint! ledger))]
    (try
      (let [response (execute! request)
            _ (when reservation
                (budget/record-receipt! provider-budget-path reservation response))
            _ (when (:error response)
                (throw (ex-info "Reconciliation transport error" {:error (:error response)})))
            parsed (jev/parse-batch request response)]
        (reduce (fn [ledger decision]
                  (let [answer (get-in parsed [:answers (:id decision)])
                        payload (if answer (classify-answer decision answer policy-config)
                                    {:status :invalid-response :reason :missing-answer
                                     :policy-version (:version policy-config)})]
                    (append-result ledger decision config
                                   (assoc payload :request-hash (:request-hash request)
                                          :template-version (:template-version request)))))
                ledger decisions))
      (catch Exception error
        (let [reason (or (:error (ex-data error)) :provider-error)
              status (case reason :timeout :timeout :interrupted :interrupted :provider-error)]
          (reduce (fn [ledger decision]
                    (append-result ledger decision config
                                   {:status status :reason reason
                                    :policy-version (:version policy-config)
                                    :request-hash (:request-hash request)
                                    :template-version (:template-version request)}))
                  ledger decisions))))))

(defn- retained-valid? [answer decision config]
  (and (= (decision-key decision config) (:decision-key answer))
       (= jev/template-version (:template-version answer))
       (= (:model config) (:model-version answer))
       (map? (:receipt answer))))

(defn- ready? [decision known]
  (every? #(= :approved (get known %)) (:dependencies decision)))

(defn- source-derived-identity? [decision]
  (and (= :identity (:family decision))
       (or (some #(and (string? %) (.startsWith ^String % "source-observation:"))
                 (get-in decision [:subject :pair]))
           (some #(= "source-derived" (get-in % [:citation :kind]))
                 (:evidence decision)))))

(defn- source-identity-reason [decision]
  (let [pair (get-in decision [:subject :pair])
        candidates (:candidates decision)
        versions (get-in decision [:subject :observation-versions])
        citations (mapv :citation (:evidence decision))
        refs (mapv #(get versions %) candidates)
        sha? #(and (string? %) (boolean (re-matches #"[0-9a-f]{64}" %)))]
    (if-not (and (= :same-person (:action decision))
                 (vector? pair) (= 2 (count pair)) (= 2 (count (set pair)))
                 (vector? candidates) (<= 2 (count candidates))
                 (= (count candidates) (count (set candidates)))
                 (= (first pair) (first candidates))
                 (some #{(second pair)} candidates)
                 (= (set candidates) (set (keys versions)))
                 (= refs citations) (= (count candidates) (count citations))
                 (= 1 (count (set (map :snapshot_sha256 refs))))
                 (every? (fn [[id ref]]
                           (and (= "source-derived" (:kind ref))
                                (= id (str "source-observation:" (:snapshot_record_id ref)))
                                (every? sha? (map ref [:snapshot_sha256 :snapshot_record_id
                                                       :source_sha256 :packet_sha256
                                                       :observation_version]))
                                (string? (:source_name ref))
                                (string? (:adapter_version ref))
                                (map? (:citation ref))))
                         (map vector candidates refs)))
      :invalid-source-ref
      ;; Source refs currently bind name and position, but no verified event,
      ;; session or category context for the identity rule.
      :source-context-unverified)))

(defn run!
  "Resolve a bounded set of decisions. execute! is the only external boundary.
   Retained answers and deterministic outcomes bypass HTTP. Independent ready
   decisions batch together; dependencies advance only after acceptance."
  [ledger decisions {:keys [config policy execute! checkpoint! retained-answers deterministic-results
                            provider-budget-path provider-pricing]}]
  (when-not (and (= ledger-version (:version ledger)) (vector? decisions)
                 (= (count decisions) (count (set (map :id decisions))))
                 (every? (comp string? :id) decisions) (map? config) (map? policy))
    (throw (ex-info "Invalid reconciliation run" {})))
  (let [execute! (or execute! (fn [_] (throw (ex-info "Missing reconciliation transport" {:error :missing-transport}))))]
    (loop [ledger ledger remaining decisions known {}]
      (if (empty? remaining) ledger
          (let [ready (filterv #(every? (set (keys known)) (:dependencies %)) remaining)]
            (if (empty? ready)
              (reduce (fn [ledger decision]
                        (append-result ledger decision config
                                       {:status :dependency-blocked :reason :missing-or-cyclic-dependency
                                        :policy-version (:version policy)})) ledger remaining)
              (let [ledger
                    (reduce (fn [ledger decision]
                              (let [id (:id decision)
                                    human (human-event ledger id)
                                    prior (current-event ledger decision config)
                                    deterministic (get deterministic-results id)
                                    retained (get retained-answers id)]
                                (cond
                                  human ledger
                                  (source-derived-identity? decision)
                                  (append-result ledger decision config
                                                 {:status :unresolved :origin :deterministic
                                                  :reason (source-identity-reason decision)
                                                  :rule-version source-identity-rule-version
                                                  :policy-version (:version policy)})
                                  (not (ready? decision known))
                                  (append-result ledger decision config
                                                 {:status :dependency-blocked :reason :dependency-unapproved
                                                  :policy-version (:version policy)})
                                  (and deterministic (= :approve (:status deterministic)))
                                  (append-result ledger decision config
                                                 {:status (if (and (= :approve (:status deterministic))
                                                                   (:rule-version deterministic)) :approved :unresolved)
                                                  :origin :deterministic :reason (:reason deterministic)
                                                  :rule-version (:rule-version deterministic)
                                                  :policy-version (:version policy)})
                                  (and prior (:answer prior) (not= (:version policy) (:policy-version prior)))
                                  (append-result ledger decision config
                                                 (assoc (classify-answer decision (:answer prior) policy)
                                                        :origin :cached-jev
                                                        :request-hash (:request-hash prior)
                                                        :template-version (:template-version prior)))
                                  (and retained (retained-valid? retained decision config))
                                  (append-result ledger decision config
                                                 (assoc (classify-answer decision retained policy)
                                                        :origin :retained))
                                  (and prior (= (:version policy) (:policy-version prior))
                                       (not= :dependency-blocked (:status prior))) ledger
                                  :else ledger)))
                            ledger ready)
                    pending (filterv (fn [decision]
                                       (let [event (or (human-event ledger (:id decision))
                                                       (current-event ledger decision config))]
                                         (and (ready? decision known)
                                              (or (nil? event)
                                                  (= :dependency-blocked (:status event))))))
                                     ready)
                    ledger (if (seq pending)
                             (reduce (fn [ledger request]
                                       (let [ids (set (:decision-ids request))
                                             members (filterv #(ids (:id %)) pending)]
                                         (dispatch-batch ledger members request config policy execute! checkpoint!
                                                         provider-budget-path provider-pricing)))
                                     ledger (jev/prepare-batches config pending))
                             ledger)
                    known (reduce (fn [known decision]
                                    (assoc known (:id decision)
                                           (:status (or (human-event ledger (:id decision))
                                                        (current-event ledger decision config)))))
                                  known ready)
                    remaining (filterv (complement (set ready)) remaining)]
                (recur ledger remaining known))))))))

(defn project-private
  "Approved private decisions only. Existing athlete/attempt ledgers still own canonical groups."
  [ledger decisions config]
  (let [current (inspect ledger decisions config)
        approved? (fn [id] (= :approved (get-in current [id :status])))]
    {:scope :private-reconciliation-decisions
     :revision (count (:events ledger))
     :approved-decisions
     (mapv (fn [decision]
             (let [view (get current (:id decision))]
               {:decision-id (:id decision) :family (:family decision)
                :action (:action view) :origin (:origin view)
                :evidence (:evidence view) :answer (:answer view)
                :policy-version (:policy-version view)}))
           (filter (fn [decision]
                     (and (approved? (:id decision))
                          (keyword? (get-in current [(:id decision) :action]))
                          (every? approved? (:dependencies decision)))) decisions))}))

(defn- ledger-path [path]
  (let [path (Paths/get (str path) (make-array String 0))]
    (when-not (.isAbsolute path)
      (throw (ex-info "Private ledger path must be absolute" {:path (str path)})))
    path))

(defn- private-permissions! [^Path path]
  (try
    (Files/setPosixFilePermissions path (PosixFilePermissions/fromString "rw-------"))
    (catch UnsupportedOperationException _ nil)))

(defn load-ledger!
  "Read a private EDN ledger and verify its digest. Missing file is empty."
  [path]
  (let [path (ledger-path path)]
    (if-not (Files/exists path (make-array LinkOption 0))
      (empty-ledger)
      (let [envelope (try (edn/read-string (Files/readString path))
                          (catch Exception error
                            (throw (ex-info "Unreadable private reconciliation ledger" {} error))))
            ledger (:ledger envelope)]
        (when-not (and (= ledger-version (:version ledger))
                       (vector? (:events ledger))
                       (= (:sha256 envelope) (digest ledger)))
          (throw (ex-info "Private reconciliation ledger integrity check failed" {})))
        ledger))))

(defn save-ledger!
  "Atomically persist a private append-only ledger; reject history truncation or rewrite."
  [path ledger]
  (let [path (ledger-path path)
        parent (.getParent path)]
    (when-not (and (= ledger-version (:version ledger)) (vector? (:events ledger)))
      (throw (ex-info "Invalid private reconciliation ledger" {})))
    (Files/createDirectories parent (make-array java.nio.file.attribute.FileAttribute 0))
    (let [lock-path (.resolve parent (str (.getFileName path) ".lock"))]
      (with-open [channel (FileChannel/open lock-path
                                            (into-array StandardOpenOption [StandardOpenOption/CREATE
                                                                            StandardOpenOption/WRITE]))
                  _lock (.lock channel)]
        (private-permissions! lock-path)
        (let [previous (load-ledger! path)
              old-events (:events previous)
              events (:events ledger)]
          (when-not (and (<= (count old-events) (count events))
                         (= old-events (subvec events 0 (count old-events))))
            (throw (ex-info "Private reconciliation ledger history changed" {})))
          (let [temp (Files/createTempFile parent ".reconciliation-" ".edn"
                                           (make-array java.nio.file.attribute.FileAttribute 0))]
            (try
              (private-permissions! temp)
              (Files/writeString temp (pr-str {:sha256 (digest ledger) :ledger ledger})
                                 (into-array StandardOpenOption [StandardOpenOption/WRITE
                                                                 StandardOpenOption/TRUNCATE_EXISTING]))
              (Files/move temp path
                          (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE
                                                          StandardCopyOption/REPLACE_EXISTING]))
              (finally (Files/deleteIfExists temp)))))))
    ledger))

(defn run-file!
  "Durable private run. Checkpoint each batch before dispatch, so interrupted
   requests remain unknown and identical reruns never automatically resend."
  [path decisions options]
  (let [result (run! (load-ledger! path) decisions
                     (assoc options :checkpoint! #(save-ledger! path %)))]
    (save-ledger! path result)
    result))
