(ns freediving.ffessm-2025-monofin
  "Source-bound FFESSM 2025 monofin category tables."
  (:require [clojure.string :as str]))

(def women-sha256 "48e9f2a761b432382af672d7fe7adc555594435e55b527013287b586eee1f84f")
(def men-sha256 "d88ec7eb3fef240f7f76dadaef8ec87c90475f288d67e4d8ba00fc2844cbac36")
(def junior-men-sha256 "4c141387c47911b87b23dffd747c1c30ecaa250290aa9f39fae18b69c3ba39d4")

(def ^:private profiles
  {women-sha256 {:heading "Résultats Monopalme Femmes" :category "Femmes" :gender "F" :section "FFESSM" :rows 2 :version "ffessm-2025-monopalme-femmes/1"}
   men-sha256 {:heading "Résultats Monopalme Hommes" :category "Hommes" :gender "M" :section "FFESSM" :rows 8 :version "ffessm-2025-monopalme-hommes/1"}
   junior-men-sha256 {:heading "Résultats Monopalme JUNIORS" :category "Hommes Juniors" :gender "M" :section "Hommes" :rows 1 :version "ffessm-2025-monopalme-hommes-juniors/1"}})

(defn parser-version [sha256] (:version (get profiles sha256)))

(defn matching-sha [pages]
  (when (= 1 (count pages))
    (let [page (first pages)]
      (some (fn [[sha {:keys [heading section]}]]
              (when (and (str/includes? page "Championnat de France Eau Libre 2025")
                         (str/includes? page heading)
                         (str/includes? page "Villefranche-sur-Mer")
                         (str/includes? page (str "\n" section "\n"))
                         (str/includes? page "Classement")) sha)) profiles))))

(defn supported? [sha256 pages]
  (boolean (and (get profiles sha256) (= sha256 (matching-sha pages)))))

(defn- source-lines [page]
  (mapv (fn [n text] {:page 1 :line (inc n) :text text})
        (range) (str/split page #"\n" -1)))

(defn- row-values [text {:keys [category gender section]}]
  (let [cells (str/split (str/trim text) #"\s{2,}")
        [raw-rank family given nationality announced realized depth-penalty plate-penalty points card] cells
        number? #(boolean (and % (re-matches #"-?\d+" %)))
        announced-num (some-> announced (str/replace #"\s*m$" "") parse-long)
        realized-num (when (number? realized) (parse-long realized))
        depth-num (when (number? depth-penalty) (parse-long depth-penalty))
        plate-num (when (number? plate-penalty) (parse-long plate-penalty))
        points-num (when (number? points) (parse-long points))
        dq? (= raw-rank "DQ")
        valid? (and (= 10 (count cells)) (re-matches #"(?:\d+|DQ)" raw-rank)
                    (= nationality "Française") (some? announced-num)
                    (some? realized-num) (some? depth-num) (some? plate-num)
                    (some? points-num) (pos? announced-num)
                    (<= 0 realized-num announced-num)
                    (= depth-num (- announced-num realized-num))
                    (if dq?
                      (and (= card "Rouge") (zero? points-num) (zero? plate-num))
                      (and (contains? #{"Blanc" "Jaune"} card)
                           (= card (if (zero? plate-num) "Blanc" "Jaune"))
                           (= points-num (- realized-num depth-num plate-num)))))]
    (when valid?
      {:raw {:rank raw-rank :family-name family :given-name given :nationality nationality
             :announced-depth announced :realized-depth realized :depth-penalty depth-penalty
             :plate-penalty plate-penalty :final-points points :card card}
       :parsed {:federation "FFESSM" :ranking-scope section :event-date nil
                :source-name (str given " " family) :family-name family :given-name given
                :gender gender :nationality nationality :category category :discipline "CWT-MONO"
                :rank (when-not dq? (parse-long raw-rank)) :raw-rank raw-rank
                :announced-depth (bigdec announced-num) :realized-depth (bigdec realized-num)
                :depth-penalty (bigdec depth-num) :plate-penalty (bigdec plate-num)
                :final-points (bigdec points-num) :card card :unit "m"
                :result-status (if dq? :disqualified :valid)}})))

(defn- candidate [line profile]
  (let [{:keys [raw parsed]} (row-values (:text line) profile)]
    {:coordinates {:page 1 :line (:line line) :column-start 1 :column-end (inc (count (:text line)))}
     :source-lines [line] :raw {:line (:text line) :fields raw}
     :parse-status (if parsed :parsed :unparsed) :parsed parsed
     :fields (into {} (map (fn [[k v]] [k {:status (if (nil? v) :unknown :parsed) :value v}]) parsed))
     :review-status :unreviewed
     :unresolved-reasons (cond-> [:owner-review-required] (nil? parsed) (conj :unparsed-source-line))}))

(defn parse-pages [sha256 pages]
  (let [{:keys [rows version] :as profile} (get profiles sha256)
        page (or (first pages) "")
        lines (source-lines page)
        nonblank (vec (remove #(str/blank? (:text %)) lines))
        results (->> nonblank (filter #(re-matches #"\s*(?:\d+|DQ)\s{2,}.*" (:text %))) vec)
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
