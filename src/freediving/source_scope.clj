(ns freediving.source-scope
  "Typed source facts for partial scope descriptors. Missing facts stay absent."
  (:require [clojure.string :as str]
            [freediving.html-evidence :as evidence]
            [freediving.aida-html :as html])
  (:import [org.jsoup Jsoup]))

(defn- visible? [element]
  (not-any? (fn [e]
              (or (#{"template" "script" "noscript"} (.tagName e))
                  (.hasAttr e "hidden")
                  (= "true" (str/lower-case (str/trim (.attr e "aria-hidden"))))
                  (re-find #"(?i)(?:^|;)\s*(?:display\s*:\s*none|visibility\s*:\s*hidden)\s*(?:!important\s*)?(?:;|$)" (.attr e "style"))))
            (cons element (.parents element))))
(defn- selected [doc selector]
  (let [xs (.select doc selector)]
    (when (and (= 1 (count xs)) (visible? (first xs)))
      (let [s (str/trim (.wholeText (first xs)))] (when-not (str/blank? s) s)))))

(defn- registered-event [artifact]
  (when-not (= (:job-id artifact) (html/digest (select-keys artifact html/identity-keys)))
    (throw (ex-info "HTML extraction identity mismatch" {})))
  (doseq [{:keys [acquisition-id manifest]} (:acquisitions artifact)]
    (when-not (and (= acquisition-id (html/digest manifest))
                   (= (:source-sha256 artifact) (:sha256 manifest)))
      (throw (ex-info "HTML acquisition identity mismatch" {}))))
  (let [ids (mapv #(second (re-matches #"https://www\.aidainternational\.org/StartList/([0-9]+)(?:\?day_index=[0-9]+|#start)?"
                                       (or (get-in % [:manifest :final-url]) ""))) (:acquisitions artifact))]
    (when (and (seq ids) (every? some? ids) (= 1 (count (set ids)))) (first ids))))

(defn- row-values! [artifact ordinal event-id context doc]
  (let [payload (get (:candidates artifact) ordinal)
        table (nth (.select doc "table") (dec (get-in payload [:coordinates :table])) nil)
        row (when table (nth (vec (filter #(identical? table (.closest % "table")) (.select table "tr"))) (dec (get-in payload [:coordinates :row])) nil))
        fields (get-in payload [:raw :fields])
        date (selected doc "li.active .days")
        discipline (selected doc "select#discipline option[selected]")
        gender (selected doc "select#gender option[selected]")
        cells (when row (vec (filter #(= "td" (.tagName %)) (.children row))))
        name-index (.indexOf ^java.util.List (:headers context) "Diver")
        links (when (and cells (<= 0 name-index) (< name-index (count cells)))
                (.select (nth cells name-index) "a[href]"))
        profile (when (and (= 1 (count links)) (visible? (first links)))
                  (let [href (.attr (first links) "href")]
                    (when (re-matches #"https://www\.aidainternational\.org/Profile-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}" href) href)))]
    (doseq [[selected raw] (concat [[discipline (get fields "Discipline")]
                                    [gender (get fields "Gender")]]
                                   (mapcat (fn [acquisition]
                                             [[(some-> (get-in acquisition [:filters :discipline]) name) (get fields "Discipline")]
                                              [(some-> (get-in acquisition [:filters :gender]) name) (get fields "Gender")]])
                                           (:acquisition-context context)))]
      (let [normalize #(some-> % str/trim str/upper-case)
            canon #(get {"M" "MALE" "MEN" "MALE" "F" "FEMALE" "WOMEN" "FEMALE"} (normalize %) (normalize %))]
        (when (and selected raw (not= "ALL" (normalize selected)) (not= (canon selected) (canon raw)))
          (throw (ex-info "Conflicting row and selected source context" {})))))
    (when (seq (:context-errors context)) (throw (ex-info "Conflicting source acquisition context" {})))
    (when-not (and row (visible? row) (every? visible? (.select row "*")))
      (throw (ex-info "Hidden source row context" {})))
    (into {} (filter (fn [[_ v]] (and (string? v) (not (str/blank? v)))))
          {:event-id event-id
           :federation (when event-id "AIDA")
           :event-name (:event-name context)
           :date (when (= date (:event-date context)) date)
           :discipline (or (get fields "Discipline") (when (= discipline (:selected-discipline context)) discipline))
           :category (or (get fields "Gender") (when (= gender (:selected-gender context)) gender))
           :source-name (get fields "Diver")
           :source-athlete-id profile})))

(defn html-values!
  "Replay the complete extraction before exposing typed, visible source facts."
  [artifact ordinal]
  (row-values! artifact ordinal (registered-event artifact)
               (evidence/bound-context! artifact (get (:candidates artifact) ordinal) ordinal (:source-sha256 artifact))
               (Jsoup/parse ^String (:raw-html artifact))))

(def daily-fields [:federation :event-id :date :discipline :category :source-athlete-id])
(defn daily-collision-key
  "Conservative ambiguity key only; never rewrites source bindings or asserts a match."
  [values]
  (let [normalize #(some-> % str/trim str/upper-case)
        gender (normalize (:category values))]
    [(:federation values) (:event-id values) (:date values)
     (normalize (:discipline values))
     (get {"M" "MALE" "MEN" "MALE" "F" "FEMALE" "WOMEN" "FEMALE"} gender gender)
     (some-> (:source-athlete-id values) str/lower-case)]))

(defn daily-values!
  "Read-only opt-in daily view audit. Replay all rows; require visible event context
  and unique exact source-profile keys across the whole artifact. This proves
  neither event completeness, round/session meaning nor cross-source identity."
  [artifact]
  (let [event-id (registered-event artifact)
        context (evidence/bound-context! artifact (first (:candidates artifact)) 0 (:source-sha256 artifact))
        doc (Jsoup/parse ^String (:raw-html artifact))
        _ (when-not (and (zero? (get-in artifact [:reconciliation :unsupported-table-count]))
                         (every? #(and (= :attempts (:source-family %)) (= :parsed (:parse-status %))) (:candidates artifact))
                         (= (reduce + (map :data-row-count (:tables artifact))) (count (:candidates artifact))))
            (throw (ex-info "Incomplete daily row census" {})))
        values (mapv (fn [ordinal]
                       (let [table (get-in artifact [:candidates ordinal :coordinates :table])]
                         (row-values! artifact ordinal event-id
                                      (assoc context :headers (:headers (first (filter #(= table (:table %)) (:tables artifact))))) doc)))
                     (range (count (:candidates artifact))))]
    (when-not (and (seq values)
                   (every? (fn [v] (every? #(and (string? (v %)) (not (str/blank? (v %)))) (conj daily-fields :event-name))) values))
      (throw (ex-info "Incomplete daily source context" {})))
    (when-not (= (count values) (count (set (map daily-collision-key values))))
      (throw (ex-info "Ambiguous daily participant across source rows" {})))
    values))

(def pdf-results-source-sha256 "f403777b250b7ae816adea945db4cd5ddea51be7349c2efa57046a08671a5758")
(def pdf-results-artifact-sha256 "c4d68ff2c14eb8a797658e247e10373114b7370c763b60afa9e7068835b3e6cf")
(def pdf-results-job-id "b035efaef9e24ba8cee6e2b5cbcfa85416702068713442098dd7bfe1259fba65")
(def pdf-results-ordinals [126 127 128 129])
(def pdf-results-lines [9 10 11 12])
(def pdf-results-names ["Ediz DUMAN" "Yusuf ERKAN" "Timur KURU" "Walter STRUMBICHLER"])
(defn pdf-results-contract
  "Supported exact table containing ordinal, without granting row authority."
  [ordinal]
  (cond
    (some #{ordinal} pdf-results-ordinals)
    {:ordinals pdf-results-ordinals :lines pdf-results-lines :names pdf-results-names
     :page 10 :discipline "DYN-BF" :category "JUNIORS \u2014 MEN" :date "2026-06-12"}
    (and (integer? ordinal) (<= 56 ordinal 90))
    {:ordinals (vec (range 56 91)) :lines (vec (range 9 44))
     :page 5 :discipline "DNF" :category "SENIORS \u2014 WOMEN" :date "2026-06-11"}))
(defn pdf-results-view!
  "Exact retained CMAS PDF table census. Positions identify published claims only."
  ([artifact] (pdf-results-view! artifact (first pdf-results-ordinals)))
  ([artifact ordinal]
   (let [{:keys [page ordinals lines names discipline category date] :as contract}
         (pdf-results-contract ordinal)
         source-page (when page (get (:pages artifact) (dec page)))
         candidates (:candidates artifact)
         table (mapv #(get candidates %) ordinals)
         page-lines (into {} (map (juxt :line :text) (:lines source-page)))
         scoped (keep-indexed (fn [i c]
                                (when (= [discipline category date]
                                         ((juxt :discipline :category :event-date) (:parsed c))) i)) candidates)]
     (when-not (and contract
                    (= pdf-results-source-sha256 (:source-sha256 artifact))
                    (= pdf-results-job-id (:job-id artifact))
                    (= "cmas-2026-indoor-time/2" (:parser-version artifact))
                    (= 2 (:schema-version artifact))
                    (= 32 (count (:pages artifact)))
                    (= page (:page source-page))
                    (= (str/join "\n" (map :text (:lines source-page))) (:text source-page))
                    (= (count page-lines) (count (:lines source-page)))
                    (= ordinals (vec scoped))
                    (= (count ordinals) (count table))
                    (= (count table) (count (set (map #(get-in % [:parsed :source-name]) table))))
                    (every? true?
                            (map-indexed
                             (fn [i c]
                               (let [line (get page-lines (nth lines i))
                                     name (get-in c [:parsed :source-name])]
                                 (and (string? line) (not (str/blank? line))
                                      (string? name) (not (str/blank? name))
                                      (= :parsed (:parse-status c))
                                      (= {:page page :line (nth lines i)}
                                         (select-keys (:coordinates c) [:page :line]))
                                      (= [{:page page :line (nth lines i) :text line}] (:source-lines c))
                                      (= line (get-in c [:raw :line]))
                                      (= name (get-in c [:raw :fields :source-name]))
                                      (or (nil? names) (= (nth names i) name))
                                      (= "CMAS" (get-in c [:parsed :federation]))
                                      (= [discipline category date]
                                         ((juxt :discipline :category :event-date) (:parsed c)))))) table)))
       (throw (ex-info "Unsupported or incomplete PDF results-view census" {})))
     (assoc (dissoc contract :lines :names)
            :source-sha256 pdf-results-source-sha256 :artifact-sha256 pdf-results-artifact-sha256))))
