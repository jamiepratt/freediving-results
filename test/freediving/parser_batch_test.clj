(ns freediving.parser-batch-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [freediving.parser-batch :as batch]))

(def hash-a (apply str (repeat 64 "a")))
(def hash-b (apply str (repeat 64 "b")))

(defn document [sha]
  {:source-sha256 sha :format :html
   :positions [{:id "row-1" :citation "https://example.invalid/results#row-1"}
               {:id "row-2" :citation "https://example.invalid/results#row-2"}
               {:id "row-3" :citation "https://example.invalid/results#row-3" :examined? false}]
   :sections [{:id "other-table" :citation "https://example.invalid/results#other-table"
               :examined? false}]})

(defn claim [sha parser positions]
  {:parser-id parser :parser-version "1" :match-reason "retained table signature"
   :source-restriction {:sha256s #{sha} :formats #{:html}}
   :supported-positions positions :claimed-positions positions})

(deftest retained-batch-replay-is-idempotent-and-versioned
  (let [first-entry {:document (document hash-a)
                     :claims [(claim hash-a "alpha" #{"row-1"})
                              (claim hash-a "beta" #{"row-1" "row-2"})]}
        revised-entry {:document (document hash-b)
                       :claims [(claim hash-b "alpha" #{"row-1"})]}
        result (batch/replay-batch [first-entry first-entry revised-entry])
        again (batch/replay-batch [revised-entry first-entry])]
    (is (= (:documents result) (:documents again)))
    (is (= (:exception-queue result) (:exception-queue again)))
    (is (= [hash-a hash-b] (mapv :source-sha256 (:documents result))))
    (is (= ["row-2"] (mapv :position-id (get-in result [:documents 0 :routed]))))
    (is (= ["beta"] (mapv (comp :parser-id :claim) (get-in result [:documents 0 :routed]))))
    (is (= ["1"] (mapv (comp :parser-version :claim) (get-in result [:documents 0 :routed]))))
    (is (= [[:ambiguous "row-1"] [:unexamined "row-3"] [:unexamined "other-table"]]
           (mapv (juxt :status #(or (:position-id %) (:section-id %)))
                 (get-in result [:documents 0 :gaps]))))
    (is (= #{"alpha" "beta"}
           (set (map :parser-id (get-in result [:documents 0 :gaps 0 :contenders])))))
    (is (= 0 (get-in result [:metrics :requests])))
    (is (= 0 (get-in result [:metrics :cache-reuses])))
    (is (= 2 (get-in result [:metrics :retained-source-reuses])))
    (is (= 1 (get-in result [:metrics :duplicate-inputs])))
    (is (= {:known-positions 6 :routed 2 :gaps 4 :unexamined-sections 2}
           (get-in result [:metrics :coverage])))
    (is (= 6 (get-in result [:metrics :exceptions])))
    (is (= [hash-a hash-a hash-a hash-b hash-b hash-b]
           (mapv :source-sha256 (:exception-queue result))))
    (is (= "https://example.invalid/results#row-1"
           (get-in result [:exception-queue 0 :citation])))))

(deftest conflicting-inventories-for-same-source-are-rejected
  (let [entry {:document (document hash-a) :claims []}]
    (is (= :conflicting-source-inventory
           (try (batch/replay-batch [entry (assoc-in entry [:document :positions 0 :citation] "different")])
                (catch clojure.lang.ExceptionInfo error
                  (:reason (ex-data error))))))))

(deftest changed-parser-claims-for-same-source-are-preserved
  (let [entry {:document (document hash-a) :claims [(claim hash-a "alpha" #{"row-1"})]}
        changed {:document (document hash-a) :claims [(assoc (claim hash-a "beta" #{"row-2"})
                                                             :parser-version "2")]}
        result (batch/replay-batch [entry changed])]
    (is (= 1 (count (:documents result))))
    (is (= ["1" "2"] (mapv (comp :parser-version :claim)
                           (get-in result [:documents 0 :routed]))))
    (is (= #{"1" "2"} (set (get-in result [:documents 0 :parser-versions]))))))

(deftest false-positive-claim-is-reported-without-covering-positions
  (let [wrong (assoc-in (claim hash-a "wrong" #{"row-1"})
                        [:source-restriction :sha256s] #{hash-b})
        result (batch/replay-batch [{:document (document hash-a) :claims [wrong]}])]
    (is (empty? (get-in result [:documents 0 :routed])))
    (is (= :source-mismatch (get-in result [:documents 0 :rejected-claims 0 :reason])))
    (is (= 1 (get-in result [:metrics :rejected-claims])))
    (is (= 3 (get-in result [:metrics :coverage :gaps])))
    (is (= 4 (get-in result [:metrics :exceptions])))))

(deftest unsupported-registered-format-stays-in-exception-queue
  (let [json (assoc (document hash-a) :format :json)
        result (batch/replay-registered-batch [{:document json :retained-input {}}])]
    (is (empty? (get-in result [:documents 0 :routed])))
    (is (= [:no-checked-json-replay-bridge]
           (get-in result [:documents 0 :unsupported-reasons])))
    (is (= {:source-sha256 hash-a :source-version hash-a
            :status :unsupported-document :reason :no-checked-json-replay-bridge}
           (last (:exception-queue result))))
    (is (= 5 (get-in result [:metrics :exceptions])))
    (is (= 0 (get-in result [:metrics :requests])))))

(defn -main [& _]
  (let [result (run-tests 'freediving.parser-batch-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
