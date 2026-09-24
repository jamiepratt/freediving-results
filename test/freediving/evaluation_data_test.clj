(ns freediving.evaluation-data-test
  (:require [clojure.test :refer [deftest is]]
            [freediving.evaluation-data :as data]))

(defn sample-case [id split]
  {:case-id id :split split :input {:left {:name id} :right {:name id}}
   :source-family-ids [id] :person-group-ids [id]
   :evidence [{:evidence-id id :source-sha256 (apply str (repeat 64 (first id)))
               :artifact-sha256 (apply str (repeat 64 (first id)))
               :observation-id id :page 1 :lines [1 2]}]
   :label {:outcome :match :provenance :synthetic :fixture-id id}})
(defn dataset [cases]
  {:schema-version 1 :dataset-id "synthetic-v1" :rubric-version "identity-v1"
   :grouping {:attested-by "fixture-author" :method "explicit person and source families"
              :limitations "Unknown identities and undiscovered copies remain possible."}
   :cases cases})

(deftest validates-evidence-and-prevents-held-out-leakage
  (let [a (sample-case "a" :development) b (sample-case "b" :held-out)]
    (is (= (dataset [a b]) (data/validate-dataset! (dataset [a b]))))
    (doseq [bad [(dataset [a (assoc b :person-group-ids ["a"])])
                 (dataset [a (assoc b :source-family-ids ["a"])])
                 (dataset [a (assoc b :evidence (:evidence a))])
                 (dataset [a a])
                 (dataset [(assoc a :evidence [])])
                 (dataset [(assoc a :label {:outcome :match :provenance :owner})])]]
      (is (thrown? clojure.lang.ExceptionInfo (data/validate-dataset! bad))))))

(defn result [id outcome]
  {:case-id id :outcome outcome :latency-ms 10 :cost {:status :unknown}})

(deftest metrics-keep-label-provenance-denominators-and-unknowns
  (let [cases [(sample-case "a" :held-out)
               (assoc-in (sample-case "b" :held-out) [:label :outcome] :no-match)
               (assoc (sample-case "c" :held-out) :label nil)
               (assoc (sample-case "d" :held-out) :label
                      {:outcome :match :provenance :owner :reviewer "test reviewer"
                       :review-id "test-only-owner-shape" :evidence-ids ["d"]
                       :reviewed-at "2026-09-23T00:00:00Z"
                       :review-artifact-sha256 (apply str (repeat 64 "d"))})]
        predictions [(result "a" :no-match) (result "b" :match)
                     (result "c" :abstain) (result "d" :error)]
        m (data/metrics cases predictions)]
    (is (= {:count 1 :denominator 1 :rate 1} (get-in m [:synthetic :false-merges])))
    (is (= {:count 1 :denominator 1 :rate 1} (get-in m [:synthetic :missed-matches])))
    (is (= {:count 0 :denominator 1 :rate 0} (get-in m [:asserted :missed-matches])))
    (is (= 1 (get-in m [:asserted :errors])))
    (is (= 0 (get-in m [:owner :case-count])))
    (is (= 1 (get-in m [:unlabeled :abstentions])))
    (is (= 2 (get-in m [:overall :review-volume])))
    (is (= {:metered-count 0 :unknown-count 4 :totals-by-currency {}} (:cost m)))
    (is (= 4 (get-in m [:latency :measured-count])))
    (doseq [bad [(pop predictions) (conj predictions (first predictions))
                 (assoc-in predictions [0 :outcome] :maybe)
                 (assoc-in predictions [0 :cost] {:status :metered :amount -1 :currency "USD"})]]
      (is (thrown? clojure.lang.ExceptionInfo (data/metrics cases bad))))))

(deftest dataset-rejects-conflicting-evidence-and-nonportable-inputs
  (let [a (sample-case "a" :development)
        b (sample-case "b" :development)]
    (doseq [bad [(dataset [(assoc-in a [:input :bad] (Object.))])
                 (dataset [a (assoc-in b [:evidence 0 :evidence-id] "a")])
                 (dataset [(assoc a :label {:outcome :match :provenance :owner
                                            :review-id "r" :reviewer "r" :evidence-ids ["a"]})])]]
      (is (thrown? clojure.lang.ExceptionInfo (data/validate-dataset! bad))))
    (binding [*print-length* 1 *print-level* 1]
      (is (thrown? clojure.lang.ExceptionInfo
                   (data/validate-dataset! (dataset [(assoc-in a [:input :huge] (apply str (repeat 10000001 "x")))])))))))

(deftest grouping-chains-and-bounds
  (let [a (sample-case "a" :development)
        b (assoc (sample-case "b" :development) :person-group-ids ["a" "b"])
        c (assoc (sample-case "c" :held-out) :person-group-ids ["b"])]
    (is (thrown? clojure.lang.ExceptionInfo (data/validate-dataset! (dataset [a b c]))))
    (is (thrown? clojure.lang.ExceptionInfo (data/validate-dataset! (dataset []))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (data/validate-dataset! (dataset (vec (repeat 10001 a))))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (data/validate-dataset! (dataset [(assoc-in a [:evidence 0 :lines] [2 1])]))))))

(deftest metrics-preserve-unknown-latency-and-partial-metering
  (let [cases [(assoc (sample-case "a" :held-out) :label nil)
               (sample-case "b" :held-out)]
        m (data/metrics cases [(assoc (result "a" :match) :latency-ms nil)
                               (assoc (result "b" :abstain) :cost
                                      {:status :metered :amount 0.002M :currency "USD"})])]
    (is (nil? (get-in m [:unlabeled :false-merges :rate])))
    (is (= 0 (get-in m [:synthetic :decisive-count])))
    (is (= {:metered-count 1 :unknown-count 1 :totals-by-currency {"USD" 0.002M}} (:cost m)))
    (is (= {:measured-count 1 :unknown-count 1 :total-ms 10 :mean-ms 10} (:latency m)))
    (is (= 0 (get-in (data/metrics [] []) [:overall :case-count])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (data/metrics cases [(result "a" :match) (assoc (result "b" :match) :latency-ms ##NaN)])))))

(deftest verified-metrics-require-authoritative-database-not-file-flags
  (is (thrown? Exception
               (data/metrics-verified
                "jdbc:untrusted-file-flags"
                {:verified? true :label-source :verified-owner-review
                 :dataset (dataset [(sample-case "a" :held-out)])}
                [(result "a" :match)]))))

(deftest no-incurred-cost-requires-an-undispatched-result
  (let [cases [(sample-case "a" :held-out)]
        skipped {:case-id "a" :outcome :error :error :comparator-halted
                 :dispatch-status :not-dispatched :halted-by-case-id "prior"
                 :attempts [] :latency-ms nil :cost {:status :not-incurred}}]
    (is (= 1 (get-in (data/metrics cases [skipped]) [:cost :not-incurred-count])))
    (doseq [bad [(assoc skipped :attempts [{}]) (assoc skipped :latency-ms 1)
                 (dissoc skipped :dispatch-status) (assoc skipped :outcome :match)
                 (assoc skipped :cost {:status :not-incurred :amount 0})]]
      (is (thrown? clojure.lang.ExceptionInfo (data/metrics cases [bad]))))))
