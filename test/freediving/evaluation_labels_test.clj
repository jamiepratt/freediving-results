(ns freediving.evaluation-labels-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is use-fixtures]]
            [freediving.observations-test :as f]
            [freediving.observations :as observations]
            [freediving.reviews :as reviews]
            [freediving.evaluation-labels :as labels]))
(def owner (System/getenv "FREEDIVING_TEST_REVIEW_URL"))
(use-fixtures :each (fn [test]
                      (f/sql! f/admin "DROP SCHEMA IF EXISTS freediving CASCADE")
                      (observations/migrate! f/admin "observations_app")
                      (reviews/migrate! f/admin "observations_app" "reviews_owner")
                      (labels/migrate! f/admin "reviews_owner" :synthetic)
                      (test)))
(defn sample []
  (let [{:keys [root artifact] :as s} (f/synthetic 1 "pair-label-synthetic/1")]
    (f/publish! s) (observations/import! f/app root (:job-id artifact))
    (let [stored (observations/inspect f/app (:job-id artifact))]
      (mapv (fn [i] {:job-id (:job-id artifact) :ordinal i
                     :candidate-id (:candidate_id (nth (:observations stored) i))
                     :source-sha256 (:source-sha256 artifact)
                     :artifact-sha256 (:artifact_sha256 (first (observations/list-extractions f/app))) :page 1 :line (inc i)}) [0 1]))))
(defn request [pair]
  {:id "label-1" :pair pair :base-revision 0 :review-revisions [0 0] :outcome :match
   :actor "synthetic-reviewer" :reason "Synthetic pair comparison"
   :rubric-version "pair-v1"})
(deftest explicit-pair-export-is-verifiable-and-synthetic
  (let [pair (sample) before (observations/inspect f/app (:job-id (first pair)))
        decision (labels/decide! owner (request pair))
        receipt (labels/export owner {:rubric-version "pair-v1"})]
    (is (= decision (labels/decide! owner (request pair))))
    (is (= "reviews_owner" (:db-role decision)))
    (is (= :ready (:status receipt)))
    (is (= 1 (count (get-in receipt [:dataset :cases]))))
    (is (= :synthetic (get-in receipt [:dataset :cases 0 :label :provenance])))
    (is (= :synthetic-fixture (:label-source (labels/verify! owner receipt))))
    (is (= receipt (labels/export owner {:rubric-version "pair-v1"})))
    (is (= (:observations before) (:observations (observations/inspect f/app (:job-id (first pair))))))))
(defn -main [& _]
  (let [r (clojure.test/run-tests 'freediving.evaluation-labels-test)]
    (shutdown-agents) (when (pos? (+ (:fail r) (:error r))) (System/exit 1))))
(deftest unknown-revoked-superseded-and-forged-exports
  (let [pair (sample) req (request pair)]
    (is (= :not-evaluable (:status (labels/export owner {:rubric-version "pair-v1"}))))
    (labels/decide! owner (assoc req :outcome :unknown))
    (is (= [:unknown] (mapv :reason (:exclusions (labels/export owner {:rubric-version "pair-v1"})))))
    (labels/decide! owner (assoc req :id "label-2" :base-revision 1 :outcome :no-match))
    (let [receipt (labels/export owner {:rubric-version "pair-v1"})]
      (is (= :no-match (get-in receipt [:dataset :cases 0 :label :outcome])))
      (is (thrown-with-msg? Exception #"differs" (labels/verify! owner (assoc-in receipt [:dataset :cases 0 :label :provenance] :owner))))
      (is (thrown-with-msg? Exception #"differs" (labels/verify! owner (assoc-in receipt [:snapshot :corpus-mode] :real))))
      (labels/decide! owner (assoc req :id "label-3" :base-revision 2 :outcome :revoke))
      (is (thrown-with-msg? Exception #"stale" (labels/verify! owner receipt)))
      (let [revoked (labels/export owner {:rubric-version "pair-v1"})]
        (is (= :not-evaluable (:status revoked)))
        (is (= [:superseded :superseded :revoked] (mapv :reason (:exclusions revoked))))))))
(deftest stale-reviewed-evidence-and-invalid-coordinates
  (let [pair (sample) req (request pair) t (select-keys (first pair) [:job-id :ordinal])]
    (is (thrown-with-msg? Exception #"coordinates" (labels/decide! owner (assoc-in req [:pair 0 :line] 2))))
    (labels/decide! owner req)
    (let [receipt (labels/export owner {:rubric-version "pair-v1"})]
      (reviews/propose! f/app (merge t {:id "p" :base-revision 0 :field :identity :category :identity-matching
                                        :before {:outcome :unknown} :after {:outcome :no-match}
                                        :evidence [{:page 1 :line 1}] :actor "synthetic" :reason "Generic no-match is not a pair label"}))
      (reviews/decide! owner {:id "a" :proposal-id "p" :action :approve :base-revision 0 :actor "synthetic" :reason "Synthetic only"})
      (is (thrown-with-msg? Exception #"Stale observation" (labels/decide! owner (assoc req :id "next" :base-revision 1))))
      (is (thrown-with-msg? Exception #"stale" (labels/verify! owner receipt)))
      (is (= [:stale-review] (mapv :reason (:exclusions (labels/export owner {:rubric-version "pair-v1"}))))))))
(deftest permissions-mode-and-bounds
  (let [req (request (sample))]
    (is (thrown? Exception (labels/decide! f/app req)))
    (is (thrown? Exception (labels/decide! owner (assoc req :corpus-mode :real))))
    (is (thrown-with-msg? Exception #"mode" (labels/migrate! f/admin "reviews_owner" :real)))
    (doseq [table ["evaluation_corpus" "evaluation_labels"] op ["UPDATE %s SET %s" "DELETE FROM %s" "TRUNCATE %s"]]
      (is (thrown? java.sql.SQLException (f/sql! owner (format op (str "freediving." table) (if (= table "evaluation_corpus") "mode='real'" "id=id"))))))
    (labels/decide! owner req)
    (labels/decide! owner (assoc req :id "unknown" :base-revision 1 :outcome :unknown))
    (is (thrown-with-msg? Exception #"bound" (labels/export owner {:rubric-version "pair-v1" :max-labels 1})))))
(deftest reciprocal-and-concurrent-pair-revisions
  (let [req (request (sample)) gate (promise)
        jobs (mapv (fn [r] (future @gate (try (labels/decide! owner r) (catch Exception _ :conflict))))
                   [req (assoc req :id "reciprocal" :pair (vec (reverse (:pair req))) :outcome :no-match)])]
    (deliver gate true)
    (let [results (mapv deref jobs)]
      (is (= 1 (count (filter #{:conflict} results))))
      (is (= 1 (count (get-in (labels/export owner {:rubric-version "pair-v1"}) [:dataset :cases])))))))
(defn alternate-pair [parser spelling]
  (let [{:keys [root artifact]} (f/synthetic 1 parser)
        artifact (update artifact :candidates #(mapv (fn [c] (assoc-in c [:parsed :source-name] spelling)) %))]
    (f/publish! {:root root :artifact artifact})
    (observations/import! f/app root (:job-id artifact))
    (let [stored (observations/inspect f/app (:job-id artifact))
          ext (first (filter #(= (:job-id artifact) (:job_id %)) (observations/list-extractions f/app)))]
      (mapv (fn [i] {:job-id (:job-id artifact) :ordinal i :candidate-id (:candidate_id (nth (:observations stored) i))
                     :source-sha256 (:source-sha256 artifact) :artifact-sha256 (:artifact_sha256 ext)
                     :page 1 :line (inc i)}) [0 1]))))
(deftest extraction-versions-cannot-inflate-reviewed-pairs
  (let [pair (sample) v2 (alternate-pair "pair-label-synthetic/2" "Different extraction spelling")]
    (labels/decide! owner (request pair))
    (labels/decide! owner (assoc (request v2) :id "version-2"))
    (let [receipt (labels/export owner {:rubric-version "pair-v1"})]
      (is (= 1 (count (get-in receipt [:dataset :cases]))))
      (is (= [:duplicate-pair] (mapv :reason (:exclusions receipt)))))))
(deftest revoked-copy-cannot-resurrect-old-version
  (let [p1 (sample) p2 (alternate-pair "pair-label-synthetic/2" "Example")]
    (labels/decide! owner (request p1))
    (labels/decide! owner (assoc (request p2) :id "copy" :outcome :revoke))
    (is (= :not-evaluable (:status (labels/export owner {:rubric-version "pair-v1"}))))))
(deftest contradicting-copy-labels-are-not-counted
  (let [p1 (sample) p2 (alternate-pair "pair-label-synthetic/2" "Example")]
    (labels/decide! owner (request p1))
    (labels/decide! owner (assoc (request p2) :id "copy" :outcome :no-match))
    (let [receipt (labels/export owner {:rubric-version "pair-v1"})]
      (is (= :not-evaluable (:status receipt)))
      (is (= #{:conflicting-pair-labels} (set (map :reason (:exclusions receipt))))))))
(deftest concurrent-export-is-one-consistent-snapshot
  (let [req (request (sample)) _ (labels/decide! owner req)
        before (labels/export owner {:rubric-version "pair-v1"})
        read-ready (promise) resume (promise)
        exporting (future (labels/export owner {:rubric-version "pair-v1"}
                                         {:on-snapshot #(do (deliver read-ready true) (deref resume 5000 nil))}))]
    (is (= true (deref read-ready 5000 :timeout)))
    (try
      (labels/decide! owner (assoc req :id "concurrent-revoke" :base-revision 1 :outcome :revoke))
      (finally (deliver resume true)))
    (is (= before (deref exporting 5000 :timeout)))
    (is (= :not-evaluable (:status (labels/export owner {:rubric-version "pair-v1"}))))))
(deftest private-receipts-and-reviewable-history
  (let [pair (sample) req (request pair) dir (.toRealPath (.toPath (java.io.File. "data")) (make-array java.nio.file.LinkOption 0))
        root (java.nio.file.Files/createTempDirectory dir "label-receipts-" (make-array java.nio.file.attribute.FileAttribute 0))]
    (labels/decide! owner req)
    (is (= [0 0] (:review-revisions (labels/history owner pair))))
    (is (= 1 (:base-revision (labels/history owner pair))))
    (let [receipt (labels/export owner {:rubric-version "pair-v1"}) gate (promise)
          writing (mapv (fn [_] (future @gate (labels/write-receipt! (str root) receipt))) (range 4))]
      (deliver gate true)
      (let [paths (mapv deref writing)]
        (is (apply = paths))
        (is (= receipt (edn/read-string (slurp (first paths)))))
        (is (= "rw-------" (java.nio.file.attribute.PosixFilePermissions/toString (java.nio.file.Files/getPosixFilePermissions (.toPath (java.io.File. (first paths))) (make-array java.nio.file.LinkOption 0)))))))
    (let [link (.resolve root "link") target (.resolve root "target")]
      (java.nio.file.Files/createDirectories target (make-array java.nio.file.attribute.FileAttribute 0))
      (java.nio.file.Files/createSymbolicLink link target (make-array java.nio.file.attribute.FileAttribute 0))
      (is (thrown-with-msg? Exception #"symlink" (labels/write-receipt! (str (.resolve link "nested")) (labels/export owner {:rubric-version "pair-v1"})))))))
(deftest conflicting-active-local-identity-is-excluded
  (let [pair (sample) left (first pair) right (second pair)
        p (merge (select-keys left [:job-id :ordinal])
                 {:id "identity" :base-revision 0 :category :identity-matching :field :identity
                  :before {:outcome :unknown} :after {:outcome :matched :identity-id (str "local-observation:" (:job-id right) ":" (:ordinal right))}
                  :identity-target right :evidence [left right] :actor "synthetic" :reason "Explicit local identity"})]
    (reviews/propose! f/app p)
    (reviews/decide! owner {:id "identity-approved" :proposal-id "identity" :action :approve :base-revision 0 :actor "synthetic" :reason "Synthetic only"})
    (labels/decide! owner (assoc (request pair) :outcome :no-match :review-revisions [1 0]))
    (let [receipt (labels/export owner {:rubric-version "pair-v1"})]
      (is (= :not-evaluable (:status receipt)))
      (is (= [:conflicting-identity-review] (mapv :reason (:exclusions receipt)))))))
(deftest malformed-direct-owner-envelope-is-rejected
  (let [pair (sample) decision (labels/decide! owner (request pair))
        forged (-> decision (assoc :id "forged" :pair-key "wrong-body-key" :revision 2)
                   (assoc-in [:request :id] "forged") (assoc-in [:request :base-revision] 1))]
    (with-open [c (java.sql.DriverManager/getConnection owner)
                s (.prepareStatement c "INSERT INTO freediving.evaluation_labels(id,pair_key,revision,outcome,body_edn) VALUES(?,?,2,'match',?)")]
      (.setString s 1 "forged") (.setString s 2 (:pair-key decision)) (.setString s 3 (pr-str forged)) (.executeUpdate s))
    (is (thrown-with-msg? Exception #"envelope" (labels/export owner {:rubric-version "pair-v1"})))))
