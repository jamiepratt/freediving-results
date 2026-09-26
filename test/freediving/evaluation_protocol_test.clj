(ns freediving.evaluation-protocol-test
  (:require [clojure.data.json :as json]
            [freediving.evaluation-providers :as providers]
            [freediving.evaluation-providers-test :refer [with-server reply! jev-answer]]
            [clojure.test :refer [deftest is]]
            [freediving.evaluation-protocol :as protocol]))

(def archive "Event 2026\n1 José SILVA BRA 75\n2 SILVA Jose POR 82\n")
(defn sha [s]
  (format "%064x" (java.math.BigInteger. 1 (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8")))))
(def reference {:evidence-id "source-row-1" :source-sha256 (apply str (repeat 64 "a"))
                :artifact-sha256 (sha archive) :observation-id "row-1" :page 1 :lines [2 2]
                :source-family-id "publisher-event"})

(deftest archived-source-lines-are-exact-and-content-addressed
  (let [e (protocol/source-evidence reference archive)]
    (is (= ["1 José SILVA BRA 75"] (:exact-lines e)))
    (is (= reference (dissoc e :exact-lines)))
    (is (thrown? clojure.lang.ExceptionInfo (protocol/source-evidence reference (str archive "changed"))))
    (is (thrown? clojure.lang.ExceptionInfo (protocol/source-evidence (assoc reference :lines [4 5]) archive))))
  (is (thrown? clojure.lang.ExceptionInfo (protocol/source-evidence (assoc reference :review-reason "leak") archive))))

(defn record [id name]
  {:record-id id
   :fields (assoc (zipmap [:name :event-name :event-date :discipline :representation :rank :performance :age-category :birth-date]
                          (repeat {:value nil :evidence-ids []}))
                  :name {:value name :evidence-ids ["source-row-1"]})
   :sources [(protocol/source-evidence reference archive)] :uncertainties [] :publisher-identity nil})
(defn input [] {:schema-version "freediving-source/1" :left (record "left-row" "José SILVA") :right (record "right-row" "SILVA Jose")})

(deftest source-only-input-retains-originals-and-rejects-review-leakage
  (let [in (input)]
    (is (= in (protocol/validate-input! in)))
    (doseq [bad [(assoc in :label :match)
                 (assoc-in in [:left :review-reason] "owner says match")
                 (assoc-in in [:left :fields :name :value] {:label :match})
                 (assoc-in in [:left :fields :nationality] {:value "BRA" :evidence-ids ["source-row-1"]})
                 (assoc-in in [:left :fields :name :evidence-ids] ["missing"])
                 (assoc-in in [:left :fields :event-date] {:value nil :evidence-ids ["source-row-1"]})
                 (assoc-in in [:left :sources 0 :owner-id] "post-review")]]
      (is (thrown? clojure.lang.ExceptionInfo (protocol/validate-input! bad))))))

(deftest page-local-lines-in-edn-archives-are-verified
  (let [artifact (pr-str {:pages [{:page 2 :lines [{:line 1 :text "Header"} {:line 2 :text "Exact row"}]}]})
        ref (assoc reference :artifact-sha256 (sha artifact) :page 2)]
    (is (= ["Exact row"] (:exact-lines (protocol/source-evidence ref artifact))))))

(def config {:provider :jev :identity-protocol :freediving-source-v1 :diagnostics-version 2
             :model "jev-1.13.0" :endpoint "http://127.0.0.1:1/"})
(deftest opt-in-domain-request-freezes-source-only-semantics
  (let [in (input) seen (atom nil)]
    (with-server (fn [exchange]
                   (reset! seen (json/read-str (slurp (.getRequestBody exchange)) :key-fn keyword))
                   (reply! exchange 200 (json/write-str {:model "jev-1.13.0" :answers {:identity jev-answer}})))
      (fn [url]
        (let [request (providers/prepare-request (assoc config :endpoint url)
                                                 {:case-id "case" :input in :label :no-match :review-reason "PRIVATE OWNER"})
              result (providers/execute! request {:bearer-token "fixture-secret"})]
          (is (= :match (:outcome result)))
          (is (= "shadow-adapters/6" (:adapter-version request)))
          (is (= protocol/descriptor (:protocol request)))
          (is (= in (json/read-str (:state @seen) :key-fn keyword)))
          (is (= (protocol/question "/left" "/right") (get-in @seen [:questions :identity])))
          (is (not (.contains (pr-str request) "PRIVATE OWNER")))
          (is (nil? (:response-body result))))))))

(deftest domain-single-question-rejects-unrequested-answer-ids
  (with-server (fn [exchange]
                 (reply! exchange 200 (json/write-str {:model "jev-1.13.0" :answers {:identity jev-answer :other jev-answer}})))
    (fn [url]
      (let [result (providers/execute! (providers/prepare-request (assoc config :endpoint url) {:input (input)})
                                       {:bearer-token "fixture-secret"})]
        (is (= :error (:outcome result)))
        (is (= [:invalid-answer-identifiers] (:validation-reasons result)))))))

(deftest domain-protocol-has-a-hard-provider-input-bound
  (let [large (reduce (fn [in side]
                        (reduce (fn [out field] (assoc-in out [side :fields field]
                                                          {:value (apply str (repeat 3000 "x"))
                                                           :evidence-ids ["source-row-1"]}))
                                in protocol/field-keys)) (input) [:left :right])]
    (is (thrown? clojure.lang.ExceptionInfo
                 (providers/prepare-request (assoc config :max-request-bytes 1048576) {:input large})))))

(deftest domain-scenarios-preserve-evidence-without-asserting-model-accuracy
  ;; A loopback canned answer tests transport, never semantic classification.
  (doseq [[left right] [["José Silva" "SILVA Jose"] ["AL1CE SMITH" "Alice Smith"]
                        ["Alex Lee" "Alex Lee"] ["Ana" "Ana Costa"]]]
    (let [in (-> (input)
                 (assoc-in [:left :fields :name :value] left)
                 (assoc-in [:right :fields :name :value] right)
                 (assoc-in [:left :fields :representation] {:value "BRA" :evidence-ids ["source-row-1"]})
                 (assoc-in [:right :fields :representation] {:value "POR" :evidence-ids ["source-row-1"]}))
          request (providers/prepare-request config {:input in})]
      (is (= in (:input request)))
      (is (= in (json/read-str (:state (json/read-str (:body request) :key-fn keyword)) :key-fn keyword)))
      (is (= {:value nil :evidence-ids []} (get-in request [:input :left :fields :birth-date])))
      (is (= (get-in in [:left :sources]) (get-in in [:right :sources])))))
  (let [in (-> (input)
               (assoc-in [:left :fields :birth-date] {:value "1980-01-01" :evidence-ids ["source-row-1"]})
               (assoc-in [:right :fields :birth-date] {:value "1990-01-01" :evidence-ids ["source-row-1"]})
               (assoc-in [:left :uncertainties] [{:value "OCR digit may be wrong" :evidence-ids ["source-row-1"]}]))]
    (is (= in (:input (providers/prepare-request config {:input in}))))))

(deftest opt-in-config-and-publisher-authority-are-explicit
  (doseq [bad [(assoc config :identity-protocol nil) (assoc config :identity-protocol :future)
               (assoc config :provider :llm) (dissoc config :diagnostics-version)
               (assoc config :diagnostics-version 1) (assoc config :max-request-bytes 8)]]
    (is (thrown? clojure.lang.ExceptionInfo (providers/prepare-request bad {:input (input)}))))
  (let [identity {:value "publisher-123" :authority "publisher.example/athletes"
                  :uniqueness "person-within-authority" :evidence-ids ["source-row-1"]}
        in (assoc-in (input) [:left :publisher-identity] identity)]
    (is (= in (protocol/validate-input! in)))
    (doseq [bad [(assoc identity :uniqueness "unknown") (assoc identity :evidence-ids [])
                 (assoc identity :decision :match) (assoc identity :authority {:owner :match})]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (protocol/validate-input! (assoc-in in [:left :publisher-identity] bad)))))))

(deftest compact-projection-preserves-evidence-and-local-audit-mapping
  (let [in (input)
        project (requiring-resolve 'freediving.evaluation-protocol/compact-projection)
        result (project in)
        wire (:input result)]
    (is (= result (project in)))
    (is (= "freediving-compact/1" (:projection-version result)))
    (is (= in (get-in result [:mapping :complete-input])))
    (is (= {:value "José SILVA" :sources ["s1"]} (get-in wire [:left :fields :name])))
    (is (not (contains? (get-in wire [:left :fields]) :birth-date)))
    (is (= ["1 José SILVA BRA 75"] (get-in wire [:sources "s1" :excerpt])))
    (is (= 1 (count (:sources wire))))
    (doseq [removed ["source-row-1" "left-row" "publisher-event" (:source-sha256 reference)]]
      (is (not (.contains (json/write-str wire) removed))))))

(deftest compact-raw-only-biography-and-ambiguous-tables-remain-data
  (let [lines ["POS  ATHLETE               BIRTH  CLUB" "1    CASINI Andrea         1985   Natatorium Treviso" "2    REDAELLI Luca          1987   Club B" "     wrapped ambiguous continuation"]
        source (assoc (first (:sources (:left (input)))) :exact-lines lines :lines [1 4])
        in (-> (input)
               (assoc-in [:left :sources] [source])
               (assoc-in [:right :sources] [source])
               (assoc-in [:left :uncertainties] [{:value "Birth/club row association uncertain; do not infer citizenship" :evidence-ids ["source-row-1"]}]))
        compact (:input (protocol/compact-projection in))]
    (is (= lines (get-in compact [:sources "s1" :excerpt])))
    (is (= ["s1"] (get-in compact [:left :source-order])))
    (is (= "Birth/club row association uncertain; do not infer citizenship" (get-in compact [:left :uncertainties 0 :value])))
    (is (= ["s1"] (get-in compact [:left :uncertainties 0 :sources])))
    (is (not (contains? (get-in compact [:left :fields]) :birth-date)))))

(deftest compact-native-questions-are-self-contained-and-opt-in
  (let [cfg (assoc config :identity-protocol :freediving-compact-v1 :native-batch-size 2
                   :native-diagnostics-version 2 :probability-sum-tolerance 0.02)
        cases [{:case-id "one" :input (input)} {:case-id "two" :input (input)}]
        batch (first (providers/prepare-batches cfg cases))
        single (first (providers/prepare-batches (assoc cfg :native-batch-size 1) cases))
        decode #(json/read-str (:body %) :key-fn keyword)]
    (is (= "shadow-adapters/13" (:adapter-version batch)))
    (is (= (get-in (decode single) [:questions :identity_0]) (get-in (decode batch) [:questions :identity_1])))
    (is (= (:instruction protocol/compact-descriptor) (:state (decode batch))))
    (is (= (input) (get-in batch [:projections 0 :mapping :complete-input])))
    (is (not (.contains (:body batch) "source-sha256")))
    (is (= (:criteria (protocol/question "/left" "/right")) (get-in (decode batch) [:questions :identity_0 :criteria])))))

(deftest compact-duplicates-do-not-become-independent-corroboration
  (let [a (first (:sources (:left (input))))
        duplicate (assoc a :evidence-id "duplicate" :observation-id "copy" :source-family-id "misassigned-family")
        header (assoc a :evidence-id "header" :lines [1 1] :exact-lines ["Name          Country    Result"])
        in (-> (input)
               (assoc-in [:left :sources] [header a duplicate])
               (assoc-in [:left :fields :name :evidence-ids] ["source-row-1" "duplicate"]))
        projected (protocol/compact-projection in)
        wire (:input projected)]
    (is (= 2 (count (:sources wire))))
    (is (= 1 (count (set (map :dependence (vals (:sources wire)))))))
    (is (= ["s1" "s2" "s2"] (get-in wire [:left :source-order])))
    (is (= ["s2"] (get-in wire [:left :fields :name :sources])))
    (is (= ["Name          Country    Result"] (get-in wire [:sources "s1" :excerpt])))
    (is (= #{"source-row-1" "duplicate"} (set (map :evidence-id (get-in projected [:mapping :sources "s2"])))))))

(deftest compact-dependence-is-transitive-without-erasing-distinct-lines
  (let [a (first (:sources (:left (input))))
        b (assoc a :evidence-id "b" :source-family-id "second-family" :exact-lines ["1985  Natatorium Treviso"])
        c (assoc b :evidence-id "c" :source-sha256 (apply str (repeat 64 "c"))
                 :artifact-sha256 (apply str (repeat 64 "d")) :exact-lines ["1987  Club B"])
        in (-> (input) (assoc-in [:left :sources] [a c b]))
        wire (:input (protocol/compact-projection in))]
    (is (= 3 (count (:sources wire))))
    (is (= 1 (count (set (map :dependence (vals (:sources wire)))))))
    (is (= [["1 José SILVA BRA 75"] ["1987  Club B"] ["1985  Natatorium Treviso"]]
           (mapv #(get-in wire [:sources % :excerpt]) (get-in wire [:left :source-order]))))))

(deftest compact-probability-contract-and-limits-are-explicit
  (let [cfg (assoc config :identity-protocol :freediving-compact-v1 :native-batch-size 1
                   :native-diagnostics-version 2 :probability-sum-tolerance 0.02)
        cases [{:case-id "one" :input (input)}]]
    (doseq [bad [(dissoc cfg :probability-sum-tolerance)
                 (assoc cfg :native-batch-size 81)
                 (assoc cfg :max-request-bytes 100)
                 (assoc cfg :companion-assessments [:contradiction])]]
      (is (thrown? Exception (providers/prepare-batches bad cases))))))

(deftest original-guidance-is-an-isolated-opt-in-on-compact-evidence
  (let [cfg (assoc config :identity-protocol :freediving-compact-v1 :native-batch-size 5
                   :native-diagnostics-version 2 :probability-sum-tolerance 0.02)
        cases (mapv #(hash-map :case-id (str "case-" %) :input (input)) (range 6))
        compact (providers/prepare-batches cfg cases)
        original (providers/prepare-batches (assoc cfg :identity-guidance :original-v1) cases)]
    (doseq [[short full] (map vector compact original)]
      (let [a (json/read-str (:body short)) b (json/read-str (:body full))]
        (is (= (dissoc a "state") (dissoc b "state")))
        (is (= (:instruction protocol/question-local-descriptor) (get b "state")))
        (is (= (:instruction protocol/compact-descriptor) (get a "state")))
        (is (= (:projections short) (:projections full)))
        (is (not= short full))))
    (doseq [bad [(assoc cfg :identity-guidance :unknown)
                 (assoc cfg :identity-guidance :original-v1 :identity-protocol :freediving-question-local-v1)]]
      (is (thrown? Exception (providers/prepare-batches bad cases))))))
