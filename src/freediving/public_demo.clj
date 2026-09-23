(ns freediving.public-demo
  "Local synthetic-only public interface demonstration. Reports remain private."
  (:require [clojure.java.io :as io] [clojure.string :as str]
            [freediving.archive :as archive] [freediving.observations :as observations]
            [freediving.reviews :as reviews] [freediving.publication :as publication]
            [freediving.public-results :as public-results])
  (:import [java.security MessageDigest] [java.util HexFormat]
           [java.nio.file Files Paths] [java.nio.file.attribute PosixFilePermissions]))

(defn- endpoints! [admin ingest reviewer]
  (let [[_ port user] (when (string? admin) (re-matches #"jdbc:postgresql://127\.0\.0\.1:([0-9]{1,5})/public_demo\?user=([a-z_][a-z0-9_]*)" admin))
        prefix (str "jdbc:postgresql://127.0.0.1:" port "/public_demo?user=")]
    (when-not (and port user (<= 1 (Long/parseLong port) 65535)
                   (not (#{"observations_app" "reviews_owner" "reviews_public"} user))
                   (= ingest (str prefix "observations_app")) (= reviewer (str prefix "reviews_owner")))
      (throw (ex-info "Requires matching explicit loopback public_demo endpoints and separate roles" {})))))
(defn- sha [s]
  (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") (.getBytes ^String s "UTF-8"))))
(defn- canonical [v]
  (cond (map? v) (into (sorted-map) (map (fn [[k x]] [k (canonical x)]) v))
        (sequential? v) (mapv canonical v) :else v))
(defn- private-dir! [path]
  (let [p (Paths/get path (make-array String 0))]
    (Files/createDirectories p (into-array java.nio.file.attribute.FileAttribute
                                           [(PosixFilePermissions/asFileAttribute (PosixFilePermissions/fromString "rwx------"))]))
    (.toString (.toRealPath p (make-array java.nio.file.LinkOption 0)))))
(defn- fixture! [root label rows]
  (let [text (str/join "\n" (map :text rows)) source-sha (sha text)
        path (str root "/" label ".txt") archive-root (str root "/archive")
        manifest {:sha256 source-sha :discovery-url (str "https://example.invalid/" label)
                  :final-url (str "https://example.invalid/" label ".txt")
                  :acquisition-method "Generated local synthetic demo, no network acquisition"
                  :retrieved-at "2026-09-23T00:00:00Z" :content-type "text/plain"
                  :publisher "Synthetic demo federation" :relationship :publisher :mirror-of nil}]
    (spit path text)
    (Files/setPosixFilePermissions (.toPath (io/file path)) (PosixFilePermissions/fromString "rw-------"))
    (archive/register! archive-root path manifest)
    (let [identity {:source-sha256 source-sha :acquisitions (:acquisitions (archive/inspect archive-root source-sha))
                    :evidence-sha256 [] :actor "synthetic-demo" :config {:synthetic true}
                    :parser-version "synthetic-public-demo/1" :schema-version 1
                    :pdfinfo-version "not-applicable-text-fixture" :tool {:name "synthetic-fixture" :version "1" :arguments []}}
          artifact (merge identity
                          {:job-id (sha (pr-str (canonical identity))) :processed-at "2026-09-23T00:00:00Z"
                           :pdf-page-count 1 :raw-text text :publication {:status :blocked}
                           :pages [{:page 1 :text text :lines (mapv (fn [i r] {:line (inc i) :text (:text r)}) (range) rows)}]
                           :candidates (mapv (fn [i r]
                                               {:coordinates {:page 1 :line (inc i)}
                                                :raw {:line (:text r) :fields {:source-name (:name r) :performance (:raw-value r)}}
                                                :parsed (when (:value r)
                                                          {:source-name (:name r) :performance (:value r) :unit "m"
                                                           :discipline (or (:discipline r) "DYN") :representation "AIN"
                                                           :event-date (:date r) :federation (:federation r)
                                                           :category (:category r) :status nil})
                                                :parse-status (if (:value r) :parsed :unparsed)
                                                :review-status :unreviewed
                                                :unresolved-reasons (if (:value r) [:owner-review-required] [:unparsed-source-line])}) (range) rows)})]
      (archive/derive! archive-root (:job-id artifact) (constantly artifact) nil)
      {:root archive-root :job-id (:job-id artifact)})))

(defn- approve! [ingest reviewer target id field after extra]
  (let [state (reviews/effective reviewer target)
        before (if (= field :identity) (:identity state) (get-in state [:fields field]))
        base (:revision state)]
    (reviews/propose! ingest
                      (merge target {:id id :base-revision base :field field :before before :after after
                                     :category (if (= field :identity) :identity-matching :name-normalization)
                                     :evidence [{:page 1 :line (inc (:ordinal target))}]
                                     :actor "synthetic-demo" :reason "Generated synthetic source and explicit demo authority only"} extra))
    (reviews/decide! reviewer {:id (str id "-approved") :proposal-id id :action :approve :base-revision base
                               :actor "synthetic-demo" :reason "Synthetic example, never a real athlete approval"})))
(defn- validate! [reviewer target id]
  (let [d (publication/diagnose reviewer target)]
    (publication/decide! reviewer
                         (merge target {:id id :base-revision (:revision d) :review-revision (:review-revision d)
                                        :policy-version publication/current-policy :observation (:observation d)
                                        :action :validate :actor "synthetic-demo"
                                        :reason "Generated text fixture checked against its exact synthetic row"
                                        :evidence [{:page 1 :line (inc (:ordinal target))}]
                                        :attestations {:source-visual-accuracy true :no-unresolved-substantive-errors true}}))))
(defn seed!
  "Requires an empty dedicated database. Returns PRIVATE IDs for local demonstration."
  [admin ingest reviewer root]
  (endpoints! admin ingest reviewer)
  (with-open [c (java.sql.DriverManager/getConnection admin) s (.createStatement c)
              r (.executeQuery s "SELECT NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname NOT IN ('public','information_schema') AND nspname NOT LIKE 'pg_%') AND NOT EXISTS (SELECT 1 FROM pg_class WHERE relnamespace='public'::regnamespace)")]
    (.next r)
    (when-not (.getBoolean r 1) (throw (ex-info "Demo seed requires empty database; existing data is never reset" {}))))
  (when (.exists (io/file root)) (throw (ex-info "Choose a new private fixture directory" {})))
  (observations/migrate! admin "observations_app")
  (reviews/migrate! admin "observations_app" "reviews_owner")
  (publication/migrate! admin "reviews_owner")
  (public-results/migrate! admin "reviews_owner" "reviews_public")
  (let [root (private-dir! root)
        names ["Alex Éxample" "Casey Sample" "Synthetic Participant 03" "Synthetic Participant 04"
               "Synthetic Participant 05" "Synthetic Participant 06" "Example Alex" "Synthetic Participant 08"
               "Synthetic Participant 09" "Synthetic Participant 10" "Synthetic Participant 11" "Synthetic <script>alert(1)</script>"]
        rows (mapv (fn [i name]
                     (let [value (+ 90 i) discipline (if (< i 6) "DYN" "CWT")]
                       {:name name :value value :raw-value (str value) :discipline discipline
                        :date (when (not= i 2) (if (< i 6) "2026-09-01" "2026-09-02"))
                        :federation (if (< i 6) "Synthetic Federation A" "Synthetic Federation B")
                        :category (when (not= i 2) (if (even? i) "Synthetic open" "Synthetic masters"))
                        :text (str (inc i) " " name " AIN " value " m " discipline " [SYNTHETIC]"
                                   " | Federation: " (if (< i 6) "Synthetic Federation A" "Synthetic Federation B")
                                   " | Date: " (if (= i 2) "not recorded" (if (< i 6) "2026-09-01" "2026-09-02"))
                                   " | Category: " (if (= i 2) "not recorded" (if (even? i) "Synthetic open" "Synthetic masters")))})) (range) names)
        docs [(fixture! root "synthetic-public-pool" (conj (subvec rows 0 6) {:text "PRIVATE unreadable ?" :name "PRIVATE unreadable" :raw-value "?"}))
              (fixture! root "synthetic-public-depth" (conj (subvec rows 6 12) {:text "PRIVATE unvalidated 70 m" :name "PRIVATE unvalidated" :value 70 :raw-value "70"}))]
        targets (vec (for [doc docs ordinal (range 6)] {:job-id (:job-id doc) :ordinal ordinal}))
        a (first targets) b (nth targets 6) reversed (second targets)]
    (doseq [{:keys [root job-id]} docs] (observations/import! ingest root job-id))
    (let [anchor (merge (:observation (publication/diagnose reviewer a)) {:page 1 :line 1})
          identity {:outcome :matched :identity-id (str "local-observation:" (:job-id a) ":0")}]
      (doseq [[t id] [[a "synthetic-link-a"] [b "synthetic-link-b"]]]
        (approve! ingest reviewer t id :identity identity {:identity-target anchor :evidence [anchor]})))
    (approve! ingest reviewer a "synthetic-name" :source-name "Alex Example" {})
    (approve! ingest reviewer reversed "synthetic-reversed-name" :source-name "Casey Sample Corrected" {})
    (reviews/decide! reviewer {:id "synthetic-name-reversal" :event-id "synthetic-reversed-name-approved"
                               :action :reverse :base-revision 1 :actor "synthetic-demo"
                               :reason "Synthetic reversal example: original spelling restored"})
    (reviews/propose! ingest (merge (nth targets 2)
                                    {:id "PRIVATE-PROPOSAL" :base-revision 0 :category :name-normalization
                                     :field :source-name :before "Synthetic Participant 03" :after "PRIVATE-PENDING-NAME"
                                     :evidence [{:page 1 :line 3}] :actor "PRIVATE-ACTOR" :reason "PRIVATE-REASON"}))
    (doseq [[i t] (map-indexed vector targets)] (validate! reviewer t (str "synthetic-validation-" i)))
    (let [refresh (public-results/refresh! reviewer)]
      {:synthetic true :owner-reviewed-pilot-cases 0 :counts (observations/counts ingest)
       :jobs (mapv :job-id docs) :targets {:linked-a a :linked-b b :reversed reversed :unresolved (nth targets 2)
                                           :revoke (nth targets 3) :hidden {:job-id (:job-id (second docs)) :ordinal 6}}
       :refresh refresh})))
(defn -main [& args]
  (try
    (when-not (= 1 (count args)) (throw (ex-info "Expected private fixture directory" {})))
    (prn (seed! (System/getenv "FREEDIVING_DEMO_ADMIN_URL") (System/getenv "FREEDIVING_DEMO_INGEST_URL")
                (System/getenv "FREEDIVING_DEMO_REVIEW_URL") (first args)))
    (catch Exception _ (binding [*out* *err*] (println "Synthetic public demo setup failed; requires empty loopback public_demo and explicit separate roles."))
           (System/exit 1))))
