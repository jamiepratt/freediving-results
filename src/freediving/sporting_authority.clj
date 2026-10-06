(ns freediving.sporting-authority
  "Pinned owner-only signatures, fresh challenges and ordered immutable delivery receipts."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.time Duration Instant]
           [java.security KeyFactory MessageDigest Signature SecureRandom]
           [java.security.spec X509EncodedKeySpec]
           [java.util Base64 HexFormat]
           [java.nio.file Files Path LinkOption]
           [java.nio.file.attribute PosixFilePermission]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))
(def genesis (apply str (repeat 64 "0")))
(def policy "aida-baseline-v1")
(defn sha [value]
  (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") (.getBytes (str value) "UTF-8"))))
(defn canonical-json [value]
  (letfn [(sort-value [v]
            (cond (map? v) (into (sorted-map) (map (fn [[k x]] [(if (keyword? k) (name k) k) (sort-value x)]) v))
                  (sequential? v) (mapv sort-value v)
                  (keyword? v) (name v)
                  :else v))]
    (json/write-str (sort-value value) :escape-unicode false :escape-slash false)))
(defn- reject! [] (throw (ex-info "Current sporting authority unavailable" {})))
(defn- exact! [value keys]
  (when-not (and (map? value) (= keys (set (map name (clojure.core/keys value))))) (reject!)))
(defn- digest? [v] (and (string? v) (boolean (re-matches #"[a-f0-9]{64}" v))))
(defn read-config [path]
  (when path
    (let [file (Path/of path (make-array String 0)) parent (.getParent file)
          options (make-array LinkOption 0)
          unsafe #{PosixFilePermission/GROUP_WRITE PosixFilePermission/OTHERS_READ
                   PosixFilePermission/OTHERS_WRITE PosixFilePermission/OTHERS_EXECUTE}]
      (when-not (and (.isAbsolute file) parent (not (Files/isSymbolicLink file))
                     (not (Files/isSymbolicLink parent))
                     (not-any? unsafe (Files/getPosixFilePermissions file options))
                     (not-any? #{PosixFilePermission/GROUP_WRITE PosixFilePermission/OTHERS_WRITE}
                               (Files/getPosixFilePermissions parent options))
                     (#{"root" (System/getProperty "user.name")} (str (Files/getOwner file options)))) (reject!))
      (json/read-str (slurp path) :key-fn keyword))))
(defn config [] (read-config (System/getenv "FREEDIVING_SPORTING_AUTHORITY_CONFIG")))
(defn- config! [cfg]
  (exact! (dissoc cfg :request_timeout_ms) #{"endpoint" "request_secret" "public_key_der" "key_id"})
  (when-not (and (integer? (:request_timeout_ms cfg 30000)) (<= 1000 (:request_timeout_ms cfg 30000) 30000)) (reject!))
  (let [uri (URI. (:endpoint cfg))]
    (when-not (and (= "http" (.getScheme uri)) (= "127.0.0.1" (.getHost uri))
                   (pos? (.getPort uri)) (= "/owner-evidence/api/sporting-authority/current" (.getPath uri))
                   (nil? (.getQuery uri)) (nil? (.getFragment uri)) (nil? (.getUserInfo uri))
                   (string? (:request_secret cfg)) (<= 32 (count (:request_secret cfg)) 256)) (reject!)))
  cfg)
(def event-keys #{"revision" "previous_sha256" "head_sha256" "action" "publication_sha256" "binding_sha256" "policy" "decision_sha256"})
(defn- events! [payload]
  (let [events (:events payload) revision (:revision payload)]
    (when-not (and (vector? events) (nat-int? revision) (<= revision 10000) (= revision (count events))) (reject!))
    (loop [remaining events prior genesis seqno 1]
      (when-let [event (first remaining)]
        (exact! event event-keys)
        (when-not (and (= seqno (:revision event)) (= prior (:previous_sha256 event))
                       (digest? (:head_sha256 event)) (digest? (:binding_sha256 event)) (digest? (:decision_sha256 event))
                       (= policy (:policy event)) (#{"stage" "approve" "reverse"} (:action event))
                       (or (nil? (:publication_sha256 event)) (digest? (:publication_sha256 event)))
                       (= (:head_sha256 event) (sha (canonical-json (dissoc event :head_sha256))))) (reject!))
        (recur (next remaining) (:head_sha256 event) (inc seqno))))
    (let [last-event (peek events)]
      (when-not (and (= (:head_sha256 payload) (or (:head_sha256 last-event) genesis))
                     (= (:previous_sha256 payload) (or (:previous_sha256 last-event) genesis))) (reject!)))))
(defn verify-envelope
  "No truth flags or shared-key response signatures accepted. Signature covers the whole current feed."
  [cfg nonce envelope]
  (config! cfg)
  (exact! envelope #{"schema" "payload" "signature_base64" "key_id"})
  (let [decoder (Base64/getDecoder) der (.decode decoder ^String (:public_key_der cfg))
        key (-> (KeyFactory/getInstance "Ed25519") (.generatePublic (X509EncodedKeySpec. der)))
        verifier (Signature/getInstance "Ed25519")
        payload (:payload envelope)]
    (when-not (and (= "sporting-authority-envelope/v1" (:schema envelope))
                   (= (:key_id cfg) (:key_id envelope) (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") der)))) (reject!))
    (.initVerify verifier key)
    (.update verifier (.getBytes (canonical-json payload) "UTF-8"))
    (when-not (.verify verifier (.decode decoder ^String (:signature_base64 envelope))) (reject!))
    (exact! payload #{"schema" "nonce" "revision" "head_sha256" "previous_sha256" "issued_at" "expires_at" "status" "binding_sha256" "publication" "publication_sha256" "policy" "events"})
    (let [now (Instant/now) issued (Instant/parse (:issued_at payload)) expires (Instant/parse (:expires_at payload))]
      (when-not (and (= "sporting-authority-current/v1" (:schema payload)) (= nonce (:nonce payload))
                     (= policy (:policy payload)) (#{"current" "unavailable"} (:status payload))
                     (not (.isAfter issued (.plusSeconds now 1))) (not (.isBefore issued (.minusSeconds now 5)))
                     (.isAfter expires now) (not (.isAfter expires (.plusSeconds issued 5)))) (reject!)))
    (events! payload)
    (when-not (or (nil? (:binding_sha256 payload)) (digest? (:binding_sha256 payload))) (reject!))
    (when (:publication payload)
      (let [last-event (peek (:events payload))]
        (when-not (and (= "current" (:status payload)) (= "approve" (:action last-event))
                       (= (:binding_sha256 payload) (:binding_sha256 last-event))
                       (= (:publication_sha256 payload) (:publication_sha256 last-event)
                          (sha (canonical-json (:publication payload))))) (reject!))))
    (when (and (= "unavailable" (:status payload)) (:publication payload)) (reject!))
    (assoc payload :key_id (:key_id cfg))))
(defonce ^:private http-client
  (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofMillis 750)) .build))
(defn fetch-current!
  "Fresh random nonce on every call; no retry, fallback or cached successful response."
  ([] (fetch-current! (config)))
  ([cfg]
   (config! cfg)
   (let [random (byte-array 32) _ (.nextBytes (SecureRandom.) random)
         nonce (.formatHex (HexFormat/of) random)
         body (canonical-json {:schema "sporting-authority-challenge/v1" :nonce nonce})
         mac (Mac/getInstance "HmacSHA256")
         _ (.init mac (SecretKeySpec. (.getBytes (:request_secret cfg) "UTF-8") "HmacSHA256"))
         auth (.formatHex (HexFormat/of) (.doFinal mac (.getBytes body "UTF-8")))
         request (-> (HttpRequest/newBuilder (URI. (:endpoint cfg)))
                     (.timeout (Duration/ofMillis (:request_timeout_ms cfg 30000)))
                     (.header "Content-Type" "application/json")
                     (.header "X-Freediving-Sporting-HMAC" auth)
                     (.POST (java.net.http.HttpRequest$BodyPublishers/ofString body)) .build)
         response (.send http-client request (HttpResponse$BodyHandlers/ofString))]
     (when-not (and (= 200 (.statusCode response)) (<= (count (.body response)) 16777216)) (reject!))
     (verify-envelope cfg nonce (json/read-str (.body response) :key-fn keyword :bigdec true)))))
(defn current-for?
  "Checks delivered receipt against freshly signed head and exact projection binding."
  [event]
  (try
    (let [current (fetch-current!)]
      (and (= "current" (:status current)) (:publication current)
           (= (:bridge_revision event) (:revision current))
           (= (:bridge_head event) (:head_sha256 current))
           (= (:bridge_binding event) (:binding_sha256 current))
           (= (:bridge_publication event) (:publication_sha256 current)
              (sha (canonical-json (edn/read-string (:body_edn event)))))
           (= (:bridge_key event) (:key_id current))
           (= (:policy_version event) (:policy current))))
    (catch Exception _ false)))
(defn decode-publication [publication]
  ;; Convert only domain enum slots; names, URLs, event IDs and hashes remain strings.
  (let [enum-facts #{:source-authority :finality :sanction :review :outcome :same-attempt :source-conflict :scoring-policy}
        value-map (fn [value ks] (reduce (fn [m k] (if (string? (get m k)) (update m k keyword) m)) value ks))
        decode-fact (fn [k fact]
                      (let [v (:value fact)
                            v (cond (enum-facts k) (when v (keyword v))
                                    (= k :source-view) (value-map v [:kind :environment])
                                    (= k :final) (update (value-map v [:basis :conversion]) :value #(when (some? %) (bigdec %)))
                                    (= k :comparable-category) (value-map v [:group :para-class :age-class :age-equivalence])
                                    (= k :listing) (value-map v [:publisher :kind])
                                    (= k :international-sanction) (value-map v [:authority :level :status])
                                    (= k :source-selection) (value-map v [:basis :tie-break-rule])
                                    (and (= k :source-gender) (= v "unknown")) :unknown
                                    :else v)]
                        (assoc fact :value v :policy (keyword (:policy fact)))))]
    (-> publication
        (update :rows #(mapv (fn [row]
                               (cond-> (update row :facts (fn [facts] (into {} (map (fn [[k fact]] [k (decode-fact k fact)]) facts))))
                                 (:hypothetical row) (update :hypothetical (partial decode-fact :final)))) %))
        (update-in [:cohort :binding :policy] keyword))))
