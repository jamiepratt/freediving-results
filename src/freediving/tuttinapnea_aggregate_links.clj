(ns freediving.tuttinapnea-aggregate-links
  "Pure, source-bound links from a supporting combined ranking to original results."
  (:require [clojure.string :as str]
            [freediving.tuttinapnea-2026 :as individual]))

(def combined-sha256 "de8ceaf26a21147ad62a7242d0b2f2eea7df1c39c5a442d7cd5f6370d7386dd5")
(def school-region-sha256 "2b7a9d25193ef68317300fc4fe92633b882f743e72a745660262218449be2415")
(def january-combined-sha256 "902f58b6526aa2e161e8597cf73576897c9d80f7ada5689c12e8e45312d72d41")
(def january-dynamic-sha256 "aede835f6af33149cf6c5dd4b6498b1d0cb34c2d3ec5a5cd95e325265ed65fcf")
(def january-static-sha256 "ad0f41f593a3842f1127e8095de94ae1169a8672b47e40068a524ffe0d037779")

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
  [team (str/upper-case name) gender discipline performance])

(defn- reverse-two-token-name [name]
  (let [tokens (str/split (str/trim name) #"\s+")]
    (when (= 2 (count tokens))
      (str/join " " (reverse tokens)))))

(defn- exact-or-reversed [indexed team name gender discipline performance]
  (let [direct (get indexed (key-for team name gender discipline performance))
        reversed (when-let [other (reverse-two-token-name name)]
                   (get indexed (key-for team other gender discipline performance)))
        matches (distinct (concat direct reversed))]
    (when (= 1 (count matches)) (first matches))))

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
                    :let [source (exact-or-reversed indexed (:team row) (:source-name row)
                                                    (:gender row) discipline performance)]
                    :when source
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

(def ^:private january-combined-pattern
  #"^\s*(.+?)\s{2,}([FM])\s{2,}(\d+(?:,\d+)?)\s{2,}(\d+(?:,\d+)?)\s{2,}(\d+(?:,\d+)?)\s*$")

(defn- january-combined-row [page line text]
  (when-let [[_ prefix gender dynamic static total]
             (re-matches january-combined-pattern text)]
    (let [location (mapv str/trim (str/split (str/trim prefix) #"\s{2,}"))]
      (when (= 2 (count location))
        {:citation {:page page :line line :text text}
         :team (first location) :source-name (second location)
         :gender gender :dynamic dynamic :static static :total total}))))

(defn- january-source-row [candidate]
  (let [sha (:source-sha256 candidate)
        parsed (:parsed candidate)
        raw (:raw candidate)
        line (first (:source-lines candidate))
        discipline (:discipline parsed)
        values (get-in raw [:fields :result])
        location (get-in raw [:fields :location])]
    (when (and (#{january-dynamic-sha256 january-static-sha256} sha)
               (= 1 (count (:source-lines candidate)))
               (pos-int? (:page line)) (pos-int? (:line line))
               (string? (:text line)) (not (str/blank? (:text line)))
               (= (:text line) (:line raw))
               (= :result (:status parsed))
               (#{"DYN" "STA"} discipline)
               (= sha (if (= discipline "DYN") january-dynamic-sha256
                          january-static-sha256))
               (= 2 (count location))
               (= (:team parsed) (first location))
               (= (:source-name parsed) (second location))
               (= (:gender parsed) (get-in raw [:fields :gender]))
               (= (:realised-performance parsed)
                  (nth values (if (= discipline "DYN") 1 0) nil))
               (string? (:points parsed))
               (= (:points parsed) (last values))
               (re-find (re-pattern (str "\\s" (java.util.regex.Pattern/quote (:points parsed))
                                         "\\s*$")) (:text line)))
      {:citation line :source-sha256 sha :parsed parsed})))

(defn reconcile-january
  "Cite exact individual point cells repeated by the January combined ranking.
   Neither combined points nor totals become sporting-attempt observations."
  [sha256 pages individuals]
  (when-not (= january-combined-sha256 sha256)
    (throw (ex-info "January combined ranking source hash mismatch" {})))
  (let [rows (->> pages
                  (map-indexed (fn [page text]
                                 (keep-indexed (fn [line value]
                                                 (january-combined-row (inc page) (inc line) value))
                                               (str/split text #"\n" -1))))
                  (apply concat)
                  vec)
        indexed (group-by (fn [{:keys [parsed]}]
                            (key-for (:team parsed) (:source-name parsed)
                                     (:gender parsed) (:discipline parsed)
                                     (:points parsed)))
                          (keep january-source-row individuals))
        links (for [row rows
                    [discipline points] [["DYN" (:dynamic row)] ["STA" (:static row)]]
                    :let [matches (get indexed (key-for (:team row) (:source-name row)
                                                        (:gender row) discipline points))]
                    :when (= 1 (count matches))
                    :let [source (first matches)]]
                {:relationship :repeats-individual-result
                 :discipline discipline :raw-result points
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
