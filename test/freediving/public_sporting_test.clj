(ns freediving.public-sporting-test
  (:require [clojure.test :refer [deftest is use-fixtures run-tests]]
            [freediving.public-results :as public]
            [freediving.public-server-test :as http]
            [freediving.public-results-test :as sample]
            [freediving.observations-test :as fixture]
            [freediving.extraction-test :as extraction-fixture]
            [freediving.observations :as observations]
            [freediving.reviews :as reviews]
            [freediving.reviews-test :as review-fixture]
            [freediving.publication :as publication]
            [freediving.revisions :as revisions]
            [freediving.event-selections :as selections]
            [freediving.public-sporting :as sporting]
            [freediving.deployment :as deployment]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [freediving.sporting-authority :as authority]
            [clojure.string :as str])
  (:import [java.sql DriverManager]))
(def ^:dynamic *private-authority* nil)
(defn private-command! [command]
  (let [{:keys [writer reader]} *private-authority*]
    (.write writer (str (authority/canonical-json command) "\n"))
    (.flush writer)
    (let [reply (json/read-str (.readLine reader) :key-fn keyword :bigdec true)]
      (when (or (:error reply) (not= 200 (:status reply))) (throw (ex-info "Synthetic private fixture rejected command" reply))) reply)))
(defn with-private-authority! [f]
  (let [root (or (System/getenv "FREEDIVING_PRIVATE_TEST_ROOT") (System/getProperty "user.dir"))
        process (.start (ProcessBuilder. ^java.util.List ["python3" (str root "/tests/sporting_authority_fixture.py")]))
        reader (io/reader (.getInputStream process)) writer (io/writer (.getOutputStream process))
        cfg (if-let [line (.readLine reader)]
              (json/read-str line :key-fn keyword)
              (throw (ex-info "Synthetic owner fixture failed to start; inspect fixture separately" {})))
        hook (Thread. #(.destroy process))]
    (.addShutdownHook (Runtime/getRuntime) hook)
    (try
      (binding [*private-authority* {:process process :reader reader :writer writer :config cfg}]
        (with-redefs [authority/config (constantly cfg)] (f)))
      (finally
        (.removeShutdownHook (Runtime/getRuntime) hook)
        (try
          (.write writer "{\"op\":\"stop\"}\n") (.flush writer) (.readLine reader)
          (catch Exception _ nil))
        (when-not (.waitFor process 3 java.util.concurrent.TimeUnit/SECONDS) (.destroy process))
        (.waitFor process)
        (.close reader) (.close writer)))))
(defn publish-authorized! [payload]
  (private-command! {:op "stage" :publication payload})
  (private-command! {:op "approve"})
  (sporting/deliver-current! fixture/admin))
(defn setup! []
  (fixture/sql! fixture/admin "DROP SCHEMA IF EXISTS freediving CASCADE")
  (observations/migrate! fixture/admin "observations_app")
  (reviews/migrate! fixture/admin "observations_app" "reviews_owner")
  (publication/migrate! fixture/admin "reviews_owner")
  (public/migrate! fixture/admin "reviews_owner" "reviews_public")
  (revisions/migrate! fixture/admin "observations_app" "reviews_owner")
  (selections/migrate! fixture/admin "reviews_owner")
  (sporting/migrate! fixture/admin "reviews_public"))
(use-fixtures :each (fn [f] (setup!) (with-private-authority! f)))
(deftest published-source-records-do-not-create-sporting-authority
  (sample/validate! (sample/sample) "synthetic-public")
  (public/refresh! sample/reviewer)
  (http/with-server
    (fn [url]
      (let [r (http/request url "/api/comparison")]
        (is (= 200 (:status r)))
        (is (= "withheld" (get-in r [:body :status])))
        (is (= [] (get-in r [:body :rows])))
        (is (= 0 (get-in r [:body :coverage :eligible-comparison-peers])))
        (is (= 1 (get-in (http/request url "/api/results") [:body :total])))))))

(defn query! [sql & args]
  (with-open [c (DriverManager/getConnection fixture/admin) s (.prepareStatement c sql)]
    (doseq [[i value] (map-indexed vector args)] (.setObject s (inc i) value))
    (with-open [r (.executeQuery s)]
      (let [m (.getMetaData r)]
        (loop [out []]
          (if (.next r)
            (recur (conj out (into {} (for [i (range 1 (inc (.getColumnCount m)))]
                                        [(keyword (.getColumnLabel m i)) (.getObject r i)])))) out))))))
(defn execute! [sql & args]
  (with-open [c (DriverManager/getConnection fixture/admin) s (.prepareStatement c sql)]
    (doseq [[i value] (map-indexed vector args)] (.setObject s (inc i) value)) (.executeUpdate s)))
(defn synthetic-cohort!
  ([] (synthetic-cohort! "POL"))
  ([country]
   (let [input-roots (atom {})
         targets
         (mapv (fn [federation]
                 (let [pdf (extraction-fixture/synthetic-pdf (str "BT /F1 12 Tf 40 750 Td (Synthetic " federation " DNF results) Tj ET"))
                       {:keys [root artifact]} (with-redefs [extraction-fixture/synthetic-pdf (constantly pdf)]
                                                 (fixture/synthetic 1 (str "public-sporting-synthetic/" federation)))
                       artifact (update artifact :candidates
                                        #(mapv (fn [i c]
                                                 (update c :parsed merge
                                                         {:federation federation :source-name (str federation i)
                                                          :representation country :discipline "DNF" :unit "m"
                                                          :event-date (if (= federation "CMAS") "2026-06-11" "2026-06-03")
                                                          :category (when (= federation "CMAS") "SENIORS - WOMEN")
                                                          :gender (when (= federation "AIDA") "Women")
                                                          :rank (inc i) :final-distance (if (= federation "CMAS") (if (zero? i) 100M 90M) nil)
                                                          :realized-distance (if (= federation "CMAS") (if (zero? i) 100M 90M) nil)
                                                          :performance (if (zero? i) 100M 110M)
                                                          :card (when (= federation "AIDA") (if (zero? i) "WHITE" "RED"))}))
                                               (range) %))]
                   (swap! input-roots assoc federation {:root root :artifact artifact})
                   (fixture/publish! {:root root :artifact artifact})
                   (observations/import! fixture/app root (:job-id artifact))
                   (doseq [i (range 2)] (sample/validate! {:job-id (:job-id artifact) :ordinal i} (str "sporting-" federation i)))
                   {:job-id (:job-id artifact) :ordinal 0})) ["CMAS" "AIDA"])
         _ (public/refresh! sample/reviewer)
         bound (query! "SELECT p.result_id,p.body_edn,d.source_sha256,d.artifact_sha256,d.ordinal,encode(sha256(convert_to(row(d.job_id,d.ordinal,d.candidate_id,d.artifact_sha256)::text,'UTF8')),'hex') AS observation_id FROM freediving.public_results p JOIN freediving.public_projection_cache c USING(result_id) JOIN freediving.publication_decisions d ON d.id=c.validation_id ORDER BY p.result_id")
         rows (mapv (fn [b]
                      (let [public (edn/read-string (:body_edn b)) fields (:effective public)
                            federation (:federation fields)
                            dq? (= "RED" (:card fields))
                            value (if dq? 110M (if (= federation "CMAS") (:final-distance fields) (:performance fields)))
                            reference {:result-id (:result_id b) :source-sha256 (:source_sha256 b)
                                       :artifact-sha256 (:artifact_sha256 b) :ordinal (:ordinal b) :observation-id (:observation_id b)}
                            fact (fn [v]
                                   {:value v :binding reference :policy :aida-baseline-v1
                                    :citation {:url "https://example.org/results.pdf" :source-sha256 (:source_sha256 b)
                                               :artifact-sha256 (:artifact_sha256 b) :page 1 :line (inc (:ordinal b))}})]
                        {:result-id (:result_id b) :reference reference
                         :source {:federation federation :event-id (if (= federation "CMAS") "novi-sad" "4852")
                                  :view-id (if (= federation "CMAS") "seniors-women-dnf" "2026-06-03")}
                         :facts {:source-view (fact {:federation federation :event-id (if (= federation "CMAS") "novi-sad" "4852")
                                                     :view-id (if (= federation "CMAS") "seniors-women-dnf" "2026-06-03")
                                                     :kind (if (= federation "CMAS") :results :attempts) :environment :pool})
                                 :source-authority (fact :official-results) :finality (fact :verified-final)
                                 :sanction (fact :eligible) :review (fact :verified)
                                 :outcome (fact (if dq? :disqualified :finally-valid))
                                 :same-attempt (fact :distinct) :source-conflict (fact :resolved)
                                 :final (fact {:value (if dq? nil value) :unit "m" :basis :verified-post-penalty :decimal-places 0 :conversion :verified})
                                 :scoring-policy (fact :aida-baseline-v1)
                                 :source-gender (fact (when (= federation "AIDA") "Women"))
                                 :comparable-category (fact {:group :women :para-class :non-para :age-class :unknown :age-equivalence :unknown})
                                 :represented-country (fact country)
                                 :listing (fact {:publisher (keyword federation) :kind :archive})
                                 :international-sanction (fact {:authority (keyword federation) :status :verified :level :international})
                                 :official-event-placing (fact (:rank fields))}
                         :hypothetical (when dq? (fact {:value 110M :unit "m" :basis :verified-source-achieved :decimal-places 0 :conversion :verified}))})) bound)
         cohort-id (sporting/sha "synthetic-public-cohort")
         ref {:cohort-id cohort-id :policy :aida-baseline-v1 :source-versions (vec (sort (set (map :artifact_sha256 bound))))}
         payload {:schema "public-sporting/v1" :rows rows :cutoff "2026-06-12T00:00:00Z"
                  :cohort {:binding ref :value (vec (sort (map :result-id rows)))
                           :citation {:url "https://example.org/results.pdf" :page 1}}}
         event (publish-authorized! payload)]
     {:cohort-id cohort-id :event event :payload payload :targets targets :input-roots @input-roots})))

(deftest cited-current-cmas-and-aida-cohort-ranks-through-restricted-http
  (synthetic-cohort!)
  (http/with-server
    (fn [url]
      (let [r (http/request url "/api/comparison") rows (get-in r [:body :rows])]
        (is (= 200 (:status r)))
        (is (= 3 (get-in r [:body :coverage :eligible-comparison-peers])))
        (is (= true (get-in r [:body :demo])))
        (is (= 3 (get-in (http/request url "/api/results?comparison=2026-pool-dnf-women") [:body :comparison :eligible-comparison-peers])))
        (is (= #{"AIDA" "CMAS"} (set (map :federation rows))))
        (is (= [1 1 3] (sort (keep #(get-in % [:ranks 2 :rank]) rows))))
        (is (= 1 (count (filter :hypothetical rows))))
        (is (not (re-find #"PRIVATE|job-id|candidate-id|db_role|packet|receipt" (pr-str (:body r)))))
        (doseq [row rows]
          (is (= row (get-in (http/request url (:detail-api-url row)) [:body :attempt])))
          (doseq [rank (:ranks row) :when (:api-url rank)]
            (let [peer (http/request url (:api-url rank))]
              (is (= 200 (:status peer)))
              (is (= (:eligible-peer-denominator rank) (count (get-in peer [:body :rows]))))
              (is (= (:descriptor rank) (get-in peer [:body :descriptor]))))))))))

(deftest changed-authority-rejects-old-detail-and-exact-peer-links
  (let [{:keys [payload]} (synthetic-cohort!)]
    (http/with-server
      (fn [url]
        (let [row (first (get-in (http/request url "/api/comparison") [:body :rows]))
              old-detail (:detail-api-url row) old-peer (get-in row [:ranks 2 :api-url])]
          (publish-authorized! payload)
          (is (= 404 (:status (http/request url old-detail))))
          (is (= 404 (:status (http/request url old-peer))))
          (let [new (first (get-in (http/request url "/api/comparison") [:body :rows]))]
            (is (= 200 (:status (http/request url (:detail-api-url new)))))
            (is (= 200 (:status (http/request url (get-in new [:ranks 2 :api-url])))))))))))

(deftest distinct-attempt-denominator-includes-a-verified-disqualified-attempt
  (synthetic-cohort!)
  (http/with-server
    (fn [url]
      (let [coverage (get-in (http/request url "/api/comparison") [:body :coverage])]
        (is (= 4 (:source-positions coverage)))
        (is (= 2 (:source-versions coverage)))
        (is (= 4 (:distinct-sporting-attempts coverage)))
        (is (= 3 (:eligible-comparison-peers coverage)))))))

(deftest the-first-public-cohort-requires-eligible-peers-from-both-federations
  (let [{:keys [payload]} (synthetic-cohort!)]
    (publish-authorized!
     (update payload :rows #(mapv (fn [row]
                                    (if (= "AIDA" (get-in row [:source :federation]))
                                      (assoc-in row [:facts :finality :value] :unknown) row)) %)))
    (http/with-server
      (fn [url]
        (let [r (:body (http/request url "/api/comparison"))]
          (is (= "withheld" (:status r)))
          (is (= 0 (get-in r [:coverage :eligible-comparison-peers])))
          (is (every? #(nil? (:api-url %)) (mapcat :ranks (:rows r)))))))))

(deftest exact-peer-links-replay-filters-and-reject-tampering
  (synthetic-cohort!)
  (http/with-server
    (fn [url]
      (let [path "/api/comparison?comparison=2026-pool-dnf-women&federation=CMAS&representation=POL&sanction_scope=default&listing_filter=international"
            r (:body (http/request url path)) row (first (:rows r)) peer (get-in row [:ranks 2 :api-url])]
        (is (= 2 (get-in r [:coverage :eligible-comparison-peers])))
        (is (= #{"CMAS"} (set (map :federation (:rows r)))))
        (is (= 200 (:status (http/request url peer))))
        (is (= 404 (:status (http/request url (str/replace peer "federation=CMAS" "federation=AIDA")))))
        (is (= 404 (:status (http/request url (str "/api/comparison/peers/" (apply str (repeat 64 "f")))))))
        (doseq [p ["/api/comparison?q=private" "/api/comparison?date=2026-06-03" "/api/comparison?category=Women"
                   "/api/comparison?representation=invalid" "/api/comparison?sanction_scope=anything" "/api/comparison?comparison=unsupported"]]
          (is (= 400 (:status (http/request url p)))))))))

(deftest owned-preparation-rejects-private-fields-and-does-not-grant-a-writer
  (let [{:keys [payload]} (synthetic-cohort!)
        before (query! "SELECT count(*) AS n FROM freediving.public_sporting_authority_events")]
    (doseq [unsafe [(assoc payload :private "PRIVATE-SECRET")
                    (assoc-in payload [:rows 0 :facts :finality :citation :actor] "PRIVATE-SECRET")
                    (assoc-in payload [:rows 0 :facts :final :value :receipt] "PRIVATE-SECRET")
                    (assoc-in payload [:rows 0 :source :job-id] "PRIVATE-SECRET")]]
      (is (thrown? clojure.lang.ExceptionInfo (sporting/publish-derived! fixture/admin unsafe))))
    (is (= before (query! "SELECT count(*) AS n FROM freediving.public_sporting_authority_events")))
    (is (thrown? Exception (sporting/publish-derived! sample/reader-url payload)))
    (doseq [table ["public_sporting_members" "public_sporting_authority_events" "public_sporting_policy_events" "canonical_attempt_state"]]
      (is (thrown? java.sql.SQLException (fixture/sql! sample/reader-url (str "SELECT * FROM freediving." table)))))
    (is (thrown? java.sql.SQLException (fixture/sql! sample/reader-url "SELECT freediving.public_sporting_local_snapshot()")))
    (with-open [c (DriverManager/getConnection sample/reader-url) s (.createStatement c) r (.executeQuery s "SELECT body_edn,bindings_json FROM freediving.public_sporting_comparison")]
      (is (.next r))
      (is (not (re-find #"PRIVATE|job-id|candidate-id|actor|receipt|packet" (str (.getString r 1) (.getString r 2))))))
    (is (thrown? java.sql.SQLException (fixture/sql! fixture/admin "DELETE FROM freediving.public_sporting_authority_events")))))

(deftest source-publication-and-sporting-withdrawals-clear-ranks-immediately
  (let [{:keys [payload cohort-id targets]} (synthetic-cohort!)]
    (http/with-server
      (fn [url]
        (let [row (first (get-in (http/request url "/api/comparison") [:body :rows]))]
          (query! "INSERT INTO freediving.public_sporting_authority_events(cohort_id,action,policy_version,expected_members,body_edn) VALUES(?,'withdraw','aida-baseline-v1',0,'{}') RETURNING revision" cohort-id)
          (is (= "withheld" (get-in (http/request url "/api/comparison") [:body :status])))
          (is (= 404 (:status (http/request url (:detail-api-url row)))))
          (is (= 404 (:status (http/request url (get-in row [:ranks 2 :api-url])))))
          (is (= 4 (get-in (http/request url "/api/results") [:body :total]))))
        (publish-authorized! payload)
        (let [row (first (get-in (http/request url "/api/comparison") [:body :rows]))]
          (sample/validate! (first targets) "changed-source-validation")
          (public/refresh! sample/reviewer)
          (is (= 4 (get-in (http/request url "/api/results") [:body :total])))
          (is (= "withheld" (get-in (http/request url "/api/comparison") [:body :status])))
          (is (= 404 (:status (http/request url (:detail-api-url row)))))
          (is (= 404 (:status (http/request url (get-in row [:ranks 2 :api-url]))))))))))

(deftest scoring-policy-and-role-changes-are-checked-on-subsequent-requests
  (synthetic-cohort!)
  (http/with-server
    (fn [url]
      (let [row (first (get-in (http/request url "/api/comparison") [:body :rows]))]
        (fixture/sql! fixture/admin "GRANT SELECT ON freediving.canonical_attempt_state TO reviews_public")
        (is (= 403 (:status (http/request url "/api/comparison"))))
        (fixture/sql! fixture/admin "REVOKE SELECT ON freediving.canonical_attempt_state FROM reviews_public")
        (is (= 200 (:status (http/request url "/api/comparison"))))
        (execute! "INSERT INTO freediving.public_sporting_policy_events(policy_version) VALUES('unsupported-policy')")
        (is (= "withheld" (get-in (http/request url "/api/comparison") [:body :status])))
        (is (= 404 (:status (http/request url (:detail-api-url row)))))
        (is (= 404 (:status (http/request url (get-in row [:ranks 2 :api-url])))))
        (is (= 4 (get-in (http/request url "/api/results") [:body :total])))))))

(deftest conflicting-source-authority-is-labelled-provisional-with-separate-dq-semantics
  (let [{:keys [payload]} (synthetic-cohort!)
        row (first (filter #(= "CMAS" (get-in % [:source :federation])) (:rows payload)))
        ref (:reference row)
        alternative (assoc ref :result-id (sporting/sha "alternative") :observation-id (sporting/sha "alternative-observation"))
        citation (get-in row [:facts :finality :citation])
        selection {:selected-reference ref :conflicting-references [alternative] :basis :source-authority
                   :authority-citation citation :selection-citation citation}
        changed (update payload :rows #(mapv (fn [r]
                                               (if (= (:result-id row) (:result-id r))
                                                 (-> r (assoc-in [:facts :source-conflict :value] :selected-provisional)
                                                     (assoc-in [:facts :source-selection] (assoc (get-in row [:facts :source-conflict]) :value selection))) r)) %))]
    (publish-authorized! changed)
    (http/with-server
      (fn [url]
        (let [rows (get-in (http/request url "/api/comparison") [:body :rows])
              ranked (filter #(= "ranked" (:status %)) rows) dq (first (filter :hypothetical rows))]
          (is (= 3 (count ranked)))
          (is (every? :provisional ranked))
          (is (every? #(true? (get-in % [:ranks 2 :descriptor :provisional])) ranked))
          (is (nil? (:score dq)))
          (is (every? #(nil? (:rank %)) (:ranks dq)))
          (is (== 55 (get-in dq [:hypothetical :score :value])))
          (is (= 1 (get-in dq [:hypothetical :rank])))
          (is (= "verified-source-achieved" (get-in dq [:hypothetical :basis]))))))))

(deftest correction-approval-and-reversal-invalidate-the-owned-sporting-projection
  (let [{:keys [targets payload]} (synthetic-cohort!) target (first targets)]
    (http/with-server
      (fn [url]
        (let [row (first (get-in (http/request url "/api/comparison") [:body :rows]))]
          (reviews/propose! fixture/app (review-fixture/proposal target "synthetic-identity-proposal"))
          (sample/decide! "synthetic-identity-approval" :approve "synthetic-identity-proposal" 0)
          (is (= "withheld" (get-in (http/request url "/api/comparison") [:body :status])))
          (is (= 404 (:status (http/request url (:detail-api-url row)))))
          (sample/validate! target "revalidate-after-approval")
          (public/refresh! sample/reviewer)
          (publish-authorized! payload)
          (let [approved-row (first (get-in (http/request url "/api/comparison") [:body :rows]))]
            (is (= 3 (get-in (http/request url "/api/comparison") [:body :coverage :eligible-comparison-peers])))
            (sample/decide! "synthetic-identity-reversal" :reverse "synthetic-identity-approval" 1)
            (is (= "withheld" (get-in (http/request url "/api/comparison") [:body :status])))
            (is (= 404 (:status (http/request url (:detail-api-url approved-row)))))
            (is (= 404 (:status (http/request url (get-in approved-row [:ranks 2 :api-url])))))))))))

(defn -main [& _]
  (if (= "1" (System/getenv "FREEDIVING_TEST_SERVE"))
    (with-private-authority!
      (fn []
        (setup!)
        (synthetic-cohort!)
        (let [app ((requiring-resolve 'freediving.public-server/start!) {:database-url sample/reader-url :port 0 :demo? true})]
          (.addShutdownHook (Runtime/getRuntime) (Thread. #((requiring-resolve 'freediving.public-server/stop!) app)))
          (println (str "Synthetic sporting comparison: " (:url app) "/comparison"))
          (flush)
          @(promise))))
    (let [r (run-tests 'freediving.public-sporting-test)]
      (shutdown-agents)
      (when (pos? (+ (:fail r) (:error r))) (System/exit 1)))))

(deftest an-unmapped-represented-country-keeps-geographic-denominators-unknown
  (synthetic-cohort! "AIN")
  (http/with-server
    (fn [url]
      (let [r (:body (http/request url "/api/comparison")) ranked (filter #(= "ranked" (:status %)) (:rows r))]
        (is (= 3 (get-in r [:coverage :eligible-comparison-peers])))
        (doseq [row ranked]
          (is (= "AIN" (:represented-country row)))
          (is (number? (get-in row [:ranks 2 :rank])))
          (doseq [rank (take 2 (:ranks row))]
            (is (= "withheld" (:status rank)))
            (is (nil? (:rank rank)))
            (is (nil? (:eligible-peer-denominator rank)))
            (is (nil? (:api-url rank)))
            (is (re-find #"unknown" (:reason rank)))))))))

(deftest another-extraction-version-at-the-same-source-position-does-not-create-another-dive
  (let [{:keys [payload input-roots]} (synthetic-cohort!)
        {:keys [root artifact]} (get input-roots "CMAS")
        revised (assoc artifact :parser-version "public-sporting-synthetic/CMAS-revision")
        revised (assoc revised :job-id (fixture/hash-value (select-keys revised observations/identity-keys)))]
    (fixture/publish! {:root root :artifact revised})
    (observations/import! fixture/app root (:job-id revised))
    (doseq [i (range 2)] (sample/validate! {:job-id (:job-id revised) :ordinal i} (str "alternate-version" i)))
    (public/refresh! sample/reviewer)
    (let [bound (first (query! "SELECT p.result_id,d.source_sha256,d.artifact_sha256,d.ordinal,encode(sha256(convert_to(row(d.job_id,d.ordinal,d.candidate_id,d.artifact_sha256)::text,'UTF8')),'hex') AS observation_id FROM freediving.public_results p JOIN freediving.public_projection_cache c USING(result_id) JOIN freediving.publication_decisions d ON d.id=c.validation_id WHERE d.job_id=? AND d.ordinal=0" (:job-id revised)))
          reference {:result-id (:result_id bound) :source-sha256 (:source_sha256 bound) :artifact-sha256 (:artifact_sha256 bound)
                     :ordinal (:ordinal bound) :observation-id (:observation_id bound)}
          original (first (filter #(and (= "CMAS" (get-in % [:source :federation])) (zero? (get-in % [:reference :ordinal]))) (:rows payload)))
          cloned (-> original (assoc :result-id (:result_id bound) :reference reference)
                     (update :facts #(into {} (map (fn [[k fact]] [k (-> fact (assoc :binding reference)
                                                                         (assoc-in [:citation :artifact-sha256] (:artifact_sha256 bound)))]) %))))
          updated (update payload :rows conj cloned)
          updated (-> updated (assoc-in [:cohort :value] (vec (sort (map :result-id (:rows updated)))))
                      (assoc-in [:cohort :binding :source-versions] (vec (sort (set (map #(get-in % [:reference :artifact-sha256]) (:rows updated)))))))]
      (is (thrown? clojure.lang.ExceptionInfo (publish-authorized! updated)))
      (http/with-server
        (fn [url]
          (let [body (:body (http/request url "/api/comparison"))]
            (is (= "withheld" (:status body)))
            (is (= 0 (get-in body [:coverage :eligible-comparison-peers])))
            (is (nil? (get-in body [:coverage :distinct-sporting-attempts])))
            (is (= [] (:rows body)))
            (is (= 6 (get-in (http/request url "/api/results") [:body :total])))))))))

(deftest sporting-row-citations-require-the-exact-source-row-coordinate
  (let [{:keys [payload]} (synthetic-cohort!)
        before (query! "SELECT count(*) AS n FROM freediving.public_sporting_authority_events")]
    (doseq [fact [:review :final :official-event-placing :source-gender :represented-country :outcome]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (sporting/publish-derived! fixture/admin (assoc-in payload [:rows 0 :facts fact :citation :line] 99)))))
    (is (= before (query! "SELECT count(*) AS n FROM freediving.public_sporting_authority_events")))))

(deftest an-aida-total-view-is-not-invented-into-a-sporting-attempt
  (let [{:keys [payload]} (synthetic-cohort!)]
    (publish-authorized!
     (update payload :rows #(mapv (fn [row]
                                    (if (= "AIDA" (get-in row [:source :federation]))
                                      (assoc-in row [:facts :source-view :value :kind] :totals) row)) %)))
    (http/with-server
      (fn [url]
        (is (= "withheld" (get-in (http/request url "/api/comparison") [:body :status])))
        (is (= 0 (get-in (http/request url "/api/comparison") [:body :coverage :eligible-comparison-peers])))))))

(deftest normal-deployment-installs-all-twenty-three-migrations-and-preserves-source-and-sporting-data
  (synthetic-cohort!)
  (let [counts #(query! "SELECT (SELECT count(*) FROM freediving.observations) AS observations,(SELECT count(*) FROM freediving.publication_decisions) AS publications,(SELECT count(*) FROM freediving.public_results) AS public,(SELECT count(*) FROM freediving.public_sporting_authority_events) AS authority,(SELECT count(*) FROM freediving.public_sporting_members) AS members")
        before (counts)]
    (dotimes [_ 2] (is (= {:schema-version 23} (deployment/migrate! fixture/admin))))
    (is (= before (counts)))
    (is (= (vec (range 1 24)) (mapv :version (query! "SELECT version FROM freediving.schema_migrations ORDER BY version"))))
    (http/with-server
      (fn [url]
        (is (= 3 (get-in (http/request url "/api/comparison") [:body :coverage :eligible-comparison-peers])))
        (is (= 4 (get-in (http/request url "/api/results") [:body :total])))))))
