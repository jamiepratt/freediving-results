(ns freediving.private-attempt-inspector-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [freediving.private-attempt-inspector :as inspector]
            [freediving.attempt-comparison-test :as synthetic]))

(def packet {:schema "private-retained-attempts/v1" :request synthetic/request
             :cutoff "2026-10-01T12:39:47Z" :census {:source-positions 2 :versioned-selected-rows 4}
             :observations [(assoc-in (synthetic/row "synthetic-cmas" "CMAS" 100M)
                                      [:candidate :coordinates] {:page 1 :row 1})
                            (assoc-in (synthetic/row "synthetic-aida" "AIDA" 90M)
                                      [:candidate :coordinates] {:table 1 :row 2})]})

(deftest absent-current-authority-clears-all-caller-ranks-and-preserves-private-fields
  (let [result (inspector/inspect packet {} nil)
        rows (:rows result)]
    (is (= 2 (get-in result [:coverage :withheld])))
    (is (= 0 (get-in result [:coverage :ranked])))
    (is (every? #(nil? (:rank %)) rows))
    (is (= "unknown" (:review (first rows))))
    (is (= 2 (get-in result [:counts :source_positions])))
    (is (nil? (get-in result [:counts :distinct_sporting_attempts])))
    (is (seq (:raw_fields (first rows))))))

(defn synthetic-authority []
  {:status :current
   :rows (mapv (fn [row]
                 {:reference (:reference row) :coordinates (get-in row [:candidate :coordinates])
                  :source (:source row) :evidence (:evidence row) :publication :approved
                  :citations (zipmap [:review :source-authority :finality :sanction :final
                                      :attempt-relationship :publication :category]
                                     (repeat {:synthetic "isolated test evidence only"}))
                  :comparable-category {:group :women :age-class :seniors :age-equivalence :verified
                                        :age-citation {:synthetic "age category equivalence"} :citation {:synthetic "category"}}
                  :event {:listing-evidence [{:publisher :CMAS :kind :archive :citation {:synthetic "listing"}}]
                          :sanction-evidence [{:status :verified :level :international :authority :CMAS
                                               :citation {:synthetic "sanction"}}]}})
               (:observations packet))})

(deftest isolated-synthetic-current-claims-rank-then-withdraw-immediately
  (let [current (synthetic-authority)
        ranked (inspector/inspect packet {} current)
        withdrawn (inspector/inspect packet {} (assoc current :rows []))
        stale (inspector/inspect packet {} (assoc current :status :stale))
        wrong-row (inspector/inspect packet {} (assoc-in current [:rows 0 :reference :ordinal] 1))]
    (is (= [1 2] (mapv :rank (:rows ranked))))
    (is (= 1 (get-in ranked [:counts :eligible_peer_cohorts])))
    (is (every? #(not (contains? % :rank)) (:rows withdrawn)))
    (is (every? #(not (contains? % :rank)) (:rows stale)))
    (is (= 1 (get-in wrong-row [:coverage :withheld])))
    (is (= ["synthetic-aida"] (mapv #(get-in % [:reference :candidate-id])
                                    (filter :rank (:rows wrong-row)))))))

(deftest filters-run-after-cohort-denominators-with-private-pagination
  (let [result (inspector/inspect packet {:federation "AIDA" :limit 1 :offset 0} nil)]
    (is (= 2 (get-in result [:coverage :provided])))
    (is (= 1 (get-in result [:pagination :total])))
    (is (= ["AIDA"] (mapv :federation (:rows result))))
    (is (thrown? Exception (inspector/inspect packet {:limit 201} nil)))))

(deftest exact-coordinate-mismatch-withholds-even-when-reference-matches
  (let [authority (assoc-in (synthetic-authority) [:rows 0 :coordinates :row] 99)
        result (inspector/inspect packet {} authority)]
    (is (= 1 (get-in result [:coverage :withheld])))
    (is (every? #(not (contains? % :rank))
                (filter #(= "CMAS" (:federation %)) (:rows result))))))

(deftest retained-corpus-preserves-both-versions-and-zero-real-authority
  (when-let [bundle (System/getenv "FREEDIVING_PRIVATE_CORPUS")]
    (let [retained (inspector/prepare-packet bundle "2026-10-01T12:39:47Z")
          result (inspector/inspect retained {:limit 200} nil)
          versions (mapcat :retained-versions (:observations retained))]
      (is (= 276 (count versions)))
      (is (= 276 (count (set (map :reference versions)))))
      (is (= {"WHITE" 90 "RED" 13} (get-in result [:readiness :selected-context :aida :parsed-card-counts])))
      (is (= [56 90] ((juxt :ordinal-min :ordinal-max) (get-in result [:readiness :selected-context :cmas]))))
      (is (every? #(and (seq (get-in % [:candidate :coordinates]))
                        (seq (get-in % [:candidate :raw])) (seq (get-in % [:candidate :parsed]))) versions))
      (is (= 138 (get-in result [:coverage :withheld])))
      (is (= 0 (get-in result [:coverage :ranked])))
      (is (every? #(not (contains? % :rank)) (:rows result))))))

(deftest mapped-lists-put-official-placing-before-exact-geographic-peers
  (let [authority (-> (synthetic-authority)
                      (assoc-in [:rows 0 :official-placing] {:value 7 :citation {:synthetic "event placing"}}))
        result (inspector/inspect packet {} authority)
        row (first (:rows result))
        lists (:comparison_lists row)]
    (is (= 7 (get-in row [:official_placing :value])))
    (is (= [:national :continental :international] (mapv :geography lists)))
    (is (= [2 2 2] (mapv :denominator lists)))
    (is (= [1 1 1] (mapv :rank lists)))
    (is (= ["synthetic-cmas" "synthetic-aida"] (:peer-ids (last lists))))
    (is (every? #(re-find #"^/api/attempt-inspector\?peer_anchor=" (:href %)) lists))
    (is (= :aida-baseline-v1 (get-in row [:common_score :policy])))))

(defn link-filters [href]
  (into {} (for [part (str/split (second (str/split href #"\?" 2)) #"&")
                 :let [[k v] (str/split part #"=" 2)]]
             [(keyword k) (java.net.URLDecoder/decode v "UTF-8")])))

(deftest exact-peer-link-replays-current-membership-and-withdraws-on-drift
  (let [authority (synthetic-authority)
        current (inspector/inspect packet {} authority)
        link (get-in current [:rows 0 :comparison_lists 2 :href])
        filters (link-filters link)
        replay (inspector/inspect packet filters authority)
        withdrawn (inspector/inspect packet filters (assoc authority :status :stale))
        tampered (inspector/inspect packet (assoc filters :peer_token (apply str (repeat 64 "0"))) authority)]
    (is (= :current (get-in replay [:peer_view :status])))
    (is (= ["synthetic-cmas" "synthetic-aida"] (mapv #(get-in % [:reference :candidate-id]) (:rows replay))))
    (is (= 2 (get-in replay [:peer_view :descriptor :denominator])))
    (is (= :stale (get-in withdrawn [:peer_view :status])))
    (is (empty? (:rows withdrawn)))
    (is (empty? (:rows tampered)))
    (is (thrown? Exception (inspector/inspect packet (assoc filters :geography "invented") authority)))))

(deftest unknown-geography-and-independent-listing-sanction-filters-remain-explicit
  (let [unknown-packet (-> packet (assoc-in [:observations 0 :candidate :parsed :representation] "AIN")
                           (assoc-in [:request :representations] #{"POL" "AIN"}))
        unknown (inspector/inspect unknown-packet {} (synthetic-authority))
        lists (get-in unknown [:rows 0 :comparison_lists])
        national (assoc-in (synthetic-authority) [:rows 0 :event :sanction-evidence]
                           [{:status :verified :level :national :authority :national-federation
                             :citation {:synthetic "national sanction"}}])
        default (inspector/inspect packet {} national)
        broad (inspector/inspect packet {:sanction_scope "broad" :listing_filter "international"} national)]
    (is (= [0 0 2] (mapv :denominator lists)))
    (is (nil? (:href (first lists))))
    (is (nil? (:rank (first (:rows default)))))
    (is (= 2 (get-in broad [:rows 0 :comparison_lists 2 :denominator])))
    (is (thrown? Exception (inspector/inspect packet {:listing_filter "invented"} national)))))

(deftest sporting-filters-narrow-rank-denominator-and-survive-exact-link-replay
  (let [authority (synthetic-authority)
        filtered (inspector/inspect packet {:federation "CMAS"} authority)
        list (get-in filtered [:rows 0 :comparison_lists 2])
        params (link-filters (:href list))
        replay (inspector/inspect packet params authority)]
    (is (= ["synthetic-cmas"] (:peer-ids list)))
    (is (= 1 (:denominator list)))
    (is (= "CMAS" (:federation params)))
    (is (= ["CMAS"] (mapv :federation (:rows replay))))
    (is (= :stale (get-in (inspector/inspect packet (assoc params :federation "AIDA") authority) [:peer_view :status])))
    (is (= 0 (get-in (inspector/inspect packet (assoc params :peer_token (apply str (repeat 64 "0"))) authority) [:coverage :ranked])))
    (is (empty? (:rows (inspector/inspect packet {:environment "depth"} authority))))))

(deftest mapped-disqualified-hypothetical-is-separate-from-main-lists
  (let [authority (-> (synthetic-authority)
                      (assoc-in [:rows 0 :evidence :outcome] :disqualified)
                      (assoc-in [:rows 0 :evidence :hypothetical]
                                {:final {:value 100M :unit "m" :basis :verified-source-achieved
                                         :decimal-places 0 :conversion :verified}
                                 :citation {:synthetic "reviewed DQ hypothetical"}}))
        result (inspector/inspect packet {} authority)
        dq (first (filter #(= "CMAS" (:federation %)) (:rows result)))]
    (is (= 1 (get-in result [:coverage :ranked])))
    (is (nil? (:rank dq)))
    (is (= [1 1 1] (mapv :denominator (:hypothetical_lists dq))))
    (is (= [1 1 1] (mapv :rank (:hypothetical_lists dq))))
    (is (every? :hypothetical (:hypothetical_lists dq)))
    (is (= ["synthetic-aida"] (:peer-ids (last (:comparison_lists dq)))))))

(deftest age-equivalence-and-para-class-stay-out-of-unproven-main-cohorts
  (let [unproven (update-in (synthetic-authority) [:rows 0 :comparable-category] dissoc :age-citation)
        para (assoc-in (synthetic-authority) [:rows 0 :comparable-category :para-class] :synthetic-para)]
    (is (= ["AIDA"] (mapv :federation (filter :rank (:rows (inspector/inspect packet {:age_class "seniors"} unproven))))))
    (is (= 2 (get-in (inspector/inspect packet {} unproven) [:coverage :ranked])))
    (is (= "unknown" (:category (first (filter #(= "AIDA" (:federation %)) (:rows (inspector/inspect packet {} unproven)))))))
    (is (= ["AIDA"] (mapv :federation (filter :rank (:rows (inspector/inspect packet {} para))))))))

(deftest hypothetical-position-obeys-the-same-membership-gates-as-valid-peers
  (let [dq-authority (-> (synthetic-authority)
                         (assoc-in [:rows 0 :evidence :outcome] :disqualified)
                         (assoc-in [:rows 0 :evidence :hypothetical]
                                   {:final {:value 100M :unit "m" :basis :verified-source-achieved
                                            :decimal-places 0 :conversion :verified}
                                    :citation {:synthetic "achieved"}}))
        national (assoc-in dq-authority [:rows 0 :event :sanction-evidence]
                           [{:status :verified :level :national :authority :national-federation
                             :citation {:synthetic "national"}}])
        unknown (assoc-in dq-authority [:rows 0 :event :sanction-evidence] [])
        para (assoc-in dq-authority [:rows 0 :comparable-category :para-class] :synthetic-para)
        no-peers (assoc-in dq-authority [:rows 1 :publication] :unknown)
        ain-packet (-> packet (assoc-in [:observations 0 :candidate :parsed :representation] "AIN")
                       (assoc-in [:request :representations] #{"POL" "AIN"}))
        dq-row (fn [p a filters] (first (filter #(= "CMAS" (:federation %)) (:rows (inspector/inspect p filters a)))))]
    (doseq [authority [national unknown para no-peers]]
      (is (every? #(nil? (:rank %)) (:hypothetical_lists (dq-row packet authority {})))))
    (is (= 1 (get-in (dq-row packet national {:sanction_scope "broad"}) [:hypothetical_lists 2 :rank])))
    (is (nil? (get-in (dq-row ain-packet dq-authority {}) [:hypothetical_lists 0 :rank])))
    (is (nil? (get-in (dq-row ain-packet dq-authority {}) [:hypothetical_lists 1 :rank])))
    (is (= 1 (get-in (dq-row ain-packet dq-authority {}) [:hypothetical_lists 2 :rank])))))

(deftest mapped-provisional-source-choice-marks-affected-peer-descriptors
  (let [reference (get-in packet [:observations 0 :reference])
        alternative (assoc reference :candidate-id "alternative" :ordinal 10)
        authority (-> (synthetic-authority)
                      (assoc-in [:rows 0 :evidence :source-conflict] :selected-provisional)
                      (assoc-in [:rows 0 :evidence :source-selection]
                                {:selected-reference reference :conflicting-references [alternative]
                                 :basis :source-authority :authority-citation {:synthetic "higher authority"}
                                 :selection-citation {:synthetic "selected exact source"}}))
        result (inspector/inspect packet {} authority)]
    (is (= 2 (get-in result [:coverage :ranked])))
    (is (every? :provisional (mapcat :comparison_lists (:rows result))))
    (is (= :source-authority (get-in result [:rows 0 :source_choice :basis])))))

(deftest national-peer-route-displays-national-rank-rather-than-global-rank
  (let [p (-> packet (assoc-in [:observations 1 :candidate :parsed :representation] "FRA")
              (assoc-in [:request :representations] #{"POL" "FRA"}))
        a (synthetic-authority)
        result (inspector/inspect p {} a)
        aida (first (filter #(= "AIDA" (:federation %)) (:rows result)))
        replay (inspector/inspect p (link-filters (get-in aida [:comparison_lists 0 :href])) a)]
    (is (= 2 (:rank aida)))
    (is (= 1 (get-in replay [:rows 0 :rank])))
    (is (= 1 (get-in replay [:rows 0 :rank_descriptor :denominator])))
    (is (= 1 (get-in replay [:coverage :ranked])))
    (is (= 1 (get-in replay [:coverage :in-scope])))
    (is (= 0 (get-in replay [:coverage :withheld])))
    (is (= 1 (get-in replay [:peer_coverage :denominator])))))

(defn -main [& _]
  (let [result (run-tests 'freediving.private-attempt-inspector-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
