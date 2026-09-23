(ns freediving.public-server
  "Loopback public website over the restricted public projection only."
  (:require [clojure.data.json :as json] [clojure.java.io :as io]
            [clojure.string :as str] [freediving.public-results :as public]
            [freediving.corrections :as corrections])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress URLDecoder]
           [java.io PushbackReader StringReader]
           [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets CodingErrorAction]
           [java.security MessageDigest]
           [java.util HexFormat]
           [java.sql DriverManager]
           [java.time LocalDate]
           [java.util.concurrent ThreadPoolExecutor ThreadPoolExecutor$AbortPolicy ArrayBlockingQueue TimeUnit ExecutorService]))
(defn- fail! [status] (throw (ex-info "Public request unavailable" {:status status})))
(defn- query [c sql]
  (with-open [s (.createStatement c) r (.executeQuery s sql)]
    (loop [rows []]
      (if (.next r) (recur (conj rows (.getBoolean r 1))) rows))))
(defn- authority! [url]
  (when-not (and (string? url)
                 (re-matches #"jdbc:postgresql://127\.0\.0\.1:[0-9]{1,5}/[A-Za-z0-9_]+(?:\?(?:user|password|sslmode|connectTimeout|socketTimeout)=[^&]*)(?:&(?:user|password|sslmode|connectTimeout|socketTimeout)=[^&]*)*|jdbc:postgresql://127\.0\.0\.1:[0-9]{1,5}/[A-Za-z0-9_]+" url))
    (fail! 400))
  (with-open [c (DriverManager/getConnection url)]
    (when (some true?
                (mapcat #(query c %)
                        ["SELECT rolsuper OR rolcreaterole OR rolcreatedb OR rolbypassrls OR rolreplication FROM pg_roles WHERE rolname=current_user"
                         "SELECT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname=current_user))"
                         "SELECT EXISTS(SELECT 1 FROM pg_database WHERE datname=current_database() AND pg_has_role(current_user,datdba,'MEMBER'))"
                         "SELECT EXISTS(SELECT 1 FROM pg_namespace WHERE nspname NOT LIKE 'pg_%' AND nspname <> 'information_schema' AND (pg_has_role(current_user,nspowner,'MEMBER') OR has_schema_privilege(current_user,oid,'CREATE')))"
                         "SELECT EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema' AND c.relkind IN ('r','v','m','p','f') AND (pg_has_role(current_user,c.relowner,'MEMBER') OR (c.oid <> 'freediving.public_results'::regclass AND has_table_privilege(current_user,c.oid,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')) OR has_table_privilege(current_user,c.oid,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')))"
                         "SELECT has_database_privilege(current_user,current_database(),'CREATE')"
                         "SELECT EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema' AND c.relkind IN ('r','v','m','p','f') AND ((c.oid <> 'freediving.public_results'::regclass AND has_any_column_privilege(current_user,c.oid,'SELECT')) OR has_any_column_privilege(current_user,c.oid,'INSERT,UPDATE,REFERENCES')))"
                         "SELECT EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema' AND p.prosecdef AND has_function_privilege(current_user,p.oid,'EXECUTE'))"
                         "SELECT NOT has_table_privilege(current_user,'freediving.public_results','SELECT')"]))
      (fail! 403))))
(defn- respond! [^HttpExchange e status value mime]
  (let [bytes (.getBytes (if (= mime "application/json") (json/write-str value) (str value)) "UTF-8")]
    (doseq [[k v] {"Content-Type" (str mime "; charset=utf-8") "Cache-Control" "no-store"
                   "X-Content-Type-Options" "nosniff" "Referrer-Policy" "no-referrer"
                   "Content-Security-Policy" "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"
                   "Permissions-Policy" "camera=(), microphone=(), geolocation=()"}]
      (.set (.getResponseHeaders e) k v))
    (.sendResponseHeaders e status (long (alength bytes)))
    (with-open [o (.getResponseBody e)] (.write o bytes))))
(defn- params [raw]
  (when (> (count raw) 2048) (fail! 400))
  (reduce (fn [m part]
            (let [[k v] (str/split part #"=" 2)
                  decode #(try (URLDecoder/decode (or % "") "UTF-8") (catch Exception _ (fail! 400)))
                  k (decode k) v (decode v)]
              (when (or (contains? m k) (not (contains? #{"q" "federation" "discipline" "category" "date" "page" "limit"} k))
                        (> (count v) 200) (re-find #"[\p{Cc}\p{Cs}\uFFFD]" v)) (fail! 400))
              (assoc m k v))) {} (if (seq raw) (str/split raw #"&" -1) [])))
(defn- positive [value default maximum]
  (if (nil? value) default
      (if (re-matches #"[1-9][0-9]{0,5}" value)
        (let [n (Long/parseLong value)] (if (<= n maximum) n (fail! 400)))
        (fail! 400))))
(def filter-fields {"federation" :federation "discipline" :discipline "category" :category "date" :event-date})
(defn- listing [rows p demo?]
  (let [page (positive (get p "page") 1 100000) limit (positive (get p "limit") 10 50)
        date (get p "date")
        _ (when (seq date) (try (LocalDate/parse date) (catch Exception _ (fail! 400))))
        q (str/lower-case (get p "q" ""))
        found (filterv (fn [r]
                         (and (some #(str/includes? (str/lower-case (str (get-in r [% :source-name]))) q) [:original :effective])
                              (every? (fn [[param field]] (or (str/blank? (get p param)) (= (get p param) (str (get-in r [:effective field]))))) filter-fields))) rows)]
    {:results (vec (take limit (drop (* (dec page) limit) found)))
     :page page :limit limit :total (count found) :pages (long (Math/ceil (/ (count found) (double limit))))
     :filters (into {} (map (fn [[param field]] [(keyword param) (vec (sort (set (keep #(some-> (get-in % [:effective field]) str) rows))))]) filter-fields))
     :coverage {:scope :pilot :completeness :partial :results (count rows)
                :approved_identities (count (set (keep #(get-in % [:identity :id]) rows)))}
     :demo (boolean demo?)}))
(defn- correction-body! [^HttpExchange e]
  (let [h (.getRequestHeaders e)]
    (when-not (= ["1"] (vec (.get h "X-Correction-Request"))) (fail! 403))
    (when-not (and (= 1 (count (.get h "Content-Type")))
                   (re-matches #"application/json(?:; ?charset=utf-8)?" (or (.getFirst h "Content-Type") ""))) (fail! 415))
    (when-let [length (.getFirst h "Content-Length")]
      (when (or (not (re-matches #"[0-9]{1,6}" length)) (> (Long/parseLong length) 16384)) (fail! 413)))
    (let [bytes (.readNBytes (.getRequestBody e) 16385)]
      (when (> (alength bytes) 16384) (fail! 413))
      (try
        (let [decoder (doto (.newDecoder StandardCharsets/UTF_8)
                        (.onMalformedInput CodingErrorAction/REPORT) (.onUnmappableCharacter CodingErrorAction/REPORT))
              text (str (.decode decoder (ByteBuffer/wrap bytes)))
              seen (atom #{})
              fields {"id" :id "result-id" :result-id "version" :version "suggestion" :suggestion "reason" :reason "evidence" :evidence}]
          ;; Requests are flat string maps. Reject nested containers before parsing,
          ;; respecting JSON string escapes, to bound parser stack usage.
          (loop [chars (seq text) quoted? false escaped? false depth 0]
            (when-let [c (first chars)]
              (cond
                escaped? (recur (next chars) quoted? false depth)
                (and quoted? (= c (char 92))) (recur (next chars) true true depth)
                (= c (char 34)) (recur (next chars) (not quoted?) false depth)
                quoted? (recur (next chars) true false depth)
                (= c \[) (fail! 400)
                (= c \{) (if (pos? depth) (fail! 400) (recur (next chars) false false (inc depth)))
                (= c \}) (recur (next chars) false false (dec depth))
                :else (recur (next chars) false false depth))))
          (with-open [reader (PushbackReader. (StringReader. text) 16384)]
            (let [r (json/read reader :key-fn (fn [k] (when (or (not (contains? fields k)) (contains? @seen k)) (fail! 400))
                                                (swap! seen conj k) (fields k)))]
              (when-not (and (map? r) (= (set (vals fields)) (set (keys r))) (every? string? (vals r))) (fail! 400))
              (loop [c (.read reader)] (when-not (= -1 c) (when-not (Character/isWhitespace (char c)) (fail! 400)) (recur (.read reader))))
              r)))
        (catch Exception _ (fail! 400))))))
(defn- client-key [^HttpExchange e]
  ;; Only the actual socket peer counts. Forwarded headers never establish identity.
  (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256")
                                      (.getAddress (.getAddress (.getRemoteAddress e))))))
(def correction-errors
  {400 "Enter a suggested change, reason and safe evidence reference within the field limits."
   403 "Same-origin correction request required."
   404 "Result unavailable or changed. Reload the result before submitting."
   409 "Request ID already used with different details. Edit the request and retry."
   413 "Request too large. Shorten the correction details."
   415 "JSON correction request required."
   429 "Too many correction requests. Try again later."
   503 "Correction service unavailable. Retry the same request later."})
(defn- routes! [^HttpExchange e {:keys [url database-url submission-database-url demo?]}]
  (let [uri (.getRequestURI e) path (.getRawPath uri) raw (.getRawQuery uri)
        headers (.getRequestHeaders e)
        host (.get headers "Host") origin (.get headers "Origin")
        send #(respond! e 200 % "application/json")]
    (when-not (= [(.getAuthority (java.net.URI/create url))] (vec host)) (fail! 403))
    (when (and origin (not= [url] (vec origin))) (fail! 403))
    (when-not (or (= "GET" (.getRequestMethod e))
                  (and submission-database-url (= path "/api/corrections") (= "POST" (.getRequestMethod e)))) (fail! 405))
    (when (or (> (count path) 200) (re-find #"%" path)) (fail! 404))
    (cond
      (and (= path "/api/corrections") (= "POST" (.getRequestMethod e)))
      (do (when (some? raw) (fail! 400))
          (when-not (= [url] (vec origin)) (fail! 403))
          (let [r (correction-body! e)] (send (corrections/submit! submission-database-url r (client-key e)))))
      (= path "/api/results") (let [p (params raw)] (send (listing (public/results database-url) p demo?)))
      (re-matches #"/api/(results|athletes)/[0-9a-f]{64}" path)
      (do (when (seq raw) (fail! 400))
          (let [[_ kind id] (re-matches #"/api/(results|athletes)/([0-9a-f]{64})" path)
                rows (public/results database-url)
                found (if (= kind "results") (first (filter #(= id (:result-id %)) rows))
                          (filterv #(and (= :approved (get-in % [:identity :status])) (= id (get-in % [:identity :id]))) rows))]
            (when (or (nil? found) (and (vector? found) (empty? found))) (fail! 404))
            (if (and (= kind "results") submission-database-url)
              (if-let [detail (corrections/public-detail database-url id)]
                (send (assoc detail :demo (boolean demo?))) (fail! 404))
              (send {(if (= kind "results") :result :results) found :demo (boolean demo?)}))))
      (or (= path "/") (re-matches #"/(results|athletes)/[0-9a-f]{64}" path) (contains? #{"/public.js" "/public.css"} path))
      (do (when (and (seq raw) (not= path "/")) (fail! 400))
          (when (= path "/") (params raw))
          (let [[resource mime] (get {"/public.js" ["public.js" "text/javascript"] "/public.css" ["public.css" "text/css"]} path ["public.html" "text/html"])]
            (if-let [r (io/resource resource)] (respond! e 200 (slurp r) mime) (fail! 404))))
      :else (fail! 404))))
(defn start! [{:keys [database-url submission-database-url port demo?] :or {port 0 demo? false}}]
  (when-not (and (integer? port) (<= 0 port 65535) (boolean? demo?)) (fail! 400))
  (authority! database-url)
  (when submission-database-url
    (when-not (= (first (str/split database-url #"\?"))
                 (first (str/split submission-database-url #"\?"))) (fail! 400))
    (corrections/assert-submitter! submission-database-url))
  ;; JDK request/response deadlines are process-wide; set before its first server.
  (System/setProperty "sun.net.httpserver.maxReqTime" "10")
  (System/setProperty "sun.net.httpserver.maxRspTime" "10")
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" port) 16)
        executor (ThreadPoolExecutor. 4 4 0 TimeUnit/MILLISECONDS (ArrayBlockingQueue. 32) (ThreadPoolExecutor$AbortPolicy.))
        config {:database-url database-url :submission-database-url submission-database-url :demo? demo? :url (str "http://127.0.0.1:" (.getPort (.getAddress server)))}]
    (.createContext server "/" (reify HttpHandler
                                 (handle [_ e]
                                   (try (routes! e config)
                                        (catch Exception ex
                                          (let [reason (:status (ex-data ex))
                                                status (if (integer? reason) reason
                                                           (get {:invalid 400 :unavailable 404 :conflict 409 :rate-limited 429 :capacity 503} reason 503))
                                                correction? (= "/api/corrections" (.getRawPath (.getRequestURI e)))]
                                            (when (= status 429) (.set (.getResponseHeaders e) "Retry-After" "3600"))
                                            (respond! e status {:error (if correction? (get correction-errors status "Correction unavailable")
                                                                           (if (= status 503) "Service unavailable" "Record unavailable"))} "application/json")))
                                        (finally (.close ^HttpExchange e))))))
    (.setExecutor server executor) (.start server)
    (assoc config :server server :executor executor)))
(defn stop! [{:keys [server executor]}]
  (.stop ^HttpServer server 0)
  (.shutdownNow ^ExecutorService executor))
(defn -main [& args]
  (try
    (when-not (and (<= 1 (count args) 2) (re-matches #"[0-9]{1,5}" (first args))
                   (or (= 1 (count args)) (= "--synthetic-demo" (second args)))) (fail! 400))
    (let [app (start! {:database-url (System/getenv "FREEDIVING_PUBLIC_DATABASE_URL")
                       :submission-database-url (System/getenv "FREEDIVING_SUBMIT_DATABASE_URL")
                       :port (Long/parseLong (first args)) :demo? (= "--synthetic-demo" (second args))})]
      (.addShutdownHook (Runtime/getRuntime) (Thread. #(stop! app)))
      (println (:url app)))
    (catch Exception _ (binding [*out* *err*] (println "Public server could not start")) (System/exit 1))))
