(ns freediving.athens-geometry
  "Add field evidence without guessing the spelling of detached combining glyphs."
  (:require [clojure.string :as str]
            [freediving.athens :as legacy]
            [freediving.depth-2025 :as geometry]))

(def parser-version "cmas-athens-pool/7")
(defn- only [xs] (when (= 1 (count xs)) (first xs)))
(defn- glyphs [texts] (frequencies (str/replace (apply str texts) #"\s" "")))
(defn- mark? [s] (boolean (re-matches #"\p{M}+" s)))
(defn- valid-box? [{:keys [width height words]}]
  ;; Poppler emits zero-width boxes for the inspected combining glyph. This is
  ;; deliberately local: other geometry parsers retain their stricter contract.
  (and width height (pos? width) (pos? height)
       (every? (fn [{:keys [text x-min x-max y-min y-max]}]
                 (and (every? some? [x-min x-max y-min y-max])
                      (<= 0 x-min x-max width) (or (< x-min x-max) (mark? text))
                      (<= 0 y-min) (< y-min y-max) (<= y-max height))) words)))
(defn- recover-fields [c box]
  (if-not (and (valid-box? box) (some #{:ambiguous-wrapped-name} (:unresolved-reasons c))) c
          (let [lines (:source-lines c)
                nation (get-in c [:raw :fields :representation])
                expected (glyphs (map :text lines))
                rows (for [anchor (:words box) :when (= nation (:text anchor))
                           :let [ws (filterv #(< (abs (- (:y-min %) (:y-min anchor))) 3) (:words box))]
                           :when (= expected (glyphs (map :text ws)))] {:anchor anchor :words ws})
                {:keys [anchor words]} (only rows)
                rank (only (filter #(and (re-matches #"\d+" (:text %)) (< (:x-max %) (:x-min anchor))) words))
                names (when rank (sort-by :x-min (filter #(and (>= (:x-min %) (:x-max rank))
                                                               (<= (:x-max %) (:x-min anchor))) words)))
                tail (str/join "\n" (concat (map :text (:metadata-evidence c)) [(:text (last lines))]))
                parsed (some-> (legacy/parse-pages [tail]) :candidates first :parsed)]
            (if-not (and rank parsed (some #(mark? (:text %)) names)) c
                    (-> c
                        (assoc :geometry-evidence words :name-geometry-evidence (vec names))
                        (update :fields #(merge (into {} (map (fn [[k v]] [k {:status (if (nil? v) :unknown :parsed) :value v}])
                                                              (assoc (dissoc parsed :source-name) :rank (parse-long (:text rank))))) %))
                        (assoc-in [:fields :source-name] {:status :ambiguous :value nil :reason :detached-combining-mark})
                        (update :unresolved-reasons conj :detached-combining-mark))))))
(defn parse-pages-with-geometry [pages xml]
  (let [old (legacy/parse-pages pages) boxes (geometry/geometry-pages xml)
        fragment-pages (set (keep #(when (= "fi" (str/trim (get-in % [:raw :line])))
                                     (get-in % [:coordinates :page])) (:candidates old)))]
    (assoc old :parser-version parser-version :geometry-xml xml
           :candidates
           (mapv (fn [c]
                   (let [page (get-in c [:coordinates :page])
                         box (when (= (count pages) (count boxes)) (nth boxes (dec page)))]
                     (cond-> (recover-fields c box)
                       (and (fragment-pages page) (get-in c [:parsed :source-name]))
                       (-> (update :unresolved-reasons conj :detached-name-fragment-unresolved)
                           (assoc :detached-fragment-geometry (filterv #(= "fi" (:text %)) (:words box)))))))
                 (:candidates old)))))
