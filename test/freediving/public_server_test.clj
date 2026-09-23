(ns freediving.public-server-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [freediving.public-results :as public]
            [freediving.publication :as publication]
            [freediving.observations :as observations]
            [freediving.reviews :as reviews]
            [freediving.public-results-test :as sample]
            [freediving.observations-test :as fixture])
  (:import [java.net URI] [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]))
(use-fixtures :each
  (fn [f]
    (fixture/sql! fixture/admin "DROP SCHEMA IF EXISTS freediving CASCADE")
    (observations/migrate! fixture/admin "observations_app")
    (reviews/migrate! fixture/admin "observations_app" "reviews_owner")
    (publication/migrate! fixture/admin "reviews_owner")
    (public/migrate! fixture/admin "reviews_owner" "reviews_public")
    (f)))
(defn request [url path]
  (let [r (.send (HttpClient/newHttpClient)
                 (.build (HttpRequest/newBuilder (URI/create (str url path))))
                 (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode r) :body (json/read-str (.body r) :key-fn keyword) :headers (.headers r)}))
(deftest eligible-unresolved-source-is-searchable-over-http
  (let [target (sample/sample)]
    (sample/validate! target "http-v1") (public/refresh! sample/reviewer)
    (let [start! (requiring-resolve 'freediving.public-server/start!)
          stop! (requiring-resolve 'freediving.public-server/stop!)
          app (start! {:database-url sample/reader-url :port 0 :demo? true})]
      (try
        (let [r (request (:url app) "/api/results?q=%C3%89xample")]
          (is (= 200 (:status r)))
          (is (= 1 (get-in r [:body :total])))
          (is (= "unresolved" (get-in r [:body :results 0 :identity :status])))
          (is (= true (get-in r [:body :demo]))))
        (finally (stop! app))))))
(defn -main [& _]
  (let [r (clojure.test/run-tests 'freediving.public-server-test)]
    (shutdown-agents) (when (pos? (+ (:fail r) (:error r))) (System/exit 1))))
(defn with-server [f]
  (let [app ((requiring-resolve 'freediving.public-server/start!) {:database-url sample/reader-url :port 0 :demo? true})]
    (try (f (:url app)) (finally ((requiring-resolve 'freediving.public-server/stop!) app)))))
(deftest bounds-pagination-errors-and-no-store
  (let [target (sample/sample)]
    (sample/validate! target "v1")
    (sample/validate! (assoc target :ordinal 1) "v2")
    (public/refresh! sample/reviewer)
    (with-server
      (fn [url]
        (is (= 1 (count (get-in (request url "/api/results?limit=1&page=2") [:body :results]))))
        (is (= 2 (get-in (request url "/api/results?limit=1") [:body :pages])))
        (is (= 0 (get-in (request url "/api/results?discipline=OTHER") [:body :total])))
        (is (= ["CWT"] (get-in (request url "/api/results") [:body :filters :discipline])))
        (doseq [path ["/api/results?limit=51" "/api/results?page=0" "/api/results?page=-1"
                      "/api/results?date=2026-02-30" "/api/results?unknown=x" "/api/results?q=a&q=b"
                      "/api/results?q=%00" "/api/results?page=9999999"]]
          (is (= 400 (:status (request url path))) path))
        (doseq [path ["/api/results/private" "/api/owner" "/data" "/api/athletes/absent"]]
          (is (= 404 (:status (request url path))) path))
        (is (= "no-store" (.orElse (.firstValue (:headers (request url "/api/results")) "Cache-Control") "")))
        (is (= "no-store" (.orElse (.firstValue (:headers (request url "/api/unknown")) "Cache-Control") "")))))))
(deftest startup-rejects-private-capabilities
  (let [start! (requiring-resolve 'freediving.public-server/start!)]
    (doseq [url [fixture/admin fixture/app sample/reviewer]]
      (is (thrown? Exception (start! {:database-url url :port 0}))))
    (fixture/sql! fixture/admin "GRANT SELECT (payload_edn) ON freediving.observations TO reviews_public")
    (try
      (is (thrown? Exception
                   (let [app (start! {:database-url sample/reader-url :port 0})]
                     ((requiring-resolve 'freediving.public-server/stop!) app))))
      (finally (fixture/sql! fixture/admin "REVOKE SELECT (payload_edn) ON freediving.observations FROM reviews_public")))))
(deftest corrections-history-and-revocation-have-no-http-cache
  (let [target (sample/sample)
        proposal ((requiring-resolve 'freediving.reviews-test/proposal) target "identity")]
    (reviews/propose! fixture/app proposal) (sample/decide! "a1" :approve "identity" 0)
    (sample/validate! target "v1") (public/refresh! sample/reviewer)
    (with-server
      (fn [url]
        (let [row (first (get-in (request url "/api/results") [:body :results]))
              result-path (str "/api/results/" (:result-id row))
              history-path (str "/api/athletes/" (get-in row [:identity :id]))
              absent (str "/api/results/" (apply str (repeat 64 "0")))]
          (is (= row (get-in (request url result-path) [:body :result])))
          (is (= [row] (get-in (request url history-path) [:body :results])))
          (reviews/propose! fixture/app
                            (merge ((requiring-resolve 'freediving.reviews-test/proposal) target "name")
                                   {:base-revision 1 :field :source-name :category :name-normalization :before "Éxample" :after "<script>Changed</script>"}))
          (sample/decide! "a2" :approve "name" 1)
          (is (= 404 (:status (request url result-path))))
          (sample/validate! target "v2") (public/refresh! sample/reviewer)
          (is (= "<script>Changed</script>" (get-in (request url "/api/results?q=%C3%89xample") [:body :results 0 :effective :source-name])))
          (is (= 1 (get-in (request url "/api/results?q=Changed") [:body :total])))
          (is (= "approve" (get-in (request url result-path) [:body :result :correction-audit 0 :action])))
          (sample/decide! "r1" :reverse "a2" 2)
          (sample/validate! target "v3") (public/refresh! sample/reviewer)
          (is (= ["approve" "reverse"] (mapv :action (get-in (request url result-path) [:body :result :correction-audit]))))
          (sample/validate! target "revoke" :revoke)
          (doseq [path [result-path history-path absent]]
            (is (= {:status 404 :body {:error "Record unavailable"}}
                   (select-keys (request url path) [:status :body]))))
          (is (= 0 (get-in (request url "/api/results") [:body :coverage :results])))
          (is (not (re-find #"PRIVATE|payload_edn|job-id|proposal" (pr-str (:body (request url result-path)))))))))))
(deftest host-origin-method-and-database-failures-are-private
  (with-server
    (fn [url]
      (doseq [[method origin status] [["POST" url 405] ["GET" "https://evil.example" 403]]]
        (let [r (.send (HttpClient/newHttpClient)
                       (.build (doto (HttpRequest/newBuilder (URI/create (str url "/api/results")))
                                 (.header "Origin" origin)
                                 (.method method (java.net.http.HttpRequest$BodyPublishers/noBody))))
                       (HttpResponse$BodyHandlers/ofString))]
          (is (= status (.statusCode r)))
          (is (not (.contains (.body r) "freediving")))))
      (let [r (.send (HttpClient/newHttpClient)
                     (.build (HttpRequest/newBuilder (URI/create (str/replace (str url "/api/results") "127.0.0.1" "localhost"))))
                     (HttpResponse$BodyHandlers/ofString))]
        (is (= 403 (.statusCode r))))
      (fixture/sql! fixture/admin "REVOKE SELECT ON freediving.public_results FROM reviews_public")
      (is (= {:status 503 :body {:error "Service unavailable"}}
             (select-keys (request url "/api/results") [:status :body]))))))
