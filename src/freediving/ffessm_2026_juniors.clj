(ns freediving.ffessm-2026-juniors
  "Source-bound FFESSM 2026 junior positions from the printed JUNIORS PDF."
  (:require [clojure.string :as str]))

(def source-sha256 "2097a4f271c7c4c32a2945278ef0ba19b798791690c10c87a62573b9ef538413")
(def parser-version "ffessm-2026-juniors/1")

(def ^:private sections
  {"Femmes FIM" {:gender "F" :sex "Femme" :category "Juniors Femmes"
                 :discipline "FIM" :scope :juniors-femmes-fim}
   "Hommes FIM" {:gender "M" :sex "Homme" :category "Juniors Hommes"
                 :discipline "FIM" :scope :juniors-hommes-fim}
   "Hommes CWT bi" {:gender "M" :sex "Homme" :category "Juniors Hommes"
                    :discipline "CWT bi" :scope :juniors-hommes-cwt-bi}})

(defn supported? [pages]
  (let [page (first pages)]
    (boolean (and (= 1 (count pages))
                  (str/includes? page "Championnat de France Eau Libre 2026")
                  (str/includes? page "Résultats JUNIORS")
                  (every? #(str/includes? page %) (keys sections))
                  (str/includes? page "Classement")
                  (str/includes? page "COMMENTAIRES")))))

(defn- source-lines [page]
  (mapv (fn [index value] {:page 1 :line (inc index) :text value})
        (range) (str/split page #"\n" -1)))

(defn- metres [raw]
  (when-let [[_ value] (and raw (re-matches #"(\d+)\s*m" raw))]
    (bigdec value)))

(defn- row-values [text {:keys [gender sex category discipline scope]}]
  (let [cells (str/split (str/trim text) #"\s{2,}")
        [rank surname given raw-sex nationality federation announced raw-discipline
         reached penalty points card comment & extra] cells
        declared (metres announced)
        realized (metres reached)
        lost (metres penalty)
        score (when (and points (re-matches #"\d+" points)) (bigdec points))]
    (when (and (<= 12 (count cells) 13) (empty? extra) (re-matches #"\d+" rank)
               surname given (= sex raw-sex) (= "FFESSM" federation)
               (= discipline raw-discipline) declared realized lost score
               (pos? declared) (<= 0M realized declared)
               (= lost (- declared realized)) (= score (- realized lost))
               (= "Blanc" card))
      {:raw {:rank rank :surname surname :given-name given :sex raw-sex
             :nationality nationality :federation federation :depth-declared announced
             :discipline raw-discipline :depth-reached reached :depth-penalty penalty
             :default-tag nil :final-performance points :card card :comment comment}
       :parsed {:federation federation :event-date nil :session "Résultats JUNIORS"
                :source-name (str given " " surname) :gender gender
                :representation nationality :category category :discipline discipline
                :ranking-scope scope :rank (parse-long rank) :depth-declared declared
                :depth-reached realized :depth-penalty lost :default-tag nil
                :final-performance score :card card :reason comment
                :unit "m" :result-status :valid}})))

(defn- candidate [line section]
  (let [{:keys [raw parsed]} (row-values (:text line) (get sections section))]
    {:coordinates {:page 1 :line (:line line) :column-start 1
                   :column-end (inc (count (:text line)))}
     :ranking-scope (get-in sections [section :scope])
     :source-lines [line] :raw {:line (:text line) :fields raw}
     :parse-status (if parsed :parsed :unparsed) :parsed parsed
     :fields (into {} (map (fn [[key value]]
                             [key {:status (if (nil? value) :unknown :parsed) :value value}])
                           parsed))
     :review-status :unreviewed
     :unresolved-reasons (cond-> [:owner-review-required]
                           (nil? parsed) (conj :unparsed-source-line))}))

(defn parse-pages [pages]
  (let [page (or (first pages) "")
        lines (source-lines page)
        section (atom nil)
        rows (->> lines
                  (keep (fn [line]
                          (let [value (str/trim (:text line))]
                            (cond
                              (contains? sections value) (do (reset! section value) nil)
                              (and (re-find #"^\d+\s{2,}" value)
                                   (str/includes? value "FFESSM"))
                              (candidate line @section)
                              :else nil))))
                  vec)
        counts (frequencies (map (fn [row]
                                   (some (fn [[heading profile]]
                                           (when (= (:scope profile) (:ranking-scope row)) heading))
                                         sections)) rows))
        parsed-count (count (filter #(= :parsed (:parse-status %)) rows))
        complete? (and (supported? pages) (= {"Femmes FIM" 2 "Hommes FIM" 2
                                              "Hommes CWT bi" 2} counts)
                       (= 6 parsed-count))
        candidate-lines (set (map #(get-in % [:coordinates :line]) rows))
        nonblank (remove #(str/blank? (:text %)) lines)]
    {:parser-version parser-version :schema-version 3
     :status (if complete? :needs-review :partial-unsupported-needs-parser)
     :pages [{:page 1 :text page :lines lines
              :status (if complete? :needs-review :unsupported-page)}]
     :candidates rows
     :reconciliation {:page-count (count pages) :coverage (if complete? :complete :partial)
                      :candidate-count (count rows) :parsed-count parsed-count
                      :unparsed-count (- (count rows) parsed-count)
                      :unresolved-count (count rows) :section-counts counts
                      :unique-performance-count nil
                      :nonblank-line-count (count nonblank)
                      :noncandidate-lines (vec (remove #(contains? candidate-lines (:line %)) nonblank))
                      :status :unreviewed}
     :publication {:status :blocked :reasons [:owner-review-required :reconciliation-unreviewed]}}))
