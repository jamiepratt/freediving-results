(ns freediving.extraction
  (:require [clojure.string :as str]
            [clojure.java.shell :as shell]
            [clojure.edn :as edn]
            [freediving.archive :as archive]
            [freediving.aida :as aida]
            [freediving.athens :as athens]
            [freediving.athens-geometry :as athens-geometry]
            [freediving.novi-sad :as novi-sad]
            [freediving.croatia-open :as croatia-open]
            [freediving.italy-open :as italy-open]
            [freediving.san-mauro-2026 :as san-mauro]
            [freediving.san-mauro-static-2026 :as san-mauro-static]
            [freediving.tuttinapnea-2026 :as tuttinapnea]
            [freediving.tuttinapnea-2025-static :as tuttinapnea-2025-static]
            [freediving.tuttinapnea-2025-dynamic :as tuttinapnea-2025-dynamic]
            [freediving.world-games-2025 :as world-games]
            [freediving.world-games-series-2025 :as world-games-series]
            [freediving.kaohsiung-2025 :as kaohsiung]
            [freediving.lodz-2025 :as lodz]
            [freediving.lodz-2026 :as lodz-2026]
            [freediving.unu-tampa-2025 :as unu-tampa]
            [freediving.noxy-2025 :as noxy]
            [freediving.deep-dominica-2025 :as deep-dominica]
            [freediving.belgrade-2026 :as belgrade-2026]
            [freediving.deep-dominica-2026 :as deep-dominica-2026]
            [freediving.camotes-challenge-2026 :as camotes-challenge-2026]
            [freediving.vertical-blue-2025 :as vertical-blue]
            [freediving.ffessm-2025-day1 :as ffessm-2025-day1]
            [freediving.ffessm-2025-day2 :as ffessm-2025-day2]
            [freediving.ffessm-2025-monofin :as ffessm-2025-monofin]
            [freediving.ffessm-2025-bipalmes :as ffessm-2025-bipalmes]
            [freediving.ffessm-2025-sans-palmes :as ffessm-2025-sans-palmes]
            [freediving.ffessm-2025-immersion-libre :as ffessm-2025-immersion-libre]
            [freediving.ffessm-2026 :as ffessm-2026]
            [freediving.ffessm-2026-men :as ffessm-2026-men]
            [freediving.ffessm-2026-bipalmes-women :as ffessm-bipalmes-women]
            [freediving.ffessm-2026-regular-categories :as ffessm-regular]
            [freediving.ffessm-2026-final-categories :as ffessm-final]
            [freediving.ffessm-2026-juniors :as ffessm-juniors]
            [freediving.fedas-indoor :as fedas-indoor]
            [freediving.fedas-indoor-2026 :as fedas-indoor-2026]
            [freediving.fedas-outdoor :as fedas-outdoor]
            [freediving.vdst-neckar-2025 :as vdst-neckar]
            [freediving.vdst-rhein-main-2025 :as vdst-rhein-main]
            [freediving.vdst-chemnitz-2025 :as vdst-chemnitz-2025]
            [freediving.vdst-chemnitz-2026 :as vdst-chemnitz-2026]
            [freediving.indoor-2026 :as indoor-2026]
            [freediving.indoor-time-2026 :as indoor-time]
            [freediving.depth-2025 :as depth-2025]
            [freediving.depth-2026 :as depth-2026]
            [freediving.depth :as depth]))

(def parser-version "cmas-cwt-men/1")
(defn- number-value [s] (when s (parse-long s)))
(defn- field [value] {:status (if (nil? value) :unknown :parsed) :value value})
(defn- parse-line [line]
  (when-let [[_ rank name representation tail]
             (re-matches #"\s*(?:(\d+)\s+)?(.+?)\s+(CMAS1|AIN|[A-Z]{3})\s+Men Senior\s*(.*?)\s*" line)]
    (let [tokens (str/split tail #"\s+")
          nums (take-while #(re-matches #"\d+" %) tokens)
          rest-tokens (drop (count nums) tokens)
          status (when (#{"PEN" "DNS" "DSQ"} (first rest-tokens)) (first rest-tokens))
          notes (str/join " " (if status (rest rest-tokens) rest-tokens))
          normal? (and (#{2 3} (count nums)) (or (nil? status) (= "PEN" status)))
          valid? (or normal? (and (= "DNS" status) (empty? nums))
                     (and (= "DSQ" status) (= 1 (count nums))))]
      (when valid?
        {:rank (number-value rank) :source-name name :representation representation
         :category "Men Senior" :attempted-depth (number-value (first nums))
         :final-depth (when normal? (number-value (last nums)))
         :penalty (when (= 3 (count nums)) (number-value (second nums)))
         :status status :notes (when-not (str/blank? notes) notes) :unit nil
         ::raw {:rank rank :source-name name :representation representation :category "Men Senior"
                :attempted-depth (first nums) :final-depth (when normal? (last nums))
                :penalty (when (= 3 (count nums)) (second nums)) :status status
                :notes (when-not (str/blank? notes) notes)}}))))

(defn- parse-cmas-pages
  "Parse exact pdftotext layout page strings. Coordinates are 1-based text lines,
   not PDF geometry. Every nonblank line remains accounted for and review blocked."
  [pages]
  (let [whole (str/join "\n" pages)
        supported? (and (str/includes? whole "2025 CMAS World Championship Freediving Outdoor")
                        (str/includes? whole "CWT MEN SENIORS"))
        dates (distinct (map second (re-seq #"(?m)^\s*(\d{2}/\d{2}/\d{4})\s*$" whole)))
        event-date (when (= 1 (count dates))
                     (try (let [[d m y] (str/split (first dates) #"/")]
                            (str (java.time.LocalDate/of (parse-long y) (parse-long m) (parse-long d))))
                          (catch java.time.DateTimeException _ nil)))
        page-data (mapv (fn [idx text]
                          {:page (inc idx) :text text
                           :status (if (str/blank? text) :needs-OCR :text-extracted)
                           :lines (mapv (fn [n s] {:line (inc n) :text s})
                                        (range) (str/split text #"\n" -1))}) (range) pages)
        lines (for [page page-data line (:lines page) :when (not (str/blank? (:text line)))]
                {:page (:page page) :line (:line line) :text (:text line)})
        candidates (if supported?
                     (mapv (fn [{:keys [page line text]}]
                             (let [result (parse-line text) parsed (when result (dissoc result ::raw))]
                               {:coordinates {:page page :line line :column-start 1 :column-end (inc (count text))}
                                :raw {:line text :fields (::raw result)}
                                :parse-status (if parsed :parsed :unparsed)
                                :parsed (when parsed (assoc parsed :federation "CMAS" :event-date event-date :discipline "CWT"))
                                :fields (into {} (map (fn [[k v]] [k (field v)])
                                                      (assoc (or parsed {}) :unit nil :event-date event-date)))
                                :review-status :unreviewed}))
                           (remove #(re-find #"^\s*(?:2025 CMAS World Championship Freediving Outdoor|\d{2}/\d{2}/\d{4}|Result|CWT MEN SENIORS|FINAL|DEPTH|RANK\s+SURNAME.*|Report Created.*|Data Processing.*)\s*$" (:text %)) lines)) [])
        parsed-count (count (filter #(= :parsed (:parse-status %)) candidates))]
    {:parser-version parser-version
     :status (cond (some #(= :needs-OCR (:status %)) page-data) :needs-OCR
                   (not supported?) :unsupported-needs-parser
                   :else :needs-review)
     :pages page-data :candidates candidates
     :reconciliation {:page-count (count pages) :candidate-count (when supported? (count candidates))
                      :parsed-count (when supported? parsed-count) :unparsed-count (when supported? (- (count candidates) parsed-count))
                      :unresolved-count (when supported? (count candidates))
                      :nonblank-line-count (count lines)
                      :noncandidate-lines (vec (remove (fn [line] (some #(= (select-keys line [:page :line])
                                                                            (select-keys (:coordinates %) [:page :line])) candidates)) lines))
                      :status :unreviewed}
     :publication {:status :blocked :reasons [:owner-review-required :reconciliation-unreviewed :units-not-explicit]}}))

(defn parse-pages
  ([pages] (parse-pages nil pages))
  ([sha256 pages]
   (let [vdst-neckar? (str/includes? (str/join "\n" pages) "1. Neckar Apnoe Cup und 2. Baden-Württembergische Meisterschaft")
         vdst-rhein-main? (str/includes? (str/join "\n" pages) "22. Rhein-Main Cup 2025")
         vdst-chemnitz-2025? (str/includes? (str/join "\n" pages) "2. Chemnitzer Apnoe Cup")
         vdst-chemnitz-2026? (str/includes? (str/join "\n" pages) "3. Chemnitzer Apnoe Cup - 50m Edition")
         fedas-indoor? (str/includes? (str/join "\n" pages) "RESULTADOS DEL CAMPEONATO DE ESPAÑA DE APNEA INDOOR")
         fedas-outdoor? (and (str/includes? (str/join "\n" pages) "CAMPEONATO DE ESPAÑA DE APNEA OUTDOOR")
                             (str/includes? (str/join "\n" pages) "FEDERACIÓN ESPAÑOLA DE ACTIVIDADES SUBACUÁTICAS"))
         bipalmes-women? (ffessm-bipalmes-women/supported? pages)
         regular-men? (ffessm-regular/supported? ffessm-regular/men-sha256 pages)
         regular-women? (ffessm-regular/supported? ffessm-regular/women-sha256 pages)
         final-sha (some #(when (ffessm-final/supported? % pages) %)
                         [ffessm-final/cnf-men-sha256 ffessm-final/fim-women-sha256
                          ffessm-final/fim-men-sha256])
         juniors? (ffessm-juniors/supported? pages)
         monofin-sha (ffessm-2025-monofin/matching-sha pages)
         b18-sha (some #(% pages) [ffessm-2025-bipalmes/matching-sha
                                   ffessm-2025-sans-palmes/matching-sha
                                   ffessm-2025-immersion-libre/matching-sha])
         san-mauro-static? (san-mauro-static/supported? pages)
         tuttinapnea? (tuttinapnea/supported? pages)
         tuttinapnea-2025-static? (tuttinapnea-2025-static/supported? pages)
         tuttinapnea-2025-dynamic? (tuttinapnea-2025-dynamic/supported? pages)]
     (when (and san-mauro-static? (not= sha256 san-mauro-static/source-sha256))
       (throw (ex-info "San Mauro static parser is bound to a different source PDF" {})))
     (when (and tuttinapnea? (not (contains? tuttinapnea/source-sha256s sha256)))
       (throw (ex-info "TuttinApnea parser is bound to different source PDFs" {})))
     (when (and tuttinapnea-2025-static?
                (not= sha256 tuttinapnea-2025-static/source-sha256))
       (throw (ex-info "TuttinApnea January static parser is bound to a different source PDF" {})))
     (when (and tuttinapnea-2025-dynamic?
                (not= sha256 tuttinapnea-2025-dynamic/source-sha256))
       (throw (ex-info "TuttinApnea January dynamic parser is bound to a different source PDF" {})))
     (when (and bipalmes-women? (not= sha256 ffessm-bipalmes-women/source-sha256))
       (throw (ex-info "French 2026 bifins women parser is bound to a different source PDF" {})))
     (when (and regular-men? (not= sha256 ffessm-regular/men-sha256))
       (throw (ex-info "French 2026 bifins men parser is bound to a different source PDF" {})))
     (when (and regular-women? (not= sha256 ffessm-regular/women-sha256))
       (throw (ex-info "French 2026 no fins women parser is bound to a different source PDF" {})))
     (when (and final-sha (not= sha256 final-sha))
       (throw (ex-info "French 2026 final category parser is bound to a different source PDF" {})))
     (when (and juniors? (not= sha256 ffessm-juniors/source-sha256))
       (throw (ex-info "French 2026 juniors parser is bound to a different source PDF" {})))
     (when (and monofin-sha (not= sha256 monofin-sha))
       (throw (ex-info "French 2025 monofin parser is bound to a different source PDF" {})))
     (when (and b18-sha (not= sha256 b18-sha))
       (throw (ex-info "French 2025 category parser is bound to a different source PDF" {})))
     (when (and vdst-neckar? (not= sha256 vdst-neckar/source-sha256))
       (throw (ex-info "VDST Neckar parser is bound to a different source PDF" {})))
     (when (and vdst-rhein-main? (not= sha256 vdst-rhein-main/source-sha256))
       (throw (ex-info "VDST Rhein-Main parser is bound to a different source PDF" {})))
     (when (and vdst-chemnitz-2025? (not= sha256 vdst-chemnitz-2025/source-sha256))
       (throw (ex-info "VDST Chemnitz 2025 parser is bound to a different source PDF" {})))
     (when (and vdst-chemnitz-2026? (not= sha256 vdst-chemnitz-2026/source-sha256))
       (throw (ex-info "VDST Chemnitz 2026 parser is bound to a different source PDF" {})))
     (when (and fedas-indoor? (not (contains? #{fedas-indoor/source-2025-sha256
                                                fedas-indoor-2026/source-2026-sha256} sha256)))
       (throw (ex-info "FEDAS indoor parser is bound to a different source PDF" {})))
     (when (and fedas-outdoor? (not (contains? #{fedas-outdoor/outdoor-2025-sha256
                                                 fedas-outdoor/outdoor-2026-sha256} sha256)))
       (throw (ex-info "FEDAS outdoor parser is bound to a different source PDF" {})))
     (cond (= sha256 vdst-neckar/source-sha256) (vdst-neckar/parse-pages sha256 pages)
           (= sha256 vdst-rhein-main/source-sha256) (vdst-rhein-main/parse-pages sha256 pages)
           (= sha256 vdst-chemnitz-2025/source-sha256) (vdst-chemnitz-2025/parse-pages sha256 pages)
           (= sha256 vdst-chemnitz-2026/source-sha256) (vdst-chemnitz-2026/parse-pages sha256 pages)
           (= sha256 fedas-indoor/source-2025-sha256) (fedas-indoor/parse-pages sha256 pages)
           (= sha256 fedas-indoor-2026/source-2026-sha256) (fedas-indoor-2026/parse-pages sha256 pages)
           (= sha256 fedas-outdoor/outdoor-2025-sha256) (fedas-outdoor/parse-pages sha256 pages)
           (= sha256 fedas-outdoor/outdoor-2026-sha256) (fedas-outdoor/parse-pages sha256 pages)
           b18-sha ((cond (ffessm-2025-bipalmes/parser-version b18-sha) ffessm-2025-bipalmes/parse-pages
                          (ffessm-2025-sans-palmes/parser-version b18-sha) ffessm-2025-sans-palmes/parse-pages
                          :else ffessm-2025-immersion-libre/parse-pages) sha256 pages)
           monofin-sha (ffessm-2025-monofin/parse-pages sha256 pages)
           bipalmes-women? (ffessm-bipalmes-women/parse-pages pages)
           (or regular-men? regular-women?) (ffessm-regular/parse-pages sha256 pages)
           final-sha (ffessm-final/parse-pages sha256 pages)
           juniors? (ffessm-juniors/parse-pages pages)
           (depth/supported? pages) (depth/parse-pages pages)
           (depth-2025/supported? pages) (depth-2025/parse-pages pages)
           (aida/supported? pages) (aida/parse-pages pages)
           (athens/supported? pages) (athens/parse-pages pages)
           (novi-sad/supported? pages) (novi-sad/parse-pages pages)
           (croatia-open/supported? pages) (croatia-open/parse-pages pages)
           (italy-open/supported? pages) (italy-open/parse-pages pages)
           san-mauro-static? (san-mauro-static/parse-pages sha256 pages)
           tuttinapnea? (tuttinapnea/parse-pages sha256 pages)
           tuttinapnea-2025-static? (tuttinapnea-2025-static/parse-pages sha256 pages)
           tuttinapnea-2025-dynamic? (tuttinapnea-2025-dynamic/parse-pages sha256 pages)
           (san-mauro/supported? pages) (san-mauro/parse-pages sha256 pages)
           (world-games/supported? pages) (world-games/parse-pages pages)
           (world-games-series/supported? pages) (world-games-series/parse-pages pages)
           (kaohsiung/supported? pages) (kaohsiung/parse-pages pages)
           (lodz/supported? pages) (lodz/parse-pages pages)
           (lodz-2026/supported? pages) (lodz-2026/parse-pages pages)
           (unu-tampa/supported? pages) (unu-tampa/parse-pages pages)
           (noxy/supported? pages) (noxy/parse-pages pages)
           (deep-dominica/supported? pages) (deep-dominica/parse-pages pages)
           (belgrade-2026/supported? pages) (belgrade-2026/parse-pages pages)
           (deep-dominica-2026/supported? pages) (deep-dominica-2026/parse-pages pages)
           (vertical-blue/supported? pages) (vertical-blue/parse-pages pages)
           (ffessm-2025-day1/supported? pages) (ffessm-2025-day1/parse-pages pages)
           (ffessm-2025-day2/supported? pages) (ffessm-2025-day2/parse-pages pages)
           (ffessm-2026/supported? pages) (ffessm-2026/parse-pages pages)
           (ffessm-2026-men/supported? pages) (ffessm-2026-men/parse-pages pages)
           (depth-2026/supported? pages) (depth-2026/parse-pages-with-geometry pages "")
           :else (parse-cmas-pages pages)))))

(defn- canonical [value]
  (cond (map? value) (into (sorted-map) (map (fn [[k v]] [k (canonical v)]) value))
        (vector? value) (mapv canonical value)
        (sequential? value) (mapv canonical value)
        :else value))
(defn- digest [value]
  (.formatHex (java.util.HexFormat/of)
              (.digest (java.security.MessageDigest/getInstance "SHA-256")
                       (.getBytes (binding [*print-length* nil *print-level* nil] (pr-str (canonical value))) "UTF-8"))))
(defn- command! [& args]
  (let [result (apply shell/sh (concat args [:out-enc "UTF-8" :err-enc "UTF-8"]))]
    (when-not (zero? (:exit result))
      (throw (ex-info "PDF tool failed" {:tool (first args) :exit (:exit result) :stderr (:err result)})))
    result))

(defn- depth-2026-selected? [pages]
  (and (depth-2026/supported? pages)
       (not-any? #(% pages) [depth/supported? depth-2025/supported? aida/supported? athens/supported? novi-sad/supported?])))

(defn legacy-novi-artifact? [artifact]
  (and (= 3 (:schema-version artifact)) (= novi-sad/parser-version (:parser-version artifact))))

(defn croatia-open-artifact? [artifact]
  (and (= 3 (:schema-version artifact)) (= croatia-open/parser-version (:parser-version artifact))))

(defn italy-open-artifact? [artifact]
  (and (= 3 (:schema-version artifact)) (= italy-open/parser-version (:parser-version artifact))))

(defn san-mauro-artifact? [artifact]
  (and (= 3 (:schema-version artifact))
       (= san-mauro/parser-version (:parser-version artifact))
       (contains? san-mauro/source-sha256s (:source-sha256 artifact))))

(defn san-mauro-static-artifact? [artifact]
  (and (= 3 (:schema-version artifact))
       (= san-mauro-static/parser-version (:parser-version artifact))
       (= san-mauro-static/source-sha256 (:source-sha256 artifact))))

(defn san-mauro-static-claim? [artifact]
  (or (= san-mauro-static/parser-version (:parser-version artifact))
      (= san-mauro-static/source-sha256 (:source-sha256 artifact))))

(defn tuttinapnea-artifact? [artifact]
  (and (= 3 (:schema-version artifact))
       (= tuttinapnea/parser-version (:parser-version artifact))
       (contains? tuttinapnea/source-sha256s (:source-sha256 artifact))))

(defn tuttinapnea-claim? [artifact]
  (or (= tuttinapnea/parser-version (:parser-version artifact))
      (contains? tuttinapnea/source-sha256s (:source-sha256 artifact))))

(defn tuttinapnea-2025-static-artifact? [artifact]
  (and (= 3 (:schema-version artifact))
       (= tuttinapnea-2025-static/parser-version (:parser-version artifact))
       (= tuttinapnea-2025-static/source-sha256 (:source-sha256 artifact))))

(defn tuttinapnea-2025-static-claim? [artifact]
  (or (= tuttinapnea-2025-static/parser-version (:parser-version artifact))
      (= tuttinapnea-2025-static/source-sha256 (:source-sha256 artifact))))

(defn tuttinapnea-2025-dynamic-artifact? [artifact]
  (and (= 3 (:schema-version artifact))
       (= tuttinapnea-2025-dynamic/parser-version (:parser-version artifact))
       (= tuttinapnea-2025-dynamic/source-sha256 (:source-sha256 artifact))))

(defn tuttinapnea-2025-dynamic-claim? [artifact]
  (or (= tuttinapnea-2025-dynamic/parser-version (:parser-version artifact))
      (= tuttinapnea-2025-dynamic/source-sha256 (:source-sha256 artifact))))

(defn world-games-artifact? [artifact]
  (and (= 3 (:schema-version artifact)) (= world-games/parser-version (:parser-version artifact))))

(defn world-games-series-artifact? [artifact]
  (and (= 3 (:schema-version artifact)) (= world-games-series/parser-version (:parser-version artifact))))

(defn kaohsiung-artifact? [artifact]
  (and (= 3 (:schema-version artifact)) (= kaohsiung/parser-version (:parser-version artifact))))

(defn lodz-artifact? [artifact]
  (and (= 3 (:schema-version artifact)) (= lodz/parser-version (:parser-version artifact))))

(defn lodz-2026-artifact? [artifact]
  (and (= 3 (:schema-version artifact)) (= lodz-2026/parser-version (:parser-version artifact))))

(defn unu-tampa-artifact? [artifact]
  (and (= 3 (:schema-version artifact)) (= unu-tampa/parser-version (:parser-version artifact))))

(defn noxy-artifact? [artifact]
  (and (= 3 (:schema-version artifact)) (= noxy/parser-version (:parser-version artifact))))

(defn deep-dominica-artifact? [artifact]
  (and (= 3 (:schema-version artifact)) (= deep-dominica/parser-version (:parser-version artifact))))

(defn belgrade-2026-artifact? [artifact]
  (and (= 3 (:schema-version artifact)) (= belgrade-2026/parser-version (:parser-version artifact))))

(defn deep-dominica-2026-artifact? [artifact]
  (and (= 3 (:schema-version artifact)) (= deep-dominica-2026/parser-version (:parser-version artifact))))

(defn vertical-blue-artifact? [artifact]
  (and (= 3 (:schema-version artifact)) (= vertical-blue/parser-version (:parser-version artifact))))

(defn ffessm-2025-day1-artifact? [artifact]
  (and (= 3 (:schema-version artifact)) (= ffessm-2025-day1/parser-version (:parser-version artifact))))

(defn ffessm-2025-day2-artifact? [artifact]
  (and (= 3 (:schema-version artifact)) (= ffessm-2025-day2/parser-version (:parser-version artifact))))

(defn ffessm-2025-monofin-artifact? [artifact]
  (and (= 3 (:schema-version artifact))
       (some? (ffessm-2025-monofin/parser-version (:source-sha256 artifact)))
       (= (ffessm-2025-monofin/parser-version (:source-sha256 artifact))
          (:parser-version artifact))))

(defn ffessm-2025-b18-artifact? [artifact]
  (let [sha (:source-sha256 artifact)
        version (some #(% sha) [ffessm-2025-bipalmes/parser-version
                                ffessm-2025-sans-palmes/parser-version
                                ffessm-2025-immersion-libre/parser-version])]
    (and (= 3 (:schema-version artifact)) (some? version)
         (= version (:parser-version artifact)))))

(defn ffessm-2026-artifact? [artifact]
  (and (= 3 (:schema-version artifact)) (= ffessm-2026/parser-version (:parser-version artifact))))

(defn ffessm-2026-men-artifact? [artifact]
  (and (= 3 (:schema-version artifact)) (= ffessm-2026-men/parser-version (:parser-version artifact))))

(defn ffessm-2026-bipalmes-women-artifact? [artifact]
  (and (= 3 (:schema-version artifact))
       (= ffessm-bipalmes-women/parser-version (:parser-version artifact))))

(defn ffessm-2026-regular-artifact? [artifact]
  (and (= 3 (:schema-version artifact))
       (some? (ffessm-regular/parser-version (:source-sha256 artifact)))
       (= (ffessm-regular/parser-version (:source-sha256 artifact))
          (:parser-version artifact))))

(defn ffessm-2026-final-artifact? [artifact]
  (and (= 3 (:schema-version artifact))
       (some? (ffessm-final/parser-version (:source-sha256 artifact)))
       (= (ffessm-final/parser-version (:source-sha256 artifact))
          (:parser-version artifact))))

(defn ffessm-2026-juniors-artifact? [artifact]
  (and (= 3 (:schema-version artifact))
       (= ffessm-juniors/source-sha256 (:source-sha256 artifact))
       (= ffessm-juniors/parser-version (:parser-version artifact))))

(defn fedas-artifact? [artifact]
  (and (= 3 (:schema-version artifact))
       (or (and (= fedas-indoor/source-2025-sha256 (:source-sha256 artifact))
                (= fedas-indoor/parser-version (:parser-version artifact)))
           (and (= fedas-indoor-2026/source-2026-sha256 (:source-sha256 artifact))
                (= fedas-indoor-2026/parser-version (:parser-version artifact)))
           (and (contains? #{fedas-outdoor/outdoor-2025-sha256 fedas-outdoor/outdoor-2026-sha256}
                           (:source-sha256 artifact))
                (= (fedas-outdoor/parser-version (:source-sha256 artifact))
                   (:parser-version artifact))))))

(defn fedas-claim? [artifact]
  (or (= fedas-indoor/source-2025-sha256 (:source-sha256 artifact))
      (= fedas-indoor-2026/source-2026-sha256 (:source-sha256 artifact))
      (= fedas-outdoor/outdoor-2026-sha256 (:source-sha256 artifact))
      (= fedas-outdoor/outdoor-2025-sha256 (:source-sha256 artifact))
      (str/starts-with? (or (:parser-version artifact) "") "fedas-")))

(defn vdst-artifact? [artifact]
  (and (= 3 (:schema-version artifact))
       (or (and (= vdst-neckar/source-sha256 (:source-sha256 artifact))
                (= vdst-neckar/parser-version (:parser-version artifact)))
           (and (= vdst-rhein-main/source-sha256 (:source-sha256 artifact))
                (= vdst-rhein-main/parser-version (:parser-version artifact)))
           (and (= vdst-chemnitz-2025/source-sha256 (:source-sha256 artifact))
                (= vdst-chemnitz-2025/parser-version (:parser-version artifact)))
           (and (= vdst-chemnitz-2026/source-sha256 (:source-sha256 artifact))
                (= vdst-chemnitz-2026/parser-version (:parser-version artifact))))))

(defn vdst-claim? [artifact]
  (or (contains? #{vdst-neckar/source-sha256 vdst-rhein-main/source-sha256
                   vdst-chemnitz-2025/source-sha256 vdst-chemnitz-2026/source-sha256}
                 (:source-sha256 artifact))
      (str/starts-with? (or (:parser-version artifact) "") "vdst-")))

(defn- athens-selected? [pages]
  (and (athens/supported? pages)
       (not-any? #(% pages) [depth/supported? depth-2025/supported? aida/supported?])))

(defn legacy-athens-artifact? [artifact]
  (and (= 3 (:schema-version artifact)) (= athens/parser-version (:parser-version artifact))))

(defn- indoor-selected? [pages]
  (and (indoor-2026/supported? pages)
       (not-any? #(% pages) [depth/supported? depth-2025/supported? aida/supported? athens/supported?])))

(defn requires-geometry-validation?
  "Recognize geometry artifacts even after identity or page-evidence downgrades.
   The archive-aware arity is required at the import trust boundary."
  ([artifact]
   (or (#{athens-geometry/parser-version depth-2025/geometry-parser-version depth-2026/parser-version indoor-2026/parser-version indoor-time/parser-version indoor-time/legacy-parser-version} (:parser-version artifact))
       (contains? artifact :geometry-xml) (contains? (:tool artifact) :geometry-arguments)
       (and (seq (:candidates artifact))
            (or (depth-2026-selected? (map :text (:pages artifact)))
                (and (indoor-selected? (map :text (:pages artifact))) (not (legacy-novi-artifact? artifact)))))))
  ([root artifact]
   (or (requires-geometry-validation? artifact)
       (when (and (not (legacy-novi-artifact? artifact)) (not (legacy-athens-artifact? artifact))
                  (not (croatia-open-artifact? artifact)) (not (italy-open-artifact? artifact)) (not (san-mauro-artifact? artifact))
                  (not (san-mauro-static-claim? artifact)) (not (tuttinapnea-claim? artifact))
                  (not (tuttinapnea-2025-static-claim? artifact))
                  (not (tuttinapnea-2025-dynamic-claim? artifact))
                  (not (world-games-artifact? artifact))
                  (not (world-games-series-artifact? artifact))
                  (not (kaohsiung-artifact? artifact))
                  (not (lodz-artifact? artifact))
                  (not (lodz-2026-artifact? artifact))
                  (not (unu-tampa-artifact? artifact))
                  (not (noxy-artifact? artifact))
                  (not (deep-dominica-artifact? artifact))
                  (not (belgrade-2026-artifact? artifact))
                  (not (deep-dominica-2026-artifact? artifact))
                  (not (vertical-blue-artifact? artifact))
                  (not (ffessm-2025-day1-artifact? artifact))
                  (not (ffessm-2025-day2-artifact? artifact))
                  (not (ffessm-2025-monofin-artifact? artifact))
                  (not (ffessm-2026-artifact? artifact))
                  (not (ffessm-2026-men-artifact? artifact))
                  (not (ffessm-2026-bipalmes-women-artifact? artifact))
                  (not (ffessm-2026-regular-artifact? artifact))
                  (not (ffessm-2026-final-artifact? artifact))
                  (not (ffessm-2026-juniors-artifact? artifact)))
         (let [source (archive/inspect root (:source-sha256 artifact))
               raw (:out (command! "pdftotext" "-layout" "-enc" "UTF-8" (:artifact-path source) "-"))
               pages (str/split raw #"\f" -1)]
           (or (athens-selected? pages) (indoor-selected? pages) (depth-2026-selected? pages)))))))

(defn extract!
  "Extract registered PDF to private versioned EDN. Config is retained verbatim as
   processing metadata; only layout UTF-8 extraction and this parser are implemented.
   All outputs require owner review and cannot authorize publication."
  ([root sha256 options] (extract! root sha256 options {}))
  ([root sha256 {:keys [actor config] :as options} {:keys [on-progress]}]
   (when-not (and (= #{:actor :config} (set (keys options)))
                  (string? actor) (not (str/blank? actor)) (map? config))
     (throw (ex-info "Expected {:actor nonblank-string :config map}" {})))
   (let [source (archive/inspect root sha256)
         _ (when (empty? (:acquisitions source)) (throw (ex-info "PDF lacks acquisition evidence" {})))
         evidence (archive/extraction-evidence root)
         tool-version (str/trim (:err (command! "pdftotext" "-v")))
         result (command! "pdftotext" "-layout" "-enc" "UTF-8" (:artifact-path source) "-")
         raw (:out result)
         segments (str/split raw #"\f" -1)
         pages (if (and (> (count segments) 1) (= "" (last segments))) (pop (vec segments)) (vec segments))
         vdst? (contains? #{vdst-neckar/source-sha256 vdst-rhein-main/source-sha256
                            vdst-chemnitz-2025/source-sha256 vdst-chemnitz-2026/source-sha256} sha256)
         fedas-indoor? (contains? #{fedas-indoor/source-2025-sha256
                                    fedas-indoor-2026/source-2026-sha256} sha256)
         fedas-outdoor? (contains? #{fedas-outdoor/outdoor-2025-sha256
                                     fedas-outdoor/outdoor-2026-sha256} sha256)
         depth? (depth/supported? pages)
         depth-2025? (and (not depth?) (depth-2025/supported? pages))
         aida? (aida/supported? pages)
         athens? (athens-selected? pages)
         indoor? (and (not (or depth? depth-2025? aida? athens?)) (indoor-2026/supported? pages))
         indoor-time? (and indoor? (indoor-time/supported? pages))
         novi? (novi-sad/supported? pages)
         croatia? (croatia-open/supported? pages)
         italy? (italy-open/supported? pages)
         san-mauro? (san-mauro/supported? pages)
         san-mauro-static? (san-mauro-static/supported? pages)
         tuttinapnea? (tuttinapnea/supported? pages)
         tuttinapnea-2025-static? (tuttinapnea-2025-static/supported? pages)
         tuttinapnea-2025-dynamic? (tuttinapnea-2025-dynamic/supported? pages)
         world-games? (world-games/supported? pages)
         world-games-series? (world-games-series/supported? pages)
         kaohsiung? (kaohsiung/supported? pages)
         lodz? (lodz/supported? pages)
         lodz-2026? (lodz-2026/supported? pages)
         unu-tampa? (unu-tampa/supported? pages)
         noxy? (noxy/supported? pages)
         deep-dominica? (deep-dominica/supported? pages)
         belgrade-2026? (belgrade-2026/supported? pages)
         deep-dominica-2026? (deep-dominica-2026/supported? pages)
         vertical-blue? (vertical-blue/supported? pages)
         ffessm-2025-day1? (ffessm-2025-day1/supported? pages)
         ffessm-2026? (ffessm-2026/supported? pages)
         ffessm-new? (or (some? (ffessm-2025-monofin/matching-sha pages))
                         (some? (ffessm-2025-bipalmes/matching-sha pages))
                         (some? (ffessm-2025-sans-palmes/matching-sha pages))
                         (some? (ffessm-2025-immersion-libre/matching-sha pages))
                         (ffessm-bipalmes-women/supported? pages)
                         (ffessm-regular/supported? ffessm-regular/men-sha256 pages)
                         (ffessm-regular/supported? ffessm-regular/women-sha256 pages)
                         (some #(ffessm-final/supported? % pages)
                               [ffessm-final/cnf-men-sha256 ffessm-final/fim-women-sha256
                                ffessm-final/fim-men-sha256])
                         (ffessm-juniors/supported? pages))
         ffessm-new (when ffessm-new? (parse-pages sha256 pages))
         _ (when (and italy? (not= sha256 italy-open/source-sha256))
             (throw (ex-info "Italian Open parser is bound to a different source PDF" {})))
         _ (when (and san-mauro? (not san-mauro-static?)
                      (not (contains? san-mauro/source-sha256s sha256)))
             (throw (ex-info "San Mauro parser is bound to different source PDFs" {})))
         _ (when (and san-mauro-static? (not= san-mauro-static/source-sha256 sha256))
             (throw (ex-info "San Mauro static parser is bound to a different source PDF" {})))
         _ (when (and tuttinapnea? (not (contains? tuttinapnea/source-sha256s sha256)))
             (throw (ex-info "TuttinApnea parser is bound to different source PDFs" {})))
         _ (when (and tuttinapnea-2025-static?
                      (not= sha256 tuttinapnea-2025-static/source-sha256))
             (throw (ex-info "TuttinApnea January static parser is bound to a different source PDF" {})))
         _ (when (and tuttinapnea-2025-dynamic?
                      (not= sha256 tuttinapnea-2025-dynamic/source-sha256))
             (throw (ex-info "TuttinApnea January dynamic parser is bound to a different source PDF" {})))
         _ (when (and world-games? (not= sha256 world-games/source-sha256))
             (throw (ex-info "World Games parser is bound to a different source PDF" {})))
         _ (when (and world-games-series? (not= sha256 world-games-series/source-sha256))
             (throw (ex-info "World Games Series parser is bound to a different source PDF" {})))
         _ (when (and kaohsiung? (not= sha256 kaohsiung/source-sha256))
             (throw (ex-info "Kaohsiung parser is bound to a different source PDF" {})))
         _ (when (and lodz? (not= sha256 lodz/source-sha256))
             (throw (ex-info "Łódź parser is bound to a different source PDF" {})))
         _ (when (and lodz-2026? (not= sha256 lodz-2026/source-sha256))
             (throw (ex-info "Łódź 2026 parser is bound to a different source PDF" {})))
         _ (when (and unu-tampa? (not= sha256 unu-tampa/source-sha256))
             (throw (ex-info "UNU Tampa parser is bound to a different source PDF" {})))
         _ (when (and noxy? (not= sha256 noxy/source-sha256))
             (throw (ex-info "nOxyCup parser is bound to a different source PDF" {})))
         _ (when (and deep-dominica? (not= sha256 deep-dominica/source-sha256))
             (throw (ex-info "Deep Dominica parser is bound to a different source PDF" {})))
         _ (when (and belgrade-2026? (not= sha256 belgrade-2026/source-sha256))
             (throw (ex-info "Belgrade 2026 parser is bound to a different source PDF" {})))
         _ (when (and deep-dominica-2026? (not= sha256 deep-dominica-2026/source-sha256))
             (throw (ex-info "Deep Dominica 2026 parser is bound to a different source PDF" {})))
         _ (when (and vertical-blue? (not= sha256 vertical-blue/source-sha256))
             (throw (ex-info "Vertical Blue parser is bound to a different source PDF" {})))
         _ (when (and ffessm-2025-day1? (not= sha256 ffessm-2025-day1/source-sha256))
             (throw (ex-info "French 2025 day-one parser is bound to a different source PDF" {})))
         ffessm-2025-day2? (ffessm-2025-day2/supported? pages)
         _ (when (and ffessm-2025-day2? (not= sha256 ffessm-2025-day2/source-sha256))
             (throw (ex-info "French 2025 day-two parser is bound to a different source PDF" {})))
         _ (when (and ffessm-2026? (not= sha256 ffessm-2026/source-sha256))
             (throw (ex-info "French 2026 parser is bound to a different source PDF" {})))
         ffessm-2026-men? (ffessm-2026-men/supported? pages)
         _ (when (and ffessm-2026-men? (not= sha256 ffessm-2026-men/source-sha256))
             (throw (ex-info "French 2026 men parser is bound to a different source PDF" {})))
         depth-2026? (depth-2026-selected? pages)
         identity {:source-sha256 sha256 :acquisitions (:acquisitions source)
                   :evidence-sha256 evidence :actor actor :config config
                   :parser-version (cond (= sha256 vdst-neckar/source-sha256) vdst-neckar/parser-version (= sha256 vdst-rhein-main/source-sha256) vdst-rhein-main/parser-version (= sha256 vdst-chemnitz-2025/source-sha256) vdst-chemnitz-2025/parser-version (= sha256 vdst-chemnitz-2026/source-sha256) vdst-chemnitz-2026/parser-version (= sha256 fedas-indoor/source-2025-sha256) fedas-indoor/parser-version (= sha256 fedas-indoor-2026/source-2026-sha256) fedas-indoor-2026/parser-version fedas-outdoor? (fedas-outdoor/parser-version sha256) ffessm-new? (:parser-version ffessm-new) ffessm-2025-day2? ffessm-2025-day2/parser-version ffessm-2026-men? ffessm-2026-men/parser-version indoor-time? indoor-time/parser-version indoor? indoor-2026/parser-version depth-2026? depth-2026/parser-version depth? depth/parser-version depth-2025? depth-2025/geometry-parser-version aida? aida/parser-version athens? athens-geometry/parser-version novi? novi-sad/parser-version croatia? croatia-open/parser-version italy? italy-open/parser-version san-mauro-static? san-mauro-static/parser-version tuttinapnea? tuttinapnea/parser-version tuttinapnea-2025-static? tuttinapnea-2025-static/parser-version tuttinapnea-2025-dynamic? tuttinapnea-2025-dynamic/parser-version san-mauro? san-mauro/parser-version world-games? world-games/parser-version world-games-series? world-games-series/parser-version kaohsiung? kaohsiung/parser-version lodz? lodz/parser-version lodz-2026? lodz-2026/parser-version unu-tampa? unu-tampa/parser-version noxy? noxy/parser-version deep-dominica? deep-dominica/parser-version belgrade-2026? belgrade-2026/parser-version deep-dominica-2026? deep-dominica-2026/parser-version vertical-blue? vertical-blue/parser-version ffessm-2025-day1? ffessm-2025-day1/parser-version ffessm-2026? ffessm-2026/parser-version :else parser-version)
                   :schema-version (cond vdst? 3 (or fedas-indoor? fedas-outdoor?) 3 ffessm-new? 3 ffessm-2025-day2? 3 ffessm-2026-men? 3 indoor? 2 depth-2026? 2 depth? 2 depth-2025? 2 aida? 2 athens? 3 novi? 3 croatia? 3 italy? 3 san-mauro-static? 3 tuttinapnea? 3 tuttinapnea-2025-static? 3 tuttinapnea-2025-dynamic? 3 san-mauro? 3 world-games? 3 world-games-series? 3 kaohsiung? 3 lodz? 3 lodz-2026? 3 unu-tampa? 3 noxy? 3 deep-dominica? 3 belgrade-2026? 3 deep-dominica-2026? 3 vertical-blue? 3 ffessm-2025-day1? 3 ffessm-2026? 3 :else 1)
                   :pdfinfo-version (str/trim (:err (command! "pdfinfo" "-v")))
                   :tool (cond-> {:name "pdftotext" :version tool-version :arguments ["-layout" "-enc" "UTF-8"]}
                           (or athens? indoor? depth-2025? depth-2026?) (assoc :geometry-arguments ["-bbox-layout" "-enc" "UTF-8"]))}
         job-id (digest identity)]
     (archive/derive! root job-id
                      (fn []
                        (let [info (:out (command! "pdfinfo" (:artifact-path source)))
                              page-count (some-> (re-find #"(?m)^Pages:\s+(\d+)\s*$" info) second parse-long)]
                          (when-not (= page-count (count pages))
                            (throw (ex-info "Extracted page count does not match PDF" {:expected page-count :actual (count pages)})))
                          (merge (if (or athens? indoor? depth-2025? depth-2026?)
                                   ((cond athens? athens-geometry/parse-pages-with-geometry indoor-time? indoor-time/parse-pages-with-geometry indoor? indoor-2026/parse-pages-with-geometry depth-2026? depth-2026/parse-pages-with-geometry :else depth-2025/parse-pages-with-geometry) pages (:out (command! "pdftotext" "-bbox-layout" "-enc" "UTF-8" (:artifact-path source) "-")))
                                   (or ffessm-new (parse-pages sha256 pages))) identity
                                 {:job-id job-id :processed-at (str (java.time.Instant/now))
                                  :raw-text raw :tool-stderr (:err result)
                                  :pdf-page-count page-count}))) on-progress))))

(defn validate-geometry-artifact!
  "Replay versioned geometry and layout from the hash-verified archived PDF before import.
   Legacy PDF contracts are deliberately not reinterpreted by this validator."
  [root artifact]
  (when-not (and (= (if (= athens-geometry/parser-version (:parser-version artifact)) 3 2) (:schema-version artifact))
                 (#{athens-geometry/parser-version depth-2025/geometry-parser-version depth-2026/parser-version indoor-2026/parser-version indoor-time/parser-version indoor-time/legacy-parser-version} (:parser-version artifact))
                 (= "pdftotext" (get-in artifact [:tool :name]))
                 (= ["-layout" "-enc" "UTF-8"] (get-in artifact [:tool :arguments]))
                 (= ["-bbox-layout" "-enc" "UTF-8"] (get-in artifact [:tool :geometry-arguments])))
    (throw (ex-info "Invalid geometry extraction contract" {})))
  (let [source (archive/inspect root (:source-sha256 artifact))
        version (str/trim (:err (command! "pdftotext" "-v")))
        raw (:out (command! "pdftotext" "-layout" "-enc" "UTF-8" (:artifact-path source) "-"))
        xml (:out (command! "pdftotext" "-bbox-layout" "-enc" "UTF-8" (:artifact-path source) "-"))
        segments (vec (str/split raw #"\f" -1))
        pages (if (= "" (last segments)) (pop segments) segments)
        replay ((cond (= athens-geometry/parser-version (:parser-version artifact)) athens-geometry/parse-pages-with-geometry (= indoor-time/legacy-parser-version (:parser-version artifact)) indoor-time/parse-legacy-pages-with-geometry (= indoor-time/parser-version (:parser-version artifact)) indoor-time/parse-pages-with-geometry (= indoor-2026/parser-version (:parser-version artifact)) indoor-2026/parse-pages-with-geometry (= depth-2026/parser-version (:parser-version artifact)) depth-2026/parse-pages-with-geometry :else depth-2025/parse-pages-with-geometry) pages xml)]
    (when-not (and (= version (get-in artifact [:tool :version]))
                   (= raw (:raw-text artifact))
                   (= replay (select-keys artifact (keys replay))))
      (throw (ex-info "Geometry extraction differs from archived source replay" {})))
    artifact))

(defn validate-legacy-athens-artifact!
  "Replay the preserved /6 parser. Earlier stored bytes remain immutable, but
   versions without an executable historical parser cannot bypass /7 replay."
  [root artifact]
  (let [source (archive/inspect root (:source-sha256 artifact))
        raw (:out (command! "pdftotext" "-layout" "-enc" "UTF-8" (:artifact-path source) "-"))
        segments (vec (str/split raw #"\f" -1))
        pages (if (= "" (last segments)) (pop segments) segments)
        replay (athens/parse-pages pages)]
    (when-not (and (legacy-athens-artifact? artifact) (= raw (:raw-text artifact))
                   (= replay (select-keys artifact (keys replay))))
      (throw (ex-info "Legacy Athens extraction differs from archived source replay" {})))
    artifact))

(defn validate-legacy-novi-artifact!
  "Keep the immutable junior-only parser usable while rejecting identity downgrades."
  [root artifact]
  (let [source (archive/inspect root (:source-sha256 artifact))
        raw (:out (command! "pdftotext" "-layout" "-enc" "UTF-8" (:artifact-path source) "-"))
        segments (vec (str/split raw #"\f" -1))
        pages (if (= "" (last segments)) (pop segments) segments)
        replay (novi-sad/parse-pages pages)]
    (when-not (and (legacy-novi-artifact? artifact)
                   (= raw (:raw-text artifact))
                   (= replay (select-keys artifact (keys replay))))
      (throw (ex-info "Legacy Novi Sad extraction differs from archived source replay" {})))
    artifact))

(defn validate-croatia-open-artifact!
  "Replay this source-bound parser against the registered PDF before import."
  [root artifact]
  (let [source (archive/inspect root (:source-sha256 artifact))
        raw (:out (command! "pdftotext" "-layout" "-enc" "UTF-8" (:artifact-path source) "-"))
        segments (vec (str/split raw #"\f" -1))
        pages (if (= "" (last segments)) (pop segments) segments)
        replay (croatia-open/parse-pages pages)]
    (when-not (and (croatia-open-artifact? artifact)
                   (= raw (:raw-text artifact))
                   (= replay (select-keys artifact (keys replay))))
      (throw (ex-info "Croatian Open extraction differs from archived source replay" {})))
    artifact))

(defn- validate-ffessm-artifact! [root artifact parser artifact? source-sha256 label]
  (let [source (archive/inspect root (:source-sha256 artifact))
        raw (:out (command! "pdftotext" "-layout" "-enc" "UTF-8" (:artifact-path source) "-"))
        segments (vec (str/split raw #"\f" -1))
        pages (if (= "" (last segments)) (pop segments) segments)
        replay (parser pages)]
    (when-not (and (artifact? artifact)
                   (= source-sha256 (:source-sha256 artifact))
                   (= "pdftotext" (get-in artifact [:tool :name]))
                   (= ["-layout" "-enc" "UTF-8"] (get-in artifact [:tool :arguments]))
                   (= (str/trim (:err (command! "pdftotext" "-v"))) (get-in artifact [:tool :version]))
                   (= raw (:raw-text artifact))
                   (= replay (select-keys artifact (keys replay))))
      (throw (ex-info (str label " extraction differs from archived source replay") {})))
    artifact))

(defn validate-fedas-artifact! [root artifact]
  (let [sha (:source-sha256 artifact)
        parser (cond (= sha fedas-indoor/source-2025-sha256) fedas-indoor/parse-pages
                     (= sha fedas-indoor-2026/source-2026-sha256) fedas-indoor-2026/parse-pages
                     (contains? #{fedas-outdoor/outdoor-2025-sha256
                                  fedas-outdoor/outdoor-2026-sha256} sha) fedas-outdoor/parse-pages)]
    (when-not parser
      (throw (ex-info "FEDAS extraction differs from archived source replay" {})))
    (validate-ffessm-artifact! root artifact (partial parser sha)
                               fedas-artifact? sha "FEDAS")))

(defn validate-vdst-artifact! [root artifact]
  (let [sha (:source-sha256 artifact)
        parser (cond (= sha vdst-neckar/source-sha256) vdst-neckar/parse-pages
                     (= sha vdst-rhein-main/source-sha256) vdst-rhein-main/parse-pages
                     (= sha vdst-chemnitz-2025/source-sha256) vdst-chemnitz-2025/parse-pages
                     (= sha vdst-chemnitz-2026/source-sha256) vdst-chemnitz-2026/parse-pages)]
    (when-not (and parser (vdst-artifact? artifact))
      (throw (ex-info "VDST extraction differs from archived source replay" {})))
    (validate-ffessm-artifact! root artifact (partial parser sha)
                               vdst-artifact? sha "VDST")))

(defn validate-ffessm-2025-day1-artifact! [root artifact]
  (validate-ffessm-artifact! root artifact ffessm-2025-day1/parse-pages
                             ffessm-2025-day1-artifact? ffessm-2025-day1/source-sha256
                             "French 2025 day-one"))

(defn validate-ffessm-2025-day2-artifact! [root artifact]
  (validate-ffessm-artifact! root artifact ffessm-2025-day2/parse-pages
                             ffessm-2025-day2-artifact? ffessm-2025-day2/source-sha256
                             "French 2025 day-two"))

(defn validate-ffessm-2025-monofin-artifact! [root artifact]
  (validate-ffessm-artifact! root artifact
                             (partial ffessm-2025-monofin/parse-pages (:source-sha256 artifact))
                             ffessm-2025-monofin-artifact?
                             (:source-sha256 artifact)
                             "French 2025 monofin"))

(defn validate-ffessm-2025-b18-artifact! [root artifact]
  (let [sha (:source-sha256 artifact)
        parser (cond (ffessm-2025-bipalmes/parser-version sha) ffessm-2025-bipalmes/parse-pages
                     (ffessm-2025-sans-palmes/parser-version sha) ffessm-2025-sans-palmes/parse-pages
                     :else ffessm-2025-immersion-libre/parse-pages)]
    (validate-ffessm-artifact! root artifact (partial parser sha)
                               ffessm-2025-b18-artifact? sha "French 2025 category")))

(defn validate-ffessm-2026-artifact! [root artifact]
  (validate-ffessm-artifact! root artifact ffessm-2026/parse-pages
                             ffessm-2026-artifact? ffessm-2026/source-sha256
                             "French 2026"))

(defn validate-ffessm-2026-men-artifact! [root artifact]
  (validate-ffessm-artifact! root artifact ffessm-2026-men/parse-pages
                             ffessm-2026-men-artifact? ffessm-2026-men/source-sha256
                             "French 2026 men"))

(defn validate-ffessm-2026-bipalmes-women-artifact! [root artifact]
  (validate-ffessm-artifact! root artifact ffessm-bipalmes-women/parse-pages
                             ffessm-2026-bipalmes-women-artifact?
                             ffessm-bipalmes-women/source-sha256
                             "French 2026 bifins women"))

(defn validate-ffessm-2026-regular-artifact! [root artifact]
  (validate-ffessm-artifact! root artifact
                             (partial ffessm-regular/parse-pages (:source-sha256 artifact))
                             ffessm-2026-regular-artifact?
                             (:source-sha256 artifact)
                             "French 2026 regular category"))

(defn validate-ffessm-2026-final-artifact! [root artifact]
  (validate-ffessm-artifact! root artifact
                             (partial ffessm-final/parse-pages (:source-sha256 artifact))
                             ffessm-2026-final-artifact?
                             (:source-sha256 artifact)
                             "French 2026 final category"))

(defn validate-ffessm-2026-juniors-artifact! [root artifact]
  (validate-ffessm-artifact! root artifact ffessm-juniors/parse-pages
                             ffessm-2026-juniors-artifact?
                             ffessm-juniors/source-sha256
                             "French 2026 juniors"))

(defn validate-san-mauro-artifact!
  "Replay the immutable source-bound result sheet before import."
  [root artifact]
  (let [source (archive/inspect root (:source-sha256 artifact))
        raw (:out (command! "pdftotext" "-layout" "-enc" "UTF-8" (:artifact-path source) "-"))
        segments (vec (str/split raw #"\f" -1))
        pages (if (= "" (last segments)) (pop segments) segments)
        replay (san-mauro/parse-pages (:source-sha256 artifact) pages)]
    (when-not (and (san-mauro-artifact? artifact)
                   (= "pdftotext" (get-in artifact [:tool :name]))
                   (= ["-layout" "-enc" "UTF-8"] (get-in artifact [:tool :arguments]))
                   (= (str/trim (:err (command! "pdftotext" "-v"))) (get-in artifact [:tool :version]))
                   (= raw (:raw-text artifact))
                   (= replay (select-keys artifact (keys replay))))
      (throw (ex-info "San Mauro extraction differs from archived source replay" {})))
    artifact))

(defn- validate-source-bound-apnea-artifact!
  [root artifact artifact? parser label]
  (let [source (archive/inspect root (:source-sha256 artifact))
        raw (:out (command! "pdftotext" "-layout" "-enc" "UTF-8" (:artifact-path source) "-"))
        segments (vec (str/split raw #"\f" -1))
        pages (if (= "" (last segments)) (pop segments) segments)
        replay (parser (:source-sha256 artifact) pages)]
    (when-not (and (artifact? artifact)
                   (= "pdftotext" (get-in artifact [:tool :name]))
                   (= ["-layout" "-enc" "UTF-8"] (get-in artifact [:tool :arguments]))
                   (= (str/trim (:err (command! "pdftotext" "-v"))) (get-in artifact [:tool :version]))
                   (= raw (:raw-text artifact))
                   (= replay (select-keys artifact (keys replay))))
      (throw (ex-info (str label " extraction differs from archived source replay") {})))
    artifact))

(defn validate-san-mauro-static-artifact! [root artifact]
  (validate-source-bound-apnea-artifact! root artifact san-mauro-static-artifact?
                                         san-mauro-static/parse-pages "San Mauro static"))

(defn validate-tuttinapnea-artifact! [root artifact]
  (validate-source-bound-apnea-artifact! root artifact tuttinapnea-artifact?
                                         tuttinapnea/parse-pages "TuttinApnea"))

(defn validate-tuttinapnea-2025-static-artifact! [root artifact]
  (validate-source-bound-apnea-artifact!
   root artifact tuttinapnea-2025-static-artifact?
   tuttinapnea-2025-static/parse-pages "TuttinApnea January static"))

(defn validate-tuttinapnea-2025-dynamic-artifact! [root artifact]
  (validate-source-bound-apnea-artifact!
   root artifact tuttinapnea-2025-dynamic-artifact?
   tuttinapnea-2025-dynamic/parse-pages "TuttinApnea January dynamic"))

(defn validate-italy-open-artifact!
  "Replay source-bound text and reconciliation against the registered PDF before import."
  [root artifact]
  (let [source (archive/inspect root (:source-sha256 artifact))
        raw (:out (command! "pdftotext" "-layout" "-enc" "UTF-8" (:artifact-path source) "-"))
        segments (vec (str/split raw #"\f" -1))
        pages (if (= "" (last segments)) (pop segments) segments)
        replay (italy-open/parse-pages pages)]
    (when-not (and (italy-open-artifact? artifact)
                   (= italy-open/source-sha256 (:source-sha256 artifact))
                   (= "pdftotext" (get-in artifact [:tool :name]))
                   (= ["-layout" "-enc" "UTF-8"] (get-in artifact [:tool :arguments]))
                   (= (str/trim (:err (command! "pdftotext" "-v"))) (get-in artifact [:tool :version]))
                   (= raw (:raw-text artifact))
                   (= replay (select-keys artifact (keys replay))))
      (throw (ex-info "Italian Open extraction differs from archived source replay" {})))
    artifact))

(defn validate-world-games-artifact!
  "Replay the source-bound World Games layout before import."
  [root artifact]
  (let [source (archive/inspect root (:source-sha256 artifact))
        raw (:out (command! "pdftotext" "-layout" "-enc" "UTF-8" (:artifact-path source) "-"))
        segments (vec (str/split raw #"\f" -1))
        pages (if (= "" (last segments)) (pop segments) segments)
        replay (world-games/parse-pages pages)]
    (when-not (and (world-games-artifact? artifact)
                   (= world-games/source-sha256 (:source-sha256 artifact))
                   (= "pdftotext" (get-in artifact [:tool :name]))
                   (= ["-layout" "-enc" "UTF-8"] (get-in artifact [:tool :arguments]))
                   (= (str/trim (:err (command! "pdftotext" "-v"))) (get-in artifact [:tool :version]))
                   (= raw (:raw-text artifact))
                   (= replay (select-keys artifact (keys replay))))
      (throw (ex-info "World Games extraction differs from archived source replay" {})))
    artifact))

(defn validate-world-games-series-artifact!
  "Replay the source-bound Series heat rows before import."
  [root artifact]
  (let [source (archive/inspect root (:source-sha256 artifact))
        raw (:out (command! "pdftotext" "-layout" "-enc" "UTF-8" (:artifact-path source) "-"))
        segments (vec (str/split raw #"\f" -1))
        pages (if (= "" (last segments)) (pop segments) segments)
        replay (world-games-series/parse-pages pages)]
    (when-not (and (world-games-series-artifact? artifact)
                   (= world-games-series/source-sha256 (:source-sha256 artifact))
                   (= "pdftotext" (get-in artifact [:tool :name]))
                   (= ["-layout" "-enc" "UTF-8"] (get-in artifact [:tool :arguments]))
                   (= (str/trim (:err (command! "pdftotext" "-v"))) (get-in artifact [:tool :version]))
                   (= raw (:raw-text artifact))
                   (= replay (select-keys artifact (keys replay))))
      (throw (ex-info "World Games Series extraction differs from archived source replay" {})))
    artifact))

(defn validate-kaohsiung-artifact!
  "Replay the source-bound Kaohsiung attempt rows before import."
  [root artifact]
  (let [source (archive/inspect root (:source-sha256 artifact))
        raw (:out (command! "pdftotext" "-layout" "-enc" "UTF-8" (:artifact-path source) "-"))
        segments (vec (str/split raw #"\f" -1))
        pages (if (= "" (last segments)) (pop segments) segments)
        replay (kaohsiung/parse-pages pages)]
    (when-not (and (kaohsiung-artifact? artifact)
                   (= kaohsiung/source-sha256 (:source-sha256 artifact))
                   (= "pdftotext" (get-in artifact [:tool :name]))
                   (= ["-layout" "-enc" "UTF-8"] (get-in artifact [:tool :arguments]))
                   (= (str/trim (:err (command! "pdftotext" "-v"))) (get-in artifact [:tool :version]))
                   (= raw (:raw-text artifact))
                   (= replay (select-keys artifact (keys replay))))
      (throw (ex-info "Kaohsiung extraction differs from archived source replay" {})))
    artifact))

(defn validate-lodz-artifact!
  "Replay the source-bound Łódź attempt rows before import."
  [root artifact]
  (let [source (archive/inspect root (:source-sha256 artifact))
        raw (:out (command! "pdftotext" "-layout" "-enc" "UTF-8" (:artifact-path source) "-"))
        segments (vec (str/split raw #"\f" -1))
        pages (if (= "" (last segments)) (pop segments) segments)
        replay (lodz/parse-pages pages)]
    (when-not (and (lodz-artifact? artifact)
                   (= lodz/source-sha256 (:source-sha256 artifact))
                   (= "pdftotext" (get-in artifact [:tool :name]))
                   (= ["-layout" "-enc" "UTF-8"] (get-in artifact [:tool :arguments]))
                   (= (str/trim (:err (command! "pdftotext" "-v"))) (get-in artifact [:tool :version]))
                   (= raw (:raw-text artifact))
                   (= replay (select-keys artifact (keys replay))))
      (throw (ex-info "Łódź extraction differs from archived source replay" {})))
    artifact))

(defn validate-lodz-2026-artifact!
  "Replay source-bound 2026 Łódź positions before import."
  [root artifact]
  (let [source (archive/inspect root (:source-sha256 artifact))
        raw (:out (command! "pdftotext" "-layout" "-enc" "UTF-8" (:artifact-path source) "-"))
        segments (vec (str/split raw #"\f" -1))
        pages (if (= "" (last segments)) (pop segments) segments)
        replay (lodz-2026/parse-pages pages)]
    (when-not (and (lodz-2026-artifact? artifact)
                   (= lodz-2026/source-sha256 (:source-sha256 artifact))
                   (= "pdftotext" (get-in artifact [:tool :name]))
                   (= ["-layout" "-enc" "UTF-8"] (get-in artifact [:tool :arguments]))
                   (= (str/trim (:err (command! "pdftotext" "-v"))) (get-in artifact [:tool :version]))
                   (= raw (:raw-text artifact))
                   (= replay (select-keys artifact (keys replay))))
      (throw (ex-info "Łódź 2026 extraction differs from archived source replay" {})))
    artifact))

(defn validate-unu-tampa-artifact!
  "Replay source-bound UNU Tampa results against the registered PDF."
  [root artifact]
  (let [source (archive/inspect root (:source-sha256 artifact))
        raw (:out (command! "pdftotext" "-layout" "-enc" "UTF-8" (:artifact-path source) "-"))
        segments (vec (str/split raw #"\f" -1))
        pages (if (= "" (last segments)) (pop segments) segments)
        replay (unu-tampa/parse-pages pages)]
    (when-not (and (unu-tampa-artifact? artifact)
                   (= unu-tampa/source-sha256 (:source-sha256 artifact))
                   (= "pdftotext" (get-in artifact [:tool :name]))
                   (= ["-layout" "-enc" "UTF-8"] (get-in artifact [:tool :arguments]))
                   (= (str/trim (:err (command! "pdftotext" "-v"))) (get-in artifact [:tool :version]))
                   (= raw (:raw-text artifact))
                   (= replay (select-keys artifact (keys replay))))
      (throw (ex-info "UNU Tampa extraction differs from archived source replay" {})))
    artifact))

(defn validate-noxy-artifact!
  "Replay source-bound nOxyCup rows against the registered PDF."
  [root artifact]
  (let [source (archive/inspect root (:source-sha256 artifact))
        raw (:out (command! "pdftotext" "-layout" "-enc" "UTF-8" (:artifact-path source) "-"))
        segments (vec (str/split raw #"\f" -1))
        pages (if (= "" (last segments)) (pop segments) segments)
        replay (noxy/parse-pages pages)]
    (when-not (and (noxy-artifact? artifact)
                   (= noxy/source-sha256 (:source-sha256 artifact))
                   (= "pdftotext" (get-in artifact [:tool :name]))
                   (= ["-layout" "-enc" "UTF-8"] (get-in artifact [:tool :arguments]))
                   (= (str/trim (:err (command! "pdftotext" "-v"))) (get-in artifact [:tool :version]))
                   (= raw (:raw-text artifact))
                   (= replay (select-keys artifact (keys replay))))
      (throw (ex-info "nOxyCup extraction differs from archived source replay" {})))
    artifact))

(defn validate-deep-dominica-artifact!
  "Replay source-bound Deep Dominica rows against the registered PDF."
  [root artifact]
  (let [source (archive/inspect root (:source-sha256 artifact))
        raw (:out (command! "pdftotext" "-layout" "-enc" "UTF-8" (:artifact-path source) "-"))
        segments (vec (str/split raw #"\f" -1))
        pages (if (= "" (last segments)) (pop segments) segments)
        replay (deep-dominica/parse-pages pages)]
    (when-not (and (deep-dominica-artifact? artifact)
                   (= deep-dominica/source-sha256 (:source-sha256 artifact))
                   (= "pdftotext" (get-in artifact [:tool :name]))
                   (= ["-layout" "-enc" "UTF-8"] (get-in artifact [:tool :arguments]))
                   (= (str/trim (:err (command! "pdftotext" "-v"))) (get-in artifact [:tool :version]))
                   (= raw (:raw-text artifact))
                   (= replay (select-keys artifact (keys replay))))
      (throw (ex-info "Deep Dominica extraction differs from archived source replay" {})))
    artifact))

(defn validate-belgrade-2026-artifact!
  "Replay source-bound Belgrade 2026 rows against the registered PDF."
  [root artifact]
  (let [source (archive/inspect root (:source-sha256 artifact))
        raw (:out (command! "pdftotext" "-layout" "-enc" "UTF-8" (:artifact-path source) "-"))
        segments (vec (str/split raw #"\f" -1))
        pages (if (= "" (last segments)) (pop segments) segments)
        replay (belgrade-2026/parse-pages pages)]
    (when-not (and (belgrade-2026-artifact? artifact)
                   (= belgrade-2026/source-sha256 (:source-sha256 artifact))
                   (= "pdftotext" (get-in artifact [:tool :name]))
                   (= ["-layout" "-enc" "UTF-8"] (get-in artifact [:tool :arguments]))
                   (= (str/trim (:err (command! "pdftotext" "-v"))) (get-in artifact [:tool :version]))
                   (= raw (:raw-text artifact))
                   (= replay (select-keys artifact (keys replay))))
      (throw (ex-info "Belgrade 2026 extraction differs from archived source replay" {})))
    artifact))

(defn validate-deep-dominica-2026-artifact!
  "Replay source-bound Deep Dominica 2026 rows against the registered PDF."
  [root artifact]
  (let [source (archive/inspect root (:source-sha256 artifact))
        raw (:out (command! "pdftotext" "-layout" "-enc" "UTF-8" (:artifact-path source) "-"))
        segments (vec (str/split raw #"\f" -1))
        pages (if (= "" (last segments)) (pop segments) segments)
        replay (deep-dominica-2026/parse-pages pages)]
    (when-not (and (deep-dominica-2026-artifact? artifact)
                   (= deep-dominica-2026/source-sha256 (:source-sha256 artifact))
                   (= "pdftotext" (get-in artifact [:tool :name]))
                   (= ["-layout" "-enc" "UTF-8"] (get-in artifact [:tool :arguments]))
                   (= (str/trim (:err (command! "pdftotext" "-v"))) (get-in artifact [:tool :version]))
                   (= raw (:raw-text artifact))
                   (= replay (select-keys artifact (keys replay))))
      (throw (ex-info "Deep Dominica 2026 extraction differs from archived source replay" {})))
    artifact))

(defn validate-vertical-blue-artifact!
  "Replay source-bound Vertical Blue rows against the registered PDF."
  [root artifact]
  (let [source (archive/inspect root (:source-sha256 artifact))
        raw (:out (command! "pdftotext" "-layout" "-enc" "UTF-8" (:artifact-path source) "-"))
        segments (vec (str/split raw #"\f" -1))
        pages (if (= "" (last segments)) (pop segments) segments)
        replay (vertical-blue/parse-pages pages)]
    (when-not (and (vertical-blue-artifact? artifact)
                   (= vertical-blue/source-sha256 (:source-sha256 artifact))
                   (= "pdftotext" (get-in artifact [:tool :name]))
                   (= ["-layout" "-enc" "UTF-8"] (get-in artifact [:tool :arguments]))
                   (= (str/trim (:err (command! "pdftotext" "-v"))) (get-in artifact [:tool :version]))
                   (= raw (:raw-text artifact))
                   (= replay (select-keys artifact (keys replay))))
      (throw (ex-info "Vertical Blue extraction differs from archived source replay" {})))
    artifact))

(defn -main [& args]
  (try
    (when-not (= 3 (count args)) (throw (ex-info "Usage: ARCHIVE SHA256 OPTIONS.edn" {})))
    (let [[root sha256 file] args
          options (with-open [reader (java.io.PushbackReader. (java.io.StringReader. (String. (archive/read-source-bytes file) "UTF-8")))]
                    (let [eof (Object.) value (edn/read {:eof eof} reader)]
                      (when-not (identical? eof (edn/read {:eof eof} reader))
                        (throw (ex-info "Expected one EDN options form" {})))
                      value))]
      (prn (if (= sha256 camotes-challenge-2026/source-sha256)
             (let [ledger (:reviewed-ledger-path options)]
               (when-not (and (string? ledger) (not (str/blank? ledger)))
                 (throw (ex-info "Camotes 2026 requires :reviewed-ledger-path" {})))
               (camotes-challenge-2026/extract-reviewed-scan!
                root sha256 ledger (dissoc options :reviewed-ledger-path)))
             (extract! root sha256 options)))
      (shutdown-agents))
    (catch Exception error
      (binding [*out* *err*] (println "Extraction failed:" (.getMessage error)))
      (System/exit 1))))
