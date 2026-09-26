(ns freediving.source-relationship-ledger
  "Read-only corpus projection and private source-relationship ledger."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [freediving.ffessm-source-links :as ffessm]
            [freediving.observations :as observations]
            [freediving.source-relationships :as relationships])
  (:import [java.nio.file Files StandardCopyOption]
           [java.nio.file.attribute PosixFilePermission]))

(defn- source-position [candidate]
  (let [lines (:source-lines candidate)
        coordinates (:coordinates candidate)]
    (cond
      (seq lines) (mapv #(select-keys % [:page :line]) lines)
      (and (pos-int? (:page coordinates)) (pos-int? (:line coordinates)))
      [(select-keys coordinates [:page :line])]
      :else [])))

(defn- source-text [candidate]
  (or (when (seq (:source-lines candidate))
        (str/join "\n" (map :text (:source-lines candidate))))
      (get-in candidate [:raw :line])))

(defn- project-observation [artifact row]
  (let [candidate (:payload row)
        parsed (:parsed candidate)]
    {:ref {:job-id (:job-id artifact) :ordinal (:ordinal row)}
     :observation-kind (:kind row)
     :source-sha256 (:source-sha256 artifact)
     :position (source-position candidate)
     :source-text (source-text candidate)
     ;; A source hash is a deliberately narrow event context: no cross-source
     ;; attempt identity can be inferred without separately reviewed evidence.
     :event-key (:source-sha256 artifact)
     :discipline (:discipline parsed)
     :day (:event-date parsed)
     :parser-version (:parser-version artifact)
     :parsed parsed}))

(defn- validate-routes! [{:keys [routes source-candidates]}]
  (doseq [route routes]
    (when-not (and (= #{:route-id :source-sha256} (set (keys route)))
                   (string? (:route-id route))
                   (re-matches #"[a-z0-9][a-z0-9-]*" (:route-id route))
                   (string? (:source-sha256 route))
                   (re-matches #"[0-9a-f]{64}" (:source-sha256 route)))
      (throw (ex-info "Route evidence must contain only route-id and SHA-256" {}))))
  (doseq [candidate source-candidates]
    (when-not (= #{:left :right :reason} (set (keys candidate)))
      (throw (ex-info "Source candidates need only left, right and reason" {})))))

(defn build-ledger
  "Build a deterministic ledger from inspected jobs and declarative route evidence."
  [inspected-jobs {:keys [routes source-candidates] :as route-evidence}]
  (when-not (every? #{:routes :source-candidates} (keys route-evidence))
    (throw (ex-info "Unexpected route evidence field" {})))
  (validate-routes! route-evidence)
  (let [artifacts (mapv :artifact inspected-jobs)
        rows (mapcat (fn [{:keys [artifact observations]}]
                       (when-not (= (count (:candidates artifact)) (count observations))
                         (throw (ex-info "Incomplete inspected job" {:job-id (:job-id artifact)})))
                       (map (partial project-observation artifact) observations))
                     inspected-jobs)
        links (mapcat ffessm/source-links artifacts)]
    (relationships/classify {:observations rows
                             :same-attempt-evidence links
                             :routes routes
                             :source-candidates source-candidates})))

(defn read-corpus
  "Read imported jobs and observations; this function does not mutate the database."
  [database-url route-evidence]
  (let [jobs (observations/list-extractions database-url)
        inspected (mapv (fn [{:keys [job_id]}]
                          (or (observations/inspect database-url job_id)
                              (throw (ex-info "Listed extraction disappeared" {:job-id job_id}))))
                        jobs)]
    (build-ledger inspected route-evidence)))

(defn- private-write! [filename value]
  (let [target (.toPath (io/file filename))
        parent (.getParent (.toAbsolutePath target))
        permissions #{PosixFilePermission/OWNER_READ PosixFilePermission/OWNER_WRITE}
        temp (Files/createTempFile parent ".source-relationships-" ".edn"
                                   (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (Files/setPosixFilePermissions temp permissions)
      (spit (.toFile temp) (str (pr-str value) "\n"))
      (Files/move temp target (into-array StandardCopyOption
                                          [StandardCopyOption/ATOMIC_MOVE
                                           StandardCopyOption/REPLACE_EXISTING]))
      (finally (Files/deleteIfExists temp)))))

(defn -main [& [output-path routes-path]]
  (try
    (when-not (and output-path routes-path) (throw (ex-info "Usage: OUTPUT_EDN ROUTES_EDN" {})))
    (let [database-url (System/getenv "FREEDIVING_DATABASE_URL")
          routes (edn/read-string (slurp routes-path))]
      (when-not database-url (throw (ex-info "FREEDIVING_DATABASE_URL required" {})))
      (let [result (read-corpus database-url routes)]
        (private-write! output-path result)
        (prn {:observation-versions (count (:observations result))
              :source-routes (count (:routes result))
              :counts-by-scope (:counts-by-scope result)})))
    (catch Exception e
      (binding [*out* *err*] (println "Source relationship ledger failed:" (.getMessage e)))
      (System/exit 1))))
