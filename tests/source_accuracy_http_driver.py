"""One actual synthetic owner HTTP -> restricted JVM -> PG -> paired proof tracer."""
import json
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from sporting_authority_fixture import Fixture
from sporting_authority import canonical

payload = json.load(sys.stdin)
f = Fixture()
try:
    f.canonical_context(payload['config'], payload['rows'])
    config = json.loads((f.root / 'proof-config.json').read_bytes())
    review_path = f.root / 'source-review.json'
    writer_config = {k: config[k] for k in ('database', 'runtime_path', 'runtime_manifest_sha256')}
    writer_config['jdbc_url'] = payload['review_url']
    review_path.write_bytes(canonical(writer_config)); review_path.chmod(0o600)
    f.server.source_accuracy_review = lambda request, *, deadline, replay_only=False: f.genuine_reader.review(
        request, review_path, deadline=deadline, replay_only=replay_only)
    def context():
        status, value = f.request('/owner-evidence/api/sporting-authority/proofs')
        assert status == 200, ('proof HTTP', status)
        return value
    def body(index, operation, identifier):
        proof = context(); row = proof['rows'][index]; review = row['source_review']
        return {'id': identifier, 'action': operation, 'reference': row['reference'], 'coordinates': row['coordinates'],
                'expected_source_binding_sha256': proof['pins']['canonical_scope_bindings']['source'],
                'expected_context_pins_sha256': proof['context_pins_sha256'], 'base_revision': review['revision'],
                'event_id': review['active_event'] if operation == 'revoke' else None,
                'reason': 'Synthetic human compared one exact source version', 'source_visual_accuracy': True,
                'csrf_token': f.review()['csrf_token']}
    endpoint = '/owner-evidence/api/sporting-authority/source-accuracy'
    first = body(0, 'accept', 'synthetic-http-pdf')
    stale = f.request(endpoint, {**first, 'expected_source_binding_sha256': '0' * 64})[0]
    accepted = [f.request(endpoint, first)[0]]
    replay_status, replay = f.request(endpoint, first)
    assert replay_status == 200
    unknown = f.request(endpoint, {**first, 'id': 'unrecorded-replay'})[0]
    accepted.append(f.request(endpoint, body(1, 'accept', 'synthetic-http-html'))[0])
    proof = context()
    # No independent prerequisites or sporting decision have been fabricated.
    review = f.review()
    sporting = f.request('/owner-evidence/api/sporting-authority/actions', {
        'id': 'no-prerequisites', 'action': 'source-approve', 'reason': 'Synthetic nonexistent proposal negative check',
        'expected_revision': review['revision'], 'idempotency_key': 'unmet-prerequisites', 'csrf_token': review['csrf_token']})[0]
    # Attempt the actual route with the read-only credential: exact capability refuses.
    denied_body = body(0, 'revoke', 'reader-cannot-write')
    writer_config['jdbc_url'] = config['jdbc_url']
    review_path.write_bytes(canonical(writer_config)); review_path.chmod(0o600)
    denied = f.request(endpoint, denied_body)[0]
    writer_config['jdbc_url'] = payload['review_url']
    review_path.write_bytes(canonical(writer_config)); review_path.chmod(0o600)
    revoked = [f.request(endpoint, body(i, 'revoke', 'synthetic-http-revoke-' + str(i)))[0] for i in range(2)]
    withdrawn = context()
    print(json.dumps({'accepted': accepted, 'revoked': revoked, 'stale': stale, 'unknown_replay': unknown,
                      'replayed': replay.get('replayed'), 'reader_denied_write': denied, 'sporting_approval': sporting,
                      'sporting_revision': f.review()['revision'],
                      'accuracy': [r['upstream'].get('review', {}).get('value') for r in proof['rows']],
                      'publication': [r['upstream'].get('publication') for r in proof['rows']],
                      'withdrawn': [r['upstream'].get('review') for r in withdrawn['rows']]}))
finally:
    f.close()
