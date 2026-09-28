(ns freediving.attempt-view-adapter
  "Private exact-view adapter. Counts source positions, never sporting attempts or ranks."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.security MessageDigest]
           [java.math BigInteger]))

(def ^:private expected-index
  "5bb94195278b43d29add19358e5d3699dc67bf20f43e40daf9733c7b10f6be30")
(def ^:private versions
  {:cmas [{:job "cff457431610c929a54970f77477aa5e44679a34cab1db74a7af4f3e6e54ee61"
           :artifact "0b47b35617de991d54e20a0c573443e080c6301c5b5fbc32b5d542088c099fe2"
           :parser "cmas-2026-indoor-time/1"}
          {:job "b035efaef9e24ba8cee6e2b5cbcfa85416702068713442098dd7bfe1259fba65"
           :artifact "c4d68ff2c14eb8a797658e247e10373114b7370c763b60afa9e7068835b3e6cf"
           :parser "cmas-2026-indoor-time/2"}]
   :aida [{:job "6e6f0e1bae9e3923576907eec3a9f9d98007bc7d35d406c5bb7632f52addbeae"
           :artifact "b962e1c5fcadac01cdaf88c87749d6e2802fc5937269e52eda0efa8e2e90ac2e"
           :parser "aida-html/1"}
          {:job "bf85d8f3c3c7076853d27b21f0624b465bb7cf10a76bd24f28590c115bb41fa0"
           :artifact "a8930c9c66413855f7e9f8e172164cb35ab5f0111d7bdc27055c6a2f27e882cd"
           :parser "aida-html/1"}]})
(def ^:private sources
  {:cmas "f403777b250b7ae816adea945db4cd5ddea51be7349c2efa57046a08671a5758"
   :aida "67933b6afa56c7c4cff1df14b9b415d2e32d33f49feb10f24be46d4b59fa3e93"})

(defn- fail! [reason data] (throw (ex-info reason data)))
(defn- sha256 [bytes]
  (format "%064x" (BigInteger. 1 (.digest (MessageDigest/getInstance "SHA-256") bytes))))
(defn- verified-bytes [bundle index rel]
  (let [entry (get-in index ["files" rel])
        file (io/file bundle "payload" rel)]
    (when-not (and entry (.isFile file)) (fail! "Missing indexed corpus file" {:path rel}))
    (let [bytes (java.nio.file.Files/readAllBytes (.toPath file))]
      (when-not (and (= (count bytes) (get entry "bytes"))
                     (= (sha256 bytes) (get entry "sha256")))
        (fail! "Corpus file hash conflict" {:path rel}))
      bytes)))
(defn- edn-file [bundle index rel]
  (edn/read-string (String. (verified-bytes bundle index rel) "UTF-8")))
(defn- json-file [bundle index rel]
  (json/read-str (String. (verified-bytes bundle index rel) "UTF-8") :key-fn keyword))
(defn- index-file [bundle]
  (let [file (io/file bundle "index.json")]
    (when-not (.isFile file) (fail! "Missing corpus index" {}))
    (let [bytes (java.nio.file.Files/readAllBytes (.toPath file))]
      (when-not (= expected-index (sha256 bytes))
        (fail! "Corpus index hash conflict" {}))
      (json/read-str (String. bytes "UTF-8")))))
(defn- position [candidate]
  (select-keys (:coordinates candidate) [:page :line :table :row]))
(defn- selected? [kind candidate]
  (if (= kind :cmas)
    (and (= 5 (get-in candidate [:coordinates :page]))
         (= "DNF" (get-in candidate [:parsed :discipline]))
         (= (str "SENIORS " (char 0x2014) " WOMEN") (get-in candidate [:parsed :category]))
         (= "2026-06-11" (get-in candidate [:parsed :event-date])))
    (= "F" (get-in candidate [:parsed :gender]))))
(defn- context! [kind version artifact]
  (let [candidates (:candidates artifact)
        expected-count (if (= kind :cmas) 478 209)]
    (when-not (and (= (:job version) (:job-id artifact))
                   (= (:parser version) (:parser-version artifact))
                   (= (get sources kind) (:source-sha256 artifact))
                   (= expected-count (count candidates))
                   (every? #(= :parsed (:parse-status %)) candidates)
                   (if (= kind :aida)
                     (and (every? #(and (= "DNF" (get-in % [:parsed :discipline]))
                                        (= "2026-06-03" (get-in % [:parsed :event-date]))
                                        (#{"F" "M"} (get-in % [:parsed :gender]))) candidates)
                          (= {"F" 103 "M" 106} (frequencies (map #(get-in % [:parsed :gender]) candidates))))
                     (and (= 35 (count (filter #(= 5 (get-in % [:coordinates :page])) candidates)))
                          (every? #(selected? kind %)
                                  (filter #(= 5 (get-in % [:coordinates :page])) candidates)))))
      (fail! "Exact source view context conflict" {:kind kind :job (:job version)}))))
(defn- index-row [rows version ordinal candidate source]
  (let [row (get rows [(:job version) ordinal])]
    (when-not (and row
                   (= (:job version) (:job-id row))
                   (= ordinal (:ordinal row))
                   (= source (:source-sha256 row))
                   (= (:artifact version) (:artifact-sha256 row))
                   (= (position candidate) (select-keys (:coordinates row) [:page :line :table :row]))
                   (string? (:candidate-id row)))
      (fail! "Observation index conflict" {:job (:job version) :ordinal ordinal}))
    row))
(defn- selected-rows [kind version artifact rows]
  (->> (:candidates artifact)
       (map-indexed (fn [ordinal candidate]
                      (when (selected? kind candidate)
                        {:ordinal ordinal :candidate candidate
                         :index (index-row rows version ordinal candidate (get sources kind))})))
       (remove nil?) vec))
(defn- reconcile! [kind older newer]
  (when-not (and (= (count older) (count newer))
                 (= (mapv (fn [r] (select-keys r [:ordinal :candidate])) older)
                    (mapv (fn [r] (select-keys r [:ordinal :candidate])) newer)))
    (fail! "Repeated source versions disagree" {:kind kind})))
(defn- observation [kind version {:keys [ordinal candidate index]}]
  (let [parsed (if (= kind :aida) (assoc (:parsed candidate) :gender "Women") (:parsed candidate))]
    {:reference {:job-id (:job version) :ordinal ordinal :candidate-id (:candidate-id index)
                 :source-sha256 (get sources kind) :artifact-sha256 (:artifact version)}
     :source {:federation (if (= kind :cmas) "CMAS" "AIDA")
              :event-id (if (= kind :cmas) "novi-sad" "4852")
              :view-id (if (= kind :cmas) "seniors-women-dnf" "2026-06-03")
              :environment :pool :authority :unknown :finality :unknown :sanction :unknown}
     :candidate (assoc candidate :source-parsed (:parsed candidate) :parsed parsed)
     :evidence {:review :unknown :outcome :unknown :attempt-relationship :unknown
                :source-conflict :unresolved :final {:value nil :unit nil :basis :unknown}}}))

(defn load-exact-views
  "Read the pinned B30/B16 private bundle. Return one unreviewed observation per selected
   source position, a version-aware census and the comparison contract request. Throws on
   absent, changed or inconsistent evidence. The result grants no ranking authority."
  [bundle-root]
  (let [index (index-file bundle-root)
        manifest (json-file bundle-root index "b16/import-manifest.json")
        review-lines (str/split-lines (String. (verified-bytes bundle-root index "b16/review-index.jsonl") "UTF-8"))
        review-rows (mapv #(json/read-str % :key-fn keyword) review-lines)
        row-index (into {} (map (fn [row] [[(:job-id row) (:ordinal row)] row]) review-rows))]
    (when-not (= (count row-index) (count review-rows))
      (fail! "Duplicate observation index reference" {}))
    (let [loaded (into {}
                       (for [kind [:cmas :aida]]
                         (let [source (get sources kind)
                               source-rel (str "b16/archive/objects/" source)
                               _ (when-not (= source (sha256 (verified-bytes bundle-root index source-rel)))
                                   (fail! "Source object hash conflict" {:kind kind}))
                               pairs (mapv (fn [version]
                                             (let [entry (first (filter #(= (:job version) (:job-id %)) (:selected manifest)))
                                                   artifact-rel (str "b16/archive/derived-objects/" (:artifact version))
                                                   artifact (edn-file bundle-root index artifact-rel)]
                                               (when-not (and (= (:artifact version) (:artifact-sha256 entry))
                                                              (= source (:source-sha256 entry))
                                                              (= (:artifact version) (get-in index ["files" artifact-rel "sha256"])))
                                                 (fail! "Import manifest conflict" {:job (:job version)}))
                                               (context! kind version artifact)
                                               {:version version :rows (selected-rows kind version artifact row-index)}))
                                           (get versions kind))]
                           (reconcile! kind (:rows (first pairs)) (:rows (second pairs)))
                           [kind pairs])))
          observations (vec (mapcat (fn [kind]
                                      (let [{:keys [version rows]} (second (get loaded kind))]
                                        (map #(observation kind version %) rows))) [:cmas :aida]))
          counts (into {}
                       (for [kind [:cmas :aida]
                             :let [n (count (:rows (second (get loaded kind))))]]
                         [kind {:source-rows n :versioned-rows (* 2 n)
                                :parsed n :unparsed 0 :discrepant 0}]))
          request {:year 2026 :discipline "DNF" :environment :pool :gender :women
                   :category :seniors :federations #{"CMAS" "AIDA"}
                   :views (set (map #(select-keys (merge (:source %) (:reference %))
                                                  [:federation :event-id :view-id :source-sha256]) observations))
                   :representations (set (map #(get-in % [:candidate :parsed :representation]) observations))}]
      (when-not (= [35 103] (mapv #(get-in counts [% :source-rows]) [:cmas :aida]))
        (fail! "Exact source row census conflict" {:counts counts}))
      {:request request :observations observations
       :census {:selected counts :aida-view {:total-rows 209 :women 103 :men 106}
                :source-positions 138 :versioned-selected-rows 276
                :unparsed 0 :discrepant 0 :residual-ledger []
                :basis :source-positions-not-distinct-dives}})))
