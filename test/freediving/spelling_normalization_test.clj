(ns freediving.spelling-normalization-test
  (:require [clojure.test :refer [deftest is]]
            [freediving.spelling-normalization :as spelling]
            [freediving.evaluation :as evaluation]
            [freediving.candidates :as candidates]
            [freediving.reviews :as reviews]))

(def names {:left {:fields {:name {:value "Anastasiia Petrova"}}}
            :right {:fields {:name {:value "Anastasia Petrova"}}}})
(def match {:outcome :match :confidence 0.98
            :probabilities {:match 0.96 :no_match 0.02 :abstain 0.02}
            :spelling {:outcome :left :confidence 0.97
                       :probabilities {:left 0.96 :right 0.02 :equally_plausible 0.01
                                       :unknown 0.01 :not_applicable 0.0}}})

(deftest both-choices-must-clear-the-gate
  (is (= {:spelling "Anastasiia Petrova" :source-side :left :target-side :right
          :identity-probability 0.96 :spelling-probability 0.96
          :gate-version :jev-spelling-normalization-v1}
         (spelling/recommendation names match)))
  (is (nil? (spelling/recommendation names (assoc-in match [:probabilities :match] 0.94))))
  (is (nil? (spelling/recommendation names (assoc match :confidence 0.94))))
  (is (nil? (spelling/recommendation names (assoc-in match [:spelling :confidence] 0.94))))
  (is (nil? (spelling/recommendation names (assoc-in match [:spelling :outcome] :unknown))))
  (is (nil? (spelling/recommendation names (assoc match :outcome :abstain))))
  (is (nil? (spelling/recommendation (assoc-in names [:right :fields :name :value] "Anastasiia Petrova") match))))

(deftest margin-applies-to-each-question
  (is (nil? (spelling/recommendation names (assoc match :probabilities
                                                  {:match 0.95 :no_match 0.75 :abstain 0.0}))))
  (is (nil? (spelling/recommendation names (assoc-in match [:spelling :probabilities :right] 0.76)))))

(defn- bound-case []
  (let [record (fn [side job name]
                 {:record-id (str "local-observation:" job ":0")
                  :fields {:name {:value name :evidence-ids [(str side "-source")]}}
                  :sources [{:evidence-id (str side "-source") :source-sha256 (str job "-source")
                             :artifact-sha256 (str job "-artifact") :observation-id (str job "-candidate")}]})
        row (fn [job name]
              {:job-id job :ordinal 0 :kind "result-row" :source-format :pdf
               :candidate-id (str job "-candidate") :source-sha256 (str job "-source")
               :artifact-sha256 (str job "-artifact") :payload {:parsed {:source-name name}}
               :source-lines [{:page 1 :line 1}]})]
    {:case {:case-id "pair" :split :held-out
            :input {:left (record "left" "left" "Anastasiia Petrova")
                    :right (record "right" "right" "Anastasia Petrova")}}
     :rows [(row "left" "Anastasiia Petrova") (row "right" "Anastasia Petrova")]}))

(deftest source-bound-run-applies-one-audited-normalization
  (let [{:keys [case rows]} (bound-case)
        calls (atom [])
        run {:input {:configurations [{:id "jev"}]
                     :requests [[{:provider :jev :adapter-version "shadow-adapters/14"
                                  :config {:identity-protocol :freediving-compact-v3}}]]
                     :dataset {:cases [case]}}
             :report {:providers {"jev" {:results [(assoc match :case-id "pair" :batch-index 0
                                                          :request-hash "request" :trace-hash "trace"
                                                          :model-version "jev-1.13.0")]
                                         :batches [{:dispatch-status :dispatched :trace-hash "trace"
                                                    :attempt {:request-hash "request"
                                                              :result {:outcome :complete :http-status 200
                                                                       :model-version "jev-1.13.0"}}}]}}}}]
    (with-redefs [evaluation/inspect-run (fn [& _] run)
                  candidates/load-corpus (fn [& _] rows)
                  reviews/effective (fn [& _] {:revision 0 :active {} :fields {:source-name "Anastasia Petrova"}})
                  reviews/history (fn [& _] [])
                  reviews/propose! (fn [_ request] (swap! calls conj [:propose request]) request)
                  reviews/decide! (fn [_ request] (swap! calls conj [:approve request]) request)]
      (is (= :applied (:status (first (spelling/apply-run! "root" "run" "jev" "reviewer")))))
      (is (= [:propose :approve] (mapv first @calls)))
      (is (= :name-normalization (get-in @calls [0 1 :category])))
      (is (= "Anastasiia Petrova" (get-in @calls [0 1 :after])))
      (is (= [{:page 1 :line 1}] (get-in @calls [0 1 :evidence]))))
    (reset! calls [])
    (with-redefs [evaluation/inspect-run (fn [& _] run)
                  candidates/load-corpus (fn [& _] rows)
                  reviews/effective (fn [& _] {:revision 1 :active {:source-name "owner-choice"}
                                               :fields {:source-name "Owner spelling"}})
                  reviews/history (fn [& _] [])
                  reviews/propose! (fn [& _] (swap! calls conj :unexpected))]
      (is (= :existing-normalization
             (:status (first (spelling/apply-run! "root" "run" "jev" "reviewer")))))
      (is (empty? @calls)))
    (with-redefs [evaluation/inspect-run (fn [& _] run)
                  candidates/load-corpus (fn [& _] rows)
                  reviews/effective (fn [& _] {:revision 0 :active {} :fields {:source-name "Anastasia Petrova"}})
                  reviews/history (fn [& _] [{:id "owner-pending" :action :propose
                                              :category :name-normalization}])
                  reviews/propose! (fn [& _] (swap! calls conj :unexpected))]
      (is (= :pending-owner-choice
             (:status (first (spelling/apply-run! "root" "run" "jev" "reviewer")))))
      (is (empty? @calls)))
    (with-redefs [evaluation/inspect-run (fn [& _] run)
                  candidates/load-corpus (fn [& _] (assoc-in rows [1 :candidate-id] "forged"))]
      (is (thrown? Exception (spelling/apply-run! "root" "run" "jev" "reviewer"))))
    (with-redefs [evaluation/inspect-run (fn [& _] (assoc-in run [:report :providers "jev" :batches 0 :attempt :result :http-status] 403))
                  candidates/load-corpus (fn [& _] rows)
                  reviews/history (fn [& _] [])
                  reviews/propose! (fn [& _] (swap! calls conj :unexpected))]
      (is (= :no-clear-choice
             (:status (first (spelling/apply-run! "root" "run" "jev" "reviewer")))))
      (is (empty? @calls)))))
