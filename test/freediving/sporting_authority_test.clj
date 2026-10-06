(ns freediving.sporting-authority-test
  (:require [clojure.test :refer [deftest is use-fixtures run-tests]]
            [freediving.public-results :as public]
            [freediving.public-sporting-test :as fixture]
            [freediving.public-sporting :as sporting]
            [freediving.public-results-test :as sample]
            [freediving.observations-test :as db]
            [freediving.sporting-authority :as authority]
            [freediving.public-server-test :as http])
  (:import [java.sql DriverManager]))
(use-fixtures :each (fn [f] (fixture/setup!) (fixture/with-private-authority! f)))
(deftest local-preparation-cannot-publish-without-fresh-private-authority
  (with-redefs [fixture/publish-authorized! #(sporting/publish-derived! db/admin %)]
    (fixture/synthetic-cohort!))
  (let [response (sporting/read-response sample/reader-url "/api/comparison" {})]
    (is (= :withheld (:status response)))
    (is (= [] (:rows response)))
    (is (= 0 (get-in response [:coverage :eligible-comparison-peers])))))
(deftest authenticated-private-review-delivery-and-reversal-control-all-public-reads
  (fixture/synthetic-cohort!)
  (http/with-server
    (fn [url]
      (let [body (:body (http/request url "/api/comparison")) row (first (:rows body))
            detail (:detail-api-url row) peer (get-in row [:ranks 2 :api-url])]
        (is (= "ranked" (:status body)))
        (is (= 3 (get-in body [:coverage :eligible-comparison-peers])))
        (is (= 200 (:status (http/request url detail))))
        (fixture/private-command! {:op "reverse"})
        ;; Reversal withdraws reads before delivery or a public database change.
        (is (= "withheld" (get-in (http/request url "/api/comparison") [:body :status])))
        (is (= 404 (:status (http/request url detail))))
        (is (= 404 (:status (http/request url peer))))
        (is (= 4 (get-in (http/request url "/api/results") [:body :total])))
        (is (= :withheld (:status (sporting/deliver-current! db/admin))))))))
(deftest ordered-first-delivery-is-idempotent-even-when-writers-arrive-together
  (with-redefs [fixture/publish-authorized!
                (fn [payload]
                  (fixture/private-command! {:op "stage" :publication payload})
                  (fixture/private-command! {:op "approve"}))]
    (fixture/synthetic-cohort!))
  (let [gate (promise)
        writers (doall (repeatedly 4 #(future @gate (sporting/deliver-current! db/admin))))
        _ (deliver gate true)
        results (mapv deref writers)
        counts #(fixture/query! "SELECT (SELECT count(*) FROM freediving.public_sporting_bridge_receipts) AS receipts,(SELECT count(*) FROM freediving.public_sporting_authority_events) AS authority")
        before-retry (counts)]
    (is (every? :revision results))
    (is (= 1 (count (set (map :revision results)))))
    (is (= [{:receipts 4 :authority 1}] before-retry))
    (dotimes [_ 2] (sporting/deliver-current! db/admin))
    (is (= before-retry (counts)))
    (is (= :ranked (:status (sporting/read-response sample/reader-url "/api/comparison" {}))))))
(deftest expired-private-authority-withdraws-every-read-and-old-link
  (fixture/synthetic-cohort!)
  (http/with-server
    (fn [url]
      (let [row (first (get-in (http/request url "/api/comparison") [:body :rows]))]
        (fixture/private-command! {:op "expire"})
        (is (= "withheld" (get-in (http/request url "/api/comparison") [:body :status])))
        (is (= 404 (:status (http/request url (:detail-api-url row)))))
        (is (= 404 (:status (http/request url (get-in row [:ranks 2 :api-url])))))
        (is (= 4 (get-in (http/request url "/api/results") [:body :total])))))))
(deftest unavailable-current-private-source-authority-refuses-all-old-links
  (fixture/synthetic-cohort!)
  (http/with-server
    (fn [url]
      (let [row (first (get-in (http/request url "/api/comparison") [:body :rows]))]
        (fixture/private-command! {:op "unavailable"})
        (is (= "withheld" (get-in (http/request url "/api/comparison") [:body :status])))
        (is (= 404 (:status (http/request url (:detail-api-url row)))))
        (is (= 404 (:status (http/request url (get-in row [:ranks 2 :api-url])))))
        (is (= 4 (get-in (http/request url "/api/results") [:body :total])))))))
(deftest withdrawn-cited-rule-refuses-cached-ranks-detail-and-peer-replay
  (fixture/synthetic-cohort!)
  (http/with-server
    (fn [url]
      (let [row (first (get-in (http/request url "/api/comparison") [:body :rows]))]
        (fixture/private-command! {:op "rule-withdraw"})
        (is (= "withheld" (get-in (http/request url "/api/comparison") [:body :status])))
        (is (= 404 (:status (http/request url (:detail-api-url row)))))
        (is (= 404 (:status (http/request url (get-in row [:ranks 2 :api-url])))))
        (is (= 4 (get-in (http/request url "/api/results") [:body :total])))))))
(deftest signed-malformed-or-tampered-feed-never-writes-receipts-or-serves-old-links
  (doseq [op ["tamper" "missing-predecessor"]]
    (fixture/setup!)
    (fixture/with-private-authority!
      (fn []
        (fixture/synthetic-cohort!)
        (http/with-server
          (fn [url]
            (let [row (first (get-in (http/request url "/api/comparison") [:body :rows]))
                  before (fixture/query! "SELECT count(*) AS n FROM freediving.public_sporting_bridge_receipts")]
              (fixture/private-command! {:op op})
              (is (thrown? Exception (sporting/deliver-current! db/admin)))
              (is (= before (fixture/query! "SELECT count(*) AS n FROM freediving.public_sporting_bridge_receipts")))
              (is (= "withheld" (get-in (http/request url "/api/comparison") [:body :status])))
              (is (= 404 (:status (http/request url (:detail-api-url row)))))
              (is (= 404 (:status (http/request url (get-in row [:ranks 2 :api-url])))))
              (is (= 4 (get-in (http/request url "/api/results") [:body :total]))))))))))
(deftest source-publication-change-during-delivery-refuses-the-stale-preparation
  (let [{:keys [targets]} (with-redefs [fixture/publish-authorized!
                                        (fn [payload]
                                          (fixture/private-command! {:op "stage" :publication payload})
                                          (fixture/private-command! {:op "approve"}))]
                            (fixture/synthetic-cohort!))]
    (db/sql! db/admin "CREATE FUNCTION freediving.synthetic_delivery_pause() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(781246999); RETURN NEW; END $$; CREATE TRIGGER aa_synthetic_delivery_pause BEFORE INSERT ON freediving.public_sporting_authority_events FOR EACH ROW EXECUTE FUNCTION freediving.synthetic_delivery_pause()")
    (with-open [lock (DriverManager/getConnection db/admin) statement (.createStatement lock)]
      (.execute statement "SELECT pg_advisory_lock(781246999)")
      (let [writer (future (try (sporting/deliver-current! db/admin) :accepted (catch Exception _ :rejected)))]
        (try
          (loop [remaining 100]
            (when-not (pos? (:n (first (fixture/query! "SELECT count(*) AS n FROM pg_locks WHERE locktype='advisory' AND objid=781246999 AND NOT granted"))))
              (when (zero? remaining) (throw (ex-info "Synthetic delivery did not reach pause" {})))
              (Thread/sleep 20)
              (recur (dec remaining))))
          (sample/validate! (first targets) "synthetic-source-revision-during-delivery")
          (public/refresh! sample/reviewer)
          (finally (.execute statement "SELECT pg_advisory_unlock(781246999)")))
        (is (= :rejected @writer))
        (is (= 0 (:n (first (fixture/query! "SELECT count(*) AS n FROM freediving.public_sporting_authority_events")))))
        (is (= :withheld (:status (sporting/read-response sample/reader-url "/api/comparison" {}))))
        (is (= 4 (:n (first (fixture/query! "SELECT count(*) AS n FROM freediving.public_results")))))))))
(deftest changed-private-pins-or-unavailable-signer-withdraw-without-changing-source-records
  (fixture/synthetic-cohort!)
  (let [current-config (:config fixture/*private-authority*)]
    (is (= :ranked (:status (sporting/read-response sample/reader-url "/api/comparison" {}))))
    (with-redefs [authority/config (constantly (assoc current-config :request_secret (apply str (repeat 32 "x"))))]
      (is (= [] (:rows (sporting/read-response sample/reader-url "/api/comparison" {})))))
    (let [before (fixture/query! "SELECT count(*) AS n FROM freediving.public_sporting_bridge_receipts")]
      (with-redefs [authority/config (constantly (assoc current-config :key_id (authority/sha "different-signer")))]
        (is (= [] (:rows (sporting/read-response sample/reader-url "/api/comparison" {}))))
        (is (thrown? Exception (sporting/deliver-current! db/admin))))
      (is (= before (fixture/query! "SELECT count(*) AS n FROM freediving.public_sporting_bridge_receipts"))))
    (fixture/private-command! {:op "drift"})
    (is (= :withheld (:status (sporting/read-response sample/reader-url "/api/comparison" {}))))
    (is (= 4 (:n (first (fixture/query! "SELECT count(*) AS n FROM freediving.public_results")))))))
(deftest local-facts-cannot-borrow-a-valid-owner-receipt
  (let [{:keys [payload]} (fixture/synthetic-cohort!)
        event (first (fixture/query! "SELECT * FROM freediving.public_sporting_comparison"))
        wrong (assoc-in payload [:rows 0 :facts :finality :value] :unknown)]
    (is (authority/current-for? event))
    (is (false? (authority/current-for? (assoc event :body_edn (pr-str wrong)))))
    (is (thrown? Exception (sporting/publish-derived! db/admin wrong {:revision (:bridge_revision event) :head_sha256 (:bridge_head event)})))
    (is (thrown? java.sql.SQLException (db/sql! sample/reader-url "SELECT * FROM freediving.public_sporting_bridge_receipts")))
    (is (thrown? java.sql.SQLException (db/sql! db/admin "DELETE FROM freediving.public_sporting_bridge_receipts")))))
(defn -main [& _]
  (let [r (run-tests 'freediving.sporting-authority-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail r) (:error r))) (System/exit 1))))
