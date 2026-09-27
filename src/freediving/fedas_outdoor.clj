(ns freediving.fedas-outdoor
  "Source-bound FEDAS 2025 and 2026 outdoor championship PDF tables."
  (:require [clojure.string :as str]))

(def outdoor-2025-sha256 "a89c4fdf22f04a0f29499db326d217d83a0b0e504c3a1cdcee0188bb9e186e4a")
(def outdoor-2026-sha256 "62f5715b5742814f374ad991b708c22b097b22338da1c31db7901d7d187f4cb2")

(def ^:private profiles
  {outdoor-2025-sha256
   {:year 2025 :pages 5 :venue "Lanzarote"
    :date-range ["2025-09-20" "2025-09-21"]
    :marker "RESULTADOS DEL CAMPEONATO DE ESPAÑA DE APNEA OUTDOOR"
    :version "fedas-outdoor-2025/1"
    :sections {[:M "FIM"] 8 [:F "FIM"] 4 [:M "CWT"] 7 [:F "CWT"] 4
               [:M "CWT-BF"] 6 [:F "CWT-BF"] 3 [:M "CNF"] 7 [:F "CNF"] 4}}
   outdoor-2026-sha256
   {:year 2026 :pages 4 :venue "Radazul, Tenerife"
    :date-range ["2026-09-04" "2026-09-06"]
    :marker "VIII CAMPEONATO DE ESPAÑA DE APNEA OUTDOOR TENERIFE 2026"
    :version "fedas-outdoor-2026/1"
    :sections {[:M "FIM"] 6 [:F "FIM"] 4 [:M "CWT"] 4 [:F "CWT"] 5
               [:M "CWT-BF"] 5 [:F "CWT-BF"] 4 [:M "CNF"] 6 [:F "CNF"] 2}}})

(def ^:private federations #{"FECDAS" "FEDECAS" "FAAS" "FASRM" "FMDAS" "FEGAS" "FASCV"})
(def ^:private header #"CLASIFICACIÓN (MASCULINA|FEMENINA) (FIM|CWT-BF|CWT|CNF)\b")

(defn parser-version [sha256] (:version (get profiles sha256)))

(defn supported? [sha256 pages]
  (let [{:keys [year marker venue]} (get profiles sha256)
        whole (str/join "\n" pages)]
    (boolean (and year (seq pages)
                  (str/includes? whole "FEDERACIÓN ESPAÑOLA DE ACTIVIDADES SUBACUÁTICAS")
                  (str/includes? whole marker)
                  (str/includes? whole venue)
                  (str/includes? whole (str year))
                  (str/includes? whole "CLASIFICACIÓN")))))

(defn- decimal [text]
  (when (and text (re-matches #"\d+(?:,\d+)?" text))
    (bigdec (str/replace text "," "."))))

(defn- fields [text other-column-start]
  (let [cells (vec (str/split (str/trim text) #"\s{2,}"))
        ranked? (boolean (re-matches #"\d+" (first cells)))
        offset (if ranked? 1 0)
        [given surname federation announced & tail] (subvec cells offset)
        [realized penalty obtained status points]
        (case (count tail)
          1 [nil nil nil (first tail) nil]
          4 [(nth tail 0) nil (nth tail 1) (nth tail 2) (nth tail 3)]
          5 [(nth tail 0) (nth tail 1) (nth tail 2) (nth tail 3) (nth tail 4)]
          [nil nil nil nil nil])
        penalty-label (when (and penalty obtained status (some? other-column-start))
                        (let [status-start (.lastIndexOf ^String text ^String status)
                              obtained-start (.lastIndexOf ^String text ^String obtained (dec status-start))
                              penalty-start (.lastIndexOf ^String text ^String penalty (dec obtained-start))]
                          (when (<= 0 penalty-start)
                            (if (>= penalty-start other-column-start)
                              "PEN OTROS" "PEN (AP>RP)"))))
        rank (when ranked? (parse-long (first cells)))
        announced-value (decimal announced)
        realized-value (decimal realized)
        penalty-value (decimal penalty)
        obtained-value (decimal obtained)
        points-value (decimal points)
        valid? (and given surname (federations federation) announced-value
                    (cond
                      (#{"OK" "PEN"} status)
                      (and rank realized-value obtained-value points-value
                           (<= 0M obtained-value realized-value announced-value)
                           (= obtained-value (- realized-value (or penalty-value 0M)))
                           (= (= status "PEN") (some? penalty-value)))
                      (or (= "DNS" status) (str/starts-with? (or status "") "DQ-"))
                      (and (nil? rank) (nil? realized) (nil? obtained) (nil? points)
                           (nil? penalty) (= 1 (count tail)))
                      :else false))]
    {:raw {:rank (when ranked? (first cells)) :given-name given :surname surname
           :federation federation :announced announced :realized realized
           :penalty penalty :penalty-label penalty-label
           :obtained obtained :status status :points points}
     :values (when valid?
               {:rank rank :source-name (str given " " surname)
                :representation federation :depth-declared announced-value
                :depth-reached realized-value :depth-penalty penalty-value
                :final-performance obtained-value :points points-value
                :status status :result-status (cond (= status "DNS") :did-not-start
                                                    (str/starts-with? status "DQ-") :disqualified
                                                    :else :valid) :unit "m"})}))

(defn- source-lines [page-number page]
  (mapv (fn [index line] {:page page-number :line (inc index) :text line})
        (range) (str/split page #"\n" -1)))

(defn- candidate [line scope profile other-column-start]
  (let [{:keys [raw values]} (fields (:text line) other-column-start)
        [gender discipline] scope
        parsed (when values
                 (assoc values :federation "FEDAS" :gender (name gender)
                        :category (if (= gender :M) "Masculina" "Femenina")
                        :discipline discipline :ranking-scope scope
                        :event-date nil :event-date-range (:date-range profile)
                        :venue (:venue profile)))
        coordinates {:page (:page line) :line (:line line)
                     :column-start 1 :column-end (inc (count (:text line)))}]
    {:coordinates coordinates :ranking-scope scope :source-lines [line]
     :raw {:line (:text line) :fields raw}
     :parse-status (if parsed :parsed :unparsed) :parsed parsed
     :fields (into {} (map (fn [[k v]] [k {:status (if (nil? v) :unknown :parsed) :value v}]) parsed))
     :review-status :unreviewed
     :unresolved-reasons (cond-> [:owner-review-required]
                           (nil? parsed) (conj :unparsed-source-line))}))

(defn parse-pages [sha256 pages]
  (let [{:keys [version sections] :as profile} (get profiles sha256)
        supported (supported? sha256 pages)
        page-data (mapv (fn [index page]
                          {:page (inc index) :text page
                           :lines (source-lines (inc index) page)}) (range) pages)
        scope (atom nil)
        other-column-start (atom nil)
        candidates (->> page-data
                        (mapcat :lines)
                        (keep (fn [line]
                                (let [value (:text line)]
                                  (if (str/includes? value "CLASIFICACIÓN FINAL POR COMUNIDADES")
                                    (do (reset! scope nil) (reset! other-column-start nil) nil)
                                    (if-let [[_ sex discipline] (re-find header value)]
                                      (do (reset! scope [(if (= sex "MASCULINA") :M :F) discipline])
                                          (reset! other-column-start nil) nil)
                                      (if (and @scope (str/includes? value "Posición")
                                               (str/includes? value "OTROS"))
                                        (do (reset! other-column-start (str/index-of value "OTROS")) nil)
                                        (when (and @scope (some #(re-find (re-pattern (str "\\b" % "\\b")) value) federations))
                                          (candidate line @scope profile @other-column-start))))))))
                        vec)
        counts (frequencies (map :ranking-scope candidates))
        parsed-count (count (filter #(= :parsed (:parse-status %)) candidates))
        complete? (and supported (= (:pages profile) (count pages))
                       (= sections counts) (= parsed-count (count candidates)))
        candidate-positions (set (map #(select-keys (:coordinates %) [:page :line]) candidates))
        noncandidate-lines (vec (for [page page-data line (:lines page)
                                      :when (and (not (str/blank? (:text line)))
                                                 (not (candidate-positions (select-keys line [:page :line]))))]
                                  line))]
    {:parser-version version :schema-version 3
     :status (if complete? :needs-review :partial-unsupported-needs-parser)
     :pages (mapv #(assoc % :status (if complete? :needs-review :unsupported-page)) page-data)
     :candidates candidates
     :reconciliation {:page-count (count pages) :coverage (if complete? :complete :partial)
                      :candidate-count (count candidates) :parsed-count parsed-count
                      :unparsed-count (- (count candidates) parsed-count)
                      :unresolved-count (count candidates) :section-counts counts
                      :noncandidate-lines noncandidate-lines :status :unreviewed}
     :publication {:status :blocked :reasons [:owner-review-required :reconciliation-unreviewed]}}))
