(ns freediving.correction-http-test
  (:require [clojure.data.json :as json]
            [clojure.java.shell :as shell]
            [freediving.evaluation-labels :as labels]
            [freediving.revisions :as revisions]
            [freediving.corrections :as corrections]
            [freediving.public-server-test :as http]
            [clojure.test :refer [deftest is use-fixtures run-tests]]
            [freediving.public-server :as server]
            [freediving.observations-test :as fixture]
            [freediving.observations :as observations]
            [freediving.reviews :as reviews]
            [freediving.publication :as publication]
            [freediving.public-results :as public]
            [freediving.public-results-test :as sample])
  (:import [java.net URI] [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers] [java.util UUID]))
(use-fixtures :each
  (fn [f]
    (fixture/sql! fixture/admin "DROP SCHEMA IF EXISTS freediving CASCADE")
    (observations/migrate! fixture/admin "observations_app")
    (reviews/migrate! fixture/admin "observations_app" "reviews_owner")
    (publication/migrate! fixture/admin "reviews_owner")
    (public/migrate! fixture/admin "reviews_owner" "reviews_public")
    (corrections/migrate! fixture/admin "reviews_owner" "corrections_submit")
    (f)))
(deftest configured-submission-capability-must-be-verified
  (is (thrown? Exception
               (let [s (server/start! {:database-url sample/reader-url :port 0
                                       :submission-database-url sample/reader-url})]
                 (server/stop! s)))))
(deftest public-reader-cannot-also-execute-submission-capability
  (fixture/sql! fixture/admin "GRANT EXECUTE ON FUNCTION freediving.submit_correction(uuid,text,text,text,text,text,text) TO reviews_public")
  (try
    (is (thrown? Exception (let [app (server/start! {:database-url sample/reader-url :port 0})] (server/stop! app))))
    (finally (fixture/sql! fixture/admin "REVOKE EXECUTE ON FUNCTION freediving.submit_correction(uuid,text,text,text,text,text,text) FROM reviews_public"))))
(def submit-url (System/getenv "FREEDIVING_TEST_SUBMIT_URL"))
(defn post [url body & [headers path]]
  (let [b (HttpRequest/newBuilder (URI/create (str url (or path "/api/corrections"))))]
    (doseq [[k v] (merge {"Origin" url "Content-Type" "application/json" "X-Correction-Request" "1"} headers) :when v] (.header b k v))
    (.POST b (HttpRequest$BodyPublishers/ofString (if (string? body) body (json/write-str body))))
    (let [r (.send (HttpClient/newHttpClient) (.build b) (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode r) :body (json/read-str (.body r) :key-fn keyword) :headers (.headers r)})))
(defn with-corrections [f]
  (let [target (sample/sample)]
    (sample/validate! target "public-correction-target") (public/refresh! sample/reviewer)
    (let [app (server/start! {:database-url sample/reader-url :submission-database-url submit-url :port 0})
          row (first (public/results sample/reader-url))
          detail (:body (http/request (:url app) (str "/api/results/" (:result-id row))))
          request {:id (str (UUID/randomUUID)) :result-id (:result-id row) :version (get-in detail [:correction :version])
                   :suggestion "Żółć <script>32</script>" :reason "Compare original source" :evidence "https://example.org/result.pdf page 1 row 1"}]
      (try (f (:url app) request target row) (finally (server/stop! app))))))
(deftest anonymous-private-receipts-retry-and-persistent-limits
  (with-corrections
    (fn [url r _ row]
      (let [receipt (post url r)]
        (is (= 200 (:status receipt)))
        (is (= "pending" (get-in receipt [:body :status])))
        (is (= (:id r) (get-in receipt [:body :id])))
        (is (= true (get-in (post url r) [:body :duplicate])))
        (is (= 409 (:status (post url (assoc r :reason "changed")))))
        (is (= (:id r) (get-in (post url (assoc r :id (str (UUID/randomUUID)))) [:body :id])))
        (is (= row (public/result sample/reader-url (:result-id r))))
        (is (= 404 (:status (http/request url "/api/corrections"))))
        (is (not (.contains (pr-str (:body receipt)) "Żółć")))
        (doseq [n (range 4)] (is (= 200 (:status (post url (assoc r :id (str (UUID/randomUUID)) :suggestion (str "change " n)))))))
        (is (= 429 (:status (post url (assoc r :id (str (UUID/randomUUID)) :suggestion "sixth") {"X-Forwarded-For" "192.0.2.9" "Forwarded" "for=192.0.2.10"}))))
        (is (= true (get-in (post url r) [:body :duplicate])))
        (let [app (server/start! {:database-url sample/reader-url :submission-database-url submit-url :port 0})]
          (try (is (= 429 (:status (post (:url app) (assoc r :id (str (UUID/randomUUID)) :suggestion "after restart")))))
               (finally (server/stop! app))))))))
(deftest strict-body-origin-and-inert-evidence
  (with-corrections
    (fn [url r _ _]
      (doseq [headers [{"Origin" nil} {"Origin" "https://evil.example"} {"X-Correction-Request" nil}]]
        (is (= 403 (:status (post url r headers)))))
      (is (= 415 (:status (post url r {"Content-Type" "text/plain"}))))
      (is (= 413 (:status (post url (apply str (repeat 16385 "x"))))))
      (doseq [body [(str (json/write-str r) " {}") (str "{\"id\":\"duplicate\"," (subs (json/write-str r) 1))
                    (assoc r :suggestion ["nested"]) (assoc r :upload "../private") (assoc r :reason "")
                    (assoc r :suggestion (apply str (repeat 1001 "x")))
                    (assoc r :evidence "javascript:alert(1)") (assoc r :evidence "https://user:secret@example.org/a")
                    (assoc r :evidence "https://example.org/a?token=secret")]]
        (is (= 400 (:status (post url body))) (pr-str (dissoc (if (map? body) body {}) :id))))
      (is (= 400 (:status (post url r nil "/api/corrections?reason=private"))))
      (is (= 200 (:status (post url r)))))))
(deftest stale-hidden-revoked-and-absent-targets-indistinguishable
  (with-corrections
    (fn [url r target _]
      (sample/validate! target "revoked" :revoke)
      (let [unavailable (select-keys (post url r) [:status :body])]
        (is (= 404 (:status unavailable)))
        (is (= unavailable (select-keys (post url (assoc r :result-id (apply str (repeat 64 "0")))) [:status :body])))
        (sample/validate! target "new-validation") (public/refresh! sample/reviewer)
        (is (= unavailable (select-keys (post url r) [:status :body])))
        (is (empty? (:requests (corrections/list-requests sample/reviewer {}))))))))
(defn -main [& _]
  (let [r (run-tests 'freediving.correction-http-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail r) (:error r))) (System/exit 1))))

(deftest authenticated-gateway-keeps-visitor-rate-limits-separate
  (let [target (sample/sample)
        _ (sample/validate! target "gateway-target")
        _ (public/refresh! sample/reviewer)
        row (first (public/results sample/reader-url))
        detail (corrections/public-detail sample/reader-url (:result-id row))
        secret (apply str (repeat 64 "b"))
        origin "https://poc.alphacompose.com"
        app (server/start! {:database-url sample/reader-url :submission-database-url submit-url
                            :public-origin origin :gateway-secret secret :port 0})
        url (str "http://127.0.0.1:" (.getPort (.getAddress ^com.sun.net.httpserver.HttpServer (:server app))))
        send (fn [ip n]
               (post url {:id (str (UUID/randomUUID)) :result-id (:result-id row)
                          :version (get-in detail [:correction :version]) :suggestion (str "change " n)
                          :reason "Check original source" :evidence "https://example.org/source.pdf page 1"}
                     {"Origin" origin "X-Freediving-Gateway" secret "X-Freediving-Client" ip}))]
    (try
      (doseq [n (range 5)] (is (= 200 (:status (send "203.0.113.1" n)))))
      (is (= 429 (:status (send "203.0.113.1" 6))))
      (is (= 200 (:status (send "203.0.113.2" 7))))
      (finally (server/stop! app)))))

(defn deploy! []
  (let [r (shell/sh "java" "-cp" (System/getProperty "java.class.path") "clojure.main" "-m" "freediving.deployment"
                    :env (assoc (into {} (System/getenv)) "FREEDIVING_MIGRATION_URL" fixture/admin))]
    (is (zero? (:exit r)) (:err r))))
(deftest normal-deployment-fresh-and-historical-upgrade-preserve-corrections-and-policy
  (doseq [mode [:fresh :historical :standalone]
          :let [historical? (= mode :historical)]]
    (fixture/sql! fixture/admin "DROP SCHEMA freediving CASCADE")
    (when (not= mode :fresh)
      (observations/migrate! fixture/admin "observations_app")
      (reviews/migrate! fixture/admin "observations_app" "reviews_owner")
      (publication/migrate! fixture/admin "reviews_owner")
      (public/migrate! fixture/admin "reviews_owner" "reviews_public" {:defer-html-view? historical?})
      (corrections/migrate! fixture/admin "reviews_owner" "corrections_submit")
      (labels/migrate! fixture/admin "reviews_owner" :real)
      (revisions/migrate! fixture/admin "observations_app" "reviews_owner")
      (when historical?
        (fixture/sql! fixture/admin "DO $$ BEGIN IF EXISTS(SELECT 1 FROM freediving.schema_migrations WHERE version=9) THEN RAISE EXCEPTION 'Not a historical upgrade'; END IF; END $$")))
    (let [t (when historical? (sample/sample))]
      (when t (sample/validate! t "historical") (public/refresh! sample/reviewer))
      (deploy!)
      (deploy!)
      (when t (is (= 1 (count (public/results sample/reader-url)))))
      (public/activate-html-policy! fixture/admin "hide-existing-public-results" "Synthetic deployment checkpoint")
      (is (= [] (public/results sample/reader-url)))
      (let [t (or t (sample/sample)) d (publication/diagnose sample/reviewer t)
            request (merge t {:id "policy2" :action :validate :base-revision (:revision d)
                              :review-revision (:review-revision d) :policy-version "extraction-publication/2"
                              :observation (:observation d) :evidence [{:page 1 :line 1}] :actor "synthetic"
                              :attestations {:source-visual-accuracy true :no-unresolved-substantive-errors true} :reason "Synthetic validation"})]
        (publication/decide! sample/reviewer request)
        (public/refresh! sample/reviewer)
        (is (= 1 (count (public/results sample/reader-url))))
        (let [app (server/start! {:database-url sample/reader-url :submission-database-url submit-url :port 0})]
          (try
            (let [row (first (public/results sample/reader-url))
                  detail (http/request (:url app) (str "/api/results/" (:result-id row)))
                  correction {:id (str (UUID/randomUUID)) :result-id (:result-id row)
                              :version (get-in detail [:body :correction :version])
                              :suggestion "90 m" :reason "Synthetic source" :evidence "https://example.org/results.pdf page 1"}]
              (is (= 200 (:status detail)))
              (is (string? (:version correction)))
              (is (= 200 (:status (post (:url app) correction))))
              (publication/decide! sample/reviewer (assoc request :id "revoke" :action :revoke :base-revision (inc (:base-revision request))))
              (is (= 404 (:status (http/request (:url app) (str "/api/results/" (:result-id row)))))))
            (finally (server/stop! app))))))))

(deftest standalone-correction-install-refuses-unverified-html-view-and-rolls-back
  (fixture/sql! fixture/admin "DROP SCHEMA freediving CASCADE")
  (observations/migrate! fixture/admin "observations_app")
  (reviews/migrate! fixture/admin "observations_app" "reviews_owner")
  (publication/migrate! fixture/admin "reviews_owner")
  (public/migrate! fixture/admin "reviews_owner" "reviews_public")
  (fixture/sql! fixture/admin "UPDATE freediving.schema_migrations SET sha256=repeat('0',64) WHERE version=9")
  (is (thrown? Exception (corrections/migrate! fixture/admin "reviews_owner" "corrections_submit")))
  (is (false? (fixture/sql! fixture/admin "DO $$ BEGIN IF EXISTS(SELECT 1 FROM freediving.schema_migrations WHERE version=5) OR to_regclass('freediving.correction_requests') IS NOT NULL THEN RAISE EXCEPTION 'Partial correction install'; END IF; END $$"))))
