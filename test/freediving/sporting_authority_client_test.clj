(ns freediving.sporting-authority-client-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [freediving.sporting-authority :as authority])
  (:import [java.security KeyPairGenerator Signature MessageDigest]
           [java.util Base64 HexFormat]
           [java.time Instant]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute PosixFilePermission]))
(defn signed-fixture []
  (let [pair (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519"))
        der (.getEncoded (.getPublic pair)) encoder (Base64/getEncoder)
        key-id (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") der))
        cfg {:endpoint "http://127.0.0.1:12345/owner-evidence/api/sporting-authority/current"
             :request_secret (apply str (repeat 32 "s")) :public_key_der (.encodeToString encoder der) :key_id key-id}
        nonce (authority/sha "synthetic-challenge")
        base {:revision 1 :previous_sha256 authority/genesis :action "stage" :publication_sha256 (authority/sha "proposed")
              :binding_sha256 (authority/sha "current-pins") :policy authority/policy :decision_sha256 (authority/sha "source-rule-decision")}
        event (assoc base :head_sha256 (authority/sha (authority/canonical-json base)))
        now (Instant/now)
        payload {:schema "sporting-authority-current/v1" :nonce nonce :revision 1 :head_sha256 (:head_sha256 event)
                 :previous_sha256 authority/genesis :issued_at (str now) :expires_at (str (.plusSeconds now 5))
                 :status "current" :binding_sha256 (:binding_sha256 event) :publication nil :publication_sha256 nil
                 :policy authority/policy :events [event]}
        sign (fn [payload]
               (let [signer (Signature/getInstance "Ed25519")]
                 (.initSign signer (.getPrivate pair))
                 (.update signer (.getBytes (authority/canonical-json payload) "UTF-8"))
                 {:schema "sporting-authority-envelope/v1" :payload payload
                  :key_id key-id :signature_base64 (.encodeToString encoder (.sign signer))}))]
    {:cfg cfg :nonce nonce :payload payload :sign sign}))
(deftest owner-signature-and-fresh-nonce-are-both-required
  (let [{:keys [cfg nonce payload sign]} (signed-fixture) signed (sign payload)]
    (is (= 1 (:revision (authority/verify-envelope cfg nonce signed))))
    (is (= 1 (:revision (authority/verify-envelope (assoc cfg :request_timeout_ms 30000) nonce signed))))
    (doseq [timeout [0 999 30001 "30000"]]
      (is (thrown? Exception (authority/verify-envelope (assoc cfg :request_timeout_ms timeout) nonce signed))))
    (doseq [envelope [(assoc-in signed [:payload :binding_sha256] (authority/sha "tampered"))
                      (assoc signed :key_id (authority/sha "different-key"))
                      (assoc signed :hmac_sha256 (authority/sha "shared-key-forgery"))]]
      (is (thrown? Exception (authority/verify-envelope cfg nonce envelope))))
    (is (thrown? Exception (authority/verify-envelope cfg (authority/sha "replayed-nonce") signed)))
    (doseq [changed [(assoc payload :expires_at (str (.minusSeconds (Instant/now) 1)))
                     (assoc payload :issued_at (str (.minusSeconds (Instant/now) 10)))
                     (assoc payload :policy "unsupported")
                     (assoc payload :private "secret")]]
      (is (thrown? Exception (authority/verify-envelope cfg nonce (sign changed)))))))
(deftest a-valid-signature-does-not-excuse-a-missing-or-changed-predecessor
  (let [{:keys [cfg nonce payload sign]} (signed-fixture)]
    (doseq [changed [(assoc payload :events [])
                     (assoc payload :revision 2)
                     (assoc-in payload [:events 0 :previous_sha256] (authority/sha "missing-predecessor"))
                     (assoc-in payload [:events 0 :head_sha256] (authority/sha "tampered-head"))
                     (assoc-in payload [:events 0 :action] "unchecked-approve")
                     (assoc-in payload [:events 0 :decision_sha256] nil)
                     (assoc payload :head_sha256 (authority/sha "changed-head"))]]
      (is (thrown? Exception (authority/verify-envelope cfg nonce (sign changed)))))))
(deftest signature-cannot-bind-a-different-publication-to-an-existing-reviewed-event
  (let [{:keys [cfg nonce payload sign]} (signed-fixture)
        publication {:schema "public-sporting/v1" :rows []}
        changed (assoc payload :publication publication :publication_sha256 (authority/sha (authority/canonical-json publication)))]
    (is (thrown? Exception (authority/verify-envelope cfg nonce (sign changed))))))
(deftest signer-pin-configuration-must-be-protected
  (let [{:keys [cfg]} (signed-fixture) dir (Files/createTempDirectory "sporting-config-synthetic" (make-array FileAttribute 0))
        file (.resolve dir "config.json") link (.resolve dir "symlink.json")]
    (try
      (spit (.toFile file) (authority/canonical-json cfg))
      (Files/setPosixFilePermissions file #{PosixFilePermission/OWNER_READ PosixFilePermission/OWNER_WRITE})
      (is (= cfg (authority/read-config (str file))))
      ;; A root-owned file is the real deployment case. Its filesystem principal
      ;; is the only stand-in on developer machines where tests run unprivileged.
      (let [original-user (System/getProperty "user.name")]
        (try
          (System/setProperty "user.name" "root")
          (with-redefs-fn {#'authority/file-owner (fn [_ _] "root")}
            #(is (= cfg (authority/read-config (str file)))))
          (finally (System/setProperty "user.name" original-user))))
      (Files/createSymbolicLink link file (make-array FileAttribute 0))
      (is (thrown? Exception (authority/read-config (str link))))
      (Files/setPosixFilePermissions file #{PosixFilePermission/OWNER_READ PosixFilePermission/OTHERS_READ})
      (is (thrown? Exception (authority/read-config (str file))))
      (finally (Files/deleteIfExists link) (Files/deleteIfExists file) (Files/deleteIfExists dir)))))
(defn -main [& _]
  (let [r (run-tests 'freediving.sporting-authority-client-test)]
    (shutdown-agents)
    (when (pos? (+ (:fail r) (:error r))) (System/exit 1))))
