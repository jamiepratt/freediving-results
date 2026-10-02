(ns freediving.parser-batch
  "Pure replay of retained position inventories and explicit parser claims."
  (:require [freediving.parser-routing :as routing]
            [freediving.parser-adapters :as adapters]))

(defn- merge-entry [by-source {:keys [document claims] :as entry}]
  (let [sha (:source-sha256 document)
        previous (get by-source sha)]
    (when (and previous (not= document (:document previous)))
      (throw (ex-info "Conflicting inventories for one source version"
                      {:reason :conflicting-source-inventory :source-sha256 sha})))
    (assoc by-source sha
           (if previous
             (-> previous
                 (update :claims into claims)
                 (update :unsupported-reasons into (:unsupported-reasons entry))
                 (update :extraction-status #(or % (:extraction-status entry))))
             (assoc entry :claims (vec claims))))))

(defn- route-entry [{:keys [document claims unsupported-reasons extraction-status]}]
  (let [routed (routing/route-document document claims)]
    (assoc routed
           :source-version (:source-sha256 document)
           :parser-versions (->> claims (map :parser-version) (filter string?) set sort vec)
           :unsupported-reasons (->> unsupported-reasons distinct (sort-by name) vec)
           :extraction-status extraction-status)))

(defn- queue-entries [{:keys [source-sha256 source-version gaps unsupported-reasons]}]
  (into (mapv (fn [gap]
                (assoc gap :source-sha256 source-sha256 :source-version source-version))
              gaps)
        (map (fn [reason]
               {:source-sha256 source-sha256 :source-version source-version
                :status :unsupported-document :reason reason})
             unsupported-reasons)))

(defn replay-batch
  "Route retained {:document inventory :claims [...]} entries with no I/O.

   Repeated discovery of identical source bytes and inventory merges claims;
   changed bytes remain a separate source version. Conflicting inventories for
   the same bytes fail closed. Coverage counts known positions separately from
   unexamined sections whose position count is unknown. Request/cache metrics
   are zero because replay observes neither retrieval nor archive lookup."
  [entries]
  (let [entries (vec entries)
        merged (reduce merge-entry {} entries)
        documents (->> merged (sort-by key) (mapv (comp route-entry val)))
        exception-queue (into [] (mapcat queue-entries) documents)
        known-positions (reduce + (map (fn [document]
                                         (+ (count (:routed document))
                                            (count (filter :position-id (:gaps document)))))
                                       documents))
        routed-count (reduce + (map (comp count :routed) documents))
        unexamined-sections (count (filter :section-id exception-queue))]
    {:documents documents
     :exception-queue exception-queue
     :metrics {:requests 0
               :cache-reuses 0
               :retained-source-reuses (count documents)
               :duplicate-inputs (- (count entries) (count documents))
               :coverage {:known-positions known-positions
                          :routed routed-count
                          :gaps (- known-positions routed-count)
                          :unexamined-sections unexamined-sections}
               :exceptions (count exception-queue)
               :rejected-claims (reduce + (map (comp count :rejected-claims) documents))}}))

(defn replay-registered-batch
  "Replay retained extractor inputs through registered, source-bound adapters.
   Each entry has :document and :retained-input. Unsupported adapters create
   explicit source-level exceptions; they never manufacture coverage claims."
  [entries]
  (replay-batch
   (mapv (fn [{:keys [document retained-input]}]
           (let [{:keys [claims unsupported-reasons extraction]}
                 (adapters/claims-for-document document retained-input)]
             {:document document :claims claims
              :unsupported-reasons unsupported-reasons
              :extraction-status (:status extraction)}))
         entries)))
