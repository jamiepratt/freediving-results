(ns freediving.private-canonical-status-test
  (:require [clojure.test :refer [deftest is]]
            [freediving.private-canonical-status :as status]
            [freediving.canonical-attempt-store :as attempt]
            [freediving.athlete-identity :as identity]))

(deftest independent-scopes-require-exact-export-and-owner-bindings
  (let [snapshot (apply str (repeat 64 "a"))
        export-sha (apply str (repeat 64 "b"))
        binding {:decision_id "decision"}
        proposal {:canonical_binding binding}
        event {:id "owner-store:227" :type :same-attempt
               :owner-request {:binding binding
                               :owner-event {:id "owner-store:227" :store_revision 227
                                             :snapshot_sha256 snapshot :proposal proposal}}}
        readback {:database "canonical" :evidence-sha256 (apply str (repeat 64 "c"))
                  :ledger {:events [event] :observation-versions
                           {"view" {:observation-revision {:snapshot_sha256 snapshot}}}}
                  :projection {:revision 7 :counts {:accepted-attempts 5}}}
        config {:jdbc_url "private" :database "canonical" :snapshot_sha256 snapshot
                :exports {:same_attempt {:sha256 export-sha
                                         :export {:snapshot_sha256 snapshot
                                                  :proposals [proposal]}}}}]
    (with-redefs [attempt/private-readback (constantly readback)
                  identity/private-canonical-readback (fn [_] (throw (ex-info "stale view" {})))]
      (let [receipt (status/read-status config)]
        (is (= #{:same_attempt} (set (keys (:scopes receipt)))))
        (is (= {:revision 7 :owner_event_revision 227 :accepted_count 5
                :export_sha256 export-sha :evidence_sha256 (:evidence-sha256 readback)}
               (get-in receipt [:scopes :same_attempt]))))
      (is (empty? (:scopes (status/read-status (assoc-in config [:exports :same_attempt :export :proposals] []))))))
    (with-redefs [attempt/private-readback (constantly (assoc readback :database "wrong"))]
      (is (empty? (:scopes (status/read-status config)))))))
