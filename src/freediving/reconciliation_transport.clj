(ns freediving.reconciliation-transport
  "Single-attempt Jev transport. Credentials never enter requests or receipts."
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.io ByteArrayOutputStream InputStream]
           [java.lang ProcessBuilder$Redirect]
           [java.net URI]
           [java.net.http HttpClient HttpClient$Redirect HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers HttpTimeoutException]
           [java.nio.charset StandardCharsets]
           [java.time Duration]
           [java.util.concurrent TimeUnit]))

(defn- command-output [args token]
  (let [builder (ProcessBuilder. ^java.util.List args)
        env (.environment builder)]
    (.remove env "OP_SERVICE_ACCOUNT_TOKEN")
    (when token (.put env "OP_SERVICE_ACCOUNT_TOKEN" token))
    (.redirectError builder ProcessBuilder$Redirect/DISCARD)
    (let [process (.start builder)
          output (future (slurp (.getInputStream process)))]
      (if (.waitFor process 10 TimeUnit/SECONDS)
        (if (zero? (.exitValue process))
          (str/trim @output)
          (throw (ex-info "Credential lookup failed" {})))
        (do (.destroyForcibly process)
            (future-cancel output)
            (throw (ex-info "Credential lookup timed out" {})))))))

(defn- credential []
  (let [user (System/getenv "USER")]
    (when-not (seq user)
      (throw (ex-info "Credential lookup unavailable" {})))
    (let [token (command-output ["security" "find-generic-password" "-s"
                                 "api-shell 1Password service account" "-a" user "-w"] nil)
          items (json/read-str (command-output ["op" "item" "list" "--vault"
                                                "Shell Access" "--format" "json"] token))
          ids (keep (fn [item]
                      (when (str/includes? (str/lower-case (str (get item "title"))) "typesafe")
                        (get item "id"))) items)]
      (when-not (= 1 (count ids))
        (throw (ex-info "Ambiguous credential item" {})))
      (let [item (json/read-str (command-output ["op" "item" "get" (first ids)
                                                 "--vault" "Shell Access" "--format" "json"] token))
            values (keep (fn [field]
                           (when (and (seq (get field "value"))
                                      (or (= "CONCEALED" (get field "type"))
                                          (= "PASSWORD" (get field "purpose"))))
                             (get field "value"))) (get item "fields"))]
        (when-not (= 1 (count values))
          (throw (ex-info "Invalid credential item" {})))
        (first values)))))

(defn- valid-token? [token]
  (and (string? token) (seq token) (= token (str/trim token))
       (not= token "REPLACE_WITH_REAL_KEY")
       (not (re-find #"[\r\n]" token))))

(defn- endpoint [value]
  (try
    (let [uri (URI. value)
          host (.getHost uri)]
      (when (and (string? host) (nil? (.getUserInfo uri))
                 (nil? (.getQuery uri)) (nil? (.getFragment uri))
                 (or (= "https" (.getScheme uri))
                     (and (= "http" (.getScheme uri))
                          (#{"localhost" "127.0.0.1" "::1" "[::1]"} host))))
        uri))
    (catch Exception _ nil)))

(defn- bounded-body [^InputStream input maximum]
  (with-open [stream input
              output (ByteArrayOutputStream.)]
    (let [buffer (byte-array 8192)]
      (loop [total 0]
        (let [read (.read stream buffer)]
          (if (neg? read)
            (.toString output "UTF-8")
            (let [next-total (+ total read)]
              (when (> next-total maximum)
                (throw (ex-info "Response too large" {:reason :response-too-large})))
              (.write output buffer 0 read)
              (recur next-total))))))))

(defn execute!
  "Return a raw response or a finite error. Never retry an uncertain dispatch."
  [request runtime]
  (let [config (:config request)
        uri (endpoint (:endpoint config))
        body (:body request)
        timeout (or (:timeout-ms config) 10000)
        max-request (or (:max-request-bytes config) 49152)
        max-response (or (:max-response-bytes config) 1048576)]
    (if-not (and (= :jev (:provider request)) uri (string? body)
                 (<= 1 timeout 60000)
                 (<= 1 max-request 1048576) (<= 1 max-response 1048576)
                 (<= (alength (.getBytes body StandardCharsets/UTF_8)) max-request))
      {:outcome :error :error :invalid-request}
      (try
        (let [token (or (:bearer-token runtime) (credential))]
          (if-not (valid-token? token)
            {:outcome :error :error :credential-unavailable}
            (let [client (-> (HttpClient/newBuilder)
                             (.connectTimeout (Duration/ofMillis timeout))
                             (.followRedirects HttpClient$Redirect/NEVER)
                             (.build))
                  http-request (-> (HttpRequest/newBuilder uri)
                                   (.timeout (Duration/ofMillis timeout))
                                   (.header "Content-Type" "application/json")
                                   (.header "Authorization" (str "Bearer " token))
                                   (.POST (HttpRequest$BodyPublishers/ofString body))
                                   (.build))
                  response (.send client http-request (HttpResponse$BodyHandlers/ofInputStream))
                  status (.statusCode response)]
              (if (= 200 status)
                {:raw-response (bounded-body (.body response) max-response)
                 :http-status status}
                (do (.close ^InputStream (.body response))
                    {:outcome :error
                     :error (cond
                              (<= 300 status 399) :redirect
                              (#{401 403} status) :unauthorized
                              :else :http-error)})))))
        (catch HttpTimeoutException _ {:outcome :error :error :timeout})
        (catch InterruptedException _
          (.interrupt (Thread/currentThread))
          {:outcome :error :error :interrupted})
        (catch clojure.lang.ExceptionInfo error
          {:outcome :error :error (or (:reason (ex-data error)) :credential-unavailable)})
        (catch Exception _ {:outcome :error :error :transport-error})))))
