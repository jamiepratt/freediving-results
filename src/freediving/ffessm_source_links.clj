(ns freediving.ffessm-source-links
  "Cited within-PDF links between FFESSM 2026 open and national ranking rows."
  (:require [freediving.ffessm-2026 :as cwt-women]
            [freediving.ffessm-2026-bipalmes-women :as bifins-women]
            [freediving.ffessm-2026-regular-categories :as regular]
            [freediving.ffessm-2026-final-categories :as final]))

(def ^:private linked-sources
  #{cwt-women/source-sha256 bifins-women/source-sha256
    regular/women-sha256 final/cnf-men-sha256 final/fim-men-sha256})

(def ^:private match-fields
  [:source-name :representation :depth-declared :depth-reached :depth-penalty
   :default-tag :final-performance :card :reason :result-status :discipline :category])

(defn- position [candidate]
  (select-keys (:coordinates candidate) [:page :line]))

(defn- source-line [artifact candidate]
  (let [{:keys [page line]} (position candidate)
        page-data (when (pos-int? page) (nth (:pages artifact) (dec page) nil))
        line-data (when (pos-int? line) (nth (:lines page-data) (dec line) nil))
        text (get-in candidate [:raw :line])]
    (when (and (pos-int? page) (pos-int? line)
               (= page (:page page-data)) (= line (:line line-data))
               (string? text) (= text (:text line-data)))
      text)))

(defn- same-printed-result? [open subset]
  (let [left (:parsed open) right (:parsed subset)
        left-fields (get-in open [:raw :fields])
        right-fields (get-in subset [:raw :fields])]
    (and (= :parsed (:parse-status open) (:parse-status subset))
         (= :epreuve (:ranking-scope open))
         (= :championnat-de-france (:ranking-scope subset))
         (= (position open) (zipmap [:page :line] (get-in subset [:parsed :same-result-as])))
         (map? left-fields) (map? right-fields)
         (seq (dissoc left-fields :rank))
         (= (dissoc left-fields :rank) (dissoc right-fields :rank))
         (every? #(and (contains? left %) (contains? right %)
                       (= (get left %) (get right %)))
                 (remove #{:result-status} match-fields))
         (= (contains? left :result-status) (contains? right :result-status))
         (= (:result-status left) (:result-status right)))))

(defn source-links
  "Return proven same-attempt evidence; unknown or ambiguous rows yield no edge."
  [artifact]
  (let [candidates (:candidates artifact)
        indexed (map-indexed vector candidates)
        by-position (group-by (comp position second) indexed)
        job-id (:job-id artifact)]
    (if (and (contains? linked-sources (:source-sha256 artifact))
             (string? job-id) (vector? candidates) (vector? (:pages artifact)))
      (->> indexed
           (keep (fn [[subset-ordinal subset]]
                   (when-let [[page line] (get-in subset [:parsed :same-result-as])]
                     (when (and (pos-int? page) (pos-int? line)
                                (= 1 (count (get by-position (position subset))))
                                (= 1 (count (get by-position {:page page :line line}))))
                       (let [[open-ordinal open] (first (get by-position {:page page :line line}))
                             left-text (source-line artifact open)
                             right-text (source-line artifact subset)]
                         (when (and left-text right-text
                                    (same-printed-result? open subset))
                           (let [fields (filterv #(and (some? (get-in open [:parsed %]))
                                                       (= (get-in open [:parsed %])
                                                          (get-in subset [:parsed %])))
                                                 match-fields)]
                             {:left {:job-id job-id :ordinal open-ordinal}
                              :right {:job-id job-id :ordinal subset-ordinal}
                              :kind :same-attempt
                              :basis {:left-position [(position open)]
                                      :right-position [(position subset)]
                                      :left-text left-text :right-text right-text
                                      :match-fields fields}})))))))
           (sort-by (juxt (comp :ordinal :left) (comp :ordinal :right)))
           vec)
      [])))
