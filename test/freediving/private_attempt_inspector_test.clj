(ns freediving.private-attempt-inspector-test
  (:require [clojure.test :refer [deftest is run-tests]]
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
                  :comparable-category {:group :women :citation {:synthetic "category"}}
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

(defn -main [& _]
  (let [result (run-tests 'freediving.private-attempt-inspector-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
