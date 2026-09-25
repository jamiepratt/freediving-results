(ns freediving.indoor-2026
  "Source-bound 2026 Novi Sad distance tables. Notes are preserved without derived penalties."
  (:require [clojure.string :as str]
            [freediving.novi-sad :as legacy]
            [freediving.depth-2025 :as geometry]))
(def parser-version "cmas-2026-indoor-distance/1")
(def supported? legacy/supported?)
(def category-pattern #"(?:JUNIORS|SENIORS|MASTERS M[123]) [\u2013\u2014-] (?:MEN|WOMEN)")
(def date-pattern #"NOVI SAD, SERBIA\s+JUNE, (\d{2}), 2026")
(def header-tokens ["#" "Name" "&" "surname" "Country" "Realized" "Final" "Notes" "Distance" "(m)" "Distance" "(m)"])
(def row-pattern #"\s*(?:(\d+)\s+)?(.+?)\s+(CMAS1|AIN|[A-Z]{3})(?=\s+(?:\d|DSQ|DNS)|\s*$)(?:\s+.*)?")
(defn- only [xs] (when (= 1 (count xs)) (first xs)))
(defn- tokens [s] (str/split (str/trim s) #"\s+"))
(defn- center [w] (/ (+ (:x-min w) (:x-max w)) 2))
(defn- metadata? [l]
  (let [s (str/trim (:text l))]
    (or (= legacy/title s) (re-matches date-pattern s)
        (#{"DNF" "DYN-BF" "DYN"} s) (re-matches category-pattern s)
        (every? (set header-tokens) (tokens s)))))
(defn- heading-like? [l]
  (re-find #"^(?:\d{4}|NOVI|JUNE|DNF|DYN|STA|[248]X50|JUNIORS|SENIORS|MASTERS)" (str/trim (:text l))))
(defn- context [lines page]
  (let [texts (map (comp str/trim :text) lines)
        discipline (only (filter #{"DNF" "DYN-BF" "DYN"} texts))
        category (only (filter #(re-matches category-pattern %) texts))
        day (some-> (only (keep #(re-matches date-pattern %) texts)) second)
        date (when (= day ({"DNF" "11" "DYN-BF" "12" "DYN" "14"} discipline)) (str "2026-06-" day))
        ws (:words page)
        rank (only (filter #(= "#" (:text %)) ws))
        headers (when rank (filterv #(< (abs (- (:y-min %) (:y-min rank))) 14) ws))
        country (only (filter #(= "Country" (:text %)) headers))
        ds (sort-by :x-min (filter #(= "Distance" (:text %)) headers))
        units (sort-by :x-min (filter #(= "(m)" (:text %)) headers))
        cuts (when (and country (= 2 (count ds)) (= 2 (count units))
                        (= (frequencies header-tokens) (frequencies (map :text headers))))
               [(/ (+ (:x-max country) (:x-min (first ds))) 2)
                (/ (+ (:x-max (first units)) (:x-min (second ds))) 2)
                (min (:x-min (only (filter #(= "Notes" (:text %)) headers)))
                     (/ (+ (:x-max (second units)) (* 2 (:x-min (second ds))) (- (:x-min (first ds)))) 2))])
        heading-columns? (and cuts
                              (< (first cuts) (center (only (filter #(= "Realized" (:text %)) headers))) (second cuts))
                              (< (second cuts) (center (only (filter #(= "Final" (:text %)) headers))) (last cuts))
                              (<= (last cuts) (:x-min (only (filter #(= "Notes" (:text %)) headers)))))]
    {:headers-valid? (boolean (and (:valid? page) heading-columns? cuts (apply < cuts) (= (frequencies header-tokens) (frequencies (map :text headers)))))
     :valid? (boolean (and (:valid? page) discipline category date
                           (not-any? #(and (heading-like? %) (not (metadata? %))) lines)
                           (= 1 (count (filter #{legacy/title} texts)))
                           (= (frequencies header-tokens) (frequencies (map :text headers)))
                           heading-columns? cuts (apply < cuts)))
     :discipline discipline :category category :event-date date
     :cuts cuts :header-words headers :metadata-evidence (filterv metadata? lines)}))
(defn- candidate [source-lines ctx page]
  (let [line (only (filter #(re-matches row-pattern (:text %)) source-lines))
        [_ rank name nation] (when line (re-matches row-pattern (:text line)))
        prefix (frequencies (mapcat tokens (remove nil? [rank name nation])))
        cuts (:cuts ctx)
        anchors (when cuts (filter (fn [a]
                                     (and (= nation (:text a)) (< (center a) (first cuts))
                                          (= prefix (frequencies (map :text (filter #(and (< (center %) (first cuts))
                                                                                          (< (abs (- (:y-min %) (:y-min a))) 5)) (:words page))))))) (:words page)))
        anchor (only anchors)
        words (when anchor (filterv #(< (abs (- (:y-min %) (:y-min anchor))) 10) (:words page)))
        matching? (= (frequencies (mapcat #(tokens (:text %)) source-lines)) (frequencies (map :text words)))
        values (when cuts (mapv (fn [col] (let [ws (sort-by (juxt :y-min :x-min) (filter #(= col (count (take-while (fn [cut] (<= cut (center %))) cuts))) words))]
                                            (when (seq ws) (str/join " " (map :text ws))))) [1 2 3]))
        [realized final notes] values
        status (some->> notes (re-find #"^(DSQ|DNS)(?:\s|$)") second)
        raw (when name {:rank rank :source-name name :representation nation :realized-distance realized :final-distance final :notes notes :status status})
        valid? (and (:valid? ctx) name matching? (or realized final status)
                    (every? #(or (nil? %) (re-matches #"\d+(?:[,.]\d+)?" %)) [realized final])
                    (every? (fn [w] (not-any? #(< (:x-min w) % (:x-max w)) cuts)) words))
        parsed (when valid? (merge raw (select-keys ctx [:discipline :category :event-date])
                                   {:federation "CMAS" :unit "m" :rank (some-> rank parse-long)
                                    :realized-distance (some-> realized (str/replace "," ".") bigdec)
                                    :final-distance (some-> final (str/replace "," ".") bigdec)
                                    :announced-distance nil :penalty nil :card nil :record nil :medal nil}))]
    {:coordinates (assoc (select-keys (first source-lines) [:page :line]) :column-start 1 :column-end (inc (count (:text (first source-lines)))))
     :raw {:line (str/join "\n" (map :text source-lines)) :fields raw}
     :source-lines source-lines :metadata-evidence (:metadata-evidence ctx)
     :geometry-evidence words :column-geometry (select-keys ctx [:cuts :header-words])
     :parsed parsed :parse-status (if parsed :parsed :unparsed)
     :fields (into {} (map (fn [[k v]] [k {:status (if (nil? v) :unknown :parsed) :value v}])) parsed)
     :review-status :unreviewed :unresolved-reasons (if parsed [:owner-review-required] [:malformed-or-unsupported-distance-row])}))
(defn- page-candidates [lines ctx box]
  (loop [remaining (vec (remove metadata? lines)) out []]
    (if (empty? remaining) out
        (let [parsed (some (fn [n]
                             (when (<= n (count remaining))
                               (let [c (candidate (subvec remaining 0 n) ctx box)]
                                 (when (= :parsed (:parse-status c)) [n c])))) [1 2 3])]
          (if parsed
            (recur (subvec remaining (first parsed)) (conj out (second parsed)))
            (recur (subvec remaining 1) (conj out (candidate [(first remaining)] ctx box))))))))
(defn parse-pages-with-geometry [pages xml]
  (let [boxes (geometry/geometry-pages xml)
        boxes (if (= (count pages) (count boxes)) boxes (repeat (count pages) {:valid? false :words []}))
        processed (reduce (fn [out [i text box]]
                            (let [p (inc i)
                                  lines (mapv (fn [n s] {:page p :line (inc n) :text s}) (range) (str/split text #"\n" -1))
                                  nonblank (filterv #(not (str/blank? (:text %))) lines)
                                  local (context nonblank box)
                                  previous (:context (peek out))
                                  continuation? (and (:valid? previous) (:headers-valid? local)
                                                     (not-any? #(re-find #"^(?:2026|NOVI|JUNE|DNF|DYN|STA|[248]X50|JUNIORS|SENIORS|MASTERS)" (str/trim (:text %))) nonblank))
                                  ctx (if continuation?
                                        (merge previous (select-keys local [:cuts :header-words])
                                               {:metadata-evidence (into (:metadata-evidence previous) (:metadata-evidence local))}) local)
                                  candidates (if (:valid? ctx) (page-candidates nonblank ctx box) [])
                                  ctx (assoc ctx :valid? (and (:valid? ctx) (seq candidates) (every? #(= :parsed (:parse-status %)) candidates)))]
                              (conj out {:page p :text text :lines lines :status (if (seq candidates) :needs-review :unsupported-needs-parser)
                                         :context ctx :candidates candidates
                                         :noncandidate-lines (if (seq candidates) (filterv metadata? nonblank) nonblank)})))
                          [] (map vector (range) pages boxes))
        candidates (vec (mapcat :candidates processed))
        parsed (count (filter #(= :parsed (:parse-status %)) candidates))
        unsupported (mapv :page (filter #(= :unsupported-needs-parser (:status %)) processed))]
    {:parser-version parser-version :geometry-xml xml
     :status (if (seq unsupported) :partial-unsupported-needs-parser :needs-review)
     :pages (mapv #(dissoc % :candidates :noncandidate-lines :context) processed) :candidates candidates
     :reconciliation {:page-count (count pages) :candidate-count (count candidates) :parsed-count parsed
                      :unparsed-count (- (count candidates) parsed) :unresolved-count (count candidates)
                      :coverage (if (seq unsupported) :partial :complete) :unsupported-pages unsupported
                      :nonblank-line-count (count (remove #(str/blank? (:text %)) (mapcat :lines processed)))
                      :noncandidate-lines (vec (mapcat :noncandidate-lines processed)) :status :unreviewed}
     :publication {:status :blocked :reasons [:owner-review-required :reconciliation-unreviewed]}}))
