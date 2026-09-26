(ns freediving.ffessm-2026-regular-categories
  "Source-bound FFESSM 2026 Bipalmes Hommes and Sans Palmes Femmes positions."
  (:require [clojure.string :as str]))

(def men-sha256 "afd8352fcef2bdc21c284d59331dcc6330ae5f02c6a3d4b7717482a87dfee05e")
(def women-sha256 "63c0401ff151b566d2710aa5c3e7de9c84b84684d5c87b009fb58b0dd0875fa0")

(def ^:private sources
  {men-sha256 {:title "Résultats Bipalme Hommes" :sex "Homme" :gender "M"
               :category "Hommes" :discipline "CWT bi"
               :sections {:championnat-de-france 8} :links 0
               :version "ffessm-2026-bipalmes-hommes/1"}
   women-sha256 {:title "Résultats Sans Palmes Femmes" :sex "Femme" :gender "F"
                 :category "Femmes" :discipline "CNF"
                 :sections {:epreuve 3 :championnat-de-france 2} :links 2
                 :version "ffessm-2026-sans-palmes-femmes/1"}})

(defn parser-version [sha256]
  (:version (get sources sha256)))

(defn supported? [sha256 pages]
  (let [{:keys [title]} (get sources sha256)
        page (first pages)]
    (boolean (and title (= 1 (count pages))
                  (str/includes? page "Championnat de France Eau Libre 2026")
                  (str/includes? page title)
                  (str/includes? page "CHAMPIONNAT DE FRANCE")
                  (str/includes? page "Classement")
                  (str/includes? page "COMMENTAIRES")))))

(defn- source-lines [page]
  (mapv (fn [index value] {:page 1 :line (inc index) :text value})
        (range) (str/split page #"\n" -1)))

(defn- position-line? [text]
  (boolean (re-find #"^\s*\d+\s{2,}.+?\s{2,}\S+\s{2,}" text)))

(defn- metres [value]
  (when-let [[_ number] (and value (re-matches #"(\d+)\s*m" value))]
    (bigdec number)))

(defn- penalty [value]
  (when-let [[_ number] (and value (re-matches #"(\d+)m" value))]
    (bigdec number)))

(defn- row-values [text scope {:keys [sex gender category discipline]}]
  (let [cells (str/split (str/trim text) #"\s{2,}")
        [rank surname given raw-sex nationality federation announced raw-discipline reached
         raw-penalty & tail] cells
        [tag points card] (if (= 3 (count tail)) tail (cons nil tail))
        declared (metres announced)
        realized (metres reached)
        lost (penalty raw-penalty)
        score (when (and points (re-matches #"\d+" points)) (bigdec points))
        tag-value (some-> tag parse-long)]
    (when (and (<= 12 (count cells) 13) (re-matches #"\d+" rank)
               (= sex raw-sex) (= "FFESSM" federation) (= discipline raw-discipline)
               declared realized lost score (contains? #{"Blanc" "Jaune"} card)
               (pos? declared) (<= 0M realized declared)
               (= lost (- declared realized))
               (= score (- realized lost (or tag-value 0)))
               (= (if tag-value "Jaune" "Blanc") card))
      {:raw {:rank rank :surname surname :given-name given :sex raw-sex
             :nationality nationality :federation federation :depth-declared announced
             :discipline raw-discipline :depth-reached reached :depth-penalty raw-penalty
             :default-tag tag :final-performance points :card card :comment nil}
       :parsed {:federation federation :event-date nil :source-name (str given " " surname)
                :gender gender :representation nationality :category category
                :discipline discipline :ranking-scope scope :rank (parse-long rank)
                :depth-declared declared :depth-reached realized :depth-penalty lost
                :default-tag tag-value :final-performance score :card card
                :reason nil :unit "m" :result-status :valid}})))

(defn- candidate [line scope profile]
  (let [{:keys [raw parsed]} (row-values (:text line) scope profile)]
    {:coordinates {:page 1 :line (:line line) :column-start 1
                   :column-end (inc (count (:text line)))}
     :ranking-scope scope :source-lines [line]
     :raw {:line (:text line) :fields raw}
     :parse-status (if parsed :parsed :unparsed)
     :parsed parsed
     :fields (into {} (map (fn [[key value]]
                             [key {:status (if (nil? value) :unknown :parsed) :value value}])
                           parsed))
     :review-status :unreviewed
     :unresolved-reasons (cond-> [:owner-review-required]
                           (nil? parsed) (conj :unparsed-source-line))}))

(defn- link-subset [candidates]
  (let [keys-to-match [:source-name :representation :depth-declared :depth-reached
                       :depth-penalty :default-tag :final-performance :card :reason]
        key-of #(select-keys (:parsed %) keys-to-match)
        open-by-value (group-by key-of (filter #(= :epreuve (:ranking-scope %)) candidates))]
    (mapv (fn [entry]
            (if (and (= :championnat-de-france (:ranking-scope entry)) (:parsed entry)
                     (seq open-by-value))
              (let [matches (get open-by-value (key-of entry))]
                (if (= 1 (count matches))
                  (assoc-in entry [:parsed :same-result-as]
                            ((juxt :page :line) (:coordinates (first matches))))
                  (update entry :unresolved-reasons conj :subset-link-uncertain)))
              entry)) candidates)))

(defn parse-pages [sha256 pages]
  (let [{:keys [sections links version] :as profile} (get sources sha256)
        page (or (first pages) "")
        lines (source-lines page)
        section (atom nil)
        candidates (->> lines
                        (keep (fn [line]
                                (let [value (str/trim (:text line))]
                                  (cond
                                    (= value "EPREUVE") (do (reset! section :epreuve) nil)
                                    (= value "CHAMPIONNAT DE FRANCE")
                                    (do (reset! section :championnat-de-france) nil)
                                    (position-line? value) (candidate line @section profile)
                                    :else nil))))
                        vec link-subset)
        section-counts (frequencies (map :ranking-scope candidates))
        parsed-count (count (filter #(= :parsed (:parse-status %)) candidates))
        linked-count (count (filter #(get-in % [:parsed :same-result-as]) candidates))
        complete? (and (supported? sha256 pages) (= sections section-counts)
                       (= (count candidates) parsed-count) (= links linked-count))
        candidate-lines (set (map #(get-in % [:coordinates :line]) candidates))
        nonblank (remove #(str/blank? (:text %)) lines)]
    {:parser-version version :schema-version 3
     :status (if complete? :needs-review :partial-unsupported-needs-parser)
     :pages [{:page 1 :text page :lines lines
              :status (if complete? :needs-review :unsupported-page)}]
     :candidates candidates
     :reconciliation {:page-count (count pages) :coverage (if complete? :complete :partial)
                      :candidate-count (count candidates) :parsed-count parsed-count
                      :unparsed-count (- (count candidates) parsed-count)
                      :unresolved-count (count candidates) :section-counts section-counts
                      :linked-subset-count linked-count :unique-performance-count nil
                      :nonblank-line-count (count nonblank)
                      :noncandidate-lines (vec (remove #(contains? candidate-lines (:line %)) nonblank))
                      :status :unreviewed}
     :publication {:status :blocked :reasons [:owner-review-required :reconciliation-unreviewed]}}))
