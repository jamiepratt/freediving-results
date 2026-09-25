(ns freediving.indoor-time-2026
  "Source-bound printed timing cells. Conflicting and absent headings remain unresolved."
  (:require [clojure.string :as str]
            [freediving.novi-sad :as legacy]
            [freediving.indoor-2026 :as distance]
            [freediving.depth-2025 :as geometry]))
(def legacy-parser-version "cmas-2026-indoor-time/1")
(def parser-version "cmas-2026-indoor-time/2")
(defn supported? [pages]
  (and (distance/supported? pages)
       (some #(re-find #"(?m)^\s*(?:STA|[248]X50)(?:\s|$)" %) pages)))
(def category-pattern #"(?:JUNIORS|SENIORS|MASTERS M[123]) [\u2013\u2014-] (?:MEN|WOMEN)")
(def date-pattern #"NOVI SAD, SERBIA\s+JUNE, (\d{2}), 2026")
(def header-tokens ["#" "Name" "&" "surname" "Country" "Realized" "Final" "Notes" "Distance" "(m)" "Distance" "(m)"])
(def speed-header-tokens ["#" "Name" "&" "surname" "Country" "Final" "Result" "(time)" "Notes"])
(def row-pattern #"\s*(?:(\d+)\s+)?(.+?)\s+(CMAS1|AIN|[A-Z]{3})(?=\s+(?:\d|DSQ|DNS)|\s*$)(?:\s+.*)?")
(defn- only [xs] (when (= 1 (count xs)) (first xs)))
(defn- tokens [s] (str/split (str/trim s) #"\s+"))
(defn- center [w] (/ (+ (:x-min w) (:x-max w)) 2))
(defn- metadata? [l]
  (let [s (str/trim (:text l))]
    (or (= legacy/title s) (re-matches date-pattern s)
        (#{"STA" "2X50" "4X50" "8X50"} s) (re-matches category-pattern s)
        (every? (set (concat header-tokens speed-header-tokens)) (tokens s)))))
(defn- heading-like? [l]
  (re-find #"^(?:\d{4}|NOVI|JUNE|DNF|DYN|STA|[248]X50|JUNIORS|SENIORS|MASTERS)" (str/trim (:text l))))
(defn- context [lines page]
  (let [texts (map (comp str/trim :text) lines)
        discipline (only (filter #{"STA" "2X50" "4X50" "8X50"} texts))
        sta? (= "STA" discipline)
        expected-headers (if sta? header-tokens speed-header-tokens)
        category (only (filter #(re-matches category-pattern %) texts))
        day (some-> (only (keep #(re-matches date-pattern %) texts)) second)
        date (when (= day ({"STA" "13" "2X50" "12" "4X50" "13" "8X50" "11"} discipline)) (str "2026-06-" day))
        ws (:words page)
        rank (only (filter #(= "#" (:text %)) ws))
        headers (when rank (filterv #(< (abs (- (:y-min %) (:y-min rank))) 14) ws))
        country (only (filter #(= "Country" (:text %)) headers))
        ds (sort-by :x-min (filter #(= "Distance" (:text %)) headers))
        units (sort-by :x-min (filter #(= "(m)" (:text %)) headers))
        distance-cuts (when (and country (= 2 (count ds)) (= 2 (count units))
                                 (= (frequencies expected-headers) (frequencies (map :text headers))))
                        [(/ (+ (:x-max country) (:x-min (first ds))) 2)
                         (/ (+ (:x-max (first units)) (:x-min (second ds))) 2)
                         (min (:x-min (only (filter #(= "Notes" (:text %)) headers)))
                              (/ (+ (:x-max (second units)) (* 2 (:x-min (second ds))) (- (:x-min (first ds)))) 2))])
        final-heading (only (filter #(= "Final" (:text %)) headers))
        notes-heading (only (filter #(= "Notes" (:text %)) headers))
        speed-cuts (when (and country final-heading notes-heading
                              (= (frequencies speed-header-tokens) (frequencies (map :text headers))))
                     [(/ (+ (:x-max country) (- (* 2 (:x-min final-heading)) (:x-min notes-heading))) 2)
                      ;; Source note glyphs can sit 0.77pt left of their heading.
                      ;; A 2pt inset stays in the inspected empty column gutter.
                      (:x-min final-heading) (- (:x-min notes-heading) 2)])
        cuts (if sta? distance-cuts speed-cuts)
        heading-columns? (and cuts (apply < cuts)
                              (if sta?
                                (and (< (first cuts) (center (only (filter #(= "Realized" (:text %)) headers))) (second cuts))
                                     (< (second cuts) (center final-heading) (last cuts)))
                                (every? #(< (second cuts) (center %) (last cuts))
                                        (filter #(#{"Final" "Result" "(time)"} (:text %)) headers)))
                              (<= (last cuts) (:x-min notes-heading)))]
    {:headers-valid? (boolean (and (:valid? page) heading-columns? cuts (apply < cuts) (= (frequencies expected-headers) (frequencies (map :text headers)))))
     :valid? (boolean (and (:valid? page) discipline category date
                           (not-any? #(and (heading-like? %) (not (metadata? %))) lines)
                           (= 1 (count (filter #{legacy/title} texts)))
                           (= (frequencies expected-headers) (frequencies (map :text headers)))
                           heading-columns? cuts (apply < cuts)))
     :discipline discipline :category category :event-date date
     :cuts cuts :header-words headers :metadata-evidence (filterv metadata? lines)}))
(defn- time-syntax [token]
  ;; Token syntax only: no component names, unit, normalization, or conversion.
  (if (= "0" token)
    {:components [0] :fraction nil :fraction-digits 0 :notation :integer}
    (when-let [[_ body fraction] (re-matches #"(\d{1,2}:\d{2}(?::\d{2})?)(?:[.](\d{2}))?" (or token ""))]
      {:components (mapv parse-long (str/split body #":")) :fraction fraction
       :fraction-digits (count fraction) :notation :colon-separated})))
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
        sta? (= "STA" (:discipline ctx))
        speed8? (= "8X50" (:discipline ctx))
        status (some->> notes (re-find #"^(DSQ|DNS)(?:\s|$)") second)
        raw (when name {:rank rank :source-name name :representation nation :realized-value (when sta? realized) :unlabeled-value (when-not sta? realized) :final-value final :notes notes :status status})
        valid? (and (:valid? ctx) name matching? (or realized final status)
                    (or (not speed8?) (nil? realized))
                    (every? #(or (nil? %) (if sta? (re-matches #"(?:\d{2}:\d{2}|0(?:[,.]0)?)" %) (time-syntax %))) [realized final])
                    (every? (fn [w] (not-any? #(< (:x-min w) % (:x-max w)) cuts)) words))
        parsed (when valid? (merge raw (select-keys ctx [:discipline :category :event-date])
                                   {:federation "CMAS" :unit nil :rank (some-> rank parse-long)
                                    :column-labels (if sta? {:realized-value "Realized Distance (m)" :final-value "Final Distance (m)"}
                                                       (cond-> {:final-value "Final Result (time)"}
                                                         (not speed8?) (assoc :unlabeled-value nil)))
                                    :realized-time nil :final-time (when-not sta? (time-syntax final)) :realized-duration nil :final-duration nil :split-time nil
                                    :announced-distance nil :penalty nil :card nil :record nil :medal nil}))]
    {:coordinates (assoc (select-keys (first source-lines) [:page :line]) :column-start 1 :column-end (inc (count (:text (first source-lines)))))
     :raw {:line (str/join "\n" (map :text source-lines)) :fields raw}
     :source-lines source-lines :metadata-evidence (:metadata-evidence ctx)
     :geometry-evidence words :column-geometry (select-keys ctx [:cuts :header-words])
     :parsed parsed :parse-status (if parsed :parsed :unparsed)
     :fields (into {} (map (fn [[k v]] [k {:status (if (nil? v) :unknown :parsed) :value v}])) parsed)
     :review-status :unreviewed :unresolved-reasons (if parsed (cond-> [:owner-review-required :time-unit-not-explicit]
                                                                 sta? (conj :sta-distance-header-conflict)
                                                                 (and (not sta?) (not speed8?)) (conj :unlabeled-result-column)) [:malformed-or-unsupported-time-row])}))
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
(defn- parse-time-pages [pages xml]
  (let [boxes (geometry/geometry-pages xml)
        boxes (if (= (count pages) (count boxes)) boxes (repeat (count pages) {:valid? false :words []}))
        processed (reduce (fn [out [i text box]]
                            (let [p (inc i)
                                  lines (mapv (fn [n s] {:page p :line (inc n) :text s}) (range) (str/split text #"\n" -1))
                                  nonblank (filterv #(not (str/blank? (:text %))) lines)
                                  local (context nonblank box)
                                  ctx local
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

(defn parse-legacy-pages-with-geometry
  "Immutable /1 replay, including its artifact-wide semantics blocker."
  [pages xml]
  (let [old (distance/parse-pages-with-geometry pages xml)
        timed (parse-time-pages pages xml)
        active (set (map #(get-in % [:coordinates :page]) (:candidates timed)))
        candidates (vec (sort-by (juxt #(get-in % [:coordinates :page]) #(get-in % [:coordinates :line]))
                                 (concat (remove #(active (get-in % [:coordinates :page])) (:candidates old)) (:candidates timed))))
        noncandidate (vec (sort-by (juxt :page :line)
                                   (concat (remove #(active (:page %)) (get-in old [:reconciliation :noncandidate-lines]))
                                           (filter #(active (:page %)) (get-in timed [:reconciliation :noncandidate-lines])))))
        unsupported (vec (remove active (get-in old [:reconciliation :unsupported-pages])))
        parsed (count (filter #(= :parsed (:parse-status %)) candidates))]
    (-> old
        (assoc :parser-version legacy-parser-version :candidates candidates
               :status (if (seq unsupported) :partial-unsupported-needs-parser :needs-review)
               :pages (mapv (fn [p t] (if (active (:page p)) t p)) (:pages old) (:pages timed)))
        (update :reconciliation merge
                {:candidate-count (count candidates) :parsed-count parsed :unparsed-count (- (count candidates) parsed)
                 :unresolved-count (count candidates) :unsupported-pages unsupported
                 :coverage (if (seq unsupported) :partial :complete) :noncandidate-lines noncandidate})
        (assoc :publication {:status :blocked :reasons [:owner-review-required :reconciliation-unreviewed :source-semantics-unresolved]}))))

(defn parse-pages-with-geometry
  "The /2 contract preserves source claims and localizes unresolved timing semantics.
   Distance candidates are copied unchanged. Unsupported pages remain unsupported."
  [pages xml]
  (-> (parse-legacy-pages-with-geometry pages xml)
      (assoc :parser-version parser-version)
      (update :candidates
              (fn [rows]
                (mapv (fn [row]
                        (if (some #{:time-unit-not-explicit :malformed-or-unsupported-time-row}
                                  (:unresolved-reasons row))
                          (update row :unresolved-reasons conj :source-semantics-unresolved)
                          row)) rows)))
      (update-in [:publication :reasons]
                 #(filterv (complement #{:source-semantics-unresolved}) %))))
