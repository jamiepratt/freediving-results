(ns freediving.owner-server
  "Trusted-local, synthetic owner review. The capability authenticates the owner; actor is audit text."
  (:require [clojure.data.json :as json] [clojure.java.io :as io] [clojure.string :as str]
            [freediving.candidates :as candidates] [freediving.packets :as packets]
            [freediving.reviews :as reviews] [freediving.publication :as publication])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress URLDecoder]
           [java.sql DriverManager]
           [java.security SecureRandom MessageDigest]
           [java.util Base64]
           [java.nio.file Files Paths LinkOption StandardOpenOption]
           [java.nio.file.attribute PosixFilePermissions FileAttribute]
           [java.util.concurrent Executors]))
(defn- fail! [status message] (throw (ex-info message {:status status})))
(defn- token [] (let [b (byte-array 32)] (.nextBytes (SecureRandom.) b) (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b)))
(defn- equal-secret? [a b] (and (string? a) (string? b) (MessageDigest/isEqual (.getBytes a "UTF-8") (.getBytes b "UTF-8"))))
(defn- query [c sql]
  (with-open [s (.createStatement c) r (.executeQuery s sql)]
    (let [m (.getMetaData r)] (loop [rows []] (if (.next r) (recur (conj rows (into {} (for [i (range 1 (inc (.getColumnCount m)))] [(keyword (.getColumnLabel m i)) (.getObject r i)])))) rows)))))
(defn- authority! [url]
  (when-not (and (string? url) (re-matches #"jdbc:postgresql://127\.0\.0\.1:[0-9]{1,5}/[A-Za-z0-9_]+(?:\?(?:user|password|sslmode|connectTimeout|socketTimeout)=[^&]*)(?:&(?:user|password|sslmode|connectTimeout|socketTimeout)=[^&]*)*|jdbc:postgresql://127\.0\.0\.1:[0-9]{1,5}/[A-Za-z0-9_]+" url)) (fail! 400 "Loopback PostgreSQL reviewer URL required"))
  (with-open [c (DriverManager/getConnection url)]
    (when (or (some true? (vals (first (query c "SELECT rolsuper,rolcreaterole,rolcreatedb,rolbypassrls FROM pg_roles WHERE rolname=current_user"))))
              (seq (query c "SELECT roleid FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname=current_user)"))
              (seq (query c "SELECT oid FROM pg_class WHERE relnamespace=to_regnamespace('freediving') AND pg_has_role(current_user,relowner,'MEMBER')"))
              (seq (query c "SELECT oid FROM pg_namespace WHERE nspname='freediving' AND pg_has_role(current_user,nspowner,'MEMBER')"))
              (seq (query c "SELECT oid FROM pg_database WHERE datname=current_database() AND pg_has_role(current_user,datdba,'MEMBER')")))
      (fail! 403 "Restricted reviewer role required"))
    (doseq [table ["extractions" "observations" "review_proposals" "review_decisions" "publication_decisions" "publication_policy_events"]]
      (let [v (first (query c (str "SELECT has_table_privilege(current_user,'freediving." table "','SELECT') AS readable,has_table_privilege(current_user,'freediving." table "','UPDATE,DELETE,TRUNCATE') AS mutable,has_table_privilege(current_user,'freediving." table "','INSERT') AS appendable")))]
        (when (or (not (:readable v)) (:mutable v) (not= (contains? #{"review_proposals" "review_decisions" "publication_decisions"} table) (:appendable v))) (fail! 403 "Reviewer table privileges invalid"))))
    (when (:allowed (first (query c "SELECT has_schema_privilege(current_user,'freediving','CREATE') AS allowed"))) (fail! 403 "Reviewer schema privileges invalid"))))
(defn- capability! [path secret]
  (when-not (string? path) (fail! 400 "Private capability file required"))
  (let [p (.toAbsolutePath (Paths/get path (make-array String 0))) parent (.getParent p)]
    (loop [x p] (when x (when (Files/isSymbolicLink x) (fail! 400 "Capability path cannot contain symlinks")) (recur (.getParent x))))
    (when-not (= (PosixFilePermissions/fromString "rwx------") (Files/getPosixFilePermissions parent (make-array LinkOption 0))) (fail! 400 "Capability parent must have mode 0700"))
    (Files/createFile p (into-array FileAttribute [(PosixFilePermissions/asFileAttribute (PosixFilePermissions/fromString "rw-------"))]))
    (Files/write p (.getBytes secret "UTF-8") (into-array StandardOpenOption [StandardOpenOption/WRITE]))
    p))
(defn- header [^HttpExchange e key] (.getFirst (.getRequestHeaders e) key))
(defn- respond! [^HttpExchange e status value content-type]
  (let [bytes (.getBytes (if (= content-type "application/json") (json/write-str value) (str value)) "UTF-8") h (.getResponseHeaders e)]
    (doseq [[k v] {"Content-Type" (str content-type "; charset=utf-8") "Cache-Control" "no-store" "X-Content-Type-Options" "nosniff" "Referrer-Policy" "no-referrer" "Content-Security-Policy" "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"}] (.set h k v))
    (.sendResponseHeaders e status (long (alength bytes)))
    (with-open [o (.getResponseBody e)] (.write o bytes))))
(defn- body! [^HttpExchange e]
  (when-not (= "application/json" (first (str/split (or (header e "Content-Type") "") #";"))) (fail! 415 "JSON request required"))
  (let [b (.readNBytes (.getRequestBody e) 65537)]
    (when (> (alength b) 65536) (fail! 413 "Request too large"))
    (let [v (try (json/read-str (String. b "UTF-8") :key-fn keyword) (catch Exception _ (fail! 400 "Invalid JSON")))]
      (when-not (map? v) (fail! 400 "JSON object required")) v)))
(defn- params [^HttpExchange e]
  (into {} (for [part (str/split (or (.getRawQuery (.getRequestURI e)) "") #"&") :when (seq part)
                 :let [[k v] (str/split part #"=" 2)]] [(keyword (URLDecoder/decode k "UTF-8")) (URLDecoder/decode (or v "") "UTF-8")])))
(defn- target! [e]
  (let [p (params e) n (try (Long/parseLong (:ordinal p)) (catch Exception _ -1))]
    (when-not (and (seq (:job-id p)) (<= 0 n Integer/MAX_VALUE)) (fail! 400 "Exact job-id and ordinal required")) {:job-id (:job-id p) :ordinal (int n)}))
(defn- keyword-values [r]
  (reduce (fn [r k] (if (string? (get r k)) (update r k keyword) r)) r [:action :category :field]))
(defn- request-values [r]
  (reduce (fn [r k] (if (and (map? (get r k)) (string? (get-in r [k :outcome]))) (update-in r [k :outcome] keyword) r)) (keyword-values r) [:before :after]))
(defn- session [sessions e]
  (let [cookies (str/split (or (header e "Cookie") "") #";\s*") id (some #(second (re-matches #"owner-session=([A-Za-z0-9_-]+)" %)) cookies)
        s (get @sessions id)]
    (when (and s (< (System/currentTimeMillis) (:expires s))) s)))
(defn- routes! [e {:keys [url database-url secret sessions]}]
  (let [method (.getRequestMethod e) path (.getPath (.getRequestURI e)) host (header e "Host") origin (header e "Origin")
        auth (session sessions e) get? (= method "GET") post? (= method "POST")
        respond #(respond! e 200 % "application/json")]
    (when-not (= host (subs url 7)) (fail! 403 "Host is not allowed"))
    (when (and origin (not= origin url)) (fail! 403 "Origin is not allowed"))
    (when (and post? (not= origin url)) (fail! 403 "Same-origin request required"))
    (when-not (or get? post?) (fail! 405 "Method not allowed"))
    (cond
      (and get? (contains? #{"/" "/owner.js" "/owner.css"} path))
      (let [[resource mime] (get {"/" ["owner.html" "text/html"] "/owner.js" ["owner.js" "text/javascript"] "/owner.css" ["owner.css" "text/css"]} path)]
        (if-let [r (io/resource resource)] (respond! e 200 (slurp r) mime) (fail! 404 "Resource unavailable")))
      (and get? (= path "/api/session")) (respond (cond-> {:authenticated (boolean auth) :demo true} auth (assoc :csrf (:csrf auth))))
      (and post? (= path "/api/login"))
      (let [r (body! e)]
        (when-not (equal-secret? secret (:capability r)) (fail! 401 "Invalid owner capability"))
        (let [id (token) csrf (token)]
          (reset! sessions {id {:csrf csrf :expires (+ (System/currentTimeMillis) (* 8 60 60 1000))}})
          (.set (.getResponseHeaders e) "Set-Cookie" (str "owner-session=" id "; HttpOnly; SameSite=Strict; Path=/; Max-Age=28800"))
          (respond {:authenticated true :demo true :csrf csrf})))
      (not auth) (fail! 401 "Owner login required")
      post?
      (do
        (when-not (equal-secret? (:csrf auth) (header e "X-CSRF-Token")) (fail! 403 "CSRF token required"))
        (let [r (request-values (body! e))]
          (case path
            "/api/proposals" (respond (reviews/propose! database-url r))
            "/api/decisions" (respond (reviews/decide! database-url r))
            "/api/publication" (respond (publication/decide! database-url r))
            "/api/logout" (do (reset! sessions {}) (respond {:authenticated false}))
            (fail! 404 "Unknown endpoint"))))
      (contains? #{"/api/proposals" "/api/decisions" "/api/publication" "/api/login" "/api/logout"} path) (fail! 405 "POST required")
      (= path "/api/candidates") (respond (assoc (candidates/packets (candidates/load-corpus database-url {}) {:limit 1000}) :rubric packets/rubric :demo true))
      (contains? #{"/api/detail" "/api/evidence"} path)
      (let [t (target! e) corpus (candidates/load-corpus database-url {}) row (some #(when (= t (select-keys % [:job-id :ordinal])) %) corpus)]
        (when-not row (fail! 404 "Unknown observation"))
        (if (= path "/api/evidence")
          (respond (assoc (select-keys row [:job-id :ordinal :source-lines :acquisitions :source-sha256 :artifact-sha256]) :coordinates (get-in row [:payload :coordinates])))
          (respond {:packet (assoc (candidates/packet corpus t {}) :target row :local-identity-anchor {:identity-id (str "local-observation:" (:job-id t) ":" (:ordinal t)) :reference (merge (select-keys row [:job-id :ordinal :candidate-id :source-sha256 :artifact-sha256]) (select-keys (first (:source-lines row)) [:page :line]))}) :effective (reviews/effective database-url t)
                    :history (reviews/history database-url t) :publication (publication/diagnose database-url t)
                    :publication-history (publication/history database-url t) :rubric packets/rubric :demo true})))
      :else (fail! 404 "Unknown endpoint"))))
(defn stop! [{:keys [^HttpServer server executor capability-file]}]
  (when server (.stop server 0)) (when executor (.shutdownNow ^java.util.concurrent.ExecutorService executor))
  (when capability-file (Files/deleteIfExists (Paths/get (str capability-file) (make-array String 0)))))
(defn start!
  "Start synthetic-only loopback service. New capability file in existing 0700 directory; no secret in return value."
  [{:keys [database-url port capability-file demo?] :as config}]
  (when-not (and (true? demo?) (integer? port) (<= 0 port 65535) (= #{:database-url :port :capability-file :demo?} (set (keys config)))) (fail! 400 "Explicit synthetic demo configuration required"))
  (authority! database-url)
  (when-not (every? #(true? (get-in % [:extraction-provenance :config :synthetic])) (candidates/load-corpus database-url {})) (fail! 400 "Demo database must contain only explicitly synthetic observations"))
  (let [secret (token) path (capability! capability-file secret) server (HttpServer/create) executor (Executors/newFixedThreadPool 4)]
    (try
      (.bind server (InetSocketAddress. "127.0.0.1" (int port)) 16)
      (let [port (.getPort (.getAddress server)) url (str "http://127.0.0.1:" port)
            state {:server server :executor executor :port port :url url :capability-file (str path)}
            context {:url url :database-url database-url :secret secret :sessions (atom {})}]
        (.createContext server "/" (reify HttpHandler (handle [_ e]
                                                        (try (routes! e context)
                                                             (catch clojure.lang.ExceptionInfo x
                                                               (let [message (.getMessage x) status (or (:status (ex-data x)) (if (re-find #"(?i)stale|idempotency|already decided|active unreversed" message) 409 400))]
                                                                 (respond! e status {:error message} "application/json")))
                                                             (catch Exception _ (respond! e 500 {:error "Operation failed; reload and check local configuration"} "application/json"))
                                                             (finally (.close ^HttpExchange e))))))
        (.setExecutor server executor) (.start server) state)
      (catch Exception x (stop! {:server server :executor executor :capability-file (str path)}) (throw x)))))

(defn -main [& [port capability-file]]
  (try
    (let [s (start! {:database-url (System/getenv "FREEDIVING_OWNER_DATABASE_URL")
                     :port (Integer/parseInt port) :capability-file capability-file :demo? true})]
      (.addShutdownHook (Runtime/getRuntime) (Thread. #(stop! s)))
      (println "Synthetic owner demo:" (:url s))
      (println "Read the private capability file locally to sign in."))
    (catch Exception _ (binding [*out* *err*] (println "Owner server startup failed. Verify synthetic database, restricted reviewer and private capability path.")) (System/exit 1))))
