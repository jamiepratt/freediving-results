(ns freediving.owner-server
  "Trusted-local, synthetic owner review. The capability authenticates the owner; actor is audit text."
  (:require [clojure.edn :as edn] [clojure.data.json :as json] [clojure.java.io :as io] [clojure.string :as str]
            [freediving.candidates :as candidates] [freediving.packets :as packets]
            [freediving.corrections :as corrections]
            [freediving.reviews :as reviews] [freediving.publication :as publication]
            [freediving.spelling-normalization :as spelling])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress URLDecoder]
           [java.sql DriverManager]
           [java.security SecureRandom MessageDigest]
           [java.util Base64]
           [java.nio.file Files Paths LinkOption StandardOpenOption]
           [java.nio.file.attribute PosixFilePermissions FileAttribute]
           [java.util.concurrent ThreadPoolExecutor TimeUnit ArrayBlockingQueue ThreadPoolExecutor$AbortPolicy]))
(defn- fail! [status message] (throw (ex-info message {:status status})))
(defn- token [] (let [b (byte-array 32)] (.nextBytes (SecureRandom.) b) (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b)))
(defn- equal-secret? [a b] (and (string? a) (string? b) (MessageDigest/isEqual (.getBytes a "UTF-8") (.getBytes b "UTF-8"))))
(defn- query [c sql]
  (with-open [s (.createStatement c) r (.executeQuery s sql)]
    (let [m (.getMetaData r)] (loop [rows []] (if (.next r) (recur (conj rows (into {} (for [i (range 1 (inc (.getColumnCount m)))] [(keyword (.getColumnLabel m i)) (.getObject r i)])))) rows)))))
(defn- authority! [url review-enabled?]
  (when-not (and (string? url) (re-matches #"jdbc:postgresql://127\.0\.0\.1:[0-9]{1,5}/[A-Za-z0-9_]+(?:\?(?:user|password|sslmode|connectTimeout|socketTimeout)=[^&]*)(?:&(?:user|password|sslmode|connectTimeout|socketTimeout)=[^&]*)*|jdbc:postgresql://127\.0\.0\.1:[0-9]{1,5}/[A-Za-z0-9_]+" url)) (fail! 400 "Loopback PostgreSQL reviewer URL required"))
  (with-open [c (DriverManager/getConnection url)]
    (when (or (some true? (vals (first (query c "SELECT rolsuper,rolcreaterole,rolcreatedb,rolbypassrls,rolreplication FROM pg_roles WHERE rolname=current_user"))))
              (seq (query c "SELECT roleid FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname=current_user)"))
              (seq (query c "SELECT oid FROM pg_class WHERE relnamespace=to_regnamespace('freediving') AND pg_has_role(current_user,relowner,'MEMBER')"))
              (seq (query c "SELECT oid FROM pg_namespace WHERE nspname='freediving' AND pg_has_role(current_user,nspowner,'MEMBER')"))
              (seq (query c "SELECT oid FROM pg_database WHERE datname=current_database() AND pg_has_role(current_user,datdba,'MEMBER')")))
      (fail! 403 "Restricted reviewer role required"))
    (doseq [table ["extractions" "observations" "review_proposals" "review_decisions" "extraction_reviews" "publication_decisions" "publication_policy_events" "correction_requests" "correction_triage"]]
      (let [v (first (query c (str "SELECT has_table_privilege(current_user,'freediving." table "','SELECT') AS readable,has_table_privilege(current_user,'freediving." table "','UPDATE,DELETE,TRUNCATE') AS mutable,has_table_privilege(current_user,'freediving." table "','INSERT') AS appendable")))]
        (when (or (not (:readable v)) (:mutable v) (not= (and review-enabled? (contains? #{"review_proposals" "review_decisions" "extraction_reviews" "publication_decisions" "correction_triage"} table)) (:appendable v))) (fail! 403 (if review-enabled? "Reviewer table privileges invalid" "Inspector table privileges invalid")))))
    (when (or (seq (query c "SELECT oid FROM pg_namespace WHERE nspname NOT LIKE 'pg_%' AND nspname <> 'information_schema' AND (pg_has_role(current_user,nspowner,'MEMBER') OR has_schema_privilege(current_user,oid,'CREATE'))"))
              (:allowed (first (query c "SELECT has_database_privilege(current_user,current_database(),'CREATE') AS allowed")))
              (seq (query c "SELECT p.oid FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE p.prosecdef AND p.oid IS DISTINCT FROM to_regprocedure('freediving.lock_selection_authority()') AND n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema' AND has_function_privilege(current_user,p.oid,'EXECUTE')"))
              (seq (query c "SELECT c.oid FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema' AND c.relkind IN ('r','p','v','m','f') AND (pg_has_role(current_user,c.relowner,'MEMBER') OR has_table_privilege(current_user,c.oid,'TRUNCATE,REFERENCES,TRIGGER') OR has_any_column_privilege(current_user,c.oid,'REFERENCES') OR (NOT (n.nspname='freediving' AND c.relname IN ('public_projection_cache','event_coverage_cache')) AND (has_any_column_privilege(current_user,c.oid,'UPDATE') OR has_table_privilege(current_user,c.oid,'DELETE'))) OR (NOT (n.nspname='freediving' AND c.relname IN ('review_proposals','review_decisions','extraction_reviews','publication_decisions','correction_triage','evaluation_labels','revision_proposals','revision_decisions','public_projection_cache','event_selections','event_coverage_cache')) AND has_any_column_privilege(current_user,c.oid,'INSERT')))")))
      (fail! 403 "Owner database authority invalid"))
    (when (and (not review-enabled?)
               (or (seq (query c "SELECT oid FROM pg_namespace WHERE nspname NOT LIKE 'pg_temp_%' AND has_schema_privilege(current_user,oid,'CREATE')"))
                   (:allowed (first (query c "SELECT has_database_privilege(current_user,current_database(),'CREATE') AS allowed")))
                   (seq (query c "SELECT c.oid FROM pg_class c WHERE c.relnamespace=to_regnamespace('freediving') AND c.relkind IN ('r','p','v','m','f') AND (has_table_privilege(current_user,c.oid,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') OR has_any_column_privilege(current_user,c.oid,'INSERT,UPDATE,REFERENCES'))"))
                   (seq (query c "SELECT p.oid FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE p.prosecdef AND n.nspname NOT IN ('pg_catalog','information_schema') AND has_function_privilege(current_user,p.oid,'EXECUTE')"))))
      (fail! 403 "Inspector database authority invalid"))
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
  (let [bytes (if (bytes? value) value (.getBytes (if (= content-type "application/json") (json/write-str value) (str value)) "UTF-8")) h (.getResponseHeaders e)]
    (doseq [[k v] {"Content-Type" (str content-type (when-not (= content-type "image/png") "; charset=utf-8")) "Cache-Control" "no-store" "X-Content-Type-Options" "nosniff" "Referrer-Policy" "no-referrer" "Content-Security-Policy" "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"}] (.set h k v))
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
(defn- routes! [e {:keys [url database-url secret sessions mode-info source-config score-config]}]
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
      (and get? (= path "/api/session")) (respond (cond-> (merge mode-info {:authenticated (boolean auth)}) auth (assoc :csrf (:csrf auth))))
      (and post? (= path "/api/login"))
      (let [r (body! e)]
        (when-not (equal-secret? secret (:capability r)) (fail! 401 "Invalid owner capability"))
        (let [id (token) csrf (token)]
          (reset! sessions {id {:csrf csrf :viewed (atom {}) :expires (+ (System/currentTimeMillis) (* 8 60 60 1000))}})
          (.set (.getResponseHeaders e) "Set-Cookie" (str "owner-session=" id "; HttpOnly; SameSite=Strict; Path=/; Max-Age=28800"))
          (respond (merge mode-info {:authenticated true :csrf csrf}))))
      (not auth) (fail! 401 "Owner login required")
      post?
      (do
        (when-not (equal-secret? (:csrf auth) (header e "X-CSRF-Token")) (fail! 403 "CSRF token required"))
        (when (and (not= path "/api/logout") (not (:review-enabled? mode-info))) (fail! 403 "Owner inspection is read-only"))
        (let [r (request-values (body! e))]
          (case path
            "/api/corrections/triage" (respond (corrections/triage! database-url (assoc r :proposal-id (:proposal-id r))))
            "/api/proposals" (respond (reviews/propose! database-url r))
            "/api/decisions" (respond (reviews/decide! database-url r))
            "/api/publication" (do
                                 (when (and source-config (= :validate (:action r)))
                                   (let [t (select-keys r [:job-id :ordinal])
                                         row (some #(when (= t (select-keys % [:job-id :ordinal])) %) (candidates/load-corpus database-url {}))
                                         html? (= :html (:source-format row))
                                         page (get-in row [:payload :coordinates :page])
                                         seen (get @(:viewed auth) t)]
                                     (when-not (and row seen (if html? (= (get-in row [:payload :coordinates]) (:coordinates seen)) (= page (:page seen))))
                                       (fail! 403 "View this observation's exact source evidence before attesting accuracy"))
                                     (let [current (try (if html? ((requiring-resolve 'freediving.source-pages/inspect-html!) source-config row) (:metadata ((requiring-resolve 'freediving.source-pages/render!) source-config row page)))
                                                        (catch Exception _ (fail! 400 "Verified source page unavailable")))]
                                       (when-not (= (:render-id seen) (:render-id current))
                                         (fail! 409 "Source render changed; view the source page again")))))
                                 (respond (publication/decide! database-url r)))
            "/api/logout" (do (reset! sessions {}) (respond {:authenticated false}))
            (fail! 404 "Unknown endpoint"))))
      (contains? #{"/api/proposals" "/api/decisions" "/api/publication" "/api/login" "/api/logout" "/api/corrections/triage"} path) (fail! 405 "POST required")
      (= path "/api/source-html")
      (let [t (target! e) p (params e)
            row (some #(when (= t (select-keys % [:job-id :ordinal])) %) (candidates/load-corpus database-url {}))]
        (when-not source-config (fail! 404 "Source viewer is not configured"))
        (when-not (and row (= #{:job-id :ordinal} (set (keys p)))) (fail! 400 "Exact observation required"))
        (let [evidence (try ((requiring-resolve 'freediving.source-pages/inspect-html!) source-config row)
                            (catch Exception _ (fail! 400 "Verified HTML source unavailable")))]
          (respond evidence)
          (swap! (:viewed auth) assoc t (select-keys evidence [:coordinates :render-id :source-sha256]))))
      (contains? #{"/api/source-page" "/api/source-page.png"} path)
      (let [t (target! e) p (params e)
            page (try (Integer/parseInt (:page p)) (catch Exception _ -1))
            corpus (candidates/load-corpus database-url {})
            row (some #(when (= t (select-keys % [:job-id :ordinal])) %) corpus)]
        (when-not source-config (fail! 404 "Source viewer is not configured"))
        (when-not (and row (pos? page)
                       (every? #{:job-id :ordinal :page :render-id} (keys p))) (fail! 400 "Exact observation and source page required"))
        (let [{:keys [bytes metadata]} (try ((requiring-resolve 'freediving.source-pages/render!) source-config row page)
                                            (catch Exception _ (fail! 400 "Verified source page unavailable")))
              image-url (str "/api/source-page.png?job-id=" (:job-id t) "&ordinal=" (:ordinal t) "&page=" page "&render-id=" (:render-id metadata))]
          (when (and (:render-id p) (not= (:render-id p) (:render-id metadata)))
            (fail! 409 "Source render changed; reload page evidence"))
          (if (= path "/api/source-page.png")
            (do (respond! e 200 bytes "image/png")
                (swap! (:viewed auth) assoc t (select-keys metadata [:page :render-id :source-sha256])))
            (respond (assoc metadata :image-url image-url :job-id (:job-id t) :ordinal (:ordinal t))))))
      (= path "/api/corrections")
      (let [p (params e) offset (try (Long/parseLong (get p :offset "0")) (catch Exception _ -1))]
        (when-not (<= 0 offset 10000) (fail! 400 "Correction offset must be between 0 and 10000"))
        (respond (corrections/list-requests database-url {:limit 100 :offset offset})))
      (= path "/api/candidates") (respond (merge mode-info (assoc (candidates/packets (candidates/load-corpus database-url {}) {:limit 1000}) :rubric packets/rubric)))
      (contains? #{"/api/detail" "/api/evidence"} path)
      (let [t (target! e) corpus (candidates/load-corpus database-url {}) row (some #(when (= t (select-keys % [:job-id :ordinal])) %) corpus)]
        (when-not row (fail! 404 "Unknown observation"))
        (if (= path "/api/evidence")
          (respond (assoc (select-keys row [:job-id :ordinal :source-format :source-lines :acquisitions :source-sha256 :artifact-sha256]) :coordinates (get-in row [:payload :coordinates])))
          (let [packet (candidates/packet corpus t {})
                candidate-rows (mapcat :observations (:candidates packet))
                _ (when (> (count candidate-rows) 1000) (fail! 400 "Too many candidate pairs"))
                current-cases (when score-config
                                (mapv (fn [candidate]
                                        (let [reference (select-keys candidate [:job-id :ordinal])]
                                          (try
                                            (assoc ((requiring-resolve 'freediving.jev-candidates/candidate-case)
                                                    database-url corpus t reference)
                                                   :target-reference t :candidate-reference reference)
                                            (catch clojure.lang.ExceptionInfo error
                                              (if (re-find #"Unsupported|ambiguous|Only parsed|Missing PDF source line" (or (.getMessage error) ""))
                                                {:case-id (str "unsupported:" (:job-id candidate) ":" (:ordinal candidate))
                                                 :score-status :unsupported
                                                 :target-reference (select-keys row [:job-id :ordinal :candidate-id :source-sha256 :artifact-sha256 :parser-version :schema-version])
                                                 :candidate-reference (select-keys candidate [:job-id :ordinal :candidate-id :source-sha256 :artifact-sha256 :parser-version :schema-version])}
                                                (throw error))))))
                                      candidate-rows))]
            (respond (merge mode-info {:packet (assoc packet :target row :local-identity-anchor {:identity-id (str "local-observation:" (:job-id t) ":" (:ordinal t)) :reference (merge (select-keys row [:job-id :ordinal :candidate-id :source-sha256 :artifact-sha256]) (if (= :html (:source-format row)) (select-keys (get-in row [:payload :coordinates]) [:table :row]) (select-keys (first (:source-lines row)) [:page :line])))}) :effective (reviews/effective database-url t)
                                       :jev-scores (when score-config
                                                     (if-let [run-id (:jev-run-id score-config)]
                                                       (spelling/score-view (:jev-run-root score-config)
                                                                            run-id (:jev-provider-id score-config) t corpus
                                                                            (set (map :case-id (remove :score-status current-cases))))
                                                       (spelling/score-views (:jev-run-root score-config)
                                                                             (:jev-provider-id score-config) t corpus current-cases)))
                                       :history (reviews/history database-url t) :publication (publication/diagnose database-url t)
                                       :publication-history (publication/history database-url t) :rubric packets/rubric})))))
      :else (fail! 404 "Unknown endpoint"))))
(defn stop! [{:keys [^HttpServer server executor capability-file]}]
  (when server (.stop server 0)) (when executor (.shutdownNow ^java.util.concurrent.ExecutorService executor))
  (when capability-file (Files/deleteIfExists (Paths/get (str capability-file) (make-array String 0)))))
(defn start!
  "Start an explicit loopback owner mode with a new private capability. Real inspection defaults to read-only and requires an inspector database role."
  [{:keys [database-url port capability-file demo?] :as config}]
  ;; JDK HTTP server enforces these request/response deadlines even for slow clients.
  (System/setProperty "sun.net.httpserver.maxReqTime" "15")
  (System/setProperty "sun.net.httpserver.maxRspTime" "45")
  (let [real? (= :real-inspection (:mode config))
        review-enabled? (if real? (true? (:review-enabled? config)) (true? demo?))
        allowed (if real? #{:database-url :port :capability-file :mode :archive-root :cache-root :review-enabled? :jev-run-root :jev-run-id :jev-provider-id}
                    #{:database-url :port :capability-file :demo? :archive-root :cache-root :jev-run-root :jev-run-id :jev-provider-id})]
    (when-not (and (integer? port) (<= 0 port 65535) (every? allowed (keys config))
                   (or real? (true? demo?))
                   (or (not (contains? config :review-enabled?)) (boolean? (:review-enabled? config)))
                   (= (contains? config :archive-root) (contains? config :cache-root))
                   (or (not-any? #(contains? config %) [:jev-run-root :jev-run-id :jev-provider-id])
                       (and (every? #(and (string? (get config %)) (not (str/blank? (get config %))))
                                    [:jev-run-root :jev-provider-id])
                            (or (not (contains? config :jev-run-id))
                                (and (string? (:jev-run-id config)) (not (str/blank? (:jev-run-id config)))))))
                   (or (not real?) (and (string? (:archive-root config)) (string? (:cache-root config)))))
      (fail! 400 "Explicit owner mode and source configuration required"))
    (authority! database-url review-enabled?)
    (let [corpus (candidates/load-corpus database-url {})]
      (when-not (if real? (and (seq corpus) (every? #(not (get-in % [:extraction-provenance :config :synthetic])) corpus))
                    (every? #(true? (get-in % [:extraction-provenance :config :synthetic])) corpus))
        (fail! 400 "Database corpus does not match explicit owner mode"))
      (when (:archive-root config)
        ((requiring-resolve 'freediving.source-pages/verify-config!) (select-keys config [:archive-root :cache-root]))
        ((requiring-resolve 'freediving.source-pages/verify-corpus!) (:archive-root config) corpus)))
    (let [secret (token) path (capability! capability-file secret) server (HttpServer/create) executor (ThreadPoolExecutor. 4 4 0 TimeUnit/SECONDS (ArrayBlockingQueue. 16) (ThreadPoolExecutor$AbortPolicy.))]
      (try
        (.bind server (InetSocketAddress. "127.0.0.1" (int port)) 16)
        (let [port (.getPort (.getAddress server)) url (str "http://127.0.0.1:" port)
              state {:server server :executor executor :port port :url url :capability-file (str path)}
              context {:url url :database-url database-url :secret secret :sessions (atom {})
                       :source-config (when (:archive-root config) (select-keys config [:archive-root :cache-root]))
                       :score-config (when (:jev-run-root config) (select-keys config [:jev-run-root :jev-run-id :jev-provider-id]))
                       :mode-info {:demo (not real?) :mode (if real? "real-inspection" "synthetic-demo") :review-enabled? review-enabled? :source-viewer? (boolean (:archive-root config))}}]
          (.createContext server "/" (reify HttpHandler (handle [_ e]
                                                          (try (routes! e context)
                                                               (catch clojure.lang.ExceptionInfo x
                                                                 (let [message (or ({:invalid "Check the required fields and, when linking, use an existing proposal for this observation"
                                                                                     :conflict "The request changed or the retry contents differ"
                                                                                     :unavailable "Correction request unavailable"
                                                                                     :capacity "This request has reached its triage limit"} (:status (ex-data x))) (.getMessage x)) status (let [code (:status (ex-data x))]
                                                                                                                                                                                             (if (integer? code) code
                                                                                                                                                                                                 (or ({:conflict 409 :unavailable 404 :capacity 429 :invalid 400} code)
                                                                                                                                                                                                     (if (re-find #"(?i)stale|idempotency|already decided|active unreversed" message) 409 400))))]
                                                                   (respond! e status {:error message} "application/json")))
                                                               (catch Exception _ (respond! e 500 {:error "Operation failed; reload and check local configuration"} "application/json"))
                                                               (finally (.close ^HttpExchange e))))))
          (.setExecutor server executor) (.start server) state)
        (catch Exception x (stop! {:server server :executor executor :capability-file (str path)}) (throw x))))))

(defn- read-config! [path]
  (let [p (.toAbsolutePath (Paths/get path (make-array String 0)))
        nofollow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])]
    (loop [x p] (when x (when (Files/isSymbolicLink x) (fail! 400 "Configuration path cannot contain symlinks")) (recur (.getParent x))))
    (when-not (and (Files/isRegularFile p nofollow) (<= (Files/size p) 16384)
                   (not-any? #(re-find #"GROUP|OTHERS" (str %)) (Files/getPosixFilePermissions p nofollow)))
      (fail! 400 "Private configuration file required"))
    (with-open [r (java.io.PushbackReader. (io/reader (.toFile p)))]
      (let [v (edn/read {:eof ::eof} r)]
        (when-not (and (map? v) (= ::eof (edn/read {:eof ::eof} r))) (fail! 400 "Single configuration map required")) v))))

(defn -main [& [port capability-file]]
  (try
    (let [config (if (= port "--config")
                   (assoc (read-config! capability-file) :database-url (System/getenv "FREEDIVING_OWNER_DATABASE_URL"))
                   {:database-url (System/getenv "FREEDIVING_OWNER_DATABASE_URL")
                    :port (Integer/parseInt port) :capability-file capability-file :demo? true})
          s (start! config)]
      (.addShutdownHook (Runtime/getRuntime) (Thread. #(stop! s)))
      (println "Local owner service:" (:url s))
      (println "Read the private capability file locally to sign in."))
    (catch Exception _ (binding [*out* *err*] (println "Owner server startup failed. Verify explicit mode, restricted database role, registered archive and private paths.")) (System/exit 1))))
