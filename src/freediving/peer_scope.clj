(ns freediving.peer-scope
  "Private, source-backed peer selection. Event listing and sanction evidence are independent."
  (:require [freediving.comparison-score :as score]
            [freediving.represented-geography :as geography]))

(defn- cited? [evidence]
  (and (map? evidence) (map? (:citation evidence)) (seq (:citation evidence))))

(defn- listing-class [evidence]
  (let [cited (filter cited? evidence)]
    (cond
      (some #(and (#{:CMAS :AIDA} (:publisher %))
                  (#{:archive :calendar} (:kind %))) cited) :international
      (some #(#{:national-archive :local-results} (:kind %)) cited) :national-local-only
      :else :unknown)))

(defn- sanction-class [evidence]
  (let [cited (filter cited? evidence)
        international? (some #(and (= :verified (:status %))
                                   (= :international (:level %))
                                   (#{:CMAS :AIDA} (:authority %))) cited)
        national? (some #(and (= :verified (:status %))
                              (= :national (:level %))
                              (= :national-federation (:authority %))) cited)
        unsanctioned? (some #(= :unsanctioned (:status %)) cited)]
    (cond
      (and international? (not unsanctioned?)) :international
      (and national? (not international?) (not unsanctioned?)) :national
      (and unsanctioned? (not international?) (not national?)) :unsanctioned
      :else :unknown)))

(defn- eligible? [row category]
  (and (= :ranked (:comparison-status row))
       (= :official-results (:source-authority row))
       (= :verified-final (:source-finality row))
       (= :approved (:publication row))
       (= category (get-in row [:comparable-category :group]))
       (cited? (:comparable-category row))
       (nil? (get-in row [:comparable-category :para-class]))))

(defn- competition-ranks [rows]
  (loop [ordered (sort-by (juxt (comp - :comparison-score) (comp str :id)) rows)
         index 1 previous nil previous-rank nil out {}]
    (if-let [row (first ordered)]
      (let [value (:comparison-score row)
            rank (if (= value previous) previous-rank index)]
        (recur (rest ordered) (inc index) value rank (assoc out (:id row) rank)))
      out)))

(defn- geography-match? [scope anchor-continent anchor-country row]
  (let [represented-country (:represented-country row)
        row-continent (geography/sports-continent represented-country)]
    (case scope
      :international true
      :national (and anchor-continent row-continent
                     (= anchor-country represented-country))
      :continental (and anchor-continent (= anchor-continent row-continent)))))

(defn compare-peers
  "Compare a supplied snapshot, not a corpus census. Defaults to verified CMAS/AIDA
   international sanction, all listing provenance, international geography and source-
   evidenced broad women category. Broader sanction scope never changes row eligibility.
   Returns descriptors rather than public URLs; callers must retain exact peer IDs."
  [request attempts]
  (let [{:keys [sanction-scope listing-filter category geography
                anchor-represented-country]}
        (merge {:sanction-scope :default :listing-filter :all
                :category :women :geography :international} request)]
    (when-not (and (#{:default :broad} sanction-scope)
                   (#{:all :international :national-local-only} listing-filter)
                   (#{:women :men} category)
                   (#{:international :national :continental} geography)
                   (if (= :international geography)
                     (not (contains? request :anchor-represented-country))
                     (and (contains? request :anchor-represented-country)
                          (string? anchor-represented-country)
                          (seq anchor-represented-country)))
                   (every? #{:sanction-scope :listing-filter :category :geography
                             :anchor-represented-country} (keys request)))
      (throw (ex-info "Unsupported peer scope" {:request request})))
    (let [anchor-continent (geography/sports-continent anchor-represented-country)
          base-descriptor (cond-> {:sanction-scope sanction-scope
                                   :listing-filter listing-filter
                                   :category category :geography geography
                                   :comparison-policy score/policy
                                   :represented-geography-policy geography/policy}
                            (not= :international geography)
                            (assoc :anchor-represented-country anchor-represented-country
                                   :anchor-sports-continent anchor-continent))
          classified (:rows (score/compare-verified attempts))
          rows (mapv (fn [row]
                       (let [listing (listing-class (get-in row [:event :listing-evidence]))
                             sanction (sanction-class (get-in row [:event :sanction-evidence]))]
                         (assoc (dissoc row :comparison-rank :discipline-rank :rank
                                        :rank-descriptor :peer-status
                                        :no-default-rank-reason :broader-scope-descriptor)
                                :event-classification {:listing listing :sanction sanction}))) classified)
          eligible (filter #(eligible? % category) rows)
          listed (filter #(or (= :all listing-filter)
                              (= listing-filter (get-in % [:event-classification :listing]))) eligible)
          geography-matched (filter #(geography-match? geography anchor-continent
                                                       anchor-represented-country %) listed)
          peers (filter #(or (= :broad sanction-scope)
                             (= :international (get-in % [:event-classification :sanction])))
                        geography-matched)
          ranks (competition-ranks peers)
          peer-list (->> peers (sort-by (juxt (comp - :comparison-score) (comp str :id)))
                         (mapv :id))
          descriptor (assoc base-descriptor :denominator (count peers) :peer-ids peer-list)
          broader-list (->> geography-matched (sort-by (juxt (comp - :comparison-score) (comp str :id)))
                            (mapv :id))
          broader-descriptor (assoc base-descriptor :sanction-scope :broad
                                    :denominator (count geography-matched) :peer-ids broader-list)
          output (mapv (fn [row]
                         (let [eligible-row? (eligible? row category)
                               listing-match? (or (= :all listing-filter)
                                                  (= listing-filter (get-in row [:event-classification :listing])))
                               geography-match (geography-match? geography anchor-continent
                                                                 anchor-represented-country row)
                               sanction-match? (or (= :broad sanction-scope)
                                                   (= :international (get-in row [:event-classification :sanction])))
                               peer-status (cond
                                             (not eligible-row?) :ineligible
                                             (not listing-match?) :listing-filter
                                             (not geography-match) :geography
                                             (not sanction-match?) :sanction-scope
                                             :else :ranked)]
                           (cond-> (assoc row :peer-status peer-status)
                             (= :ranked peer-status) (assoc :rank (get ranks (:id row))
                                                            :rank-descriptor descriptor)
                             (and eligible-row? listing-match? geography-match (not sanction-match?)
                                  (= :default sanction-scope))
                             (assoc :no-default-rank-reason :no-verified-international-sanction
                                    :broader-scope-descriptor broader-descriptor)))) rows)]
      {:descriptor descriptor :peer-list-descriptor descriptor
       :denominator-descriptor descriptor :rows output :peer-list peer-list
       :coverage {:provided (count attempts) :denominator (count peers)}})))
