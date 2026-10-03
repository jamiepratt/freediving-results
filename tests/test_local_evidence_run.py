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
