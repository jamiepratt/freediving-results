(ns freediving.revisions-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [freediving.observations :as observations]
            [freediving.archive :as archive]
            [freediving.archive-test :as archive-fixture]
            [freediving.extraction-test :as pdf]
            [freediving.observations-test :as fixture]
            [freediving.revisions :as revisions]
            [freediving.aida-html :as html]
            [freediving.aida-html-test :as html-fixture]
            [freediving.html-evidence :as html-evidence]))
(def reviewer (System/getenv "FREEDIVING_TEST_REVIEW_URL"))
(use-fixtures :each (fn [f]
                      (when-not fixture/admin (throw (ex-info "Isolated PostgreSQL required" {})))
                      (fixture/sql! fixture/admin "DROP SCHEMA IF EXISTS freediving CASCADE")
                      (observations/migrate! fixture/admin "observations_app")
                      (revisions/migrate! fixture/admin "observations_app" "reviews_owner") (f)))
(def scope {:federation "CMAS" :event-id "event-1" :date "2025-09-01" :venue "Synthetic"
            :discipline "CWT" :category "men" :round "final" :session "1" :bib "7"})
(defn sample
  ([version scope] (sample version scope (pdf/registered-pdf)))
  ([version scope source]
   (let [s (fixture/synthetic 1 version)
         [root digest] source
         s (assoc s :root root :artifact (assoc (:artifact s) :source-sha256 digest :acquisitions (:acquisitions (archive/inspect root digest))))
         a (-> (:artifact s)
               (assoc-in [:config :revision-scope] scope)
               (update-in [:candidates 0 :raw :fields] merge (assoc scope :revision-note "Revised synthetic report")))
         a (assoc a :job-id (fixture/hash-value (select-keys a observations/identity-keys)))
         s (assoc s :artifact a)]
     (fixture/publish! s)
     (observations/import! fixture/app (:root s) (:job-id a))
     (let [ref (revisions/reference fixture/app {:job-id (:job-id a) :ordinal 0})]
       {:reference ref :scope (into {} (for [[k v] scope] [k {:reference ref :path [:candidates 0 :raw :fields k] :value v}]))}))))
(deftest exact-source-backed-candidates
  (let [a (sample "original/1" scope) b (sample "revised/2" scope)]
    (is (= :possible-revision (:match (first (revisions/candidates fixture/app b [a])))))
    (is (= :possible-revision (:match (first (revisions/candidates fixture/app a [b])))))
    (is (thrown-with-msg? Exception #"provenance" (revisions/candidates fixture/app (assoc-in b [:reference :source-sha256] "fake") [a])))))
(defn -main [& _]
  (let [r (clojure.test/run-tests 'freediving.revisions-test)]
    (shutdown-agents) (when (pos? (+ (:fail r) (:error r))) (System/exit 1))))
(defn proposal [a b id]
  {:id id :predecessor a :successor b :base-revision 0 :actor "synthetic" :reason "Explicit synthetic revision evidence"
   :mapping-rationale "Synthetic config explicitly represents event and attempt scope"
   :revision-evidence [{:kind :correction-note :binding {:reference (:reference b) :path [:candidates 0 :raw :fields :revision-note] :value "Revised synthetic report"}}]})
(deftest pending-confirmed-reversed-history
  (let [a (sample "old/1" scope) b (sample "new/1" scope) p (proposal a b "p")]
    (is (= :possible-revision (:status (revisions/propose! fixture/app p))))
    (is (= :possible-revision (:status (first (:relationships (revisions/diagnostics fixture/app))))))
    (let [r {:id "c" :proposal-id "p" :base-revision 0 :action :confirm :actor "owner" :reason "Source reviewed"}]
      (revisions/decide! reviewer r)
      (is (= :confirmed-replacement (:status (first (:relationships (revisions/diagnostics fixture/app))))))
      (is (= (revisions/decide! reviewer r) (revisions/decide! reviewer r))))
    (revisions/decide! reviewer {:id "r" :proposal-id "p" :event-id "c" :base-revision 1 :action :reverse :actor "owner" :reason "Mistaken link"})
    (is (= :reversed (:status (first (:relationships (revisions/diagnostics fixture/app))))))
    (is (= 3 (count (revisions/history fixture/app "p"))))))
(deftest metadata-and-other-rows-are-not-source-bindings
  (let [a (sample "old/1" scope) b (sample "new/1" scope)]
    (doseq [binding [{:reference (:reference b) :path [:config :revision-scope :bib] :value "7"}
                     {:reference (:reference b) :path [:candidates 1 :raw :fields :source-name] :value "Éxample"}]]
      (is (thrown-with-msg? Exception #"source" (revisions/candidates fixture/app (assoc-in b [:scope :bib] binding) [a]))))))
(deftest source-scope-and-ambiguity
  (let [a (sample "a/1" scope) b (sample "b/1" scope)]
    (is (= [:ambiguous :ambiguous] (mapv :match (revisions/candidates fixture/app b [a a]))))
    (doseq [k [:event-id :date :venue :round :session :discipline :category]]
      (let [other (sample (str (name k) "/1") (assoc scope k "other"))]
        (is (= :unmatched (:match (first (revisions/candidates fixture/app b [other])))))))
    (let [a (sample "name-a/1" (assoc (dissoc scope :bib) :source-name "Éxample"))
          b (sample "name-b/1" (assoc (dissoc scope :bib) :source-name "Éxample"))]
      (is (= :name-only (:match (first (revisions/candidates fixture/app b [a])))))
      (revisions/propose! fixture/app (proposal a b "name"))
      (is (thrown-with-msg? Exception #"name-only" (revisions/decide! reviewer {:id "bad" :proposal-id "name" :base-revision 0 :action :confirm :actor "owner" :reason "Names alone"}))))))
(deftest revised-only-does-not-invent-predecessor
  (let [b (sample "only/2" scope)]
    (revisions/propose! fixture/app (proposal nil b "missing"))
    (let [relationship (first (:relationships (revisions/diagnostics fixture/app)))]
      (is (= :revised-only (:status relationship)))
      (is (= :unknown (:previous-values relationship)))
      (is (nil? (get-in relationship [:request :predecessor]))))
    (is (thrown-with-msg? Exception #"missing history" (revisions/decide! reviewer {:id "bad" :proposal-id "missing" :base-revision 0 :action :confirm :actor "owner" :reason "No original"})))
    (revisions/decide! reviewer {:id "ack" :proposal-id "missing" :base-revision 0 :action :acknowledge-missing :actor "owner" :reason "Earlier source unavailable"})
    (is (= :reviewed-missing-history (:status (first (:relationships (revisions/diagnostics fixture/app))))))))
(deftest authority-concurrency-conflict-and-idempotency
  (let [a (sample "a/1" scope) b (sample "b/1" scope) p (proposal a b "p")
        r {:id "c" :proposal-id "p" :base-revision 0 :action :confirm :actor "owner" :reason "Checked"}]
    (is (= (revisions/propose! fixture/app p) (revisions/propose! fixture/app p)))
    (is (thrown-with-msg? Exception #"idempotency" (revisions/propose! fixture/app (assoc p :reason "different"))))
    (is (thrown-with-msg? Exception #"capability" (revisions/decide! fixture/app r)))
    (revisions/propose! fixture/app (assoc p :id "other"))
    (let [gate (promise) workers (mapv (fn [request] (future @gate (try (revisions/decide! reviewer request) (catch Exception _ :conflict))))
                                       [r (assoc r :id "c2" :proposal-id "other")])]
      (deliver gate true)
      (let [results (mapv deref workers)]
        (is (= 1 (count (filter #{:conflict} results))))
        (is (= 1 (:revision (revisions/diagnostics fixture/app))))))
    (is (thrown-with-msg? Exception #"Stale" (revisions/decide! reviewer (assoc r :id "stale" :action :reject))))
    (doseq [url [fixture/app reviewer] table ["revision_proposals" "revision_decisions"]
            op ["UPDATE %s SET id=id" "DELETE FROM %s" "TRUNCATE %s"]]
      (is (thrown? java.sql.SQLException (fixture/sql! url (format op (str "freediving." table))))))
    (is (thrown? java.sql.SQLException (fixture/sql! fixture/app "INSERT INTO freediving.revision_decisions(id,proposal_id,revision,action,body_edn) VALUES('fake','p',2,'confirm','{}')")))))
(deftest checksum-and-restricted-migrations
  (is (= {:schema-version 8} (revisions/migrate! fixture/admin "observations_app" "reviews_owner")))
  (is (thrown-with-msg? Exception #"Separate" (revisions/migrate! fixture/admin "observations_app" "observations_app")))
  (is (thrown-with-msg? Exception #"restricted" (revisions/migrate! fixture/admin "observations_app" (System/getProperty "user.name"))))
  (fixture/sql! fixture/admin "UPDATE freediving.schema_migrations SET sha256='tampered' WHERE version=8")
  (is (thrown-with-msg? Exception #"checksum" (revisions/migrate! fixture/admin "observations_app" "reviews_owner"))))
(deftest forged-sql-envelope-and-stale-reference
  (let [a (sample "old/1" scope) b (sample "new/1" scope) p (proposal a b "p")]
    (is (thrown-with-msg? Exception #"provenance" (revisions/propose! fixture/app (assoc-in p [:successor :reference :artifact-sha256] "wrong"))))
    (revisions/propose! fixture/app p)
    (fixture/sql! fixture/admin "ALTER TABLE freediving.revision_proposals DISABLE TRIGGER immutable_revision_proposals")
    (fixture/sql! fixture/admin "UPDATE freediving.revision_proposals SET successor_ordinal=1 WHERE id='p'")
    (is (thrown-with-msg? Exception #"envelope" (revisions/decide! reviewer {:id "bad" :proposal-id "p" :base-revision 0 :action :confirm :actor "owner" :reason "Tampered"})))))
(deftest tamper-is-rejected-on-idempotent-replay
  (let [a (sample "a/1" scope) b (sample "b/1" scope) p (proposal a b "p")
        r {:id "c" :proposal-id "p" :base-revision 0 :action :confirm :actor "owner" :reason "Checked"}]
    (revisions/propose! fixture/app p)
    (revisions/decide! reviewer r)
    (fixture/sql! fixture/admin "ALTER TABLE freediving.observations DISABLE TRIGGER immutable_observations")
    (fixture/sql! fixture/admin "UPDATE freediving.observations SET candidate_id='forged' WHERE ordinal=0")
    (is (thrown-with-msg? Exception #"provenance" (revisions/propose! fixture/app p)))
    (is (thrown-with-msg? Exception #"provenance" (revisions/decide! reviewer r)))
    (is (thrown-with-msg? Exception #"provenance" (revisions/history fixture/app "p")))))
(deftest self-edges-are-not-candidates
  (let [a (sample "self/1" scope)]
    (is (= :unmatched (:match (first (revisions/candidates fixture/app a [a])))))
    (is (thrown-with-msg? Exception #"Distinct" (revisions/propose! fixture/app (proposal a a "self"))))))
(defn changed-source [text retrieved-at]
  (let [dir (archive-fixture/workspace) root (str dir "/archive") path (str dir "/source.pdf")
        bytes (pdf/synthetic-pdf (str "BT /F1 12 Tf 40 750 Td (" text ") Tj ET"))
        digest (.formatHex (java.util.HexFormat/of) (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes bytes "UTF-8")))]
    (spit path bytes)
    (archive/register! root path (assoc archive-fixture/manifest :sha256 digest :retrieved-at retrieved-at))
    [root digest]))
(deftest changed-bytes-and-out-of-order-retrieval-do-not-prove-changed-results
  (let [a (sample "version/1" scope (changed-source "Earlier report" "2026-09-24T12:00:00Z"))
        b (sample "version/2" scope (changed-source "Revised report" "2026-09-23T12:00:00Z"))]
    (is (not= (get-in a [:reference :source-sha256]) (get-in b [:reference :source-sha256])))
    (doseq [[before after] [[a b] [b a]]]
      (is (= :possible-revision (:match (first (revisions/candidates fixture/app after [before]))))))
    (let [p (revisions/propose! fixture/app (proposal a b "version"))]
      (is (= :possible-revision (:status p)))
      (is (= :not-inferred (:previous-values p)))
      (is (= 0 (:revision (revisions/diagnostics fixture/app)))))))
(deftest active-endpoints-are-exclusive-and-rejection-is-reversible
  (let [a (sample "a/1" scope) b (sample "b/1" scope) c (sample "c/1" scope)
        decision (fn [id p base action] {:id id :proposal-id p :base-revision base :action action :actor "owner" :reason "Synthetic checked"})]
    (revisions/propose! fixture/app (proposal a b "ab"))
    (revisions/decide! reviewer (decision "confirm-ab" "ab" 0 :confirm))
    (doseq [[id before after] [["ba" b a] ["ac" a c] ["bc" b c] ["duplicate" a b]]]
      (revisions/propose! fixture/app (assoc (proposal before after id) :base-revision 1))
      (is (thrown-with-msg? Exception #"Conflicting active" (revisions/decide! reviewer (decision (str "confirm-" id) id 1 :confirm)))))
    (revisions/decide! reviewer (decision "reject-ac" "ac" 1 :reject))
    (is (= :rejected (:status (first (filter #(= "ac" (:id %)) (:relationships (revisions/diagnostics fixture/app)))))))
    (revisions/decide! reviewer (assoc (decision "reverse-ac" "ac" 2 :reverse) :event-id "reject-ac"))
    (is (= :reversed (:status (first (filter #(= "ac" (:id %)) (:relationships (revisions/diagnostics fixture/app)))))))
    (is (thrown-with-msg? Exception #"active" (revisions/decide! reviewer (assoc (decision "again" "ac" 3 :reverse) :event-id "reject-ac"))))))
(deftest scalar-attempt-scope-and-conflicting-athlete-ids
  (let [a (sample "a/1" (assoc scope :attempt "1" :source-athlete-id "one"))
        b (sample "b/1" (assoc scope :attempt "2" :source-athlete-id "one"))
        c (sample "c/1" (assoc scope :attempt "1" :source-athlete-id "two"))]
    (doseq [other [b c]]
      (is (= :unmatched (:match (first (revisions/candidates fixture/app a [other])))))))
  (doseq [value [true {:fake "7"} ["7"] ""]]
    (let [a (sample (str "bad-" (hash value)) (assoc scope :bib value))]
      (is (thrown-with-msg? Exception #"scalars" (revisions/candidates fixture/app a []))))))
(deftest participant-scope-cannot-use-another-page-row
  (let [a (sample "a/1" scope) b (sample "b/1" scope)]
    (doseq [k [:bib :source-athlete-id :source-name :attempt]]
      (let [bad (assoc-in b [:scope k] {:reference (:reference b) :path [:pages 0 :lines 1 :text] :value "  Éxample  002  "})]
        (is (thrown-with-msg? Exception #"own-row" (revisions/candidates fixture/app bad [a])))))))
(deftest revision-evidence-must-have-a-nonblank-scalar-value
  (let [a (sample "a/1" scope) b (sample "b/1" scope)]
    (doseq [binding [{:reference (:reference b) :path [:candidates 0 :raw :fields] :value (get-in (:artifact (observations/inspect fixture/app (get-in b [:reference :job-id]))) [:candidates 0 :raw :fields])}]]
      (is (thrown-with-msg? Exception #"scalar" (revisions/propose! fixture/app (assoc (proposal a b "p") :revision-evidence [{:kind :version :binding binding}])))))))
(deftest blank-and-missing-revision-tokens-are-not-evidence
  (let [a (sample "a/1" scope)]
    (doseq [[n value] (map-indexed vector [nil "" "  " false [] {}])]
      (let [b (update (sample (str "bad-note/" n) (assoc scope :note value)) :scope dissoc :note)
            binding {:reference (:reference b) :path [:candidates 0 :raw :fields :note] :value value}]
        (is (thrown-with-msg? Exception #"scalar" (revisions/propose! fixture/app (assoc (proposal a b (str "p" n)) :revision-evidence [{:kind :version :binding binding}]))))))))
(deftest decision-audit-envelope-is-checked-by-history
  (let [a (sample "a/1" scope) b (sample "b/1" scope)]
    (revisions/propose! fixture/app (proposal a b "p"))
    (revisions/decide! reviewer {:id "c" :proposal-id "p" :action :confirm :base-revision 0 :actor "owner" :reason "Checked"})
    (fixture/sql! fixture/admin "ALTER TABLE freediving.revision_decisions DISABLE TRIGGER immutable_revision_decisions")
    (fixture/sql! fixture/admin "UPDATE freediving.revision_decisions SET action='reject'")
    (is (thrown-with-msg? Exception #"envelope" (revisions/history fixture/app "p")))
    (is (thrown-with-msg? Exception #"envelope" (revisions/diagnostics fixture/app)))))
(deftest retained-filename-version-is-evidence-only
  (let [[root digest :as source] (changed-source "Revised only" "2026-09-23T12:00:00Z")
        url "https://example.org/results-v2.pdf"
        _ (archive/register! root (:artifact-path (archive/inspect root digest)) (assoc archive-fixture/manifest :sha256 digest :final-url url))
        b (sample "url-version/2" scope source)
        artifact (:artifact (observations/inspect fixture/app (get-in b [:reference :job-id])))
        index (first (keep-indexed (fn [i acquisition] (when (= url (get-in acquisition [:manifest :final-url])) i)) (:acquisitions artifact)))
        binding {:reference (:reference b) :path [:acquisitions index :manifest :final-url] :value url}
        p (assoc (proposal nil b "url-version") :revision-evidence [{:kind :version :binding binding}])]
    (is (= :revised-only (:status (revisions/propose! fixture/app p))))
    (is (= [(:revision-evidence p)] (mapv #(get-in % [:request :revision-evidence]) (revisions/history fixture/app "url-version"))))
    (is (= 0 (:revision (revisions/diagnostics fixture/app))))
    (doseq [bad [(assoc binding :value "https://example.org/forged-v2.pdf")
                 (assoc binding :path [:acquisitions 999 :manifest :final-url])
                 (assoc binding :path [:acquisitions index :manifest :retrieved-at] :value "2026-09-23T12:00:00Z")]]
      (is (thrown? Exception (revisions/propose! fixture/app (assoc p :id "bad" :revision-evidence [{:kind :version :binding bad}])))))
    (doseq [k [:event-id :bib :source-athlete-id]]
      (is (thrown? Exception (revisions/candidates fixture/app (assoc-in b [:scope k] binding) []))))))

(defn html-sample
  ([source] (html-sample source "https://example.org/StartList/1"))
  ([source url] (html-sample source url nil))
  ([source url filters]
   (let [dir (archive-fixture/workspace) root (str dir "/archive") file (str dir "/source.html")
         hash (html-evidence/sha256 (.getBytes source "UTF-8"))
         _ (spit file source)
         evidence (when filters (archive/retain-evidence! root (.getBytes source "UTF-8")))
         manifest (cond-> (-> archive-fixture/manifest
                              (assoc :sha256 hash :content-type "text/html" :final-url url)
                              (dissoc :provenance))
                    filters (assoc :provenance {:publisher-url "https://example.org/" :redirect-chain [url]
                                                :browser-state {:selected-date "2025-06-28" :filters filters
                                                                :representation :rendered-dom :rendered-sha256 (:sha256 evidence)}}))
         _ (archive/register! root file manifest)
         a (html/extract! root hash {:actor "synthetic" :config {}})]
     (observations/import! fixture/app root (:job-id a))
     (revisions/reference fixture/app {:job-id (:job-id a) :ordinal 0}))))
(defn html-descriptor [ref values]
  {:reference ref :scope (into {} (for [[k v] values] [k {:reference ref :path [:html-scope k] :value v}]))})
(deftest retained-html-scope-is-replayed-without-inventing-missing-fields
  (let [ref (html-sample (str "<h1>Synthetic championship</h1>" (html-fixture/document html-fixture/cells)))
        d (html-descriptor ref {:event-name "Synthetic championship" :date "2025-06-28"
                                :discipline "DYNB" :category "Female" :source-name "ÉXAMPLE  & Person"})]
    (is (= [] (revisions/candidates fixture/app d [])))
    (doseq [field [:venue :round :session :bib :attempt]]
      (is (thrown? Exception (revisions/candidates fixture/app (html-descriptor ref {field "1"}) []))))))

(deftest html-start-order-is-never-a-bib-or-session
  (let [ref (html-sample (html-fixture/document html-fixture/cells))
        binding {:reference ref :path [:candidates 0 :raw :fields "Start"] :value "1"}]
    (doseq [field [:bib :attempt :session :round :event-id]]
      (is (thrown? Exception (revisions/candidates fixture/app {:reference ref :scope {field binding}} []))))))

(deftest retained-profile-link-is-scoped-source-id-only
  (let [profile "https://www.aidainternational.org/Profile-00000000-0000-0000-0000-000000000001"
        ref (html-sample (html-fixture/document (assoc html-fixture/cells 1 (str "<a href='" profile "'>Synthetic Person</a>"))))]
    (is (= [] (revisions/candidates fixture/app (html-descriptor ref {:source-athlete-id profile}) [])))))

(deftest registered-aida-route-binds-event-id-without-treating-day-index-as-session
  (let [ref (html-sample (html-fixture/document html-fixture/cells)
                         "https://www.aidainternational.org/StartList/1234?day_index=3")]
    (is (= [] (revisions/candidates fixture/app (html-descriptor ref {:event-id "1234" :federation "AIDA"}) [])))
    (is (thrown? Exception (revisions/candidates fixture/app (html-descriptor ref {:session "3"}) [])))))

(deftest retained-row-and-selected-discipline-cannot-conflict
  (let [ref (html-sample (str "<select id='discipline'><option selected>DYN</option></select>"
                              (html-fixture/document html-fixture/cells)))]
    (is (thrown? Exception (revisions/candidates fixture/app (html-descriptor ref {:discipline "DYNB"}) [])))))

(deftest hidden-and-ambiguous-context-cannot-supply-scope
  (doseq [[source values]
          [[(str "<div hidden><h1>Hidden event</h1></div>" (html-fixture/document html-fixture/cells)) {:event-name "Hidden event"}]
           [(str "<h1>One</h1><h1>Two</h1>" (html-fixture/document html-fixture/cells)) {:event-name "One"}]
           [(str "<div hidden>" (html-fixture/document html-fixture/cells) "</div>") {:date "2025-06-28"}]
           [(html-fixture/document (assoc html-fixture/cells 1 "<span hidden>Hidden athlete</span>")) {:source-name "Hidden athlete"}]
           [(str "<select id='discipline' hidden><option selected>DYN</option></select>"
                 (html-fixture/document html-fixture/cells)) {:discipline "DYN"}]]]
    (let [ref (html-sample source)]
      (is (thrown? Exception (revisions/candidates fixture/app (html-descriptor ref values) []))))))

(deftest rehashed-html-context-tampering-still-fails-source-replay
  (let [ref (html-sample (html-fixture/document html-fixture/cells))
        artifact (:artifact (observations/inspect fixture/app (:job-id ref)))
        forged (assoc-in artifact [:context :event-date] "2099-01-01")
        bytes (.getBytes (pr-str forged) "UTF-8")
        hash (html-evidence/sha256 bytes)
        forged-ref (assoc ref :artifact-sha256 hash)]
    (fixture/sql! fixture/admin "ALTER TABLE freediving.extractions DISABLE TRIGGER USER")
    (with-open [c (java.sql.DriverManager/getConnection fixture/admin)
                s (.prepareStatement c "UPDATE freediving.extractions SET artifact_bytes=?,artifact_sha256=? WHERE job_id=?")]
      (.setBytes s 1 bytes) (.setString s 2 hash) (.setString s 3 (:job-id ref)) (.executeUpdate s))
    (fixture/sql! fixture/admin "ALTER TABLE freediving.extractions ENABLE TRIGGER USER")
    (is (thrown-with-msg? Exception #"replay" (revisions/candidates fixture/app (html-descriptor forged-ref {:date "2099-01-01"}) [])))
    (is (thrown-with-msg? Exception #"replay" (revisions/candidates fixture/app
                                                                    {:reference forged-ref :scope {:source-name {:reference forged-ref :path [:candidates 0 :raw :fields "Diver"] :value "ÉXAMPLE  & Person"}}} [])))))

(deftest acquisition-filters-must-agree-with-row-without-selected-controls
  (doseq [filters [{:discipline :dyn} {:gender :men}]]
    (let [ref (html-sample (html-fixture/document html-fixture/cells) "https://example.org/StartList/1" filters)]
      (is (thrown-with-msg? Exception #"Conflicting" (revisions/candidates fixture/app
                                                                           (html-descriptor ref {:discipline "DYNB" :category "Female"}) [])))
      (is (thrown-with-msg? Exception #"Conflicting" (revisions/candidates fixture/app
                                                                           {:reference ref :scope {:discipline {:reference ref :path [:candidates 0 :raw :fields "Discipline"] :value "DYNB"}}} [])))))
  (doseq [filters [{} {:discipline :all :gender :all} {:discipline :dynb :gender :women}]]
    (let [ref (html-sample (html-fixture/document html-fixture/cells) "https://example.org/StartList/1" filters)]
      (is (= [] (revisions/candidates fixture/app (html-descriptor ref {:discipline "DYNB" :category "Female"}) []))))))

(def daily-profile "https://www.aidainternational.org/Profile-00000000-0000-0000-0000-000000000001")
(def daily-values {:federation "AIDA" :event-id "4349" :date "2025-06-28"
                   :discipline "DYNB" :category "Female" :source-athlete-id daily-profile})
(defn daily-document []
  (str "<h1>Synthetic championship</h1>"
       (html-fixture/document (assoc html-fixture/cells 1 (str "<a href='" daily-profile "'>Synthetic Person</a>")))))
(defn daily-descriptor [ref]
  (assoc (html-descriptor ref daily-values) :scope-contract :aida-date-view/v1))
(deftest explicit-aida-date-view-contract-yields-only-a-possible-match
  (let [a (daily-descriptor (html-sample (daily-document) "https://www.aidainternational.org/StartList/4349"))
        b (daily-descriptor (html-sample (str (daily-document) "<!-- recapture -->") "https://www.aidainternational.org/StartList/4349"))]
    (is (= [:possible-revision] (mapv :match (revisions/candidates fixture/app b [a]))))
    (is (= [:unmatched] (mapv :match (revisions/candidates fixture/app (dissoc b :scope-contract) [a]))))))

(deftest daily-scope-requires-complete-unique-participant-context-across-the-artifact
  (let [duplicate (str/replace (daily-document) "</tbody>" (str "<tr>" (apply str (map #(str "<td>" % "</td>") (assoc html-fixture/cells 1 (str "<a href='" daily-profile "'>Same person second round</a>")))) "</tr></tbody>"))
        a (daily-descriptor (html-sample duplicate "https://www.aidainternational.org/StartList/4349"))]
    (is (thrown-with-msg? Exception #"Ambiguous daily participant" (revisions/candidates fixture/app a []))))
  (let [a (daily-descriptor (html-sample (daily-document) "https://www.aidainternational.org/StartList/4349"))]
    (doseq [k (keys daily-values)]
      (is (thrown-with-msg? Exception #"daily scope" (revisions/candidates fixture/app (update a :scope dissoc k) []))))
    (doseq [k [:venue :round :session :attempt :bib]]
      (is (thrown? Exception (revisions/candidates fixture/app (assoc-in a [:scope k] (get-in a [:scope :event-id])) [])))))
  (let [a (daily-descriptor (html-sample (str/replace (daily-document) "<h1>" "<h1 hidden>") "https://www.aidainternational.org/StartList/4349"))]
    (is (thrown-with-msg? Exception #"daily source context" (revisions/candidates fixture/app a [])))))

(deftest daily-scope-refuses-incomplete-or-unsupported-row-census
  (doseq [source [(str (daily-document) "<table><tr><td>Second attempt not parsed</td></tr></table>")
                  (str/replace (daily-document) "</tbody>" "<tr><td>2</td><td>Malformed second participant</td></tr></tbody>")]]
    (let [a (daily-descriptor (html-sample source "https://www.aidainternational.org/StartList/4349"))]
      (is (thrown-with-msg? Exception #"daily row census" (revisions/candidates fixture/app a []))))))

(deftest daily-participant-ambiguity-cannot-be-hidden-by-source-spelling
  (doseq [[discipline gender] [[" DYNB " "Female"] ["DYNB" "F"] ["dynb" "FEMALE"]]]
    (let [second-row (str "<tr>" (apply str (map #(str "<td>" % "</td>") (assoc html-fixture/cells 1 (str "<a href='" daily-profile "'>Same participant</a>") 3 gender 4 discipline))) "</tr>")
          source (str/replace (daily-document) "</tbody>" (str second-row "</tbody>"))
          a (daily-descriptor (html-sample source "https://www.aidainternational.org/StartList/4349"))]
      (is (thrown-with-msg? Exception #"Ambiguous daily participant" (revisions/candidates fixture/app a []))))))

(deftest daily-contract-binds-exact-fields-versions-and-distinct-dates-events
  (let [a (daily-descriptor (html-sample (daily-document) "https://www.aidainternational.org/StartList/4349"))
        next-date (assoc-in (daily-descriptor (html-sample (str/replace (daily-document) "2025-06-28" "2025-06-29") "https://www.aidainternational.org/StartList/4349")) [:scope :date :value] "2025-06-29")
        other-event (assoc-in (daily-descriptor (html-sample (daily-document) "https://www.aidainternational.org/StartList/4350")) [:scope :event-id :value] "4350")]
    (is (= [:unmatched :unmatched] (mapv :match (revisions/candidates fixture/app a [next-date other-event]))))
    (doseq [[field value] [[:event-id "4350"] [:date "2025-06-29"] [:category "Male"] [:source-athlete-id (str/replace daily-profile "0001" "0002")]]]
      (is (thrown? Exception (revisions/candidates fixture/app (assoc-in a [:scope field :value] value) []))))
    (is (thrown? Exception (revisions/candidates fixture/app (assoc-in a [:scope :date :path] [:context :event-date]) [])))
    (is (thrown? Exception (revisions/candidates fixture/app (assoc-in a [:scope :date :reference] (:reference next-date)) [])))
    (is (thrown? Exception (revisions/candidates fixture/app (assoc a :scope-contract :unknown/v1) [])))
    (let [b (daily-descriptor (html-sample (str (daily-document) "<!-- version b -->") "https://www.aidainternational.org/StartList/4349"))
          c (daily-descriptor (html-sample (str (daily-document) "<!-- version c -->") "https://www.aidainternational.org/StartList/4349"))]
      (is (= [:ambiguous :ambiguous] (mapv :match (revisions/candidates fixture/app c [a b])))))))

(deftest rehashed-daily-context-cannot-manufacture-a-date
  (let [ref (html-sample (daily-document) "https://www.aidainternational.org/StartList/4349")
        artifact (:artifact (observations/inspect fixture/app (:job-id ref)))
        bytes (.getBytes (pr-str (assoc-in artifact [:context :event-date] "2099-01-01")) "UTF-8")
        hash (html-evidence/sha256 bytes)
        forged-ref (assoc ref :artifact-sha256 hash)]
    (fixture/sql! fixture/admin "ALTER TABLE freediving.extractions DISABLE TRIGGER USER")
    (with-open [c (java.sql.DriverManager/getConnection fixture/admin)
                s (.prepareStatement c "UPDATE freediving.extractions SET artifact_bytes=?,artifact_sha256=? WHERE job_id=?")]
      (.setBytes s 1 bytes) (.setString s 2 hash) (.setString s 3 (:job-id ref)) (.executeUpdate s))
    (fixture/sql! fixture/admin "ALTER TABLE freediving.extractions ENABLE TRIGGER USER")
    (is (thrown-with-msg? Exception #"replay" (revisions/candidates fixture/app (assoc-in (daily-descriptor forged-ref) [:scope :date :value] "2099-01-01") [])))))
