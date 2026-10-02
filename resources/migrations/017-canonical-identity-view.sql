-- Private derived identity projection. Source observations and the event ledger remain authoritative.
CREATE TABLE freediving.canonical_identity_view (
 singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
 identity_revision integer NOT NULL CHECK (identity_revision >= 0),
 evidence_sha256 text NOT NULL CHECK (evidence_sha256 ~ '^[0-9a-f]{64}$'),
 body_edn text NOT NULL,
 rebuilt_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
REVOKE ALL ON freediving.canonical_identity_view FROM PUBLIC;
