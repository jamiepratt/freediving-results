(ns freediving.owner-demo
  "Explicit synthetic fixtures. Never reads or changes the real pilot."
  (:require [clojure.java.io :as io] [clojure.string :as str]
            [freediving.archive :as archive] [freediving.observations :as observations]
            [freediving.reviews :as reviews] [freediving.publication :as publication]
            [freediving.public-results :as public-results])
  (:import [java.security MessageDigest] [java.util HexFormat]
           [java.nio.file Files Paths] [java.nio.file.attribute PosixFilePermissions]))

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
                    :parser-version "synthetic-owner-demo/1" :schema-version 1
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
                                                           :discipline "DYN" :representation "AIN" :event-date nil
                                                           :category "Synthetic open" :status nil})
                                                :parse-status (if (:value r) :parsed :unparsed)
                                                :review-status :unreviewed
                                                :unresolved-reasons (if (:value r) [:owner-review-required] [:unparsed-source-line])}) (range) rows)})]
      (archive/derive! archive-root (:job-id artifact) (constantly artifact) nil)
      {:root archive-root :job-id (:job-id artifact)})))

(defn seed!
  "Install only into an empty, explicitly named owner_demo database; never resets data."
  [admin-url ingest-url root]
  (when-not (and (string? admin-url) (string? ingest-url)
                 (re-matches #"jdbc:postgresql://127\.0\.0\.1:[0-9]+/owner_demo\?user=[a-z_][a-z0-9_]*" admin-url)
                 (re-matches #"jdbc:postgresql://127\.0\.0\.1:[0-9]+/owner_demo\?user=observations_app" ingest-url)
                 (= (first (str/split admin-url #"\?")) (first (str/split ingest-url #"\?"))))
    (throw (ex-info "Explicit loopback owner_demo database required" {})))
  (with-open [c (java.sql.DriverManager/getConnection admin-url) s (.createStatement c)
              r (.executeQuery s "SELECT to_regnamespace('freediving') IS NULL")]
    (.next r)
    (when-not (.getBoolean r 1) (throw (ex-info "Demo seed requires empty database; existing data is never reset" {}))))
  (observations/migrate! admin-url "observations_app")
  (reviews/migrate! admin-url "observations_app" "reviews_owner")
  (publication/migrate! admin-url "reviews_owner")
  (public-results/migrate! admin-url "reviews_owner" "reviews_public")
  (let [root (private-dir! root)
        docs [(fixture! root "synthetic-pool-a"
                        [{:text "1 Alex Éxample AIN 101 m" :name "Alex Éxample" :value 101 :raw-value "101"}
                         {:text "2 Casey Sample AIN 95 m" :name "Casey Sample" :value 95 :raw-value "95"}
                         {:text "3 Unreadable ? AIN ?" :name "Unreadable ?" :raw-value "?"}])
              (fixture! root "synthetic-pool-b"
                        [{:text "1 Example Alex AIN 102 m" :name "Example Alex" :value 102 :raw-value "102"}
                         {:text "2 Morgan Other AIN 90 m" :name "Morgan Other" :value 90 :raw-value "90"}])]]
    (doseq [{:keys [root job-id]} docs] (observations/import! ingest-url root job-id))
    {:synthetic true :counts (observations/counts ingest-url) :jobs (mapv :job-id docs)
     :decisions 0 :owner-reviewed-pilot-cases 0}))
(defn -main [& args]
  (try
    (when-not (= 1 (count args)) (throw (ex-info "Usage: clojure -M:owner-demo PRIVATE_DIRECTORY" {})))
    (prn (seed! (System/getenv "FREEDIVING_DEMO_ADMIN_URL") (System/getenv "FREEDIVING_DEMO_INGEST_URL") (first args)))
    (catch Exception _ (binding [*out* *err*] (println "Synthetic demo setup failed; requires empty loopback owner_demo database and explicit environment.")) (System/exit 1))))
