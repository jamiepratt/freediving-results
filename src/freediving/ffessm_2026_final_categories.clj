(ns freediving.ffessm-2026-final-categories
  "Source-bound remaining FFESSM 2026 adult category PDF positions."
  (:require [clojure.string :as str]))

(def cnf-men-sha256 "1687c316edbd9e23be70d526f97038bcad49407892aa03fb3639fb2e95c5bf1e")
(def fim-women-sha256 "ae44aeb2f1468d861fdae661abd5fb5f46b7e9ea375bfece9813754260e91174")
(def fim-men-sha256 "bad5459a615dae7445ca74d86932197cb47d30ccd595c6636ad398c1aacbbfcf")

(def ^:private profiles
  {cnf-men-sha256 {:title "Résultats Sans Palmes Hommes" :sex "Homme" :gender "M"
                   :category "Hommes" :discipline "CNF"
                   :sections {:epreuve 11 :championnat-de-france 10} :links 10
                   :version "ffessm-2026-sans-palmes-hommes/1"}
   fim-women-sha256 {:title "Résultats Immersion Libre Femmes" :sex "Femme" :gender "F"
                     :category "Femmes" :discipline "FIM"
                     :sections {:championnat-de-france 4} :links 0
                     :version "ffessm-2026-immersion-libre-femmes/1"}
   fim-men-sha256 {:title "Résultats Immersion Libre Hommes" :sex "Homme" :gender "M"
                   :category "Hommes" :discipline "FIM"
                   :sections {:epreuve 10 :championnat-de-france 9} :links 9
                   :version "ffessm-2026-immersion-libre-hommes/1"}})

(defn parser-version [sha256] (:version (get profiles sha256)))

(defn supported? [sha256 pages]
  (let [{:keys [title]} (get profiles sha256)
        page (first pages)]
    (boolean (and title (= 1 (count pages))
                  (str/includes? page "Championnat de France Eau Libre 2026")
                  (str/includes? page title)
                  (str/includes? page "CHAMPIONNAT DE FRANCE")
                  (str/includes? page "Classement")
                  (str/includes? page "COMMENTAIRES")))))

(defn- lines [page]
  (mapv (fn [n value] {:page 1 :line (inc n) :text value})
        (range) (str/split page #"\n" -1)))

(defn- metres [value]
  (when-let [[_ number] (and value (re-matches #"(\d+)\s*m" value))]
    (bigdec number)))

(defn- row-values [line scope {:keys [sex gender category discipline]}]
  (let [cells (str/split (str/trim line) #"\s{2,}")
        ranked? (boolean (re-matches #"\d+" (first cells)))
        [rank surname given raw-sex nationality federation announced raw-discipline reached raw-penalty & tail]
        (if ranked? cells (cons nil cells))
        ;; Tag is printed only for yellow cards. Red rows have a zero score and DQ text.
        [tag points card reason] (case (count tail)
                                   2 [nil (nth tail 0) (nth tail 1) nil]
                                   3 [nil (nth tail 0) (nth tail 1) (nth tail 2)]
                                   4 [(nth tail 0) (nth tail 1) (nth tail 2) (nth tail 3)]
                                   [nil nil nil nil])
        declared (metres announced)
        realized (metres reached)
        lost (metres raw-penalty)
        score (when (and points (re-matches #"\d+" points)) (bigdec points))
        tag-value (some-> tag parse-long)
        disqualified? (= "Rouge" card)
        valid? (and (<= 12 (count cells) 14) surname given (= sex raw-sex)
                    (= "FFESSM" federation) (= discipline raw-discipline)
                    declared realized lost score (pos? declared) (<= 0M realized declared)
                    (= lost (- declared realized))
                    (if disqualified?
                      (and (nil? rank) (= 0M score) (nil? tag-value)
                           (some? reason) (str/starts-with? reason "DQ "))
                      (and rank (contains? #{"Blanc" "Jaune"} card)
                           (= score (- realized lost (or tag-value 0)))
                           (= (if tag-value "Jaune" "Blanc") card))))]
    (when valid?
      {:raw {:rank rank :surname surname :given-name given :sex raw-sex
             :nationality nationality :federation federation :depth-declared announced
             :discipline raw-discipline :depth-reached reached :depth-penalty raw-penalty
             :default-tag tag :final-performance points :card card :comment reason}
       :parsed {:federation federation :event-date nil :source-name (str given " " surname)
                :gender gender :representation nationality :category category
                :discipline discipline :ranking-scope scope :rank (some-> rank parse-long)
                :depth-declared declared :depth-reached realized :depth-penalty lost
                :default-tag tag-value :final-performance score :card card
                :reason reason :unit "m"
                :result-status (if disqualified? :disqualified :valid)}})))

(defn- candidate [line scope profile]
  (let [{:keys [raw parsed]} (row-values (:text line) scope profile)]
    {:coordinates {:page 1 :line (:line line) :column-start 1
                   :column-end (inc (count (:text line)))}
     :ranking-scope scope :source-lines [line]
     :raw {:line (:text line) :fields raw}
     :parse-status (if parsed :parsed :unparsed) :parsed parsed
     :fields (into {} (map (fn [[k v]] [k {:status (if (nil? v) :unknown :parsed) :value v}]) parsed))
     :review-status :unreviewed
     :unresolved-reasons (cond-> [:owner-review-required]
                           (nil? parsed) (conj :unparsed-source-line))}))

(defn- link-subset [rows]
  (let [match-keys [:source-name :representation :depth-declared :depth-reached
                    :depth-penalty :default-tag :final-performance :card :reason :result-status]
        key-of #(select-keys (:parsed %) match-keys)
        open (group-by key-of (filter #(= :epreuve (:ranking-scope %)) rows))]
    (mapv (fn [row]
            (if (and (= :championnat-de-france (:ranking-scope row)) (:parsed row) (seq open))
              (let [matches (get open (key-of row))]
                (if (= 1 (count matches))
                  (assoc-in row [:parsed :same-result-as]
                            ((juxt :page :line) (:coordinates (first matches))))
                  (update row :unresolved-reasons conj :subset-link-uncertain)))
              row)) rows)))

(defn parse-pages [sha256 pages]
  (let [{:keys [sections links version] :as profile} (get profiles sha256)
        page (or (first pages) "")
        source-lines (lines page)
        section (atom nil)
        rows (->> source-lines
                  (keep (fn [line]
                          (let [value (str/trim (:text line))]
                            (cond
                              (= value "EPREUVE") (do (reset! section :epreuve) nil)
                              (= value "CHAMPIONNAT DE FRANCE")
                              (do (reset! section :championnat-de-france) nil)
                              (and (str/includes? value "FFESSM")
                                   (re-find #"\s(Homme|Femme)\s" value))
                              (candidate line @section profile)
                              :else nil))))
                  vec link-subset)
        section-counts (frequencies (map :ranking-scope rows))
        parsed-count (count (filter #(= :parsed (:parse-status %)) rows))
        linked-count (count (filter #(get-in % [:parsed :same-result-as]) rows))
        complete? (and (supported? sha256 pages) (= sections section-counts)
                       (= (count rows) parsed-count) (= links linked-count))
        candidate-lines (set (map #(get-in % [:coordinates :line]) rows))
        nonblank (remove #(str/blank? (:text %)) source-lines)]
    {:parser-version version :schema-version 3
     :status (if complete? :needs-review :partial-unsupported-needs-parser)
     :pages [{:page 1 :text page :lines source-lines
              :status (if complete? :needs-review :unsupported-page)}]
     :candidates rows
     :reconciliation {:page-count (count pages) :coverage (if complete? :complete :partial)
                      :candidate-count (count rows) :parsed-count parsed-count
                      :unparsed-count (- (count rows) parsed-count)
                      :unresolved-count (count rows) :section-counts section-counts
                      :linked-subset-count linked-count :unique-performance-count nil
                      :nonblank-line-count (count nonblank)
                      :noncandidate-lines (vec (remove #(contains? candidate-lines (:line %)) nonblank))
                      :status :unreviewed}
     :publication {:status :blocked :reasons [:owner-review-required :reconciliation-unreviewed]}}))
