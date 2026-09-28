(ns freediving.peer-scope-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [freediving.peer-scope :as peer]))

(defn attempt [id value listing sanction]
  {:id id :discipline :dynamic :review :verified :attempt-relationship :distinct
   :status :finally-valid :citation {:source "synthetic" :row id}
   :official-final {:value value :unit :m :decimal-places 0
                    :basis :verified-publisher-post-penalty :conversion :verified}
   :source-authority :official-results :source-finality :verified-final
   :publication :approved :publisher-placing (str "place " id)
   :publisher-category "Women" :comparable-category {:group :women :citation {:row id}}
   :represented-country "POL" :event-country "FRA"
   :event {:listing-evidence listing :sanction-evidence sanction}})

(def international-listing [{:publisher :CMAS :kind :archive :citation {:url "archive"}}])
(def local-listing [{:publisher :local-organizer :kind :local-results :citation {:url "local"}}])
(def international-sanction [{:authority :CMAS :level :international :status :verified
                             :citation {:document "designation"}}])
(def national-sanction [{:authority :national-federation :level :national :status :verified
                        :citation {:document "permit"}}])
(def unsanctioned [{:status :unsanctioned :citation {:document "explicit notice"}}])

(defn by-id [result id]
  (first (filter #(= id (:id %)) (:rows result))))

(deftest listing-does-not-prove-sanction
  (let [listed (attempt "listed" 100M international-listing [])
        local (attempt "local" 90M local-listing international-sanction)
        result (peer/compare-peers {} [listed local])]
    (is (= :unknown (get-in (by-id result "listed") [:event-classification :sanction])))
    (is (= :international (get-in (by-id result "listed") [:event-classification :listing])))
    (is (= :no-verified-international-sanction (:no-default-rank-reason (by-id result "listed"))))
    (is (= :broad (get-in (by-id result "listed") [:broader-scope-descriptor :sanction-scope])))
    (is (= :ranked (:peer-status (by-id result "local"))))
    (is (= 1 (:rank (by-id result "local"))))
    (is (= (:descriptor result) (:rank-descriptor (by-id result "local"))))
    (is (= 1 (get-in result [:coverage :denominator])))))

(deftest broad-scope-keeps-sanction-classes-and-encodes-filters
  (let [rows [(attempt "international" 100M international-listing international-sanction)
              (attempt "national" 90M local-listing national-sanction)
              (attempt "unknown" 80M local-listing [])
              (attempt "unsanctioned" 70M local-listing unsanctioned)]
        result (peer/compare-peers {:sanction-scope :broad :listing-filter :national-local-only} rows)]
    (is (= {:sanction-scope :broad :listing-filter :national-local-only}
           (select-keys (:descriptor result) [:sanction-scope :listing-filter])))
    (is (= ["national" "unknown" "unsanctioned"] (:peer-list result)))
    (is (= 3 (get-in result [:coverage :denominator])))
    (is (= [:national :unknown :unsanctioned]
           (mapv #(get-in (by-id result %) [:event-classification :sanction])
                 ["national" "unknown" "unsanctioned"])))
    (is (= :listing-filter (:peer-status (by-id result "international"))))))

(deftest wider-scope-cannot-bypass-eligibility
  (let [base (attempt "base" 100M local-listing national-sanction)
        rows [base (assoc base :id "unreviewed" :review :unreviewed)
              (assoc base :id "unpublished" :publication :pending)
              (assoc base :id "mirror" :source-authority :mirror)
              (assoc base :id "duplicate" :attempt-relationship :unknown)
              (assoc base :id "dq" :status :disqualified)]
        result (peer/compare-peers {:sanction-scope :broad} rows)]
    (is (= ["base"] (:peer-list result)))
    (is (= 1 (get-in result [:coverage :denominator])))
    (is (every? #(nil? (:rank (by-id result %)))
                ["unreviewed" "unpublished" "mirror" "duplicate" "dq"]))
    (is (= :ineligible (:peer-status (by-id result "unpublished"))))
    (is (nil? (:broader-scope-descriptor (by-id result "unpublished"))))
    (is (= "place base" (:publisher-placing (by-id result "base"))))
    (is (= "Women" (:publisher-category (by-id result "base"))))))

(deftest category-and-geography-fail-closed
  (let [base (attempt "base" 100M international-listing international-sanction)
        unknown-category (dissoc base :comparable-category)
        para (assoc-in base [:comparable-category :para-class] :p1)
        wrong-group (assoc-in base [:comparable-category :group] :men)]
    (is (= 0 (get-in (peer/compare-peers {} [unknown-category]) [:coverage :denominator])))
    (is (= 0 (get-in (peer/compare-peers {} [para]) [:coverage :denominator])))
    (is (= 0 (get-in (peer/compare-peers {} [wrong-group]) [:coverage :denominator])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (peer/compare-peers {:geography :national} [base])))))

(deftest international-listing-wins-over-local-and-needs-citation
  (let [both (attempt "both" 100M (into international-listing local-listing) international-sanction)
        uncited (attempt "uncited" 90M [{:publisher :AIDA :kind :archive}] [])
        result (peer/compare-peers {:listing-filter :international} [both uncited])]
    (is (= :international (get-in (by-id result "both") [:event-classification :listing])))
    (is (= :unknown (get-in (by-id result "uncited") [:event-classification :listing])))
    (is (= ["both"] (:peer-list result)))))

(defn -main [& _]
  (let [result (run-tests 'freediving.peer-scope-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
