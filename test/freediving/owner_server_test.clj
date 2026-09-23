(ns freediving.owner-server-test
  (:require [clojure.test :refer [deftest is use-fixtures run-tests]]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [freediving.owner-server :as server]
            [freediving.publication-test :as publication-fixture]
            [freediving.observations-test :as fixture]
            [freediving.observations :as observations]
            [freediving.reviews :as reviews]
            [freediving.publication :as publication])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.nio.file Files]))
(use-fixtures :each (fn [f]
                      (fixture/sql! fixture/admin "DROP SCHEMA IF EXISTS freediving CASCADE")
                      (observations/migrate! fixture/admin "observations_app")
                      (reviews/migrate! fixture/admin "observations_app" "reviews_owner")
                      (publication/migrate! fixture/admin "reviews_owner") (f)))
(defn request [s method path data headers]
  (let [b (HttpRequest/newBuilder (URI/create (str (:url s) path)))
        _ (doseq [[k v] headers] (.header b k v))
        _ (.method b method (if data (HttpRequest$BodyPublishers/ofString (json/write-str data)) (HttpRequest$BodyPublishers/noBody)))
        r (.send (HttpClient/newHttpClient) (.build b) (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode r) :body (try (json/read-str (.body r) :key-fn keyword) (catch Exception _ (.body r)))
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
