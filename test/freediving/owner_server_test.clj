(ns freediving.owner-server-test
  (:require [clojure.test :refer [deftest is use-fixtures run-tests]]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.java.shell :as shell]
            [freediving.owner-server :as server]
            [freediving.candidates :as candidates]
            [freediving.jev-candidates :as jev-candidates]
            [freediving.source-pages-test :as pages-fixture]
            [freediving.aida-html-test :as html-fixture]
            [freediving.publication-test :as publication-fixture]
            [freediving.observations-test :as fixture]
            [freediving.observations :as observations]
            [freediving.reviews :as reviews]
            [freediving.evaluation-labels :as labels]
            [freediving.publication :as publication]
            [freediving.public-results :as public-results]
            [freediving.corrections :as corrections])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.nio.file Files]))
(use-fixtures :each (fn [f]
                      (fixture/sql! fixture/admin "DROP SCHEMA IF EXISTS freediving CASCADE")
                      (observations/migrate! fixture/admin "observations_app")
                      (reviews/migrate! fixture/admin "observations_app" "reviews_owner")
                      (publication/migrate! fixture/admin "reviews_owner")
                      (public-results/migrate! fixture/admin "reviews_owner" "reviews_public")
                      (corrections/migrate! fixture/admin "reviews_owner" "corrections_submit") (f)))
(defn request [s method path data headers]
  (let [b (HttpRequest/newBuilder (URI/create (str (:url s) path)))
        _ (doseq [[k v] headers] (.header b k v))
        _ (.method b method (if data (HttpRequest$BodyPublishers/ofString (json/write-str data)) (HttpRequest$BodyPublishers/noBody)))
        r (.send (HttpClient/newHttpClient) (.build b) (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode r) :body (try (json/read-str (.body r) :key-fn keyword) (catch Exception _ (.body r)))
     :headers (.map (.headers r))
     :cookie (some-> (.firstValue (.headers r) "set-cookie") (.orElse nil))}))
(defn config [] {:database-url publication-fixture/reviewer :port 0 :demo? true
                 :capability-file (str (.toRealPath (Files/createTempDirectory "owner-http-test" (make-array java.nio.file.attribute.FileAttribute 0)) (make-array java.nio.file.LinkOption 0)) "/capability")})
(deftest capability-origin-and-csrf-guard-the-owner
  (let [s (server/start! (config))]
    (try
      (is (= 401 (:status (request s "GET" "/api/candidates" nil {}))))
      (is (= 403 (:status (request s "POST" "/api/login" {:capability (slurp (:capability-file s))} {"Content-Type" "application/json" "Origin" "https://evil.example"}))))
      (let [login (request s "POST" "/api/login" {:capability (slurp (:capability-file s))} {"Content-Type" "application/json" "Origin" (:url s)})
            headers {"Cookie" (first (str/split (:cookie login) #";"))}]
        (is (= 200 (:status login)))
        (is (string? (get-in login [:body :csrf])))
        (is (= 200 (:status (request s "GET" "/api/candidates" nil headers))))
        (is (= 403 (:status (request s "POST" "/api/proposals" {} (merge headers {"Content-Type" "application/json" "Origin" (:url s)})))))
        (is (= 405 (:status (request s "GET" "/api/proposals" nil headers)))))
      (finally (server/stop! s)))))
(defn -main [& _] (let [r (run-tests 'freediving.owner-server-test)] (shutdown-agents) (when (pos? (+ (:fail r) (:error r))) (System/exit 1))))

(defn login [s]
  (let [r (request s "POST" "/api/login" {:capability (slurp (:capability-file s))} {"Origin" (:url s) "Content-Type" "application/json"})]
    {"Origin" (:url s) "Content-Type" "application/json"
     "Cookie" (first (str/split (:cookie r) #";")) "X-CSRF-Token" (get-in r [:body :csrf])}))

(deftest private-detail-shows-missing-current-jev-pair
  (let [t (publication-fixture/sample (fn [a]
                                        (let [a (assoc-in a [:config :synthetic] true)]
                                          (assoc a :job-id (fixture/hash-value
                                                            (select-keys a [:source-sha256 :acquisitions :evidence-sha256
                                                                            :actor :config :parser-version :schema-version
                                                                            :pdfinfo-version :tool]))))))
        root (str (Files/createTempDirectory "owner-jev-scores-" (make-array java.nio.file.attribute.FileAttribute 0)))
        s (server/start! (assoc (config) :jev-run-root root :jev-provider-id "jev"))]
    (try
      (let [h (login s)
            row (first (candidates/load-corpus publication-fixture/reviewer {}))]
        (with-redefs [candidates/packet (fn [_ _ _] {:candidates [{:observations [row]}]})
                      jev-candidates/candidate-case (fn [& _] {:case-id "exact-pair" :input {:left {:record-id "left"}
                                                                                             :right {:record-id "right"}}})]
          (let [detail (request s "GET" (str "/api/detail?job-id=" (:job-id t) "&ordinal=0") nil h)]
            (is (= 200 (:status detail)))
            (is (= :missing (get-in detail [:body :jev-scores 0 :score-status])))
            (is (= "exact-pair" (get-in detail [:body :jev-scores 0 :case-id]))))))
      (finally (server/stop! s)))))
(deftest complete-review-and-validation-flow-preserves-source
  (let [t (publication-fixture/sample (fn [a] (let [a (assoc-in a [:config :synthetic] true)] (assoc a :job-id (fixture/hash-value (select-keys a [:source-sha256 :acquisitions :evidence-sha256 :actor :config :parser-version :schema-version :pdfinfo-version :tool]))))))
        original (observations/inspect fixture/app (:job-id t)) s (server/start! (config))]
    (try
      (let [h (login s) target-url (str "/api/detail?job-id=" (:job-id t) "&ordinal=0")
            detail (:body (request s "GET" target-url nil h))
            before (get-in detail [:effective :fields :source-name])
            p (merge t {:id "p1" :base-revision 0 :field "source-name" :category "name-normalization"
                        :before before :after "Synthetic Corrected" :actor "owner" :reason "Synthetic formatting evidence"
                        :evidence [{:page 1 :line 1}]})
            approve {:id "a1" :proposal-id "p1" :base-revision 0 :action "approve" :actor "owner" :reason "Checked evidence"}]
        (is (= 200 (:status (request s "GET" "/api/candidates" nil h))))
        (is (= 200 (:status (request s "GET" (str "/api/evidence?job-id=" (:job-id t) "&ordinal=0") nil h))))
        (is (= 200 (:status (request s "POST" "/api/proposals" p h))))
        (is (= 200 (:status (request s "POST" "/api/decisions" approve h))))
        (is (= 200 (:status (request s "POST" "/api/decisions" approve h))))
        (is (= 409 (:status (request s "POST" "/api/proposals" (assoc p :id "stale") h))))
        (is (= 409 (:status (request s "POST" "/api/decisions" (assoc approve :reason "changed") h))))
        (is (= "Synthetic Corrected" (get-in (request s "GET" target-url nil h) [:body :effective :fields :source-name])))
        (is (= 200 (:status (request s "POST" "/api/decisions" {:id "r1" :event-id "a1" :base-revision 1 :action "reverse" :actor "owner" :reason "Restore original"} h))))
        (is (= 200 (:status (request s "POST" "/api/proposals" (assoc p :id "p2" :base-revision 2) h))))
        (is (= 200 (:status (request s "POST" "/api/decisions" {:id "reject" :proposal-id "p2" :base-revision 2 :action "reject" :actor "owner" :reason "Needs more evidence"} h))))
        (let [d (get-in (request s "GET" target-url nil h) [:body :publication])
              v (merge t (select-keys d [:review-revision :policy-version :observation])
                       {:id "v1" :base-revision (:revision d) :action "validate" :actor "owner" :reason "Visually checked synthetic source"
                        :evidence [{:page 1 :line 1}] :attestations {:source-visual-accuracy true :no-unresolved-substantive-errors true}})]
          (is (= 400 (:status (request s "POST" "/api/publication" (assoc v :attestations {}) h))))
          (is (= 200 (:status (request s "POST" "/api/publication" v h))))
          (is (true? (get-in (request s "GET" target-url nil h) [:body :publication :eligible?])))
          (is (= 200 (:status (request s "POST" "/api/publication" (assoc v :id "rv1" :base-revision 1 :action "revoke" :attestations {}) h))))
          (is (false? (get-in (request s "GET" target-url nil h) [:body :publication :eligible?]))))
        (is (= (seq (:artifact-bytes original)) (seq (:artifact-bytes (observations/inspect fixture/app (:job-id t))))))
        (is (= 200 (:status (request s "POST" "/api/logout" {} h))))
        (is (= 401 (:status (request s "GET" target-url nil h)))))
      (finally (server/stop! s)))))

(deftest startup-rejects-unrestricted-and-nonsynthetic-databases
  (is (thrown? Exception (server/start! (assoc (config) :database-url fixture/admin))))
  (is (thrown? Exception (server/start! (assoc (config) :database-url fixture/app))))
  (is (thrown? Exception (server/start! (assoc (config) :database-url "jdbc:postgresql://127.0.0.1:1,remote.example:5432/db"))))
  (publication-fixture/sample)
  (is (thrown? Exception (server/start! (config)))))

(deftest request-boundaries-and-capability-lifetime
  (let [s (server/start! (config))]
    (try
      (let [h (login s)]
        (is (= 200 (:status (request s "GET" "/api/session" nil h))))
        (is (= 403 (:status (request s "POST" "/api/proposals" {} (dissoc h "Origin")))))
        (is (= 403 (:status (request s "POST" "/api/proposals" {} (assoc h "Origin" "http://evil.example")))))
        (is (= 415 (:status (request s "POST" "/api/proposals" {} (assoc h "Content-Type" "text/plain")))))
        (is (= 413 (:status (request s "POST" "/api/proposals" {:reason (apply str (repeat 65537 "x"))} h))))
        (is (= 404 (:status (request s "GET" "/api/evidence?job-id=unknown&ordinal=0" nil h))))
        (is (= 404 (:status (request s "GET" "/etc/passwd" nil h))))
        (is (= 405 (:status (request s "PUT" "/api/decisions" {} h))))
        (login s)
        (is (= 401 (:status (request s "GET" "/api/candidates" nil h))))
        (with-open [socket (java.net.Socket. "127.0.0.1" (:port s))]
          (.setSoTimeout socket 5000)
          (let [w (java.io.OutputStreamWriter. (.getOutputStream socket) "UTF-8")]
            (.write w "GET /api/session HTTP/1.1\r\nHost: attacker.example\r\nConnection: close\r\n\r\n") (.flush w)
            (is (str/includes? (.readLine (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream socket)))) "403")))))
      (finally (server/stop! s)))
    (is (not (.exists (java.io.File. (:capability-file s)))))))

(deftest policy-owner-capability-cannot-start-reviewer-server
  (fixture/sql! fixture/admin "GRANT INSERT ON freediving.publication_policy_events TO reviews_owner")
  (is (thrown? Exception (server/start! (config)))))

(deftest correction-queue-requires-owner-capability-and-triage-csrf
  (let [s (server/start! (config))]
    (try
      (is (= 401 (:status (request s "GET" "/api/corrections" nil {}))))
      (let [h (login s)]
        (is (= 200 (:status (request s "GET" "/api/corrections" nil h))))
        (is (= [] (get-in (request s "GET" "/api/corrections" nil h) [:body :requests])))
        (is (= 403 (:status (request s "POST" "/api/corrections/triage" {} (dissoc h "X-CSRF-Token")))))
        (is (= 405 (:status (request s "GET" "/api/corrections/triage" nil h))))
        (is (= 400 (:status (request s "GET" "/api/corrections?offset=-1" nil h)))))
      (finally (server/stop! s)))))

(deftest visitor-request-triage-never-substitutes-for-reviewed-approval
  (let [t (publication-fixture/sample (fn [a] (let [a (assoc-in a [:config :synthetic] true)] (assoc a :job-id (fixture/hash-value (select-keys a [:source-sha256 :acquisitions :evidence-sha256 :actor :config :parser-version :schema-version :pdfinfo-version :tool]))))))
        reviewer publication-fixture/reviewer
        reader-url (System/getenv "FREEDIVING_TEST_PUBLIC_URL")
        submit-url (System/getenv "FREEDIVING_TEST_SUBMIT_URL")]
    (publication/decide! reviewer (publication-fixture/request t "visible"))
    (public-results/refresh! reviewer)
    (let [row (first (public-results/results reader-url))
          r {:id (str (random-uuid)) :result-id (:result-id row)
             :version (corrections/target-version submit-url (:result-id row))
             :suggestion "Synthetic corrected <script>bad()</script>" :reason "Check the printed source name"
             :evidence "https://example.org/unverified#page=1"}
          _ (corrections/submit! submit-url r (apply str (repeat 64 "b")))
          s (server/start! (config))]
      (try
        (let [h (login s)
              queued (first (get-in (request s "GET" "/api/corrections" nil h) [:body :requests]))
              dismiss {:id (str (random-uuid)) :request-id (:id r) :base-revision 0 :action "dismiss"
                       :actor "owner" :reason "Visitor citation does not establish a correction"}
              p (merge t {:id "owner-registered-proposal" :base-revision 0 :field "source-name" :category "name-normalization"
                          :before (get-in row [:effective :source-name]) :after "Synthetic Corrected"
                          :actor "owner" :reason "Independently inspected registered source row" :evidence [{:page 1 :line 1}]})
              link (assoc dismiss :id (str (random-uuid)) :base-revision 1 :action "link-proposal"
                          :proposal-id (:id p) :reason "Registered source supports this normal proposal")]
          (is (= (:suggestion r) (:suggestion queued)))
          (is (= (:evidence r) (:evidence queued)))
          (is (= 400 (:status (request s "POST" "/api/corrections/triage" (assoc dismiss :reason "") h))))
          (is (= 200 (:status (request s "POST" "/api/corrections/triage" dismiss h))))
          (is (= 200 (:status (request s "POST" "/api/corrections/triage" dismiss h))))
          (is (= 409 (:status (request s "POST" "/api/corrections/triage" (assoc dismiss :id (str (random-uuid))) h))))
          (is (= 400 (:status (request s "POST" "/api/corrections/triage" link h))))
          (is (= 400 (:status (request s "POST" "/api/proposals" (assoc p :evidence [{:url (:evidence r)}]) h))))
          (is (= 200 (:status (request s "POST" "/api/proposals" p h))))
          (is (= 200 (:status (request s "POST" "/api/corrections/triage" link h))))
          (is (= row (first (public-results/results reader-url))))
          (let [events (get-in (request s "GET" "/api/corrections" nil h) [:body :requests 0 :history])]
            (is (= ["dismiss" "link-proposal"] (mapv :action events))))
          (is (= 200 (:status (request s "POST" "/api/decisions"
                                       {:id "owner-reject" :proposal-id (:id p) :base-revision 0 :action "reject"
                                        :actor "owner" :reason "More corroboration needed"} h))))
          (is (= (get-in row [:effective :source-name]) (:source-name (:fields (reviews/effective reviewer t)))))
          (is (= (:suggestion r) (get-in (request s "GET" "/api/corrections" nil h) [:body :requests 0 :suggestion]))))
        (finally (server/stop! s))))))

(deftest owner-startup-rejects-correction-record-mutation-authority
  (fixture/sql! fixture/admin "GRANT INSERT ON freediving.correction_requests TO reviews_owner")
  (is (thrown? Exception (let [s (server/start! (config))] (server/stop! s)))))

(deftest synthetic-session-reports-explicit-authority
  (let [s (server/start! (config))]
    (try
      (let [session (:body (request s "GET" "/api/session" nil (login s)))]
        (is (= "synthetic-demo" (:mode session)))
        (is (true? (:review-enabled? session)))
        (is (false? (:source-viewer? session))))
      (finally (server/stop! s)))))

(deftest inspection-refuses-reviewer-write-authority-before-source-access
  (let [c (-> (config) (dissoc :demo?) (assoc :mode :real-inspection :archive-root "/missing" :cache-root "/missing"))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Inspector table privileges invalid" (server/start! c)))))

(defn inspector! []
  (fixture/sql! fixture/admin "DO $$ BEGIN CREATE ROLE source_inspector LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT; EXCEPTION WHEN duplicate_object THEN NULL; END $$")
  (fixture/sql! fixture/admin "GRANT USAGE ON SCHEMA freediving TO source_inspector; GRANT SELECT ON freediving.extractions,freediving.observations,freediving.review_proposals,freediving.review_decisions,freediving.extraction_reviews,freediving.publication_decisions,freediving.publication_policy_events,freediving.correction_requests,freediving.correction_triage TO source_inspector")
  (str/replace publication-fixture/reviewer "user=reviews_owner" "user=source_inspector"))

(deftest registered-pages-stay-private-and-real-inspection-cannot-mutate
  (let [[page-config row] (pages-fixture/sample)
        _ (observations/import! fixture/app (:archive-root page-config) (:job-id row))
        c (merge (dissoc (config) :demo?) page-config {:mode :real-inspection :database-url (inspector!)})
        s (server/start! c)
        path (str "/api/source-page?job-id=" (:job-id row) "&ordinal=0&page=1")
        png (str/replace path "/api/source-page?" "/api/source-page.png?")]
    (try
      (is (= 401 (:status (request s "GET" png nil {}))))
      (let [h (login s) info (request s "GET" path nil h) image-url (get-in info [:body :image-url])]
        (is (= 200 (:status info)))
        (is (= 1 (get-in info [:body :page-count])))
        (is (= (:source-sha256 row) (get-in info [:body :source-sha256])))
        (is (= "real-inspection" (get-in (request s "GET" "/api/session" nil h) [:body :mode])))
        (is (false? (get-in (request s "GET" "/api/session" nil h) [:body :review-enabled?])))
        (let [image (request s "GET" image-url nil h)]
          (is (= 200 (:status image)))
          (is (= ["image/png"] (get-in image [:headers "content-type"])))
          (is (= ["no-store"] (get-in image [:headers "cache-control"]))))
        (doseq [endpoint ["/api/proposals" "/api/decisions" "/api/publication" "/api/corrections/triage"]]
          (is (= 403 (:status (request s "POST" endpoint {} h)))))
        (is (= 400 (:status (request s "GET" (str/replace path "page=1" "page=2") nil h))))
        (is (= 400 (:status (request s "GET" (str path "&path=../../etc/passwd") nil h))))
        (is (= 409 (:status (request s "GET" (str png "&render-id=stale") nil h))))
        (let [source (str (:archive-root page-config) "/objects/" (:source-sha256 row))
              original (java.nio.file.Files/readAllBytes (.toPath (java.io.File. source)))]
          (spit source "corrupt synthetic fixture")
          (is (= 400 (:status (request s "GET" image-url nil h))))
          (is (thrown? Exception (server/start! (assoc c :capability-file (str (:capability-file c) "-corrupt")))))
          (java.nio.file.Files/write (.toPath (java.io.File. source)) original (make-array java.nio.file.OpenOption 0)))
        (is (= 200 (:status (request s "POST" "/api/logout" {} h))))
        (is (= 401 (:status (request s "GET" image-url nil h)))))
      (finally (server/stop! s)))
    (is (thrown? Exception (server/start! (assoc c :review-enabled? true))))))

(deftest inspection-rejects-hidden-column-or-function-authority
  (let [url (inspector!) c (merge (dissoc (config) :demo?) {:mode :real-inspection :database-url url :archive-root "/missing" :cache-root "/missing"})]
    (fixture/sql! fixture/admin "GRANT INSERT (job_id) ON freediving.extractions TO source_inspector")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"(?:Inspector|Owner) database authority invalid" (server/start! c)))
    (fixture/sql! fixture/admin "REVOKE INSERT (job_id) ON freediving.extractions FROM source_inspector; GRANT EXECUTE ON FUNCTION freediving.submit_correction(uuid,text,text,text,text,text,text) TO source_inspector")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"(?:Inspector|Owner) database authority invalid" (server/start! c)))))

(deftest enabled-review-requires-served-target-image-and-separate-attestation
  (let [[page-config row] (pages-fixture/sample)
        _ (observations/import! fixture/app (:archive-root page-config) (:job-id row))
        s (server/start! (merge (dissoc (config) :demo?) page-config {:mode :real-inspection :review-enabled? true}))
        target (select-keys row [:job-id :ordinal])
        path (str "/api/source-page?job-id=" (:job-id row) "&ordinal=0&page=1")]
    (try
      (let [h (login s) d (publication/diagnose publication-fixture/reviewer target)
            validation (merge target (select-keys d [:review-revision :policy-version :observation])
                              {:id "fixture-validate" :base-revision (:revision d) :action "validate" :actor "synthetic-test"
                               :reason "Inspected synthetic PDF fixture" :evidence [{:page 1 :line 3}]
                               :attestations {:source-visual-accuracy true :no-unresolved-substantive-errors true}})
            metadata (request s "GET" path nil h)]
        (is (= 200 (:status metadata)))
        (is (= 403 (:status (request s "POST" "/api/publication" validation h))))
        (is (= 200 (:status (request s "GET" (get-in metadata [:body :image-url]) nil h))))
        (is (= 400 (:status (request s "POST" "/api/publication" (assoc validation :attestations {}) h))))
        (is (= 200 (:status (request s "POST" "/api/publication" validation h))))
        (let [new-h (login s)]
          (is (= 403 (:status (request s "POST" "/api/publication" validation new-h))))))
      (finally (server/stop! s)))))

(deftest review-mode-refuses-hidden-original-write-authority
  (fixture/sql! fixture/admin "GRANT UPDATE (source_sha256) ON freediving.extractions TO reviews_owner")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Owner database authority invalid" (server/start! (config)))))

(deftest explicit-evaluation-label-capability-keeps-owner-startup-compatible
  (labels/migrate! fixture/admin "reviews_owner" :synthetic)
  (let [s (server/start! (config))]
    (try
      (is (= 401 (:status (request s "GET" "/api/candidates" nil {}))))
      (finally (server/stop! s))))
  (fixture/sql! fixture/admin "GRANT UPDATE (body_edn) ON freediving.evaluation_labels TO reviews_owner")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Owner database authority invalid"
                        (server/start! (config)))))

(deftest explicit-evaluation-label-capability-is-not-inspector-or-public-authority
  (labels/migrate! fixture/admin "reviews_owner" :synthetic)
  (let [url (inspector!)
        c (merge (dissoc (config) :demo?)
                 {:mode :real-inspection :database-url url :archive-root "/missing" :cache-root "/missing"})]
    (fixture/sql! fixture/admin "GRANT INSERT ON freediving.evaluation_labels TO source_inspector")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Inspector database authority invalid"
                          (server/start! c))))
  (is (thrown? Exception
               (server/start! (assoc (config) :database-url (System/getenv "FREEDIVING_TEST_PUBLIC_URL"))))))

(deftest html-inspection-is-private-bound-and-rechecked-before-attestation
  (let [[page-config row] (pages-fixture/html-sample)
        _ (observations/import! fixture/app (:archive-root page-config) (:job-id row))
        s (server/start! (merge (dissoc (config) :demo?) page-config {:mode :real-inspection :review-enabled? true}))
        target (select-keys row [:job-id :ordinal])
        path (str "/api/source-html?job-id=" (:job-id row) "&ordinal=0")]
    (try
      (is (= 401 (:status (request s "GET" path nil {}))))
      (let [h (login s)
            validation (merge target {:id "html-validate" :action "validate" :actor "synthetic-test"
                                      :reason "Inspected synthetic HTML"})]
        (is (= 403 (:status (request s "POST" "/api/publication" validation h))))
        (let [response (request s "GET" path nil h)]
          (is (= 200 (:status response)))
          (is (= ["application/json; charset=utf-8"] (get-in response [:headers "content-type"])))
          (is (= ["no-store"] (get-in response [:headers "cache-control"])))
          (is (= (:source-sha256 row) (get-in response [:body :source-sha256])))
          (is (= (get-in row [:payload :coordinates]) (get-in response [:body :coordinates])))
          (is (nil? (get-in response [:body :page])))
          (is (= "Synthetic Pool Championship" (get-in response [:body :event-name]))))
        (is (= 400 (:status (request s "GET" (str path "&path=../../etc/passwd") nil h))))
        (is (= 400 (:status (request s "GET" (str path "&row=999") nil h))))
        (is (= 400 (:status (request s "POST" "/api/publication" validation h))))
        (spit (str (:archive-root page-config) "/objects/" (:source-sha256 row)) "changed source")
        (is (= 400 (:status (request s "POST" "/api/publication" validation h))))
        (is (= 400 (:status (request s "GET" path nil h))))
        (let [new-h (login s)]
          (is (= 403 (:status (request s "POST" "/api/publication" validation new-h))))))
      (finally (server/stop! s)))))

(deftest html-validation-requires-inspection-and-explicit-attestations
  (let [[source-config row] (with-redefs [html-fixture/cells (assoc html-fixture/cells 10 "")] (pages-fixture/html-sample))
        _ (observations/import! fixture/app (:archive-root source-config) (:job-id row))
        _ (publication/activate-policy! fixture/admin "extraction-publication/2" "Synthetic test only")
        s (server/start! (merge (dissoc (config) :demo?) source-config {:mode :real-inspection :review-enabled? true}))
        target (select-keys row [:job-id :ordinal])
        path (str "/api/source-html?job-id=" (:job-id row) "&ordinal=0")]
    (try
      (let [h (login s) validation (publication-fixture/html-request target "html-valid")]
        (is (= 403 (:status (request s "POST" "/api/publication" validation h))))
        (is (= 200 (:status (request s "GET" path nil h))))
        (is (= 400 (:status (request s "POST" "/api/publication" (assoc validation :attestations {}) h))))
        (is (= 200 (:status (request s "POST" "/api/publication" validation h))))
        (is (:eligible? (publication/diagnose publication-fixture/reviewer target)))
        (is (= 200 (:status (request s "POST" "/api/publication" (assoc validation :id "html-revoke" :action "revoke" :base-revision 1 :attestations {}) h))))
        (is (false? (:eligible? (publication/diagnose publication-fixture/reviewer target)))))
      (finally (server/stop! s)))))

(deftest pdf-table-hints-still-require-pdf-inspection-and-page-line-attestation
  (let [[page-config row] (pages-fixture/pdf-with-table-hints)
        _ (observations/import! fixture/app (:archive-root page-config) (:job-id row))
        s (server/start! (merge (dissoc (config) :demo?) page-config {:mode :real-inspection :review-enabled? true}))
        target (select-keys row [:job-id :ordinal])
        q (str "?job-id=" (:job-id row) "&ordinal=0")]
    (try
      (let [h (login s) d (publication/diagnose publication-fixture/reviewer target)
            validation (merge target (select-keys d [:review-revision :policy-version :observation])
                              {:id "pdf-hints-validate" :base-revision (:revision d) :action "validate" :actor "synthetic-test"
                               :reason "Inspected PDF with optional table hints" :evidence [{:page 1 :line 3}]
                               :attestations {:source-visual-accuracy true :no-unresolved-substantive-errors true}})
            detail (get-in (request s "GET" (str "/api/detail" q) nil h) [:body :packet])
            reference (get-in detail [:local-identity-anchor :reference])
            metadata (request s "GET" (str "/api/source-page" q "&page=1") nil h)]
        (is (= "pdf" (get-in detail [:target :source-format])))
        (is (= {:page 1 :line 3} (select-keys reference [:page :line :table :row])))
        (is (= 400 (:status (request s "GET" (str "/api/source-html" q) nil h))))
        (is (= 200 (:status (request s "GET" (get-in metadata [:body :image-url]) nil h))))
        (is (= 200 (:status (request s "POST" "/api/publication" validation h)))))
      (finally (server/stop! s)))))

(deftest normal-deployment-revision-capabilities-allow-owner-but-not-extra-writes
  (let [deployment (shell/sh "clojure" "-M" "-m" "freediving.deployment"
                             :env (assoc (into {} (System/getenv)) "FREEDIVING_MIGRATION_URL" fixture/admin))]
    (is (zero? (:exit deployment)) (str (:out deployment) (:err deployment)))
    (let [s (server/start! (config))]
      (try (is (= 401 (:status (request s "GET" "/api/candidates" nil {}))))
           (finally (server/stop! s))))
    (fixture/sql! fixture/admin "GRANT UPDATE (body_edn) ON freediving.revision_proposals TO reviews_owner")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Owner database authority invalid" (server/start! (config))))
    (fixture/sql! fixture/admin "REVOKE UPDATE (body_edn) ON freediving.revision_proposals FROM reviews_owner")
    (let [url (inspector!)]
      (fixture/sql! fixture/admin "GRANT INSERT (body_edn) ON freediving.revision_proposals TO source_inspector")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Inspector database authority invalid"
                            (server/start! (merge (dissoc (config) :demo?) {:mode :real-inspection :database-url url :archive-root "/missing" :cache-root "/missing"})))))))
