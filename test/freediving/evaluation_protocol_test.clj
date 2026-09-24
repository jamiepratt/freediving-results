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
