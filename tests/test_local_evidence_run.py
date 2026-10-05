import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import subprocess
import sys
import threading
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / 'scripts' / 'local_evidence_run.py'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(plan, directory):
    return subprocess.run([sys.executable, str(SCRIPT), 'run', '--plan', str(plan), '--run-dir', str(directory)], capture_output=True, text=True)


def fixture(tmp_path):
    packet = tmp_path / 'packet.json'
    source = tmp_path / 'source.pdf'
    source.write_bytes(b'%PDF-1.4\nsynthetic')
    generated = json.dumps({'schema': 'synthetic/v1', 'positions': [{'id': 'row-1', 'citation': {'page': 1}}]})
    counter = tmp_path / 'counter.txt'
    producer = tmp_path / 'producer.py'
    producer.write_text('import pathlib,sys\npacket,counter,content=map(pathlib.Path,sys.argv[1:])\nif content.with_suffix(".fail").exists(): raise SystemExit(8)\ncounter.write_text(counter.read_text()+"x" if counter.exists() else "x")\npacket.write_text(content.read_text())\n')
    content = tmp_path / 'content.txt'
    content.write_text(generated)
    inventory = tmp_path / 'inventory.json'
    inventory.write_text(json.dumps({'sources': [{'id': 'source.pdf', 'sha256': sha(source), 'bytes': source.stat().st_size, 'content_type': 'application/pdf', 'source_path': str(source), 'receipt': {'origin': 'synthetic'}, 'classification': 'eligible', 'metadata': {}}]}))
    plan = tmp_path / 'plan.json'
    plan.write_text(json.dumps({'schema': 'local-evidence-run-plan/v1', 'cutoff': '2026-10-03T00:00:00Z', 'stages': [{'name': 'parse', 'command': [sys.executable, str(producer), str(packet), str(counter), str(content)], 'outputs': [str(packet)]}], 'inputs': [{'name': 'packet', 'path': str(packet)}], 'excluded': [], 'source_inventory': str(inventory)}))
    return plan, packet, counter, inventory


def microplus_normal_fixture(tmp_path):
    from tests.test_cmas_microplus_snapshot_observations import fixture as microplus_fixture

    name, record_id, row = microplus_fixture(tmp_path)
    alternate_url = row['citation']['url'].replace('/17/', '/16/')
    alternate_bytes = json.dumps([{'unrelated': True}, row['raw_fields']]).encode()
    alternate_hash = hashlib.sha256(alternate_bytes).hexdigest()
    (tmp_path / 'unit-16-results.json').write_bytes(alternate_bytes)
    (tmp_path / 'unit-16-results.receipt.json').write_text(json.dumps({
        'status': 200, 'sha256': alternate_hash, 'bytes': len(alternate_bytes),
        'final_url': alternate_url}))
    alternate = {'url': alternate_url, 'source_sha256': alternate_hash, 'json_pointer': '/1'}
    row['alternate_citations'] = [alternate]
    packet = tmp_path / 'packet.json'
    data = json.loads(packet.read_text())
    data.update(issue_namespace='#55', competition_scope={'ids': [28, 33, 34, 35]},
                counts={'competitions': 4, 'units': 92, 'source_objects': 2,
                        'source_positions': 1, 'aggregate_rows': 0, 'gaps': 0,
                        'empty_units': 0})
    data['positions'][0] = row
    data['sources'].append(dict(data['sources'][0], id='sha256:' + alternate_hash,
                                unit_id=16, url=alternate_url, sha256=alternate_hash,
                                bytes=len(alternate_bytes), row_count=2))
    packet.write_text(json.dumps(data))
    inventory = tmp_path / 'inventory.json'
    inventory.write_text(json.dumps({'sources': []}))
    plan = tmp_path / 'plan.json'
    plan.write_text(json.dumps({
        'schema': 'local-evidence-run-plan/v1', 'cutoff': '2026-10-05T00:00:00Z',
        'stages': [], 'inputs': [{'name': name, 'path': str(packet)}],
        'excluded': [], 'source_inventory': str(inventory),
        'reconciliation': {'mode': 'microplus_attempt', 'source_names': [name],
                           'record_ids': [record_id], 'name_evidence': {
                               'status': 'gap', 'reason': 'identity outside same-attempt cohort'}}}))
    return plan, record_id


def test_normal_run_exports_verified_microplus_attempt_without_provider(tmp_path):
    plan, record_id = microplus_normal_fixture(tmp_path)
    target = tmp_path / 'run'
    first = run(plan, target)
    assert first.returncode == 0, first.stderr
    state = json.loads((target / 'state.json').read_text())
    assert state['local']['status'] == 'complete'
    assert state['reconciliation']['status'] == 'complete'
    assert state['reconciliation']['mode'] == 'microplus_attempt'
    assert state['reconciliation']['provider_calls'] == 0
    assert state['reconciliation']['pending_proposals'] == 1
    assert state['coverage']['confirmed_distinct_attempts'] is None
    assert state['remote']['active'] is None
    flow = target / 'reconciliation' / 'flow.edn'
    owner = target / 'reconciliation' / 'owner.sqlite'
    export = target / 'reconciliation' / 'pending-export.json'
    hashes = [sha(path) for path in (flow, owner, export)]
    second = run(plan, target)
    assert second.returncode == 0, second.stderr
    assert [sha(path) for path in (flow, owner, export)] == hashes


def test_microplus_metrics_reports_verified_local_checkpoint_without_authority(tmp_path):
    plan, _ = microplus_normal_fixture(tmp_path)
    target = tmp_path / 'run'
    assert run(plan, target).returncode == 0
    command = [sys.executable, str(SCRIPT), 'metrics', '--run-dir', str(target)]
    measured = subprocess.run(command, capture_output=True, text=True)
    assert measured.returncode == 0, measured.stderr
    report = json.loads(measured.stdout)
    state = json.loads((target / 'state.json').read_text())
    assert report['schema'] == 'microplus-attempt-local-metrics/v1'
    assert report['binding']['snapshot_sha256'] == state['local']['snapshot_sha256']
    assert report['binding']['flow_sha256'] == sha(target / 'reconciliation/flow.edn')
    assert report['binding']['export_sha256'] == sha(target / 'reconciliation/pending-export.json')
    assert report['counts']['pending_proposals'] == 1
    assert report['provider_calls'] == 0
    assert report['confirmed_distinct_attempts'] is None
    assert report['accepted_athletes'] is None
    assert report['remote_status'] == 'pending'
    with (target / 'reconciliation/flow.edn').open('ab') as output:
        output.write(b'\n')
    changed = subprocess.run(command, capture_output=True, text=True)
    assert changed.returncode != 0
    assert 'Microplus metrics checkpoint binding changed' in changed.stderr


def test_microplus_run_resumes_after_interrupted_export_and_preserves_correction(tmp_path):
    sys.path.insert(0, str(SCRIPT.parent))
    from owner_decision_store import DecisionStore

    plan, record_id = microplus_normal_fixture(tmp_path)
    target = tmp_path / 'run'
    assert run(plan, target).returncode == 0
    state_path = target / 'state.json'
    state = json.loads(state_path.read_text())
    flow_path = target / 'reconciliation/flow.edn'
    flow_hash = sha(flow_path)
    export_path = target / 'reconciliation/pending-export.json'
    export_hash = sha(export_path)
    state['reconciliation']['status'] = 'running'
    state_path.write_text(json.dumps(state))
    resumed = run(plan, target)
    assert resumed.returncode == 0, resumed.stderr
    assert sha(flow_path) == flow_hash
    assert sha(export_path) == export_hash
    store = DecisionStore(target / 'reconciliation/owner.sqlite')
    try:
        decision_id = 'microplus-attempt-' + record_id
        store.act(decision_id, action='correct', expected_revision=store.revision,
                  idempotency_key='human-correction', correction={'pair': []})
        correction = store.inspect(decision_id)
    finally:
        store.close()
    replayed = run(plan, target)
    assert replayed.returncode == 0, replayed.stderr
    replay_state = json.loads(state_path.read_text())
    assert replay_state['reconciliation']['pending_proposals'] == 0
    assert replay_state['reconciliation']['owner_store_revision'] > state['reconciliation']['owner_store_revision']
    assert json.loads(export_path.read_text())['store_revision'] < replay_state['reconciliation']['owner_store_revision']
    store = DecisionStore(target / 'reconciliation/owner.sqlite')
    try:
        assert store.inspect(decision_id) == correction
    finally:
        store.close()
    assert sha(flow_path) == flow_hash
    assert sha(export_path) == export_hash


def test_microplus_run_rejects_tampered_evidence_and_unsupported_scope(tmp_path):
    plan, record_id = microplus_normal_fixture(tmp_path)
    target = tmp_path / 'run'
    assert run(plan, target).returncode == 0
    source = tmp_path / 'unit-17-results.json'
    original = source.read_bytes()
    source.write_bytes(b'[{}]')
    tampered = run(plan, target)
    assert tampered.returncode != 0
    assert 'Microplus source receipt mismatch' in tampered.stderr
    source.write_bytes(original)
    wrong = json.loads(plan.read_text())
    wrong['reconciliation']['record_ids'] = ['0' * 64]
    different_plan = tmp_path / 'unsupported-plan.json'
    different_plan.write_text(json.dumps(wrong))
    unsupported = run(different_plan, tmp_path / 'unsupported-run')
    assert unsupported.returncode != 0
    assert 'selected Microplus record absent' in unsupported.stderr


def test_microplus_flow_cli_rejects_inadequate_source_binding(tmp_path):
    from scripts.cmas_microplus_snapshot_observations import load_attempt_evidence

    plan, record_id = microplus_normal_fixture(tmp_path)
    target = tmp_path / 'run'
    assert run(plan, target).returncode == 0
    evidence = load_attempt_evidence(target / 'snapshot', ['cmas-microplus-2026'],
                                     record_ids=[record_id])['evidence']
    evidence['observation-versions'][0]['scope-evidence']['fields']['attempt'] = -1
    result = subprocess.run(
        ['clojure', '-M', '-m', 'freediving.microplus-local-run'],
        input=json.dumps({'evidence': evidence,
                          'decision_ids': ['microplus-attempt-' + record_id],
                          'flow_path': str(tmp_path / 'inadequate-flow.edn')}),
        text=True, capture_output=True, cwd=SCRIPT.parents[1])
    assert result.returncode != 0
    assert 'source-bound' in result.stderr


def test_run_resumes_completed_stage_and_binds_snapshot_bundle(tmp_path):
    plan, packet, counter, _ = fixture(tmp_path)
    target = tmp_path / 'run'
    first = run(plan, target)
    assert first.returncode == 0, first.stderr
    assert counter.read_text() == 'x'
    state = json.loads((target / 'state.json').read_text())
    assert state['local']['status'] == 'complete'
    assert state['remote']['status'] == 'pending'
    assert state['remote']['active'] is None
    assert state['remote']['failed'] is None
    assert state['coverage']['status'] == 'verified_partial'
    assert state['coverage']['gaps'] == []
    snapshot = target / 'snapshot'
    bundle = target / 'bundle'
    snapshot_manifest = json.loads((snapshot / 'manifest.json').read_text())
    bundle_manifest = json.loads((bundle / 'manifest.json').read_text())
    for identifier, path in [('snapshot.sqlite', snapshot / 'snapshot.sqlite'), ('snapshot-manifest.json', snapshot / 'manifest.json')]:
        entry = next(item for item in bundle_manifest['sources'] if item['id'] == identifier)
        assert entry['sha256'] == sha(path)
        assert (bundle / entry['object']).read_bytes() == path.read_bytes()
    assert state['local']['snapshot_sha256'] == snapshot_manifest['snapshot_sha256']
    assert state['local']['bundle_manifest_sha256'] == sha(bundle / 'manifest.json')
    second = run(plan, target)
    assert second.returncode == 0, second.stderr
    assert counter.read_text() == 'x'
    assert sha(snapshot / 'manifest.json') == state['local']['snapshot_manifest_sha256']


def test_reconciliation_checkpoints_after_local_staging_and_replays_without_scoring(tmp_path):
    plan, _, counter, _ = fixture(tmp_path)
    spec = tmp_path / 'reconciliation.edn'
    spec.write_text('''{:config {:provider :jev :model "synthetic-jev" :version "synthetic/1"}
 :decisions [{:id "different-people" :family :identity :action :different-person
              :choices [:same-person :different-person :unknown]
              :subject {:id "synthetic-pair"} :candidates ["person-a" "person-b"]
              :dependencies [] :evidence-adequate? true
              :evidence [{:evidence-id "synthetic-row" :citation {:source-sha256 "synthetic" :locator "row 1"}
                          :fact "Two distinct synthetic people"}]}]
 :synthetic-answers {"different-people" {:type "choice" :choice "different_person"
   :confidence 0.99 :probabilities {"same_person" 0.005 "different_person" 0.99 "unknown" 0.005}}}
 :review-sample {:frame "synthetic reviewed approvals" :reviewed 1 :errors 0
                 :selection "owner-selected" :selection-bias "convenience sample"}}''')
    data = json.loads(plan.read_text())
    data['reconciliation'] = {'mode': 'synthetic', 'spec': str(spec), 'name_evidence': {'status': 'gap', 'reason': 'synthetic fixture has no affiliate roster'}}
    plan.write_text(json.dumps(data))
    target = tmp_path / 'run'
    first = run(plan, target)
    assert first.returncode == 0, first.stderr
    state = json.loads((target / 'state.json').read_text())
    assert state['local']['status'] == 'complete'
    assert state['reconciliation']['status'] == 'complete'
    assert state['reconciliation']['no_link'] == 1
    assert state['reconciliation']['provider_calls'] == 1
    receipt = state['reconciliation']['metrics']
    assert receipt['schema'] == 'local-reconciliation-metrics/v1'
    assert receipt['binding']['snapshot_sha256'] == state['local']['snapshot_sha256']
    assert receipt['binding']['decision_revision'] == state['reconciliation']['decision_revision']
    assert receipt['coverage']['decision_denominator'] == 1
    assert receipt['coverage']['by_family']['identity']['denominator'] == 1
    assert receipt['coverage']['by_family']['identity']['automatic_approved'] == 1
    assert receipt['provider']['calls_this_execution'] == 1
    assert receipt['provider']['reported_usage'] is None
    assert receipt['provider']['actual_monetary_cost'] is None
    assert receipt['sampled_error'] == {'sampling_frame': 'synthetic reviewed approvals',
                                        'numerator': 0, 'denominator': 1, 'selection': 'owner-selected',
                                        'selection_bias': 'convenience sample', 'rate': 0.0}
    assert receipt['latency_ms'] is None
    assert state['reconciliation']['gaps'] == [{'name': 'affiliate-names', 'reason': 'synthetic fixture has no affiliate roster'}]
    assert state['remote']['status'] == 'pending'
    assert state['coverage']['confirmed_distinct_attempts'] is None
    ledger_hash = sha(target / 'reconciliation' / 'flow.edn')
    second = run(plan, target)
    assert second.returncode == 0, second.stderr
    assert counter.read_text() == 'x'
    assert sha(target / 'reconciliation' / 'flow.edn') == ledger_hash
    assert json.loads((target / 'state.json').read_text())['reconciliation']['provider_calls'] == 1
    queried = subprocess.run([sys.executable, str(SCRIPT), 'metrics', '--run-dir', str(target)],
                             capture_output=True, text=True)
    assert queried.returncode == 0, queried.stderr
    assert json.loads(queried.stdout)['provider']['calls_this_execution'] == 1
    (target / 'reconciliation' / 'flow.edn').write_text('tampered')
    stale = subprocess.run([sys.executable, str(SCRIPT), 'metrics', '--run-dir', str(target)],
                           capture_output=True, text=True)
    assert stale.returncode != 0
    assert 'metrics checkpoint binding changed' in stale.stderr


def test_cli_checkpoints_flow_ack_and_preserves_local_run_when_canonical_target_is_absent(tmp_path):
    plan, packet, _, _ = fixture(tmp_path)
    content = tmp_path / 'content.txt'
    packet.write_text(content.read_text())
    preflight = tmp_path / 'preflight'
    built = subprocess.run([sys.executable, str(SCRIPT.parent / 'unified_evidence_snapshot.py'),
                            'build', '--cutoff', '2026-10-03T00:00:00Z',
                            '--input', f'packet={packet}', '--output-dir', str(preflight)],
                           capture_output=True, text=True)
    assert built.returncode == 0, built.stderr
    snapshot_sha = json.loads((preflight / 'manifest.json').read_text())['snapshot_sha256']
    token = 'synthetic-owner-import-token-12345'
    binding = {'decision_id': 'different-people', 'evidence_bindings': []}
    events = []
    acknowledgements = []

    class Owner(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def authorized(self):
            return (self.headers.get('CF-Access-Client-Id') == 'synthetic.access'
                    and self.headers.get('CF-Access-Client-Secret') == 'synthetic-secret'
                    and self.headers.get('X-Freediving-Import-Token') == token)

        def answer(self, status, data):
            body = json.dumps(data).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if not self.authorized():
                return self.answer(403, {})
            after = int(self.path.split('after_revision=')[1])
            selected = [item for item in events if item['store_revision'] > after]
            payload = json.dumps({'events': selected, 'store_revision': events[-1]['store_revision'] if events else 0,
                                  'next_revision': selected[-1]['store_revision'] if selected else after})
            signature = hmac.new(token.encode(), payload.encode(), hashlib.sha256).hexdigest()
            self.answer(200, {'payload_json': payload, 'signature': signature})

        def do_POST(self):
            if not self.authorized() or self.path != '/owner-evidence/api/decision-events/ack':
                return self.answer(403, {})
            body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            acknowledgements.append((body['target'], body['event']['id']))
            if len(acknowledgements) == 1:
                self.close_connection = True  # owner commits, reply is lost
                return
            self.answer(200, {'target': body['target'], 'event_id': body['event']['id'],
                              'checkpoints': {body['target']: body['event']['store_revision']}})

    server = ThreadingHTTPServer(('127.0.0.1', 0), Owner)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        spec = tmp_path / 'remote-reconciliation.edn'
        spec.write_text('''{:config {:provider :jev :model "synthetic-jev" :version "synthetic/1"}
 :decisions [{:id "different-people" :family :identity :action :different-person
              :choices [:same-person :different-person :unknown]
              :subject {:id "synthetic-pair"} :candidates ["person-a" "person-b"]
              :dependencies [] :evidence-adequate? true
              :evidence [{:evidence-id "synthetic-row" :citation {:source-sha256 "synthetic" :locator "row 1"}
                          :fact "Two distinct synthetic people"}]}]
 :synthetic-answers {"different-people" {:type "choice" :choice "different_person"
   :confidence 0.99 :probabilities {"same_person" 0.005 "different_person" 0.99 "unknown" 0.005}}}}''')
        owner = tmp_path / 'owner.edn'
        owner.write_text(f'''{{:base-url "http://127.0.0.1:{server.server_port}"
 :access-client-id "synthetic.access" :access-client-secret "synthetic-secret"
 :allow-loopback-http? true :import-token "{token}"
 :current-bindings {{"different-people" {{:decision_id "different-people" :evidence_bindings []}}}}
 :active-snapshot-sha256 "{snapshot_sha}" :active-binding-revision 1}}''')
        owner.chmod(0o600)
        data = json.loads(plan.read_text())
        data['reconciliation'] = {'mode': 'synthetic', 'spec': str(spec),
                                  'name_evidence': {'status': 'gap', 'reason': 'synthetic'},
                                  'owner_sync_config': str(owner)}
        plan.write_text(json.dumps(data))
        target = tmp_path / 'run'
        initial = run(plan, target)
        assert initial.returncode == 0, initial.stderr
        assert json.loads((target / 'state.json').read_text())['reconciliation']['provider_calls'] == 1

        def owner_event(revision, action):
            return {'id': f'owner-store:{revision}', 'decision_id': 'different-people',
                    'store_revision': revision, 'binding_revision': 1,
                    'snapshot_sha256': snapshot_sha, 'action': action, 'actor': 'owner',
                    'reason': 'synthetic review',
                    'proposal': {'selected_option': 'different_person', 'canonical_binding': binding}}

        events.append(owner_event(2, 'approve'))
        approved = run(plan, target)
        assert approved.returncode != 0
        state = json.loads((target / 'state.json').read_text())
        assert state['local']['status'] == 'complete'
        assert state['remote']['status'] == 'pending'
        assert state['reconciliation']['status'] == 'failed'
        assert acknowledgements == [('flow-ledger', 'owner-store:2')]
        ledger_hash = sha(target / 'reconciliation' / 'flow.edn')
        retry = run(plan, target)
        assert retry.returncode != 0
        assert sha(target / 'reconciliation' / 'flow.edn') == ledger_hash
        assert acknowledgements == [('flow-ledger', 'owner-store:2'),
                                    ('flow-ledger', 'owner-store:2')]
    finally:
        server.shutdown()
        thread.join(timeout=2)
        server.server_close()


def test_deterministic_local_reconciliation_has_no_provider_dispatch(tmp_path):
    plan, _, _, _ = fixture(tmp_path)
    spec = tmp_path / 'deterministic.edn'
    spec.write_text('''{:config {:provider :jev :model "synthetic-jev" :version "synthetic/1"}
 :decisions [{:id "known-rule" :family :identity :action :different-person
              :choices [:same-person :different-person :unknown]
              :subject {:id "synthetic-pair"} :candidates ["person-a" "person-b"]
              :dependencies [] :evidence-adequate? true
              :evidence [{:evidence-id "synthetic-row" :citation {:source-sha256 "synthetic" :locator "row 1"}
                          :fact "Synthetic rule evidence"}]}]
 :deterministic-results {"known-rule" {:status :approve :rule-version "synthetic-rule/1"}}
 :synthetic-answers {}}''')
    data = json.loads(plan.read_text())
    data['reconciliation'] = {'mode': 'synthetic', 'spec': str(spec),
                              'name_evidence': {'status': 'gap', 'reason': 'no affiliate evidence in synthetic fixture'}}
    plan.write_text(json.dumps(data))
    target = tmp_path / 'run'
    result = run(plan, target)
    assert result.returncode == 0, result.stderr
    state = json.loads((target / 'state.json').read_text())
    assert state['local']['status'] == 'complete'
    assert state['reconciliation']['provider_calls'] == 0
    assert state['reconciliation']['metrics']['provider']['calls_recorded'] == 0
    assert state['reconciliation']['metrics']['coverage']['automatic_approved'] == 1
    assert state['reconciliation']['flow_statuses'] == {'approved': 1}
    assert state['reconciliation']['unresolved'] == 1  # no canonical target for a rule-only identity decision
    assert state['reconciliation']['accepted_athletes'] is None
    assert state['reconciliation']['distinct_attempts'] is None


def test_synthetic_timeout_is_reported_with_a_partial_source_gap(tmp_path):
    plan, _, _, _ = fixture(tmp_path)
    spec = tmp_path / 'timeout.edn'
    spec.write_text('''{:config {:provider :jev :model "synthetic-jev" :version "synthetic/1"}
 :decisions [{:id "identity-timeout" :family :identity :action :same-person
              :choices [:same-person :different-person :unknown]
              :subject {:id "synthetic-pair"} :candidates ["a" "b"]
              :dependencies [] :evidence-adequate? true
              :evidence [{:evidence-id "synthetic-row" :citation {:source-sha256 "synthetic" :locator "row 1"}
                          :fact "Synthetic names"}]}]
 :synthetic-answers {} :synthetic-errors {"identity-timeout" :timeout}}''')
    data = json.loads(plan.read_text())
    excluded = tmp_path / 'unsupported-page.json'
    excluded.write_text('{}')
    data['excluded'].append({'name': 'unsupported-page', 'path': str(excluded),
                             'reason': 'synthetic unsupported section'})
    data['reconciliation'] = {'mode': 'synthetic', 'spec': str(spec),
                              'name_evidence': {'status': 'gap', 'reason': 'synthetic roster gap'}}
    plan.write_text(json.dumps(data))
    target = tmp_path / 'run'
    result = run(plan, target)
    assert result.returncode == 0, result.stderr
    metrics = json.loads((target / 'state.json').read_text())['reconciliation']['metrics']
    assert metrics['coverage']['decision_denominator'] == 1
    assert metrics['coverage']['unknown'] == 1
    assert metrics['coverage']['pending_review'] == 1
    assert metrics['source_gaps'] == 2
    assert metrics['provider']['calls_recorded'] == 1
    assert metrics['provider']['reported_usage'] is None


def test_synthetic_reconciliation_cannot_activate_remote_presentation(tmp_path):
    plan, _, _, _ = fixture(tmp_path)
    data = json.loads(plan.read_text())
    data['reconciliation'] = {'mode': 'synthetic', 'spec': str(tmp_path / 'spec.edn'),
                              'name_evidence': {'status': 'gap', 'reason': 'synthetic'}}
    plan.write_text(json.dumps(data))
    remote = tmp_path / 'remote.json'
    remote.write_text('{}')
    result = subprocess.run([sys.executable, str(SCRIPT), 'run', '--plan', str(plan),
                             '--run-dir', str(tmp_path / 'run'), '--remote-config', str(remote)],
                            capture_output=True, text=True)
    assert result.returncode != 0
    assert 'synthetic reconciliation cannot activate remote presentation' in result.stderr
    assert not (tmp_path / 'run').exists()


def test_reconciliation_requires_checked_affiliate_names_or_explicit_gap(tmp_path):
    plan, _, _, _ = fixture(tmp_path)
    data = json.loads(plan.read_text())
    data['reconciliation'] = {'mode': 'synthetic', 'spec': str(tmp_path / 'spec.edn')}
    plan.write_text(json.dumps(data))
    result = run(plan, tmp_path / 'run')
    assert result.returncode != 0
    assert 'checked affiliate names or an explicit gap' in result.stderr
    assert not (tmp_path / 'run').exists()


def test_owner_sync_requires_private_config_before_reconciliation(tmp_path):
    plan, _, _, _ = fixture(tmp_path)
    spec = tmp_path / 'synthetic.edn'
    spec.write_text('{}')
    owner = tmp_path / 'owner.edn'
    owner.write_text('{:import-token "synthetic"}')
    owner.chmod(0o644)
    data = json.loads(plan.read_text())
    data['reconciliation'] = {'mode': 'synthetic', 'spec': str(spec),
                              'name_evidence': {'status': 'gap', 'reason': 'synthetic'},
                              'owner_sync_config': str(owner)}
    plan.write_text(json.dumps(data))
    result = run(plan, tmp_path / 'run')
    assert result.returncode != 0
    assert 'owner synchronization config must be an owner-only regular file' in result.stderr
    assert not (tmp_path / 'run' / 'reconciliation' / 'flow.edn').exists()


def test_interruption_resumes_and_keeps_prior_completed_run(tmp_path):
    plan, packet, counter, _ = fixture(tmp_path)
    prior = tmp_path / 'prior'
    assert run(plan, prior).returncode == 0
    previous = sha(prior / 'snapshot' / 'manifest.json')
    (tmp_path / 'content.fail').write_text('fail')
    pending = tmp_path / 'pending'
    failed = run(plan, pending)
    assert failed.returncode != 0
    state = json.loads((pending / 'state.json').read_text())
    assert state['local']['status'] == 'failed'
    assert state['remote']['active'] is None
    assert sha(prior / 'snapshot' / 'manifest.json') == previous
    (tmp_path / 'content.fail').unlink()
    resumed = run(plan, pending)
    assert resumed.returncode == 0, resumed.stderr
    assert json.loads((pending / 'state.json').read_text())['local']['status'] == 'complete'


def test_failed_snapshot_build_resumes_with_explicit_gap(tmp_path):
    plan, packet, counter, _ = fixture(tmp_path)
    missing = tmp_path / 'missing.json'
    gap = tmp_path / 'gap.json'
    gap.write_text(json.dumps({'gaps': [{'id': 'g1'}]}))
    spec = json.loads(plan.read_text())
    spec['inputs'].append({'name': 'later', 'path': str(missing)})
    spec['excluded'].append({'name': 'unresolved', 'path': str(gap), 'reason': 'publisher unavailable'})
    plan.write_text(json.dumps(spec))
    target = tmp_path / 'run'
    assert run(plan, target).returncode != 0
    assert json.loads((target / 'state.json').read_text())['stages']['parse']['status'] == 'complete'
    missing.write_text(json.dumps({'positions': [{'id': 'later-row'}]}))
    resumed = run(plan, target)
    assert resumed.returncode == 0, resumed.stderr
    assert counter.read_text() == 'x'
    state = json.loads((target / 'state.json').read_text())
    assert state['coverage']['gaps'] == [{'name': 'unresolved', 'reason': 'publisher unavailable'}]
    manifest = json.loads((target / 'snapshot' / 'manifest.json').read_text())
    assert manifest['inputs']['unresolved']['status'] == 'excluded'
    assert manifest['inputs']['later']['status'] == 'included'


def test_completed_run_rejects_corrupt_bound_bundle(tmp_path):
    plan, _, _, _ = fixture(tmp_path)
    target = tmp_path / 'run'
    assert run(plan, target).returncode == 0
    bundle_manifest = json.loads((target / 'bundle' / 'manifest.json').read_text())
    entry = next(item for item in bundle_manifest['sources'] if item['id'] == 'snapshot-manifest.json')
    (target / 'bundle' / entry['object']).write_bytes(b'corrupt')
    assert run(plan, target).returncode != 0
    assert json.loads((target / 'state.json').read_text())['remote']['status'] == 'pending'


def test_configured_run_requires_stopped_publishers_after_local_completion(tmp_path):
    plan, _, _, _ = fixture(tmp_path)
    target = tmp_path / 'run'
    config = tmp_path / 'remote.json'
    config.write_text(json.dumps({'host': 'owner-vps'}))
    result = subprocess.run([sys.executable, str(SCRIPT), 'run', '--plan', str(plan),
                             '--run-dir', str(target), '--remote-config', str(config),
                             '--owner-access-jwt-env', 'UNSET_TEST_OWNER_JWT'],
                            capture_output=True, text=True)
    assert result.returncode != 0
    assert 'publisher requests must stop' in result.stderr
    state = json.loads((target / 'state.json').read_text())
    assert state['local']['status'] == 'complete'
    assert state['remote']['status'] == 'failed'


def test_completed_run_retries_configured_presentation_without_rerunning_local_stage(tmp_path):
    import importlib.util
    sys.path.insert(0, str(SCRIPT.parent))
    spec = importlib.util.spec_from_file_location("local_evidence_run", SCRIPT)
    local_run = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(local_run)
    plan, _, counter, _ = fixture(tmp_path)
    target = tmp_path / 'run'
    assert run(plan, target).returncode == 0
    source_sha = sha(tmp_path / 'source.pdf')
    config = tmp_path / 'remote.json'
    config.write_text(json.dumps({'host': 'owner-vps', 'root': '/private/staging',
                                  'remote_script': '/opt/private_evidence_transfer.py',
                                  'ssh': '/usr/bin/ssh', 'code_bundle': '/opt/code',
                                  'activation_script': '/opt/activate.py',
                                  'owner_url': 'https://poc.alphacompose.com/owner-evidence',
                                  'cited_record_id': 'a' * 64,
                                  'cited_source_sha256': source_sha}))
    calls = []

    class FakeRemote:
        def __init__(self, **kwargs):
            calls.append(('configured', kwargs['host']))

        def stage(self, run_dir):
            from private_evidence_ssh import receipt
            local = json.loads((run_dir / 'state.json').read_text())['local']
            calls.append(('staged', str(run_dir)))
            return receipt(local)

        def activate(self, staged):
            return 'activated'

        def owner_overview(self):
            local = json.loads((target / 'state.json').read_text())['local']
            return {'status': 200, 'snapshot_sha256': local['snapshot_sha256'],
                    'bundle_manifest_sha256': local['bundle_manifest_sha256']}

        def rollback(self, staged):
            raise AssertionError('unexpected rollback')

    local_run.run(plan, target, remote_config=config, owner_access_jwt='a.b.c',
                  publisher_requests_stopped=True, vps_reachable=lambda: True,
                  remote_factory=FakeRemote)
    state = json.loads((target / 'state.json').read_text())
    assert state['local']['status'] == 'complete'
    assert state['remote']['status'] == 'active'
    assert state['coverage']['cutoff'] == '2026-10-03T00:00:00Z'
    assert calls == [('configured', 'owner-vps'), ('staged', str(target))]
    assert counter.read_text() == 'x'


def test_missing_owner_identity_keeps_verified_local_artifacts_and_secret_off_disk(tmp_path):
    plan, _, _, _ = fixture(tmp_path)
    target = tmp_path / 'run'
    config = tmp_path / 'remote.json'
    config.write_text(json.dumps({'host': 'owner-vps'}))
    secret = 'sensitive.header.signature'
    result = subprocess.run([sys.executable, str(SCRIPT), 'run', '--plan', str(plan),
                             '--run-dir', str(target), '--remote-config', str(config),
                             '--publisher-requests-stopped'], capture_output=True,
                            text=True, env={**__import__('os').environ,
                                            'UNRELATED_TEST_SECRET': secret})
    assert result.returncode != 0
    state_text = (target / 'state.json').read_text()
    state = json.loads(state_text)
    assert state['local']['status'] == 'complete'
    assert state['remote']['status'] == 'failed'
    assert (target / 'snapshot' / 'snapshot.sqlite').exists()
    assert (target / 'bundle' / 'manifest.json').exists()
    assert secret not in state_text + result.stdout + result.stderr


def test_disposable_command_path_reaches_verified_active_without_network(tmp_path):
    plan, _, counter, _ = fixture(tmp_path)
    target = tmp_path / 'run'
    config = tmp_path / 'remote.json'
    config.write_text(json.dumps({'host': 'synthetic-vps', 'root': '/private/staging',
                                  'remote_script': '/opt/private_evidence_transfer.py',
                                  'ssh': '/usr/bin/ssh', 'code_bundle': '/opt/code',
                                  'activation_script': '/opt/activate.py',
                                  'owner_url': 'https://poc.alphacompose.com/owner-evidence',
                                  'cited_record_id': 'a' * 64,
                                  'cited_source_sha256': sha(tmp_path / 'source.pdf')}))
    harness = tmp_path / 'disposable.py'
    harness.write_text('''import json, os, sys
from pathlib import Path
sys.path.insert(0, os.environ['SCRIPTS_DIR'])
import local_evidence_run as runner
import private_evidence_remote
from private_evidence_ssh import receipt
run_dir = Path(os.environ['RUN_DIR'])
class DisposableRemote:
    def __init__(self, **kwargs):
        assert kwargs['host'] == 'synthetic-vps'
        assert kwargs['owner_access_jwt'] == os.environ['SYNTHETIC_ACCESS_JWT']
    def stage(self, path):
        return receipt(json.loads((path / 'state.json').read_text())['local'])
    def activate(self, staged):
        return 'activated'
    def owner_overview(self):
        local = json.loads((run_dir / 'state.json').read_text())['local']
        return {'status': 200, 'snapshot_sha256': local['snapshot_sha256'],
                'bundle_manifest_sha256': local['bundle_manifest_sha256']}
    def rollback(self, staged):
        raise AssertionError('unexpected rollback')
private_evidence_remote.PrivateEvidenceRemote = DisposableRemote
runner.ssh_route_reachable = lambda ssh, host: True
sys.argv = [str(runner.__file__), 'run', '--plan', os.environ['PLAN'],
            '--run-dir', str(run_dir), '--remote-config', os.environ['CONFIG'],
            '--owner-access-jwt-env', 'SYNTHETIC_ACCESS_JWT',
            '--publisher-requests-stopped']
raise SystemExit(runner.main())
''')
    env = {**__import__('os').environ, 'SCRIPTS_DIR': str(SCRIPT.parent),
           'RUN_DIR': str(target), 'PLAN': str(plan), 'CONFIG': str(config),
           'SYNTHETIC_ACCESS_JWT': 'synthetic.header.signature'}
    result = subprocess.run([sys.executable, str(harness)], capture_output=True,
                            text=True, env=env)
    assert result.returncode == 0, result.stderr
    state = json.loads((target / 'state.json').read_text())
    assert state['local']['status'] == 'complete'
    assert state['remote']['status'] == 'active'
    assert state['remote']['active'] == {'snapshot_sha256': state['local']['snapshot_sha256'],
                                         'bundle_manifest_sha256': state['local']['bundle_manifest_sha256']}
    assert counter.read_text() == 'x'
    assert env['SYNTHETIC_ACCESS_JWT'] not in result.stdout + result.stderr + json.dumps(state)


def test_retained_aida_cohort_checkpoints_exact_private_inputs_without_provider_calls(tmp_path, monkeypatch):
    import importlib.util
    spec = importlib.util.spec_from_file_location('local_evidence_run', SCRIPT)
    runner = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(runner)
    source_plan, _, _, _ = fixture(tmp_path)
    assert run(source_plan, tmp_path / 'source-run').returncode == 0
    snapshot = tmp_path / 'source-run' / 'snapshot'
    checked_names = {'schema': 'affiliate-name-input/v1',
                     'snapshot': {'path': str(snapshot),
                                  'manifest_sha256': sha(snapshot / 'manifest.json'),
                                  'sqlite_sha256': sha(snapshot / 'snapshot.sqlite'),
                                  'cutoff': '2026-10-03T00:00:00Z'},
                     'sources': [], 'assertions': [], 'gaps': [], 'roster': {}}
    inputs = {}
    for name, content in [('aida_plan', '{}'), ('aida_observations', '[]'),
                          ('decision_store', ''), ('name_evidence', json.dumps(checked_names)),
                          ('pg_export', 'job_id,ordinal,candidate_id,artifact_sha256,source_sha256,parser_version\n'),
                          ('pg_dump', 'private PG dump'),
                          ('ledger', '[]'), ('owner_corrections', '{}')]:
        path = tmp_path / name
        if name == 'decision_store':
            import sqlite3
            with sqlite3.connect(path) as db:
                db.execute('CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)')
                db.execute("INSERT INTO meta VALUES ('revision', '1')")
        else:
            path.write_text(content)
        inputs[name] = {'name': name, 'path': str(path), 'sha256': sha(path)}
    retained = tmp_path / 'retained-plan.json'
    retained.write_text(json.dumps({
        'schema': 'retained-aida-local-run-plan/v1',
        'snapshot': {'path': str(snapshot), 'manifest_sha256': sha(snapshot / 'manifest.json'),
                     'sqlite_sha256': sha(snapshot / 'snapshot.sqlite'),
                     'snapshot_sha256': json.loads((snapshot / 'manifest.json').read_text())['snapshot_sha256']},
        'inputs': list(inputs.values()), 'decision_revision': 1,
        'owner_correction_revision': 0, 'ledger_revision': 0,
        'scope': 'AIDA source observations'}))
    calls = []

    def adapter(snapshot_path, observations_path, plan_path, output_path):
        calls.append(1)
        assert snapshot_path == target / 'snapshot'
        assert plan_path.read_bytes() == (tmp_path / 'aida_plan').read_bytes()
        assert observations_path.read_bytes() == (tmp_path / 'aida_observations').read_bytes()
        output_path.write_text(json.dumps({'schema': 'retained-aida-cohort/v1',
                                           'binding': {'snapshot_sha256': json.loads((snapshot / 'manifest.json').read_text())['snapshot_sha256'],
                                                       'observations_sha256': inputs['aida_observations']['sha256'],
                                                       'plan_sha256': inputs['aida_plan']['sha256']},
                                           'counts': {'source_rows': 1, 'source_gaps': 0,
                                                      'candidate_edges': 0},
                                           'registration': {'rows': [{'observation_id': 'source-observation:synthetic'}]},
                                           'events': []}))

    target = tmp_path / 'retained-run'
    first = runner.run_retained(retained, target, adapter=adapter)
    assert first['reconciliation']['status'] == 'complete'
    assert first['reconciliation']['decision_revision'] == 1
    assert first['reconciliation']['owner_correction_revision'] == 0
    assert first['reconciliation']['ledger_revision'] == 0
    assert first['reconciliation']['provider_calls'] == 0
    assert first['reconciliation']['ledger_sha256'] == inputs['ledger']['sha256']
    assert first['reconciliation']['preflight']['pg_observation_versions'] == 0
    assert first['reconciliation']['preflight']['checked_name_assertions'] == 0
    assert calls == [1]
    assert runner.run_retained(retained, target, adapter=adapter) == first
    assert calls == [1]
    monkeypatch.setenv('FREEDIVING_REVIEW_URL', 'jdbc:postgresql://private/review')
    monkeypatch.setenv('FREEDIVING_APP_URL', 'jdbc:postgresql://private/app')
    correction = [0]

    def canonical(cohort, cohort_sha, receipt):
        assert cohort_sha == sha(cohort)
        receipt.write_text(json.dumps({'schema': 'retained-aida-canonical-receipt/v1',
                                       'cohort-sha256': cohort_sha,
                                       'identity-revision': 12 + correction[0],
                                       'human-correction-revision': correction[0],
                                       'accepted-group-count': 1 - correction[0],
                                       'human-negative-pair-count': correction[0],
                                       'provider-calls': 0}))

    applied = runner.run_retained(retained, target, adapter=adapter,
                                  canonical_apply=True, canonical=canonical)
    assert applied['canonical']['owner_correction_revision'] == 0
    correction[0] = 1  # Human reversal in canonical store after the first apply.
    replayed = runner.run_retained(retained, target, adapter=adapter,
                                   canonical_apply=True, canonical=canonical)
    assert replayed['canonical']['owner_correction_revision'] == 1
    assert replayed['canonical']['accepted_group_count'] == 0
    assert calls == [1]
    checkpoint = subprocess.run([sys.executable, str(SCRIPT), 'retained-status',
                                 '--run-dir', str(target)], capture_output=True, text=True)
    assert checkpoint.returncode == 0, checkpoint.stderr
    status = json.loads(checkpoint.stdout)
    assert status['schema'] == 'retained-aida-private-status/v1'
    assert status['binding']['snapshot_manifest_sha256'] == sha(snapshot / 'manifest.json')
    assert status['binding']['source_bundle_sha256'] == sha(target / 'cohort-bundle/manifest.json')
    assert status['binding']['export_sha256'] == sha(target / 'reconciliation/aida-cohort.json')
    assert status['canonical']['receipt_sha256'] == sha(target / 'reconciliation/canonical-receipt.json')
    assert status['canonical']['human_correction_revision'] == 1
    assert status['provider_calls'] == 0
    assert status['remote'] == {'status': 'pending', 'verified': False}
    assert status['canonical']['current_revision_verified'] is False
    assert status['publication_authority'] == 'unverified'
    assert str(tmp_path) not in checkpoint.stdout
    def readback_command(argv):
        assert argv[4] == 'readback'
        Path(argv[-1]).write_text(json.dumps({
            'schema': 'retained-aida-canonical-readback/v1',
            'database': 'isolated_test',
            'snapshot-sha256': status['binding']['snapshot_sha256'],
            'source-rows': [{'observation-id': 'source-observation:synthetic'}],
            'non-source-row-count': 0,
            'events': [{'id': f'event-{index}'} for index in range(13)],
            'projection': {'revision': 13},
            'human-correction-revision': 1}))

    original_command = runner.command
    monkeypatch.setattr(runner, 'command', readback_command)
    handoff = runner.retained_handoff(target, tmp_path / 'private-handoff', 'isolated_test')
    assert handoff['identity_revision'] == 13
    assert handoff['authority_scope'] == 'selected_isolated_store_only'
    assert handoff['publication_authority'] == 'unverified'
    assert sha(Path(handoff['path'])) == handoff['sha256']
    assert Path(handoff['path']).stat().st_mode & 0o777 == 0o600
    with __import__('pytest').raises(ValueError, match='store or receipt mismatch'):
        runner.retained_handoff(target, tmp_path / 'private-handoff', 'wrong_store')
    def mixed_corpus_command(argv):
        readback_command(argv)
        output = Path(argv[-1])
        mixed = json.loads(output.read_text())
        mixed['non-source-row-count'] = 1
        output.write_text(json.dumps(mixed))

    monkeypatch.setattr(runner, 'command', mixed_corpus_command)
    with __import__('pytest').raises(ValueError, match='store or receipt mismatch'):
        runner.retained_handoff(target, tmp_path / 'private-handoff', 'isolated_test')
    monkeypatch.setattr(runner, 'command', original_command)
    rejected_status = subprocess.run([sys.executable, str(SCRIPT), 'retained-status',
                                      '--run-dir', str(target), '--remote-config', str(tmp_path / 'remote')],
                                     capture_output=True, text=True)
    assert rejected_status.returncode != 0
    staged_dump = target / 'cohort-bundle' / 'pg_dump'
    staged_dump.write_text('changed')
    stale_status = subprocess.run([sys.executable, str(SCRIPT), 'retained-status',
                                   '--run-dir', str(target)], capture_output=True, text=True)
    assert stale_status.returncode != 0
    staged_dump.write_bytes((tmp_path / 'pg_dump').read_bytes())
    measured = subprocess.run([sys.executable, str(SCRIPT), 'metrics', '--run-dir', str(target)],
                              capture_output=True, text=True)
    assert measured.returncode == 0, measured.stderr
    assert json.loads(measured.stdout)['provider_calls'] == 0
    (target / 'reconciliation' / 'canonical-receipt.json').write_text('{}')
    stale_status = subprocess.run([sys.executable, str(SCRIPT), 'retained-status',
                                   '--run-dir', str(target)], capture_output=True, text=True)
    assert stale_status.returncode != 0
    stale_receipt = subprocess.run([sys.executable, str(SCRIPT), 'metrics', '--run-dir', str(target)],
                                   capture_output=True, text=True)
    assert stale_receipt.returncode != 0
    assert 'canonical checkpoint binding changed' in stale_receipt.stderr
    def rejected(*_):
        raise ValueError('new human correction')

    with __import__('pytest').raises(ValueError, match='new human correction'):
        runner.run_retained(retained, target, adapter=adapter,
                            canonical_apply=True, canonical=rejected)
    assert json.loads((target / 'state.json').read_text())['canonical']['status'] == 'failed'
    (tmp_path / 'aida_plan').write_text('{"stale":true}')
    with __import__('pytest').raises(ValueError, match='retained input changed'):
        runner.run_retained(retained, target, adapter=adapter)
    assert calls == [1]


def test_bound_retained_handoff_requires_historical_event_file(tmp_path, monkeypatch):
    import importlib.util
    import pytest
    spec = importlib.util.spec_from_file_location('local_evidence_run', SCRIPT)
    runner = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(runner)
    run_dir = tmp_path / 'run'
    (run_dir / 'reconciliation').mkdir(parents=True)
    cohort = run_dir / 'reconciliation' / 'aida-cohort.json'
    cohort.write_text(json.dumps({
        'binding': {'identity_revision': 1, 'history_event_ids': ['prior']},
        'registration': {'rows': [{'observation_id': 'row-1'}]}}))
    status = {'canonical': {'status': 'applied', 'identity_revision': 2,
                            'human_correction_revision': 0},
              'binding': {'snapshot_sha256': 'a' * 64, 'export_sha256': sha(cohort)}}
    monkeypatch.setenv('FREEDIVING_REVIEW_URL', 'jdbc:postgresql://private/review')
    monkeypatch.setattr(runner, 'retained_status', lambda _: status)

    def readback_command(argv):
        Path(argv[-1]).write_text(json.dumps({
            'schema': 'retained-aida-canonical-readback/v1',
            'database': 'isolated_test', 'snapshot-sha256': 'a' * 64,
            'source-rows': [{'observation-id': 'row-1'}], 'non-source-row-count': 0,
            'events': [{'id': 'prior'}, {'id': 'new'}],
            'projection': {'revision': 2}, 'human-correction-revision': 0}))

    monkeypatch.setattr(runner, 'command', readback_command)
    with pytest.raises(ValueError, match='historical event file missing'):
        runner.retained_handoff(run_dir, tmp_path / 'private-handoff', 'isolated_test')


def test_retained_aida_rejects_unverified_corpus_inputs(tmp_path):
    import importlib.util
    spec = importlib.util.spec_from_file_location('local_evidence_run', SCRIPT)
    runner = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(runner)
    source_plan, _, _, _ = fixture(tmp_path)
    assert run(source_plan, tmp_path / 'source-run').returncode == 0
    snapshot = tmp_path / 'source-run' / 'snapshot'
    files = {}
    for name, content in [('aida_plan', '{}'), ('aida_observations', '[]'),
                          ('decision_store', 'not a sqlite store'), ('name_evidence', '{}'),
                          ('pg_export', '{}'), ('pg_dump', '{}'), ('ledger', '[]'),
                          ('owner_corrections', '{}')]:
        path = tmp_path / name
        path.write_text(content)
        files[name] = {'name': name, 'path': str(path), 'sha256': sha(path)}
    plan = tmp_path / 'retained.json'
    plan.write_text(json.dumps({'schema': 'retained-aida-local-run-plan/v1',
                                'scope': 'AIDA source observations',
                                'snapshot': {'path': str(snapshot),
                                             'manifest_sha256': sha(snapshot / 'manifest.json'),
                                             'sqlite_sha256': sha(snapshot / 'snapshot.sqlite'),
                                             'snapshot_sha256': sha(snapshot / 'snapshot.sqlite')},
                                'inputs': list(files.values()), 'decision_revision': 1,
                                'ledger_revision': 0, 'owner_correction_revision': 0}))
    with __import__('pytest').raises(ValueError, match='DecisionStore SQLite required'):
        runner.run_retained(plan, tmp_path / 'retained-run', adapter=lambda *_: None)
    assert not (tmp_path / 'retained-run').exists()


def test_retained_aida_requires_packet_receipt_and_original_in_bundle(tmp_path):
    import importlib.util
    spec = importlib.util.spec_from_file_location('local_evidence_run', SCRIPT)
    runner = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(runner)
    packet = tmp_path / 'packet.json'
    receipt = tmp_path / 'receipt.json'
    original = tmp_path / 'original.html'
    packet.write_text('{}')
    original.write_text('<html></html>')
    receipt.write_text(json.dumps({'body': {'path': original.name}}))
    manifest = {'inputs': {'aida-example': {'source_schema': 'aida-selected-html-packet/v1',
                                           'path': str(packet)}}}
    assert runner.required_aida_assets(manifest, {'aida-example'}) == {packet, receipt, original}
    receipt.write_text(json.dumps({'body': {'path': 'missing.html'}}))
    with __import__('pytest').raises(ValueError, match='AIDA original HTML missing'):
        runner.required_aida_assets(manifest, {'aida-example'})


def test_retained_cohort_cli_recovers_exact_missing_packet(tmp_path):
    from test_aida_snapshot_observations import source_fixture
    from scripts.aida_identity_replay import plan_replay
    from scripts.aida_snapshot_observations import load_source_observations
    import shutil

    name, _ = source_fixture(tmp_path, '<div class="event-title--description">Synthetic Open</div>',
                             'https://www.aidainternational.org/EventPage/4408',
                             '<a href="/Athletes/Profile-123e4567-e89b-12d3-a456-426614174000">Synthetic Athlete</a>')
    observations = load_source_observations(tmp_path, [name])['observations']
    frozen = tmp_path / 'observations.json'
    frozen.write_text(json.dumps(observations))
    plan = tmp_path / 'aida-plan.json'
    plan.write_text(json.dumps(plan_replay(observations,
        expected_snapshot_sha256=json.loads((tmp_path / 'manifest.json').read_text())['snapshot_sha256'])))
    recovered = tmp_path / 'recovered'
    recovered.mkdir()
    shutil.copy2(tmp_path / 'packet.json', recovered / 'packet.json')
    shutil.copy2(tmp_path / 'receipt.json', recovered / 'receipt.json')
    shutil.copytree(tmp_path / 'raw', recovered / 'raw')
    (tmp_path / 'packet.json').unlink()
    output = tmp_path / 'cohort.json'
    result = subprocess.run([sys.executable, str(SCRIPT.parent / 'retained_aida_cohort.py'),
                             str(tmp_path), str(frozen), str(plan), str(output),
                             '--observations-sha256', sha(frozen), '--plan-sha256', sha(plan),
                             '--recovered-packet', f'{name}={recovered / "packet.json"}'],
                            capture_output=True, text=True)
    assert result.returncode == 0, result.stderr
    counts = json.loads(output.read_text())['counts']
    assert counts['source_rows'] == 1
    assert counts['source_gaps'] == 0
    stale = recovered / 'packet.json'
    stale.write_bytes(stale.read_bytes() + b' ')
    rejected = subprocess.run([sys.executable, str(SCRIPT.parent / 'retained_aida_cohort.py'),
                               str(tmp_path), str(frozen), str(plan), str(output),
                               '--observations-sha256', sha(frozen), '--plan-sha256', sha(plan),
                               '--recovered-packet', f'{name}={stale}'],
                              capture_output=True, text=True)
    assert rejected.returncode != 0
    assert 'AIDA packet hash mismatch' in rejected.stderr


def test_retained_plan_recovery_requires_frozen_hash_and_safe_original(tmp_path):
    from test_aida_snapshot_observations import source_fixture
    import shutil
    import importlib.util
    spec = importlib.util.spec_from_file_location('local_evidence_run', SCRIPT)
    runner = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(runner)
    name, _ = source_fixture(tmp_path, '', 'https://www.aidainternational.org/EventPage/4408')
    manifest = json.loads((tmp_path / 'manifest.json').read_text())
    recovered = tmp_path / 'recovered'
    recovered.mkdir()
    shutil.copy2(tmp_path / 'packet.json', recovered / 'packet.json')
    shutil.copy2(tmp_path / 'receipt.json', recovered / 'receipt.json')
    shutil.copytree(tmp_path / 'raw', recovered / 'raw')
    (tmp_path / 'packet.json').unlink()
    binding = {'source_name': name, 'path': str(recovered / 'packet.json'),
               'sha256': sha(recovered / 'packet.json'),
               'receipt_sha256': sha(recovered / 'receipt.json'),
               'original_sha256': sha(recovered / 'raw' / 'source.html')}
    assert name in runner.recovered_aida_assets(manifest, [binding], {name})
    with __import__('pytest').raises(ValueError, match='frozen source'):
        runner.recovered_aida_assets(manifest, [{**binding, 'sha256': '0' * 64}], {name})
    receipt = json.loads((recovered / 'receipt.json').read_text())
    receipt['body']['path'] = '../outside.html'
    (recovered / 'receipt.json').write_text(json.dumps(receipt))
    with __import__('pytest').raises(ValueError, match='unsafe recovered AIDA original path'):
        runner.recovered_aida_assets(manifest, [{**binding,
            'receipt_sha256': sha(recovered / 'receipt.json')}], {name})


def test_retained_runner_cli_recovers_frozen_source_and_checks_staged_bytes(tmp_path):
    from test_aida_snapshot_observations import source_fixture
    from scripts.aida_identity_replay import plan_replay
    from scripts.aida_snapshot_observations import load_source_observations
    import shutil
    import sqlite3

    source = tmp_path / 'source'
    source.mkdir()
    name, _ = source_fixture(source, '<div class="event-title--description">Synthetic Open</div>',
                             'https://www.aidainternational.org/EventPage/4408',
                             '<a href="/Athletes/Profile-123e4567-e89b-12d3-a456-426614174000">Synthetic Athlete</a>')
    snapshot = tmp_path / 'snapshot'
    built = subprocess.run([sys.executable, str(SCRIPT.parent / 'unified_evidence_snapshot.py'),
                            'build', '--cutoff', '2026-10-03T00:00:00Z',
                            '--input', f'{name}={source / "packet.json"}',
                            '--output-dir', str(snapshot)], capture_output=True, text=True)
    assert built.returncode == 0, built.stderr
    manifest = json.loads((snapshot / 'manifest.json').read_text())
    observations = load_source_observations(snapshot, [name])['observations']
    (tmp_path / 'aida_observations').write_text(json.dumps(observations))
    (tmp_path / 'aida_plan').write_text(json.dumps(plan_replay(
        observations, expected_snapshot_sha256=manifest['snapshot_sha256'])))
    recovered = tmp_path / 'recovered'
    recovered.mkdir()
    shutil.copy2(source / 'packet.json', recovered / 'packet.json')
    shutil.copy2(source / 'receipt.json', recovered / 'receipt.json')
    shutil.copytree(source / 'raw', recovered / 'raw')
    (source / 'packet.json').unlink()
    checked_names = {'schema': 'affiliate-name-input/v1',
                     'snapshot': {'path': str(snapshot),
                                  'manifest_sha256': sha(snapshot / 'manifest.json'),
                                  'sqlite_sha256': sha(snapshot / 'snapshot.sqlite'),
                                  'cutoff': '2026-10-03T00:00:00Z'},
                     'sources': [], 'assertions': [], 'gaps': [], 'roster': {}}
    (tmp_path / 'name_evidence').write_text(json.dumps(checked_names))
    (tmp_path / 'pg_export').write_text(
        'job_id,ordinal,candidate_id,artifact_sha256,source_sha256,parser_version\n')
    for role, value in [('pg_dump', 'synthetic dump'), ('ledger', '[]'),
                        ('owner_corrections', '{}')]:
        (tmp_path / role).write_text(value)
    with sqlite3.connect(tmp_path / 'decision_store') as store:
        store.execute('CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)')
        store.execute("INSERT INTO meta VALUES ('revision','1')")
    roles = ('aida_plan', 'aida_observations', 'decision_store', 'name_evidence',
             'pg_export', 'pg_dump', 'ledger', 'owner_corrections')
    plan = {'schema': 'retained-aida-local-run-plan/v1', 'scope': 'AIDA source observations',
            'snapshot': {'path': str(snapshot), 'manifest_sha256': sha(snapshot / 'manifest.json'),
                         'sqlite_sha256': sha(snapshot / 'snapshot.sqlite'),
                         'snapshot_sha256': manifest['snapshot_sha256']},
            'inputs': [{'name': role, 'path': str(tmp_path / role),
                        'sha256': sha(tmp_path / role)} for role in roles],
            'decision_revision': 1, 'owner_correction_revision': 0, 'ledger_revision': 0,
            'recovered_packets': [{'source_name': name, 'path': str(recovered / 'packet.json'),
                                   'sha256': sha(recovered / 'packet.json'),
                                   'receipt_sha256': sha(recovered / 'receipt.json'),
                                   'original_sha256': sha(recovered / 'raw/source.html')}]}
    retained = tmp_path / 'retained-plan.json'
    retained.write_text(json.dumps(plan))
    target = tmp_path / 'retained-run'
    command = [sys.executable, str(SCRIPT), 'retained', '--plan', str(retained),
               '--run-dir', str(target)]
    first = subprocess.run(command, capture_output=True, text=True)
    assert first.returncode == 0, first.stderr
    state = json.loads((target / 'state.json').read_text())
    assert state['reconciliation']['counts']['source_rows'] == 1
    assert state['reconciliation']['counts']['source_gaps'] == 0
    assert state['reconciliation']['provider_calls'] == 0
    assert json.loads((target / 'cohort-bundle/manifest.json').read_text())[
        'recovered_packets'][name]['packet_sha256'] == sha(recovered / 'packet.json')
    second = subprocess.run(command, capture_output=True, text=True)
    assert second.returncode == 0, second.stderr
    assert json.loads((target / 'state.json').read_text()) == state
    staged = target / 'cohort-bundle/recovered-packets' / name / 'packet.json'
    staged.write_bytes(b'tampered')
    metrics = subprocess.run([sys.executable, str(SCRIPT), 'metrics', '--run-dir', str(target)],
                             capture_output=True, text=True)
    assert metrics.returncode != 0
    assert 'retained input changed' in metrics.stderr
    attack = tmp_path / 'attack-run'
    attack.mkdir(mode=0o700)
    (attack / 'cohort-bundle').mkdir()
    outside = tmp_path / 'outside'
    outside.mkdir()
    (attack / 'cohort-bundle/recovered-packets').symlink_to(outside, target_is_directory=True)
    escaped = subprocess.run(command[:-1] + [str(attack)], capture_output=True, text=True)
    assert escaped.returncode != 0
    assert 'staging directory is a symlink' in escaped.stderr
    assert list(outside.iterdir()) == []
    unsafe_plan = tmp_path / 'unsafe-plan.json'
    unsafe_plan.write_text(json.dumps({**plan, 'recovered_packets': [
        {**plan['recovered_packets'][0], 'source_name': '..'}]}))
    unsafe = subprocess.run([sys.executable, str(SCRIPT), 'retained', '--plan', str(unsafe_plan),
                             '--run-dir', str(tmp_path / 'unsafe-run')],
                            capture_output=True, text=True)
    assert unsafe.returncode != 0
    assert 'invalid recovered packet binding' in unsafe.stderr
