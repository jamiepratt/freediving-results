(ns freediving.reconciliation-transport-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [freediving.reconciliation-transport :as transport])
  (:import [com.sun.net.httpserver HttpServer]
           [java.net InetSocketAddress]))

(defn with-server [handler f]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/" (reify com.sun.net.httpserver.HttpHandler
                                 (handle [_ exchange] (handler exchange))))
    (.start server)
    (try (f (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/"))
         (finally (.stop server 0)))))

(defn reply! [exchange status body]
  (let [bytes (.getBytes body "UTF-8")]
    (.sendResponseHeaders exchange status (alength bytes))
    (with-open [output (.getResponseBody exchange)]
      (.write output bytes))))

(defn request [endpoint]
  {:provider :jev
   :config {:endpoint endpoint :timeout-ms 1000 :max-response-bytes 1024}
   :body "{\"model\":\"jev-test\"}"})

(deftest sends-one-credentialed-request-and-preserves-raw-response
  (let [calls (atom [])]
    (with-server
      (fn [exchange]
        (swap! calls conj {:method (.getRequestMethod exchange)
                           :authorization (.getFirst (.getRequestHeaders exchange) "Authorization")
                           :body (slurp (.getRequestBody exchange))})
        (reply! exchange 200 "{\"model\":\"jev-test\"}"))
      (fn [url]
        (is (= {:raw-response "{\"model\":\"jev-test\"}" :http-status 200}
               (transport/execute! (request url) {:bearer-token "fixture-secret"})))
        (is (= [{:method "POST" :authorization "Bearer fixture-secret"
                 :body "{\"model\":\"jev-test\"}"}] @calls))))))

(deftest rejects-untrusted-endpoints-before-credential-lookup
  (doseq [url ["http://example.com/jev" "https://user:pass@example.com/jev"
               "https://example.com/jev?key=value" "file:///tmp/jev"]]
    (is (= {:outcome :error :error :invalid-request}
           (transport/execute! (request url) {})))))

(deftest redirects-and-large-responses-are-bounded-without-retry
  (let [calls (atom 0)]
    (with-server
      (fn [exchange]
        (swap! calls inc)
        (reply! exchange 302 "redirect"))
      (fn [url]
        (is (= {:outcome :error :error :redirect}
               (transport/execute! (request url) {:bearer-token "fixture-secret"})))
        (is (= 1 @calls)))))
  (with-server
    (fn [exchange] (reply! exchange 200 (apply str (repeat 2048 "x"))))
    (fn [url]
      (is (= {:outcome :error :error :response-too-large}
             (transport/execute! (request url) {:bearer-token "fixture-secret"}))))))

(deftest timeout-leaves-an-explicit-uncertain-dispatch
  (let [calls (atom 0)]
    (with-server
      (fn [exchange]
        (swap! calls inc)
        (Thread/sleep 300)
        (try (reply! exchange 200 "late") (catch Exception _)))
      (fn [url]
        (is (= {:outcome :error :error :timeout}
               (transport/execute! (assoc-in (request url) [:config :timeout-ms] 50)
                                   {:bearer-token "fixture-secret"})))
        (is (= 1 @calls))))))

(defn -main [& _]
  (let [result (run-tests 'freediving.reconciliation-transport-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))
