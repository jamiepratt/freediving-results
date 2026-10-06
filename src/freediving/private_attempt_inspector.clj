(ns freediving.private-attempt-inspector
  "Authenticated callers only. Fresh pinned sporting evidence is independent of parsing."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
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
                 (every? #(seq (get-in claim [:citations %])) citation-gates))
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
                (dissoc :sporting-claim :rank)
                (assoc :source_id (:source-sha256 ref) :version_id (:artifact-sha256 ref)
                       :row_coordinate (get-in row [:candidate :coordinates])
                       :raw_fields (get-in row [:candidate :raw]) :parsed_fields parsed
                       :federation (get-in row [:source :federation]) :environment "pool"
                       :discipline (:discipline parsed) :gender "women" :category "seniors"
                       :year (subs (:event-date parsed) 0 4) :representation (:representation parsed)
                       :finality (name (get-in row [:source :finality]))
                       :review (name (get-in row [:evidence :review]))
                       :publication (name (:publication row))
                       :peer_status (:peer-status peer) :peer_reasons (:reasons peer)
                       :source_access {:available false :label "Original requires exact private source binding"}))
      (:rank peer) (assoc :rank (:rank peer) :rank_descriptor (:rank-descriptor peer)))))

(def ^:private filter-keys
  #{:federation :environment :discipline :year :gender :category :representation :review :publication})

(defn inspect
  "Recompute every contract from retained rows and fresh exact authority, then filter.
   Missing or withdrawn claims clear source semantics and every cached rank."
  [packet filters authority]
  (when-not (= "private-retained-attempts/v1" (:schema packet))
    (throw (ex-info "Unsupported retained packet" {})))
  (when (seq (remove (conj filter-keys :limit :offset) (keys filters)))
    (throw (ex-info "Unsupported comparison filter" {})))
  (let [bound (mapv #(bind-row % authority) (:observations packet))
        comparison (attempts/compare-attempts (:request packet) bound)
        sporting (mapv sporting-row (:rows comparison))
        score (scores/compare-verified sporting)
        peer (peers/compare-peers {} sporting)
        by-id (into {} (map (juxt :id identity) (:rows peer)))
        rows (mapv #(displayed-row % (get by-id (get-in % [:reference :candidate-id]))) (:rows comparison))
        selected (filterv (fn [row] (every? (fn [[k v]] (or (nil? v) (= "" v) (= "all" v)
                                                            (= (str v) (str (get row k)))))
                                            (select-keys filters filter-keys))) rows)
        limit (or (:limit filters) 50) offset (or (:offset filters) 0)]
    (when-not (and (integer? limit) (<= 1 limit 200) (integer? offset) (<= 0 offset 100000))
      (throw (ex-info "Invalid comparison page" {})))
    {:schema "private-attempt-inspector/v1" :ready true :cutoff (:cutoff packet)
     :authority {:status (name (or (:status authority) :absent))
                 :reason (or (:reason authority) "No current exact-row sporting authority")}
     :counts {:source_positions (get-in packet [:census :source-positions])
              :retained_observation_versions (get-in packet [:census :versioned-selected-rows])
              :distinct_sporting_attempts nil
              :eligible_peer_cohorts (if (and (pos? (get-in peer [:coverage :denominator]))
                                              (= #{"CMAS" "AIDA"}
                                                 (set (map :federation (filter :rank rows))))) 1 0)}
     :readiness (:census packet) :coverage (:coverage comparison)
     :score_coverage (:coverage score) :peer_coverage (:coverage peer)
     :gaps ["Distinct sporting attempts unknown; retained versions are not dives"
            "Final AIDA publication/revision and post-penalty semantics require genuine exact-row review"
            "Complete 103-row AIDA difference review and both federations eligible required for public peers"]
     :filter_options (into {} (for [k filter-keys] [k (vec (sort (set (map k rows))))]))
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
