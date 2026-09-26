(ns freediving.tuttinapnea-aggregate-links
  "Pure, source-bound links from a supporting combined ranking to original results."
  (:require [clojure.string :as str]
            [freediving.tuttinapnea-2026 :as individual]))

(def combined-sha256 "de8ceaf26a21147ad62a7242d0b2f2eea7df1c39c5a442d7cd5f6370d7386dd5")
(def school-region-sha256 "2b7a9d25193ef68317300fc4fe92633b882f743e72a745660262218449be2415")

(def ^:private combined-pattern
  #"^\s*(.+?)\s{2,}([FM])\s{2,}([BMR])\s{2,}(\d+(?:,\d+)?)\s{2,}(\d+(?:,\d+)?)\s{2,}(\d+(?:,\d+)?)\s*$")

(defn- combined-row [page line text]
  (when-let [[_ prefix gender type dynamic static total] (re-matches combined-pattern text)]
    (let [location (mapv str/trim (str/split (str/trim prefix) #"\s{2,}"))]
      (when (= 4 (count location))
        {:citation {:page page :line line :text text}
         :team (first location) :source-name (last location) :gender gender
         :type type :dynamic dynamic :static static :total total}))))

(defn- source-row [candidate]
  (let [sha (:source-sha256 candidate)
        parsed (:parsed candidate)
        lines (:source-lines candidate)
        raw (get-in candidate [:raw :fields])
        discipline (:discipline parsed)
        values (:result raw)
        performance (:realised-performance parsed)]
    (when (and (#{individual/static-sha256 individual/dynamic-sha256} sha)
               (= 1 (count lines))
               (pos-int? (:page (first lines))) (pos-int? (:line (first lines)))
               (string? (:text (first lines)))
               (not (str/blank? (:text (first lines))))
               (#{"DYN" "STA"} discipline)
               (= (if (= discipline "DYN") individual/dynamic-sha256 individual/static-sha256) sha)
               (= (:team parsed) (first (:location raw)))
               (= (:source-name parsed) (last (:location raw)))
               (= (:gender parsed) (:gender raw))
               (= performance (nth values (if (= discipline "DYN") 1 0) nil))
               (= :result (:status parsed)))
      {:citation (first lines) :source-sha256 sha :parsed parsed})))

(defn- key-for [team name gender discipline performance]
  [team name gender discipline performance])

(defn reconcile
  "Return only unique exact printed row links. The combined PDF remains a
   supporting view and its rows are never sporting-attempt observations.
   Pages are original `pdftotext -layout` strings; individual candidates must
   carry their original PDF hash, source line and raw parsed cells."
  [sha256 pages individuals]
  (when-not (= combined-sha256 sha256)
    (throw (ex-info "Combined ranking source hash mismatch" {})))
  (let [rows (->> pages
                  (map-indexed (fn [page text]
                                 (keep-indexed (fn [line value]
                                                 (combined-row (inc page) (inc line) value))
                                               (str/split text #"\n" -1))))
                  (apply concat)
                  vec)
        source-rows (keep source-row individuals)
        indexed (group-by (fn [{:keys [parsed]}]
                            (key-for (:team parsed) (:source-name parsed)
                                     (:gender parsed) (:discipline parsed)
                                     (:realised-performance parsed))) source-rows)
        links (for [row rows
                    [discipline performance] [["DYN" (:dynamic row)] ["STA" (:static row)]]
                    :let [matches (get indexed (key-for (:team row) (:source-name row)
                                                        (:gender row) discipline performance))]
                    :when (= 1 (count matches))
                    :let [source (first matches)]
                    :when (or (= "STA" discipline)
                              (= (:type row) (get-in source [:parsed :type])))]
                {:relationship :repeats-individual-result
                 :discipline discipline :raw-result performance
                 :combined-source-sha256 sha256 :combined-citation (:citation row)
                 :individual-source-sha256 (:source-sha256 source)
                 :individual-position (select-keys (:citation source) [:page :line])
                 :individual-text (:text (:citation source))})]
    {:combined-source-sha256 sha256
     :combined-row-count (count rows)
     :links (vec (sort-by (juxt (comp :page :combined-citation)
                                (comp :line :combined-citation) :discipline
                                (comp :page :individual-position)
                                (comp :line :individual-position)) links))}))
