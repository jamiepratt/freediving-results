(ns freediving.retained-json-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is run-tests]]
            [freediving.parser-batch :as batch]
            [freediving.retained-json :as retained]))

(defn sha256 [bytes]
  (.formatHex (java.util.HexFormat/of)
              (.digest (java.security.MessageDigest/getInstance "SHA-256") bytes)))

(defn fixture [rows]
  (let [bytes (.getBytes (json/write-str rows) "UTF-8")
        hash (sha256 bytes)
        document {:source-sha256 hash :format :json
                  :positions (mapv (fn [i]
                                     (let [pointer (str "/" i)]
                                       {:id (str "json-pointer=" pointer)
                                        :citation (str "sha256:" hash "#json-pointer=" pointer)
                                        :coordinates {:json-pointer pointer}}))
                                   (range (count rows)))}]
    {:document document
     :retained-input {:bytes bytes
                      :receipt {:status 200 :bytes (alength bytes) :sha256 hash
                                :final_url retained/unit-results-url}}
     :hash hash}))

(defn result-row [id]
  {"UtID" 3533 "DCCmpID" 28 "EvID" 558 "ResID" id
   "ResResult" "73" "ResResultFinal" "73"})

(deftest verified-unit-results-route-at-exact-json-pointers
  (let [{:keys [document retained-input]} (fixture [(result-row 101) (result-row 102)])
        result (retained/claims-for-document document retained-input)
        routed (batch/replay-batch [{:document document :claims (:claims result)}])]
    (is (= :raw-bytes-and-receipt-sha256 (:source-verification result)))
    (is (= retained/parser-version (get-in result [:claims 0 :parser-version])))
    (is (= #{"json-pointer=/0" "json-pointer=/1"}
           (get-in result [:claims 0 :claimed-positions])))
    (is (= (mapv :citation (:positions document))
           (mapv :citation (get-in routed [:documents 0 :routed]))))
    (is (= {:known-positions 2 :routed 2 :gaps 0 :unexamined-sections 0}
           (get-in routed [:metrics :coverage])))
    (is (= 0 (get-in routed [:metrics :requests])))))

(deftest mismatches-and-false-positives-claim-nothing
  (let [{:keys [document retained-input]} (fixture [(result-row 101)])
        cases [(assoc-in retained-input [:receipt :sha256] (apply str (repeat 64 "a")))
               (assoc-in retained-input [:receipt :final_url] "https://example.invalid/results")
               (assoc-in retained-input [:receipt :bytes] 1)
               (assoc retained-input :bytes (.getBytes "[]" "UTF-8"))
               (dissoc retained-input :bytes)]
        forged (assoc-in document [:positions 0 :citation] "forged")
        missing-position (assoc document :positions [])
        wrong-schema (fixture [{"UtID" 3533 "DCCmpID" 28 "EvID" 558}])]
    (doseq [input cases]
      (is (empty? (:claims (retained/claims-for-document document input)))))
    (is (empty? (:claims (retained/claims-for-document forged retained-input))))
    (is (empty? (:claims (retained/claims-for-document missing-position retained-input))))
    (is (empty? (:claims (retained/claims-for-document
                          (:document wrong-schema) (:retained-input wrong-schema)))))))

(deftest duplicate-acquisition-and-changed-bytes-remain-versioned
  (let [first-source (fixture [(result-row 101)])
        changed-source (fixture [(result-row 102)])
        entry (fn [{:keys [document retained-input]}]
                (let [result (retained/claims-for-document document retained-input)]
                  {:document document :claims (:claims result)
                   :unsupported-reasons (:unsupported-reasons result)}))
        replay (batch/replay-batch [(entry first-source) (entry first-source)
                                    (entry changed-source)])]
    (is (= 2 (count (:documents replay))))
    (is (= 1 (get-in replay [:metrics :duplicate-inputs])))
    (is (= 2 (get-in replay [:metrics :coverage :routed])))
    (is (= 0 (get-in replay [:metrics :requests])))
    (is (= 0 (get-in replay [:metrics :cache-reuses])))))

(defn -main [& _]
  (let [result (run-tests 'freediving.retained-json-test)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
