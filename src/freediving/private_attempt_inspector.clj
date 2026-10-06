(ns freediving.private-attempt-inspector
  "Authenticated callers only. Fresh pinned sporting evidence is independent of parsing."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [freediving.attempt-view-adapter :as adapter]
            [freediving.attempt-comparison :as attempts]
            [freediving.comparison-score :as scores]
            [freediving.peer-scope :as peers]))

(defn- sha256 [bytes]
  (format "%064x" (java.math.BigInteger. 1 (.digest (java.security.MessageDigest/getInstance "SHA-256") bytes))))

(def ^:private citation-gates
  #{:review :source-authority :finality :sanction :final :attempt-relationship :publication :category})

(defn- exact-evidence [row authority]
  (when (= :current (:status authority))
    (let [matches (filter #(and (= (:reference row) (:reference %))
                                (= (get-in row [:candidate :coordinates]) (:coordinates %)))
                          (:rows authority))
          claim (first matches)]
      (when (and (seq (get-in row [:candidate :coordinates]))
                 (= 1 (count matches))
                 (every? #(and (map? (get-in claim [:citations %])) (seq (get-in claim [:citations %]))) citation-gates))
        claim))))

(defn- bind-row [row authority]
  (let [claim (exact-evidence row authority)]
    (-> row
        (dissoc :rank :comparison-rank :discipline-rank :comparison-score :rank-descriptor)
        (update :source merge {:authority :unknown :finality :unknown :sanction :unknown}
                (select-keys (:source claim) [:authority :finality :sanction]))
        (assoc :evidence (or (:evidence claim)
                             {:review :unknown :outcome :unknown :attempt-relationship :unknown
                              :source-conflict :unresolved :final {:value nil :unit nil :basis :unknown}})
               :publication (or (:publication claim) :unknown)
               :sporting-claim claim))))

(defn- sporting-row [row]
  (let [claim (:sporting-claim row)
        final (get-in row [:evidence :final])]
    {:id (get-in row [:reference :candidate-id]) :discipline :dynamic
     :selected-source-conflict? (= :selected-provisional (get-in row [:evidence :source-conflict]))
     :review (if (= :ranked (:comparison-status row)) :verified :unknown)
     :attempt-relationship (get-in row [:evidence :attempt-relationship])
     :status (get-in row [:evidence :outcome])
     :citation (:reference row)
     :official-final (-> final (assoc :unit :m :basis (if (= :verified-post-penalty (:basis final))
                                                        :verified-publisher-post-penalty :unknown)))
     :source-authority (get-in row [:source :authority])
     :source-finality (get-in row [:source :finality]) :publication (:publication row)
     :comparable-category (:comparable-category claim)
     :represented-country (get-in row [:candidate :parsed :representation])
     :event (:event claim)}))

(defn- displayed-row [row peer]
  (let [parsed (get-in row [:candidate :parsed])
        ref (:reference row)]
    (cond-> (-> row
                (dissoc :sporting-claim :rank :hypothetical)
                (assoc :source_id (:source-sha256 ref) :version_id (:artifact-sha256 ref)
                       :row_coordinate (get-in row [:candidate :coordinates])
                       :raw_fields (get-in row [:candidate :raw]) :parsed_fields parsed
                       :federation (get-in row [:source :federation]) :environment "pool"
                       :discipline (:discipline parsed) :gender "women" :category (or (:category parsed) "unknown")
                       :age_class (if (and (= :verified (get-in row [:sporting-claim :comparable-category :age-equivalence]))
                                           (seq (get-in row [:sporting-claim :comparable-category :age-citation])))
                                    (name (get-in row [:sporting-claim :comparable-category :age-class] :unknown)) "unknown")
                       :year (subs (:event-date parsed) 0 4) :representation (:representation parsed)
                       :finality (name (get-in row [:source :finality]))
                       :review (name (get-in row [:evidence :review]))
                       :publication (name (:publication row))
                       :peer_status (:peer-status peer) :peer_reasons (:reasons peer)
                       :source_access {:available false :label "Original requires exact private source binding"}))
      (:rank peer) (assoc :rank (:rank peer) :rank_descriptor (:rank-descriptor peer)))))

(def ^:private filter-keys
  #{:federation :environment :discipline :year :gender :category :age_class :representation :review :publication})

(def ^:private peer-filter-keys #{:peer_anchor :geography :peer_token :sanction_scope :listing_filter})

(defn- peer-request [filters geography country]
  (cond-> {:geography geography
           :sanction-scope (keyword (or (:sanction_scope filters) "default"))
           :listing-filter (keyword (or (:listing_filter filters) "all"))}
    (not= :international geography) (assoc :anchor-represented-country country)))

(defn- row-matches? [row filters]
  (every? (fn [[k v]] (or (nil? v) (= "" v) (= "all" v) (= (str v) (str (get row k)))))
          (select-keys filters filter-keys)))

(defn- peer-link [id descriptor token filters]
  (str "/api/attempt-inspector?"
       (str/join "&" (for [[k v] (concat [[:peer_anchor id] [:geography (name (:geography descriptor))]
                                          [:peer_token token] [:sanction_scope (or (:sanction_scope filters) "default")]
                                          [:listing_filter (or (:listing_filter filters) "all")]]
                                         (sort-by key (select-keys filters filter-keys)))]
                       (str (name k) "=" (java.net.URLEncoder/encode (str v) "UTF-8"))))))

(defn inspect
  "Recompute every contract from retained rows and fresh exact authority, then filter.
   Peer links carry a digest of the current authority and exact ordered descriptor.
   Missing, changed or withdrawn authority clears every cached rank and peer link."
  [packet filters authority]
  (when-not (= "private-retained-attempts/v1" (:schema packet))
    (throw (ex-info "Unsupported retained packet" {})))
  (when (seq (remove (into (conj filter-keys :limit :offset) peer-filter-keys) (keys filters)))
    (throw (ex-info "Unsupported comparison filter" {})))
  (when (and (some #(contains? filters %) [:peer_anchor :geography :peer_token])
             (not (and (string? (:peer_anchor filters)) (seq (:peer_anchor filters))
                       (#{"national" "continental" "international"} (:geography filters))
                       (re-matches #"[a-f0-9]{64}" (or (:peer_token filters) "")))))
    (throw (ex-info "Incomplete exact peer link" {})))
  (let [bound (mapv #(bind-row % authority) (:observations packet))
        comparison (attempts/compare-attempts (:request packet) bound)
        scoped-rows (filterv #(row-matches? (displayed-row % nil) filters) (:rows comparison))
        sporting (mapv sporting-row scoped-rows)
        score (scores/compare-verified sporting)
        scored-by-id (into {} (map (juxt :id identity) (:rows score)))
        peer (peers/compare-peers (peer-request filters :international nil) sporting)
        by-id (into {} (map (juxt :id identity) (:rows peer)))
        scopes (memoize (fn [geography country]
                          (if (and (not= :international geography) (not (and (string? country) (seq country))))
                            (assoc peer :rows [] :coverage {:provided (count sporting) :denominator 0}
                                   :descriptor (assoc (:descriptor peer) :geography geography
                                                      :anchor-represented-country nil :anchor-sports-continent nil
                                                      :denominator 0 :peer-ids [] :provisional false))
                            (peers/compare-peers (peer-request filters geography country) sporting))))
        token-for (memoize (fn [descriptor] (sha256 (.getBytes (pr-str (walk/postwalk #(if (map? %) (into (sorted-map-by (fn [a b] (compare (pr-str a) (pr-str b)))) %) %)
                                                                                      [(:cutoff packet) authority descriptor sporting (select-keys filters filter-keys)])) "UTF-8"))))
        list-for (fn [id country geography]
                   (let [result (scopes geography (when-not (= :international geography) country))
                         row (first (filter #(= id (:id %)) (:rows result)))
                         descriptor (:descriptor result)
                         token (token-for descriptor)]
                     (cond-> (assoc descriptor :rank (:rank row) :status (:peer-status row)
                                    :provisional (:provisional descriptor))
                       (pos? (:denominator descriptor)) (assoc :href (peer-link id descriptor token filters)))))
        rows (mapv (fn [row]
                     (let [id (get-in row [:reference :candidate-id])
                           scored (get scored-by-id id)
                           claim (:sporting-claim row)
                           placing (:official-placing claim)]
                       (cond-> (assoc (displayed-row row (get by-id id))
                                      :official_placing (when (and (seq (:citation placing))
                                                                   (some? (:value placing))) placing)
                                      :official_final (:official-final scored)
                                      :source_category {:gender (get-in row [:candidate :parsed :gender])
                                                        :category (get-in row [:candidate :parsed :category])
                                                        :para_class (get-in claim [:comparable-category :para-class])}
                                      :category_equivalence (:comparable-category claim)
                                      :source_choice (:source-selection (:evidence row))
                                      :common_score (when (:comparison-score scored)
                                                      {:policy scores/policy :value (:comparison-score scored)})
                                      :comparison_lists (mapv #(list-for id (:represented-country scored) %)
                                                              [:national :continental :international]))
                         (and (:hypothetical row) (= :approved (:publication claim)))
                         (assoc :hypothetical (dissoc (:hypothetical row) :rank :eligible-peer-denominator)
                                :hypothetical_lists
                                (let [hrow (assoc scored :review :verified :status :finally-valid
                                                  :official-final (-> (get-in row [:evidence :hypothetical :final])
                                                                      (assoc :unit :m :basis :verified-publisher-post-penalty)))
                                      hscore (get-in (scores/compare-verified [hrow]) [:rows 0 :comparison-score])]
                                  (when hscore
                                    (mapv (fn [geography]
                                            (let [list (list-for id (:represented-country scored) geography)
                                                  hypothetic-peers (when (or (= :international geography)
                                                                             (seq (:represented-country scored)))
                                                                     (peers/compare-peers
                                                                      (peer-request filters geography (:represented-country scored))
                                                                      (mapv #(if (= id (:id %)) hrow %) sporting)))
                                                  eligible? (and (pos? (:denominator list))
                                                                 (some #(and (= id (:id %)) (:rank %)) (:rows hypothetic-peers)))
                                                  ids (:peer-ids list)]
                                              (cond-> (assoc (dissoc list :rank :href) :hypothetical true
                                                             :status (if eligible? :hypothetical :withheld)
                                                             :value hscore :policy scores/policy)
                                                eligible? (assoc :rank (inc (count (filter #(> (:comparison-score (get scored-by-id %)) hscore) ids)))
                                                                 :href (:href list)))))
                                          [:national :continental :international]))))
                         (:discipline-rank scored) (assoc :discipline_rank (:discipline-rank scored)))))
                   scoped-rows)
        anchor (first (filter #(= (:peer_anchor filters) (get-in % [:reference :candidate-id])) rows))
        selected-list (when anchor (first (filter #(= (:geography %) (keyword (:geography filters)))
                                                  (:comparison_lists anchor))))
        replay? (contains? filters :peer_anchor)
        fresh? (and selected-list (:href selected-list)
                    (= (:peer_token filters) (token-for (dissoc selected-list :rank :status :href))))
        peer-ids (when fresh? (:peer-ids selected-list))
        replay-ranks (when fresh? (into {} (map (juxt :id :rank)
                                                (:rows (scopes (keyword (:geography filters))
                                                               (when-not (= "international" (:geography filters))
                                                                 (:representation anchor)))))))
        selected (if replay?
                   (if fresh? (mapv (fn [id] (assoc (first (filter #(= id (get-in % [:reference :candidate-id])) rows))
                                                    :rank (get replay-ranks id)
                                                    :rank_descriptor (dissoc selected-list :rank :status :href))) peer-ids) [])
                   rows)
        limit (or (:limit filters) 50) offset (or (:offset filters) 0)]
    (when-not (and (integer? limit) (<= 1 limit 200) (integer? offset) (<= 0 offset 100000))
      (throw (ex-info "Invalid comparison page" {})))
    {:schema "private-attempt-inspector/v1" :ready true :cutoff (:cutoff packet)
     :authority {:status (name (or (:status authority) :absent))
                 :reason (or (:reason authority) "No current exact-row sporting authority")}
     :peer_view (when replay? {:status (if fresh? :current :stale) :descriptor (when fresh? selected-list)
                               :reason (when-not fresh? "Peer authority or exact membership changed; reload the comparison")})
     :counts {:source_positions (get-in packet [:census :source-positions])
              :retained_observation_versions (get-in packet [:census :versioned-selected-rows])
              :distinct_sporting_attempts nil
              :eligible_peer_cohorts (if (and (pos? (get-in peer [:coverage :denominator]))
                                              (= #{"CMAS" "AIDA"}
                                                 (set (map :federation (filter :rank rows))))) 1 0)}
     :readiness (:census packet) :coverage (assoc (:coverage comparison) :in-scope (count selected)
                                                  :ranked (count (filter :rank selected))
                                                  :withheld (count (remove :rank selected)))
     :score_coverage (:coverage score) :peer_coverage (if replay? {:provided (count selected) :denominator (if fresh? (:denominator selected-list) 0)} (:coverage peer))
     :gaps ["Distinct sporting attempts unknown; retained versions are not dives"
            "Supported private scope: 2026 pool DNF women; other depth, disciplines and categories have no eligible projection"
            "Final AIDA publication/revision and post-penalty semantics require genuine exact-row review"
            "Complete 103-row AIDA difference review and both federations eligible required for public peers"]
     :filter_options (into {} (for [k filter-keys] [k (vec (sort (set (map #(k (displayed-row % nil)) (:rows comparison)))))]))
     :pagination {:offset offset :limit limit :total (count selected)}
     :rows (vec (take limit (drop offset selected)))}))

(def ^:private older-versions
  {"CMAS" {:job-id "cff457431610c929a54970f77477aa5e44679a34cab1db74a7af4f3e6e54ee61"
           :artifact-sha256 "0b47b35617de991d54e20a0c573443e080c6301c5b5fbc32b5d542088c099fe2"}
   "AIDA" {:job-id "6e6f0e1bae9e3923576907eec3a9f9d98007bc7d35d406c5bb7632f52addbeae"
           :artifact-sha256 "b962e1c5fcadac01cdaf88c87749d6e2802fc5937269e52eda0efa8e2e90ac2e"}})

(defn prepare-packet
  "Verify the pinned corpus through load-exact-views before preserving both versions."
  [bundle cutoff]
  (let [loaded (adapter/load-exact-views bundle)
        index-bytes (java.nio.file.Files/readAllBytes (.toPath (io/file bundle "index.json")))
        _ (when-not (= "5bb94195278b43d29add19358e5d3699dc67bf20f43e40daf9733c7b10f6be30"
                       (sha256 index-bytes))
            (throw (ex-info "Retained index changed" {})))
        index (json/read-str (String. index-bytes "UTF-8"))
        read-pinned (fn [relative]
                      (let [bytes (java.nio.file.Files/readAllBytes (.toPath (io/file bundle "payload" relative)))
                            pin (get-in index ["files" relative])]
                        (when-not (and (= (get pin "bytes") (count bytes))
                                       (= (get pin "sha256") (sha256 bytes)))
                          (throw (ex-info "Retained file changed during packet preparation" {})))
                        (String. bytes "UTF-8")))
        indexes (mapv #(json/read-str % :key-fn keyword)
                      (str/split-lines (read-pinned "b16/review-index.jsonl")))
        by-key (into {} (map (fn [r] [[(:job-id r) (:ordinal r)] r]) indexes))
        artifacts (into {} (for [[kind version] older-versions]
                             [kind (edn/read-string (read-pinned (str "b16/archive/derived-objects/"
                                                                      (:artifact-sha256 version))))]))
        rows (mapv (fn [row]
                     (let [ref (:reference row)
                           old (get older-versions (get-in row [:source :federation]))
                           indexed (get by-key [(:job-id old) (:ordinal ref)])
                           artifact (get artifacts (get-in row [:source :federation]))
                           candidate (get (:candidates artifact) (:ordinal ref))]
                       (when-not (and indexed (= (:source-sha256 ref) (:source-sha256 indexed)))
                         (throw (ex-info "Retained version binding absent" {})))
                       (assoc row :retained-versions
                              [{:reference (merge ref old {:candidate-id (:candidate-id indexed)})
                                :candidate candidate}
                               {:reference ref :candidate (:candidate row)}]))) (:observations loaded))
        cmas (filter #(= "CMAS" (get-in % [:source :federation])) rows)
        aida (filter #(= "AIDA" (get-in % [:source :federation])) rows)]
    (-> loaded
        (assoc :schema "private-retained-attempts/v1" :cutoff cutoff :observations rows)
        (assoc-in [:census :selected-context]
                  {:cmas {:event "Novi Sad" :date "2026-06-11" :page 5
                          :ordinal-min (apply min (map #(get-in % [:reference :ordinal]) cmas))
                          :ordinal-max (apply max (map #(get-in % [:reference :ordinal]) cmas))}
                   :aida {:event "Budapest" :selected-date "2026-06-03"
                          :parsed-card-counts (frequencies (map #(get-in % [:candidate :parsed :card]) aida))
                          :parsed-ok-count (count (filter #(= "Ok" (get-in % [:candidate :parsed :remarks])) aida))
                          :parsed-dq-count (count (filter #(str/starts-with? (get-in % [:candidate :parsed :remarks] "") "Dq") aida))}}))))

(defn -main [& [mode bundle cutoff]]
  (if (= mode "prepare")
    (prn (prepare-packet bundle cutoff))
    (let [input (json/read-str (slurp *in*) :key-fn keyword)
          packet (edn/read-string (:packet_edn input))
          authority (when (:authority_edn input) (edn/read-string (:authority_edn input)))]
      (println (json/write-str (inspect packet (:filters input) authority))))))
