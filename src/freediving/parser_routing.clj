(ns freediving.parser-routing
  "Deterministic routing of cited source positions. Claims are evidence about
   parser coverage, not authorization to import observations."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

(def supported-formats #{:pdf :html :json :image :workbook})

(defn- nonblank? [value]
  (and (string? value) (not (str/blank? value))))

(defn- valid-position? [{:keys [id citation]}]
  (and (nonblank? id) (nonblank? citation)))

(defn- validate-document! [{:keys [source-sha256 format positions sections]}]
  (when-not (and (string? source-sha256)
                 (re-matches #"[0-9a-f]{64}" source-sha256)
                 (contains? supported-formats format)
                 (vector? positions)
                 (every? valid-position? positions)
                 (or (nil? sections)
                     (and (vector? sections)
                          (every? #(and (valid-position? %)
                                        (false? (:examined? %))) sections)))
                 (let [ids (concat (map :id positions) (map :id sections))]
                   (= (count ids) (count (set ids)))))
    (throw (ex-info "Invalid source-position inventory" {:reason :invalid-document}))))

(defn- rejection-reason [document position-ids claim]
  (let [{:keys [parser-id parser-version match-reason source-restriction
                supported-positions claimed-positions]} claim]
    (cond
      (not (and (nonblank? parser-id) (nonblank? parser-version))) :missing-parser-version
      (not (nonblank? match-reason)) :missing-match-reason
      (not (and (set? (get source-restriction :sha256s))
                (seq (get source-restriction :sha256s))
                (set? (get source-restriction :formats))
                (seq (get source-restriction :formats)))) :missing-source-restriction
      (not (contains? (:sha256s source-restriction) (:source-sha256 document))) :source-mismatch
      (not (contains? (:formats source-restriction) (:format document))) :format-mismatch
      (not (and (set? supported-positions) (set? claimed-positions)
                (set/subset? claimed-positions supported-positions))) :invalid-coverage
      (not (set/subset? supported-positions position-ids)) :unknown-position
      (some (fn [position]
              (and (contains? claimed-positions (:id position))
                   (false? (:examined? position))))
            (:positions document)) :unexamined-position)))

(defn- canonical-value [value]
  (cond
    (map? value) [:map (->> value
                            (map (fn [[key item]] [(canonical-value key)
                                                   (canonical-value item)]))
                            (sort-by (comp pr-str first))
                            vec)]
    (set? value) [:set (->> value (map canonical-value) (sort-by pr-str) vec)]
    (sequential? value) [:sequence (mapv canonical-value value)]
    :else value))

(defn- claim-key [{:keys [parser-id parser-version] :as claim}]
  [(str parser-id) (str parser-version) (pr-str (canonical-value claim))])

(defn route-document
  "Route an inventoried document using explicit recognizer claims.

   Document: {:source-sha256 hex64 :format keyword :positions
              [{:id stable-id :citation exact-location :examined? true|false}]
              :sections [{:id stable-id :citation exact-location :examined? false}]}.
   Optional sections denote unexamined regions whose position count is unknown.
   Absence of :examined? means examined. Claim: {:parser-id string
   :parser-version string :match-reason string
   :source-restriction {:sha256s #{...} :formats #{...}}
   :supported-positions #{ids} :claimed-positions #{ids}}.
   A supported but unclaimed position is a gap. A claimed position with multiple
   contenders is ambiguous; no parser wins by input order. Rejected claims never
   influence coverage. Output preserves inventory order, sorts contenders by
   full EDN claim content and collapses exact duplicate claims."
  [document claims]
  (validate-document! document)
  (let [position-ids (set (map :id (:positions document)))
        classified (mapv (fn [claim]
                           {:claim claim
                            :reason (rejection-reason document position-ids claim)})
                         claims)
        accepted (->> classified (remove :reason) (map :claim)
                      (sort-by claim-key) distinct vec)
        rejected (->> classified
                      (filter :reason)
                      (map (fn [{:keys [claim reason]}]
                             {:claim claim :reason reason}))
                      (sort-by (comp claim-key :claim))
                      vec)
        covered (apply set/union #{} (map :supported-positions accepted))
        by-position (reduce (fn [result claim]
                              (reduce (fn [positions id]
                                        (update positions id (fnil conj []) claim))
                                      result (:claimed-positions claim)))
                            {} accepted)
        outcomes (mapv (fn [{:keys [id citation coordinates examined? ambiguous?]}]
                         (let [contenders (get by-position id [])
                               base (cond-> {:position-id id :citation citation}
                                      (some? coordinates) (assoc :coordinates coordinates))]
                           (cond
                             (false? examined?) (assoc base :status :unexamined)
                             ambiguous? (assoc base :status :ambiguous :reason :unresolved-source-reading)
                             (> (count contenders) 1)
                             (assoc base :status :ambiguous :contenders contenders)
                             (= (count contenders) 1)
                             (assoc base :status :routed :claim (first contenders))
                             (contains? covered id) (assoc base :status :unclaimed)
                             :else (assoc base :status :unsupported))))
                       (:positions document))]
    {:source-sha256 (:source-sha256 document)
     :format (:format document)
     :routed (vec (filter #(= :routed (:status %)) outcomes))
     :gaps (into (vec (remove #(= :routed (:status %)) outcomes))
                 (map (fn [{:keys [id citation]}]
                        {:section-id id :citation citation :status :unexamined})
                      (:sections document)))
     :rejected-claims rejected}))
