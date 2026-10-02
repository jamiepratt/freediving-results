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
    :parser-version parser-version}
   :pzf-2025-indoor-team
   {:url "https://pzf-sport.org/team_department/kadra-basen-2025/"
    :publisher "Polski Związek Freedivingu"
    :parser-id "pzf-indoor-team-directory"
    :parser-version "pzf-indoor-team-directory/1"}
   :pzf-2025-indoor-team-page2
   {:url "https://pzf-sport.org/team_department/kadra-basen-2025/page/2/"
    :publisher "Polski Związek Freedivingu"
    :parser-id "pzf-indoor-team-directory"
    :parser-version "pzf-indoor-team-directory/1"}})

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
          sources (mapv (fn [ordinal {:keys [paragraph line original-name]}]
                          {:source-sha256 source-sha256
                           :parser-version parser-version
                           :source-position {:format :html :url url
                                             :selector "#post-6495 blockquote p"
                                             :paragraph paragraph :line line
                                             :ordinal (inc ordinal)}
                           :publisher publisher :source-family :national-team-roster
                           :original-name original-name})
                        (range) entries)]
      {:source-id :japan-apnea-2025-team
       :source-sha256 source-sha256
       :parser-version parser-version
       :routing routed
       :roster-entries entries
       :name-sources sources
       :name-assertions (mapv names/name-assertion sources)})))

(def pzf-parser-version "pzf-indoor-team-directory/1")

(defn parse-pzf-team-directory
  "Parse a registered PZF 2025 indoor team directory page.
   Linked pages and unreadable cards remain explicit gaps."
  [{:keys [html source-sha256 url additional-claims]}]
  (when-not (and (string? html) (= source-sha256 (sha256 html)))
    (throw (ex-info "Source hash mismatch" {:reason :source-mismatch})))
  (let [source-id (if (= url (get-in source-registry [:pzf-2025-indoor-team-page2 :url]))
                    :pzf-2025-indoor-team-page2
                    :pzf-2025-indoor-team)
        {registered-url :url :keys [publisher parser-id]}
        (get source-registry source-id)
        document (Jsoup/parse html)
        body (.body document)
        canonical (.selectFirst document "link[rel=canonical]")
        container (.selectFirst document "#archive-container")]
    (when-not (and (= registered-url url)
                   canonical (= registered-url (.attr canonical "href"))
                   (.hasClass body "term-kadra-basen-2025")
                   (= (= source-id :pzf-2025-indoor-team-page2)
                      (.hasClass body "paged-2"))
                   container (seq (.select container "article.team")))
      (throw (ex-info "Incompatible PZF team directory" {:reason :incompatible-page})))
    (let [cards (map-indexed
                 (fn [idx article]
                   (let [post-id (some #(second (re-matches #"post-(\d+)" %))
                                       (.classNames article))
                         anchor (.selectFirst article "h2.entry-title a")
                         original-name (when anchor (str/trim (.text anchor)))
                         profile-url (when anchor (.attr anchor "href"))
                         valid? (and (.hasClass article "team_department-kadra-basen-2025")
                                     post-id
                                     (some->> original-name (re-matches #"[\p{L}\p{M} .'-]+"))
                                     (str/includes? original-name " ")
                                     (str/starts-with? profile-url "https://pzf-sport.org/team/"))]
                     {:id (if post-id (str "team-post-" post-id) (str "card-" (inc idx)))
                      :card (inc idx) :post-id post-id :original-name original-name
                      :profile-url profile-url :valid? (boolean valid?)}))
                 (.select container "article.team"))
          positions (mapv (fn [{:keys [id card post-id]}]
                            {:id id :citation (str url "#archive-container article"
                                                   (if post-id (str ".post-" post-id)
                                                       (str ":nth-of-type(" card ")"))
                                                   " h2.entry-title a")})
                          cards)
          claimed (->> cards (filter :valid?) (map :id) set)
          claim {:parser-id parser-id
                 :parser-version pzf-parser-version
                 :match-reason "registered PZF canonical taxonomy archive and team cards"
                 :source-restriction {:sha256s #{source-sha256} :formats #{:html}}
                 :supported-positions claimed :claimed-positions claimed}
          next-page (.selectFirst document "nav.pagination a[href*=/page/2/]")
          sections (cond-> [{:id "outside-team-cards"
                             :citation (str url "#main outside #archive-container article.team")
                             :examined? false}]
                     next-page (conj {:id "following-page"
                                      :citation (.attr next-page "href")
                                      :examined? false}))
          routed (routing/route-document
                  {:source-sha256 source-sha256 :format :html
                   :positions positions :sections sections}
                  (into [claim] additional-claims))
          accepted (set (map :position-id (:routed routed)))
          entries (->> cards (filter #(contains? accepted (:id %))) vec)
          sources (mapv (fn [{:keys [card post-id original-name profile-url]}]
                          {:source-sha256 source-sha256
                           :parser-version pzf-parser-version
                           :source-position {:format :html :url url
                                             :selector (str "#archive-container article.post-"
                                                            post-id " h2.entry-title a")
                                             :profile-url profile-url :row card}
                           :publisher publisher
                           :source-family :national-team-directory
                           :person-id {:authority publisher
                                       :kind :team-post-id :value post-id}
                           :original-name original-name})
                        entries)]
      {:source-id source-id
       :source-sha256 source-sha256
       :parser-version pzf-parser-version
       :routing routed
       :roster-entries entries
       :name-sources sources
       :name-assertions (mapv names/name-assertion sources)})))
