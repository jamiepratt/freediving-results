(ns freediving.ffessm-2025-bipalmes
  "Source-bound FFESSM 2025 bipalmes category tables."
  (:require [clojure.string :as str]))

(def women-sha256 "726e1db16e08984de01a18f3e891d5374ca9b18f419532393cac40b311d2a8f3")
(def men-sha256 "a35bc35b4071821119498a482653594fe1eca2794bf2e68e4377e7b9f46ec3c1")

(def ^:private profiles
  {women-sha256 {:heading "Résultats Bipalme Femmes" :category "Femmes"
                 :gender "F" :rows 4 :version "ffessm-2025-bipalmes-femmes/1"}
   men-sha256 {:heading "Résultats Bipalme Hommes" :category "Hommes"
               :gender "M" :rows 7 :version "ffessm-2025-bipalmes-hommes/1"}})

(defn parser-version [sha256]
  (:version (get profiles sha256)))

(defn matching-sha [pages]
  (when (= 1 (count pages))
    (let [page (first pages)]
      (some (fn [[sha {:keys [heading]}]]
              (when (and (str/includes? page "Championnat de France Eau Libre 2025")
                         (str/includes? page heading)
                         (str/includes? page "Villefranche-sur-Mer")
                         (str/includes? page "\nFFESSM\n")
                         (str/includes? page "Classement")) sha)) profiles))))

(defn supported? [sha256 pages]
  (boolean (and (get profiles sha256) (= sha256 (matching-sha pages)))))

(defn- source-lines [page]
  (mapv (fn [n text] {:page 1 :line (inc n) :text text})
        (range) (str/split page #"\n" -1)))

(defn- integer [value]
  (when (and value (re-matches #"\d+" value))
    (parse-long value)))

(defn- row-values [text {:keys [category gender]}]
  (let [cells (str/split (str/trim text) #"\s{2,}")
        [rank family given nationality announced realized depth-penalty plate-penalty points card] cells
        announced-num (when-let [[_ n] (and announced (re-matches #"(\d+) m" announced))]
                        (parse-long n))
        realized-num (integer realized)
        depth-num (integer depth-penalty)
        plate-num (integer plate-penalty)
        points-num (integer points)]
    (when (and (= 10 (count cells)) (integer rank)
               (every? #(and % (not (str/blank? %))) [family given nationality])
               announced-num realized-num depth-num plate-num points-num
               (pos? announced-num) (<= 0 realized-num announced-num)
               (= depth-num (- announced-num realized-num))
               (= points-num (- realized-num depth-num plate-num))
               (contains? #{"Blanc" "Jaune"} card)
               (= card (if (zero? plate-num) "Blanc" "Jaune")))
      {:raw {:rank rank :family-name family :given-name given :nationality nationality
             :announced-depth announced :realized-depth realized :depth-penalty depth-penalty
             :plate-penalty plate-penalty :final-points points :card card}
       :parsed {:federation "FFESSM" :ranking-scope "FFESSM" :event-date nil
                :source-name (str given " " family) :family-name family :given-name given
                :gender gender :nationality nationality :category category :discipline "CWT-BI"
                :rank (integer rank) :raw-rank rank :announced-depth (bigdec announced-num)
                :realized-depth (bigdec realized-num) :depth-penalty (bigdec depth-num)
                :plate-penalty (bigdec plate-num) :final-points (bigdec points-num)
                :card card :unit "m" :result-status :valid}})))

(defn- candidate [line profile]
  (let [{:keys [raw parsed]} (row-values (:text line) profile)]
    {:coordinates {:page 1 :line (:line line) :column-start 1
                   :column-end (inc (count (:text line)))}
     :source-lines [line] :raw {:line (:text line) :fields raw}
     :parse-status (if parsed :parsed :unparsed) :parsed parsed
     :fields (into {} (map (fn [[k v]] [k {:status (if (nil? v) :unknown :parsed)
                                           :value v}]) parsed))
     :review-status :unreviewed
     :unresolved-reasons (cond-> [:owner-review-required]
                           (nil? parsed) (conj :unparsed-source-line))}))

(defn parse-pages [sha256 pages]
  (let [{:keys [rows version] :as profile} (get profiles sha256)
        page (or (first pages) "")
        lines (source-lines page)
        nonblank (vec (remove #(str/blank? (:text %)) lines))
        results (->> nonblank
                     (filter #(re-matches #"\s*\d+\s{2,}.*" (:text %))) vec)
        candidates (mapv #(candidate % profile) results)
        parsed (count (filter #(= :parsed (:parse-status %)) candidates))
        complete? (and (supported? sha256 pages) (= rows (count results)) (= rows parsed))
        candidate-lines (set (map :line results))]
    {:parser-version version :schema-version 3
     :status (if complete? :needs-review :partial-unsupported-needs-parser)
     :pages [{:page 1 :text page :lines lines
              :status (if complete? :needs-review :unsupported-page)}]
     :candidates candidates
     :reconciliation {:page-count (count pages) :coverage (if complete? :complete :partial)
                      :candidate-count (count candidates) :parsed-count parsed
                      :unparsed-count (- (count candidates) parsed)
                      :unresolved-count (count candidates) :expected-rows rows
                      :nonblank-line-count (count nonblank)
                      :noncandidate-lines (vec (remove #(contains? candidate-lines (:line %)) nonblank))
                      :status :unreviewed}
     :publication {:status :blocked
                   :reasons (cond-> [:owner-review-required :reconciliation-unreviewed]
                              (not complete?) (conj :unsupported-layout-or-row-count))}}))
