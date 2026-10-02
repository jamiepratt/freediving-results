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
  (let [candidate-routes (set (mapcat (juxt :left :right) source-candidates))]
    (doseq [route routes]
      (let [sha (:source-sha256 route)]
        (when-not (and (= #{:route-id :source-sha256} (set (keys route)))
                       (string? (:route-id route))
                       (re-matches #"[a-z0-9][a-z0-9-]*" (:route-id route))
                       (or (and (string? sha) (re-matches #"[0-9a-f]{64}" sha))
                           (and (nil? sha) (contains? candidate-routes (:route-id route)))))
          (throw (ex-info "Route requires SHA-256 unless it is an unresolved candidate" {})))))
    (doseq [candidate source-candidates]
      (when-not (= #{:left :right :reason} (set (keys candidate)))
        (throw (ex-info "Source candidates need only left, right and reason" {}))))))

(defn- attempt-input [rows routes scope-bindings]
  (let [sources (->> (concat (map :source-sha256 rows) (keep :source-sha256 routes))
                     distinct sort
                     (mapv (fn [sha] {:id sha :sha256 sha})))
        positions (->> rows
                       (map (fn [row]
                              {:id (pr-str [(:source-sha256 row) (:position row)])
                               :source-id (:source-sha256 row)
                               :locator (:position row)}))
                       distinct (sort-by :id) vec)
        versions (mapv (fn [row]
                         (let [ref (:ref row)
                               binding (get scope-bindings ref)]
                           {:id (pr-str ref)
                            :position-id (pr-str [(:source-sha256 row) (:position row)])
                            :parser-version (:parser-version row)
                            :scope (:scope binding)
                            :scope-evidence (:scope-evidence binding)
                            :values (:parsed row)
                            :reference ref})) rows)]
    {:sources sources :positions positions :observation-versions versions}))

(defn build-ledger
  "Build a deterministic ledger from inspected jobs and declarative route evidence.
   Third argument is retained v1 attempt decisions and exact scope bindings."
  ([inspected-jobs route-evidence] (build-ledger inspected-jobs route-evidence {}))
  ([inspected-jobs {:keys [routes source-candidates] :as route-evidence}
    {:keys [scope-bindings events] :as attempt-evidence}]
   (when-not (every? #{:routes :source-candidates} (keys route-evidence))
     (throw (ex-info "Unexpected route evidence field" {})))
   (when-not (every? #{:scope-bindings :events} (keys attempt-evidence))
     (throw (ex-info "Unexpected attempt evidence field" {})))
   (validate-routes! route-evidence)
   (let [artifacts (mapv :artifact inspected-jobs)
         rows (mapcat (fn [{:keys [artifact observations]}]
                        (when-not (= (count (:candidates artifact)) (count observations))
                          (throw (ex-info "Incomplete inspected job" {:job-id (:job-id artifact)})))
                        (map (partial project-observation artifact) observations))
                      inspected-jobs)
         links (mapcat ffessm/source-links artifacts)
         attempt-ledger (reduce relationships/append-attempt-event
                                (relationships/empty-attempt-ledger
                                 (attempt-input rows routes scope-bindings)) events)
         attempt-projection (relationships/project-attempts attempt-ledger)]
     (assoc (relationships/classify {:observations rows
                                     :same-attempt-evidence links
                                     :routes routes
                                     :source-candidates source-candidates})
            :attempt-ledger attempt-ledger :attempt-projection attempt-projection))))

(defn read-corpus
  "Read imported jobs and observations; this function does not mutate the database."
  ([database-url route-evidence] (read-corpus database-url route-evidence {}))
  ([database-url route-evidence attempt-evidence]
   (let [jobs (observations/list-extractions database-url)
         inspected (mapv (fn [{:keys [job_id]}]
                           (or (observations/inspect database-url job_id)
                               (throw (ex-info "Listed extraction disappeared" {:job-id job_id}))))
                         jobs)]
     (build-ledger inspected route-evidence attempt-evidence))))

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

(defn -main [& [output-path routes-path attempts-path]]
  (try
    (when-not (and output-path routes-path) (throw (ex-info "Usage: OUTPUT_EDN ROUTES_EDN" {})))
    (let [database-url (System/getenv "FREEDIVING_DATABASE_URL")
          routes (edn/read-string (slurp routes-path))
          attempts (if attempts-path (edn/read-string (slurp attempts-path)) {})]
      (when-not database-url (throw (ex-info "FREEDIVING_DATABASE_URL required" {})))
      (let [result (read-corpus database-url routes attempts)]
        (private-write! output-path result)
        (prn {:observation-versions (count (:observations result))
              :source-routes (count (:routes result))
              :counts-by-scope (:counts-by-scope result)
              :attempt-counts (get-in result [:attempt-projection :counts])})))
    (catch Exception e
      (binding [*out* *err*] (println "Source relationship ledger failed:" (.getMessage e)))
      (System/exit 1))))
