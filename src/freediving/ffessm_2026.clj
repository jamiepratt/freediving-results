(ns freediving.ffessm-2026
  "Source-bound 2026 FFESSM CWT women's open and national rankings."
  (:require [clojure.string :as str]))

(def source-sha256 "9e4d611ce76134de2fd0e0807b37595b4dff1c0032387e2345352dd6e77f41e8")
(def parser-version "ffessm-2026-cwt-women/1")

(defn supported? [pages]
  (and (= 1 (count pages))
       (str/includes? (first pages) "Championnat de France Eau Libre 2026")
       (str/includes? (first pages) "Résultats Monopalme Femmes")
       (str/includes? (first pages) "EPREUVE")
       (str/includes? (first pages) "CHAMPIONNAT DE FRANCE")))

(defn- source-lines [text]
  (mapv (fn [index value] {:page 1 :line (inc index) :text value})
        (range) (str/split text #"\n" -1)))

(defn- position-line? [text]
  (boolean (re-find #"^\s*\d+\s{2,}\S+\s{2,}" text)))

(def ^:private result-pattern
  #"^\s*(\d+)\s{2,}(\S+)\s{2,}(\S+)\s{2,}(Femme)\s{2,}(.+?)\s*(FFESSM)\s+(\d+\s*m)\s+(CWT)\s+(\d+\s*m)\s+(\d+m)\s+(?:(1)\s+)?(\d+)\s+(Blanc|Jaune)(?:\s{2,}(.+?))?\s*$")

(defn- row-values [text scope]
  (when-let [[_ rank surname given sex nationality federation announced discipline reached penalty tag points card comment]
             (re-matches result-pattern text)]
    (let [nationality (str/trim nationality)
          raw {:rank rank :surname surname :given-name given :sex sex
               :nationality nationality :federation federation :depth-declared announced
               :discipline discipline :depth-reached reached :depth-penalty penalty
               :default-tag tag :final-performance points :card card :comment comment}
          parsed {:federation federation :event-date nil :source-name (str given " " surname)
                  :gender "F" :representation nationality :category "Femmes"
                  :discipline discipline :ranking-scope scope :rank (parse-long rank)
                  :depth-declared (bigdec (str/replace announced #"\s*m$" ""))
                  :depth-reached (bigdec (str/replace reached #"\s*m$" ""))
                  :depth-penalty (bigdec (str/replace penalty #"m$" ""))
                  :default-tag (some-> tag parse-long) :final-performance (bigdec points)
                  :card card :reason comment :unit "m"}]
      {:raw raw :parsed parsed})))

(defn- candidate [line scope]
  (let [{:keys [raw parsed]} (row-values (:text line) scope)]
    {:coordinates {:page 1 :line (:line line) :column-start 1
                   :column-end (inc (count (:text line)))}
     :ranking-scope scope
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
  (let [open (filter #(= :epreuve (get-in % [:parsed :ranking-scope])) candidates)
        key-of (fn [candidate]
                 (select-keys (:parsed candidate)
                              [:source-name :representation :depth-declared :depth-reached :depth-penalty
                               :default-tag :final-performance :card :reason]))
        open-by-value (group-by key-of open)]
    (mapv (fn [candidate]
            (if (= :championnat-de-france (get-in candidate [:parsed :ranking-scope]))
              (let [matches (get open-by-value (key-of candidate))]
                (if (= 1 (count matches))
                  (assoc-in candidate [:parsed :same-result-as]
                            ((juxt :page :line) (:coordinates (first matches))))
                  (update candidate :unresolved-reasons conj :subset-link-uncertain)))
              candidate)) candidates)))

(defn parse-pages [pages]
  (let [text (or (first pages) "")
        lines (source-lines text)
        section (atom nil)
        candidates (->> lines
                        (keep (fn [line]
                                (let [value (str/trim (:text line))]
                                  (cond
                                    (= value "EPREUVE") (do (reset! section :epreuve) nil)
                                    (= value "CHAMPIONNAT DE FRANCE")
                                    (do (reset! section :championnat-de-france) nil)
                                    (position-line? value) (candidate line @section)
                                    :else nil))))
                        vec link-subset)
        section-counts (frequencies (map :ranking-scope candidates))
        parsed-count (count (filter #(= :parsed (:parse-status %)) candidates))
        linked-count (count (filter #(get-in % [:parsed :same-result-as]) candidates))
        complete? (and (supported? pages) (= {:epreuve 6 :championnat-de-france 4} section-counts)
                       (= 10 parsed-count) (= 4 linked-count))
        candidate-lines (set (map #(get-in % [:coordinates :line]) candidates))
        nonblank (remove #(str/blank? (:text %)) lines)]
    {:parser-version parser-version :schema-version 3
     :status (if complete? :needs-review :partial-unsupported-needs-parser)
     :pages [{:page 1 :text text :lines lines :status (if complete? :needs-review :unsupported-page)}]
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
