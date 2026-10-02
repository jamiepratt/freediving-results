(ns freediving.affiliate-html
  "Registered, source-specific parsing of cited affiliate roster name evidence."
  (:require [clojure.string :as str]
            [freediving.name-evidence :as names]
            [freediving.parser-routing :as routing])
  (:import [java.security MessageDigest]
           [java.util HexFormat]
           [org.jsoup Jsoup]))

(def parser-version "japan-apnea-team-roster/1")
(def source-registry
  {:japan-apnea-2025-team
   {:url "https://aida-japan.com/info/6495.html"
    :post-selector "#post-6495"
    :publisher "Japan Apnea Society"
    :parser-id "japan-apnea-2025-team"
    :parser-version parser-version}})

(defn- sha256 [source]
  (.formatHex (HexFormat/of)
              (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes source "UTF-8"))))

(defn- line-texts [paragraph]
  (->> (str/split (.html paragraph) #"(?i)<br\s*/?>")
       (mapv #(str/trim (.text (Jsoup/parseBodyFragment %))))))

(defn- line-kind [line]
  (cond
    (= line "【女子】") [:category "female"]
    (= line "【男子】") [:category "male"]
    (re-matches #"《(DYNB|DNF|STA|DYN)》" line)
    [:discipline (second (re-matches #"《(DYNB|DNF|STA|DYN)》" line))]
    (str/blank? line) [:blank nil]
    (re-matches #"[\p{IsHan}\p{IsHiragana}\p{IsKatakana}々〆ヶー\s]{2,}(?:（WC）)?" line)
    [:name (str/replace line #"（WC）$" "")]
    :else [:unsupported nil]))

(defn- roster-lines [post]
  (->> (.select post "blockquote p")
       (map-indexed
        (fn [pidx paragraph]
          (map-indexed (fn [lidx line]
                         {:paragraph (inc pidx) :line (inc lidx)
                          :raw line :id (str "p" (inc pidx) ":l" (inc lidx))
                          :kind (line-kind line)})
                       (line-texts paragraph))))
       (apply concat)
       vec))

(defn parse-team-roster
  "Parse one registered 2025 team article. Returns name-only cited assertions,
   roster context and explicit router coverage. Extra claims expose collisions."
  [{:keys [html source-sha256 url additional-claims]}]
  (when-not (and (string? html) (= source-sha256 (sha256 html)))
    (throw (ex-info "Source hash mismatch" {:reason :source-mismatch})))
  (let [{registered-url :url :keys [post-selector publisher parser-id]}
        (:japan-apnea-2025-team source-registry)
        document (Jsoup/parse html)
        canonicals (.select document "link[rel=canonical]")
        post (.selectFirst document post-selector)
        title (when post (.selectFirst post "#single_title"))
        heading (when post (.selectFirst post "h4"))]
    (when-not (and (= registered-url url)
                   (some #(= registered-url (.attr % "href")) canonicals)
                   title (str/includes? (.text title) "2025")
                   heading (= "代表選手種目別一覧" (.text heading))
                   (seq (.select post "blockquote p")))
      (throw (ex-info "Incompatible affiliate roster" {:reason :incompatible-page})))
    (let [lines (roster-lines post)
          classified (loop [remaining lines category nil discipline nil result []]
                       (if-let [line (first remaining)]
                         (let [[kind value] (:kind line)
                               category' (if (= kind :category) value category)
                               discipline' (if (= kind :discipline) value discipline)]
                           (recur (rest remaining) category' discipline'
                                  (conj result (assoc line :category category'
                                                      :discipline discipline'
                                                      :kind kind :value value))))
                         result))
          candidate? (fn [{:keys [kind]}] (#{:name :unsupported} kind))
          candidates (filterv candidate? classified)
          name-ids (->> candidates
                        (filter #(and (= :name (:kind %))
                                      (:category %) (:discipline %)))
                        (map :id) set)
          positions (mapv (fn [{:keys [id paragraph line]}]
                            {:id id
                             :citation (str url "#post-6495 blockquote p:nth-of-type("
                                            paragraph "), line " line)})
                          candidates)
          claim {:parser-id parser-id :parser-version parser-version
                 :match-reason "registered canonical article, team heading and roster blockquote"
                 :source-restriction {:sha256s #{source-sha256} :formats #{:html}}
                 :supported-positions name-ids :claimed-positions name-ids}
          routed (routing/route-document
                  {:source-sha256 source-sha256 :format :html :positions positions
                   :sections [{:id "post-other-content"
                               :citation (str url "#post-6495 outside roster blockquote")
                               :examined? false}]}
                  (into [claim] additional-claims))
          accepted-ids (set (map :position-id (:routed routed)))
          entries (->> classified
                       (filter #(contains? accepted-ids (:id %)))
                       (mapv (fn [{:keys [id paragraph line category discipline value raw]}]
                               {:position-id id :paragraph paragraph :line line
                                :roster-category category :discipline discipline
                                :original-name value :printed-line raw})))
          sources (mapv (fn [{:keys [paragraph line original-name]}]
                          {:source-sha256 source-sha256
                           :parser-version parser-version
                           :source-position {:format :html :url url
                                             :selector "#post-6495 blockquote p"
                                             :paragraph paragraph :line line}
                           :publisher publisher :source-family :national-team-roster
                           :original-name original-name})
                        entries)]
      {:source-id :japan-apnea-2025-team
       :source-sha256 source-sha256
       :parser-version parser-version
       :routing routed
       :roster-entries entries
       :name-sources sources
       :name-assertions (mapv names/name-assertion sources)})))
