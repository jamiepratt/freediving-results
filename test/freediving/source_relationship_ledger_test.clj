(ns freediving.source-relationship-ledger-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [freediving.ffessm-2026-final-categories :as ffessm]
            [freediving.observations :as observations]
            [freediving.source-relationship-ledger :as ledger]))

(defn candidate [line text parsed]
  {:coordinates {:page 1 :line line}
   :source-lines [{:page 1 :line line :text text}]
   :raw {:line text} :parsed parsed})

(defn inspected [job version rows]
  {:artifact {:job-id job :source-sha256 (apply str (repeat 64 "a"))
              :parser-version version :candidates rows}
   :observations (mapv (fn [ordinal row]
                         {:ordinal ordinal :kind "result-row" :payload row})
                       (range) rows)})

(deftest corpus-ledger-retains-replay-versions-with-exact-source-references
  (let [parsed {:source-name "A Diver" :discipline "FIM" :final-performance 70M}
        row (candidate 9 "printed row" parsed)
        jobs [(inspected "job-a" "parser/1" [row])
              (inspected "job-b" "parser/2" [(assoc row :parsed (assoc parsed :final-performance 71M))])]
        result (ledger/build-ledger jobs {:routes []})]
    (is (= 2 (count (:observations result))))
    (is (= 1 (get-in result [:counts-by-scope :observation :parser-revision])))
    (is (= #{"job-a" "job-b"} (set (map (comp :job-id :ref) (:observations result)))))
    (is (= result (ledger/build-ledger (reverse jobs) {:routes []})))))

(deftest held-out-ffessm-pdf-links-only-cited-subset-rows
  (let [pages (-> (slurp (io/resource "fixtures/ffessm-2026-b16/sans-palmes-hommes.txt"))
                  (str/split #"\f" -1) vec)
        pages (if (= "" (last pages)) (pop pages) pages)
        artifact (assoc (ffessm/parse-pages ffessm/cnf-men-sha256 pages)
                        :source-sha256 ffessm/cnf-men-sha256 :job-id "ffessm-job")
        rows (mapv (fn [ordinal payload]
                     {:ordinal ordinal :kind "result-row" :payload payload})
                   (range) (:candidates artifact))
        result (ledger/build-ledger [{:artifact artifact :observations rows}] {})]
    (is (= 21 (count (:observations result))))
    (is (= 10 (get-in result [:counts-by-scope :observation :same-attempt])))
    (is (every? #(= :same-attempt (:kind %)) (:edges result)))
    (is (= result (ledger/build-ledger [{:artifact artifact :observations rows}] {})))))

(deftest route-evidence-records-duplicate-and-unknown-without-row-pairs
  (let [sha (apply str (repeat 64 "b"))
        evidence {:routes [{:route-id "vestico-default" :source-sha256 sha}
                           {:route-id "vestico-comp-6" :source-sha256 sha}
                           {:route-id "apnea-individual" :source-sha256 (apply str (repeat 64 "c"))}
                           {:route-id "apnea-aggregate" :source-sha256 (apply str (repeat 64 "d"))}]
                  :source-candidates [{:left "apnea-individual" :right "apnea-aggregate"
                                       :reason :uninspected-sporting-overlap}]}
        result (ledger/build-ledger [] evidence)]
    (is (= 1 (get-in result [:counts-by-scope :source-route :source-duplicate])))
    (is (= 1 (get-in result [:counts-by-scope :source-route :unknown])))
    (is (empty? (:edges result)))
    (is (empty? (:observations result)))))

(deftest corpus-reader-uses-imported-jobs-as-its-boundary
  (let [job (inspected "job-a" "parser/1"
                       [(candidate 1 "row" {:source-name "A Diver" :discipline "FIM"})])]
    (with-redefs [observations/list-extractions (fn [url]
                                                  (is (= "read-only-url" url))
                                                  [{:job_id "job-a"}])
                  observations/inspect (fn [url job-id]
                                         (is (= ["read-only-url" "job-a"] [url job-id]))
                                         job)]
      (is (= 1 (count (:observations (ledger/read-corpus "read-only-url" {}))))))))

(deftest route-evidence-rejects-unrelated-fields
  (is (thrown? clojure.lang.ExceptionInfo
               (ledger/build-ledger [] {:routes [{:route-id "source-one"
                                                  :source-sha256 (apply str (repeat 64 "a"))
                                                  :session-token "must-not-appear"}]}))))

(deftest unacquired-candidate-route-has-no-fabricated-hash
  (let [known-sha (apply str (repeat 64 "e"))
        evidence {:routes [{:route-id "apnea-individual" :source-sha256 known-sha}
                           {:route-id "apnea-aggregate" :source-sha256 nil}]
                  :source-candidates [{:left "apnea-individual" :right "apnea-aggregate"
                                       :reason :uninspected-sporting-overlap}]}
        result (ledger/build-ledger [] evidence)]
    (is (= nil (->> (:routes result)
                    (filter #(= "apnea-aggregate" (:route-id %))) first :source-sha256)))
    (is (= 1 (get-in result [:counts-by-scope :source-route :unknown])))
    (is (= 0 (get-in result [:counts-by-scope :source-route :source-duplicate])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (ledger/build-ledger [] {:routes [{:route-id "unacquired-orphan"
                                                    :source-sha256 nil}]})))))

(defn -main []
  (let [result (run-tests 'freediving.source-relationship-ledger-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
