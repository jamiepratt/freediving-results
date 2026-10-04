(ns freediving.retained-aida-apply-test
  (:require [clojure.test :refer [deftest is run-tests use-fixtures]]
            [freediving.athlete-identity :as identity]
            [freediving.observations :as observations]
            [freediving.observations-test :as fixture]
            [freediving.retained-aida-apply :as apply-route]
            [freediving.reviews :as reviews]))

(def admin (System/getenv "FREEDIVING_TEST_ADMIN_URL"))
(def app (System/getenv "FREEDIVING_TEST_URL"))
(def reviewer (System/getenv "FREEDIVING_TEST_REVIEW_URL"))

(use-fixtures :each
  (fn [f]
    (fixture/sql! admin "DROP SCHEMA IF EXISTS freediving CASCADE")
    (observations/migrate! admin "observations_app")
    (reviews/migrate! admin "observations_app" "reviews_owner")
    (f)))

(defn- cohort []
  (let [snapshot (apply str (repeat 64 "d"))
        profile "123e4567-e89b-12d3-a456-426614174000"
        person {:scope "AIDA" :id profile :id_kind "person"
                :href (str "/Athletes/Profile-" profile)}
        rows (mapv (fn [digit]
                     (let [record (apply str (repeat 64 digit))
                           ref {:kind "source-derived" :snapshot_sha256 snapshot
                                :snapshot_record_id record :source_name "aida"
                                :source_sha256 (apply str (repeat 64 "a"))
                                :packet_sha256 (apply str (repeat 64 "b"))
                                :observation_version (apply str (repeat 64 "c"))
                                :adapter_version "aida-snapshot-observation/3"
                                :citation {:row digit} :publisher_person person}]
                       {:observation-id (str "source-observation:" record)
                        :source-name "Synthetic Diver" :parse-status :parsed
                        :citation ref :source-observation-ref ref
                        :publisher-scope "AIDA" :publisher-athlete-id profile
                        :publisher-id-kind :person})) ["1" "2" "3"])
        refs (into {} (map (juxt :observation-id :citation) rows))
        pair (fn [a b] [(:observation-id (rows a)) (:observation-id (rows b))])
        event (fn [id a b]
                (let [ids (pair a b)]
                  {:id id :action :accept :actor-kind :automatic :pair ids
                   :rule-version identity/rule-version
                   :source-binding {:snapshot-sha256 snapshot :refs (select-keys refs ids)}}))]
    {:schema "retained-aida-cohort/v1"
     :registration {:snapshot-sha256 snapshot :rows rows :verified-refs refs}
     :events [(event "aida-edge-1" 0 1) (event "aida-edge-2" 0 2)]}))

(deftest retained-aida-canonical-apply-reverses-and-preserves-correction
  (let [input (cohort)
        first-pass (apply-route/apply-cohort! reviewer app input)]
    (is (= 2 (:identity-revision first-pass)))
    (is (= 0 (:human-correction-revision first-pass)))
    (is (= 1 (:accepted-group-count first-pass)))
    (is (= 3 (:provisional-record-count first-pass)))
    (let [split (apply-route/reverse-source-event! reviewer "aida-edge-1" "owner correction")]
      (is (= 3 (:identity-revision split)))
      (is (= 3 (:human-correction-revision split)))
      (is (= 1 (:accepted-group-count split)))
      (is (= 1 (:human-negative-pair-count split)))
      (let [[a b] (:pair (first (:events input)))]
        (is (not= (get-in (identity/private-projection reviewer) [:athletes a :group-id])
                  (get-in (identity/private-projection reviewer) [:athletes b :group-id]))))
      (is (= split (apply-route/apply-cohort! reviewer app input)))
      (is (= 3 (count (identity/private-history reviewer))))
      (is (= (:groups (identity/private-projection reviewer))
             (:groups (identity/rebuild-private-canonical-view! reviewer)))))))

(defn -main [& _]
  (let [result (run-tests 'freediving.retained-aida-apply-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
