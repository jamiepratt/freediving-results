(ns freediving.owner-identity-route
  "Strict affirmative identity bridge from authenticated owner import to canonical DB."
  (:require [clojure.string :as str]
            [freediving.athlete-identity :as identity]
            [freediving.owner-decision-export :as export]))

(defn- fail! [] (throw (ex-info "Owner identity approval lacks current exact binding" {})))
(defn- position [id]
  (when-let [[_ job ordinal] (and (string? id)
                                  (re-matches #"local-observation:([0-9a-f]{64}):([0-9]+)" id))]
    [job (Long/parseLong ordinal)]))

(defn record-imported-decision!
  "Accept only the current signed owner choice for an exact DB-backed pair.
   flow-ledger must be the persisted result of import-remote-review-events."
  [reviewer-url flow-ledger decision current-binding expected-revision]
  (let [id (:id decision)
        pair (get-in decision [:subject :pair])
        positions (when (and (vector? pair) (= 2 (count pair))) (mapv position pair))
        canonical (when (and (= 2 (count positions)) (every? some? positions))
                    (identity/private-jev-decision reviewer-url (first pair) (second pair)
                                                   {:id id :dependencies (:dependencies decision)}))
        human (last (filter #(and (= :human (:origin %)) (= id (:decision-id %)))
                            (:events flow-ledger)))
        remote (:remote-event human)
        prior (last (filter #(and (= :human (:origin %)) (= id (:decision-id %))
                                  (not= (:id human) (:id %))) (:events flow-ledger)))
        binding (get-in remote [:proposal :canonical_binding])
        revisions (:observation_revisions binding)
        bound-positions (mapv (juxt :job_id :ordinal) revisions)
        actual (when (and (vector? revisions) (seq revisions)
                          (= (count revisions) (count (set bound-positions))))
                 (export/load-observation-revisions! reviewer-url bound-positions))
        canonical-positions (mapv position (:candidates canonical))
        selected (:action human)
        canonical-action (if (= "reverse" (:action remote))
                           :reverse
                           (case selected
                             :same-person :accept
                             :different-person :reject
                             nil))
        current-groups (:athletes (when (= :reject canonical-action)
                                    (identity/private-projection reviewer-url)))]
    (when-not (and (= :identity (:family decision)) (= :same-person (:action decision))
                   (= (select-keys decision [:id :family :action :choices :subject :candidates
                                             :evidence :dependencies :evidence-adequate?])
                      (select-keys canonical [:id :family :action :choices :subject :candidates
                                              :evidence :dependencies :evidence-adequate?]))
                   (nat-int? expected-revision)
                   canonical-action
                   (or (and (= :approved (:status human))
                            (#{"approve" "correct"} (:action remote)))
                       (and (= :rejected (:status human))
                            (= "reject" (:action remote))
                            (= :different-person selected))
                       (and (= :reversed (:status human))
                            (= "reverse" (:action remote))
                            (= :approved (:status prior))
                            (= :same-person (:action prior))
                            (#{"approve" "correct"} (get-in prior [:remote-event :action]))
                            (= binding (get-in prior [:remote-event :proposal :canonical_binding]))))
                   (or (not= "approve" (:action remote))
                       (= "same_person" (get-in remote [:proposal :selected_option])))
                   (= (:id human) (:id remote))
                   (= (:remote-store-revision human) (:store_revision remote))
                   (= (:remote-binding-revision human) (:binding_revision remote))
                   (pos-int? (:remote-binding-revision human))
                   (= (:remote-snapshot-sha256 human) (:snapshot_sha256 remote))
                   (string? (:remote-snapshot-sha256 human))
                   (re-matches #"[0-9a-f]{64}" (:remote-snapshot-sha256 human))
                   (or (not= "correct" (:action remote))
                       (= (name selected)
                          (some-> (get-in remote [:correction :action])
                                  (str/replace "_" "-"))))
                   (= binding current-binding) (= id (:decision_id binding))
                   (= id (:decision_id remote))
                   (every? some? canonical-positions)
                   (= (set canonical-positions) (set bound-positions))
                   (= (set revisions) (set (vals actual)))
                   (= (set revisions)
                      (set (map :observation_revision (:evidence_bindings binding))))
                   (or (not= :reject canonical-action)
                       (not= (get-in current-groups [(first pair) :group-id])
                             (get-in current-groups [(second pair) :group-id])))
                   (every? (fn [observation-id]
                             (let [[job ordinal :as pos] (position observation-id)
                                   cited (get-in canonical [:subject :observation-versions observation-id])
                                   revision (get actual pos)]
                               (and pos (= (:job-id cited) job) (= (:ordinal cited) ordinal)
                                    (= (:source-sha256 cited) (:source_sha256 revision))
                                    (= (:artifact-sha256 cited) (:artifact_sha256 revision)))))
                           (:candidates canonical))
                   (pos-int? (:reconciliation_run_revision binding))
                   (some #(= (:reconciliation_event_id binding) (:id %))
                         (take (:reconciliation_run_revision binding) (:events flow-ledger))))
      (fail!))
    (identity/record-event! reviewer-url
                            (cond-> {:id (:id human) :action canonical-action :actor-kind :human
                                     :pair pair :base-revision expected-revision
                                     :reason (:reason human) :owner-binding binding
                                     :owner-canonical-decision canonical
                                     :owner-event-revision (:remote-store-revision human)}
                              (= :reverse canonical-action) (assoc :event-id (:id prior))
                              (and (= :accept canonical-action)
                                   (#{:rejected :reversed} (:status prior)))
                              (assoc :supersedes (:id prior))))))

(defn record-imported-approval!
  "Compatibility entry point for affirmative owner identity choices."
  [reviewer-url flow-ledger decision current-binding expected-revision]
  (when-not (= :same-person
               (:action (last (filter #(and (= :human (:origin %))
                                            (= (:id decision) (:decision-id %)))
                                      (:events flow-ledger)))))
    (fail!))
  (record-imported-decision! reviewer-url flow-ledger decision
                             current-binding expected-revision))
