#!/usr/bin/env python3
"""Checkpoint local evidence preparation without claiming remote presentation."""
import argparse
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import uuid
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SCHEMA = 'local-evidence-run-plan/v1'
RETAINED_SCHEMA = 'retained-aida-local-run-plan/v1'


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        while chunk := stream.read(1024 * 1024):
            h.update(chunk)
    return h.hexdigest()


def atomic_json(path, value):
    fd, name = tempfile.mkstemp(prefix='.state-', dir=path.parent)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as stream:
            json.dump(value, stream, sort_keys=True, separators=(',', ':'))
            stream.write('\n')
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(name, 0o600)
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def checked_plan(path):
    plan = json.loads(path.read_text())
    if plan.get('schema') != SCHEMA or not isinstance(plan.get('stages'), list) or not isinstance(plan.get('inputs'), list) or not isinstance(plan.get('excluded'), list):
        raise ValueError('invalid local run plan')
    cutoff = datetime.fromisoformat(plan['cutoff'].replace('Z', '+00:00'))
    if cutoff.tzinfo != timezone.utc or not plan['cutoff'].endswith('Z'):
        raise ValueError('cutoff must be UTC with Z suffix')
    if not plan['inputs'] or not plan.get('source_inventory'):
        raise ValueError('inputs and source_inventory required')
    names = [item['name'] for item in plan['inputs'] + plan['excluded']]
    if len(names) != len(set(names)) or any(not re.fullmatch(r'[A-Za-z0-9_.-]+', name) for name in names):
        raise ValueError('invalid or duplicate input names')
    stages = plan['stages']
    if len({stage['name'] for stage in stages}) != len(stages):
        raise ValueError('duplicate stage names')
    for stage in stages:
        if not re.fullmatch(r'[A-Za-z0-9_.-]+', stage['name']) or not stage.get('command') or not all(isinstance(x, str) for x in stage['command']) or not stage.get('outputs'):
            raise ValueError('each stage needs name, command argv and outputs')
    for item in plan['excluded']:
        if not item.get('reason'):
            raise ValueError('excluded input needs reason')
    reconciliation = plan.get('reconciliation')
    if reconciliation is not None:
        if not isinstance(reconciliation, dict) or reconciliation.get('mode') != 'synthetic' or not reconciliation.get('spec'):
            raise ValueError('reconciliation requires a synthetic specification')
        name = reconciliation.get('name_evidence')
        if not isinstance(name, dict) or name.get('status') not in ('gap', 'checked'):
            raise ValueError('reconciliation requires checked affiliate names or an explicit gap')
        if name['status'] == 'gap' and not name.get('reason'):
            raise ValueError('affiliate name gap requires reason')
        if name['status'] == 'checked' and not (name.get('path') and re.fullmatch(r'[0-9a-f]{64}', name.get('sha256', ''))):
            raise ValueError('checked affiliate names require path and SHA-256')
        if 'owner_sync_config' in reconciliation and not reconciliation['owner_sync_config']:
            raise ValueError('owner synchronization config path required')
    return plan


def checked_retained_plan(path):
    plan = json.loads(path.read_text())
    if plan.get('schema') != RETAINED_SCHEMA or plan.get('scope') != 'AIDA source observations':
        raise ValueError('invalid retained AIDA run plan')
    snapshot = plan.get('snapshot')
    if not isinstance(snapshot, dict) or set(snapshot) != {'path', 'manifest_sha256', 'sqlite_sha256', 'snapshot_sha256'}:
        raise ValueError('retained snapshot binding incomplete')
    inputs = plan.get('inputs')
    if not isinstance(inputs, list) or not inputs:
        raise ValueError('retained inputs required')
    names = [item.get('name') for item in inputs if isinstance(item, dict)]
    if len(names) != len(inputs) or len(names) != len(set(names)) or any(
            not isinstance(name, str) or not re.fullmatch(r'[A-Za-z0-9_.-]+', name) for name in names):
        raise ValueError('invalid retained input names')
    required = {'aida_plan', 'aida_observations', 'decision_store', 'name_evidence',
                'pg_export', 'pg_dump', 'ledger', 'owner_corrections'}
    if not required <= set(names):
        raise ValueError('retained cohort input roles incomplete')
    if type(plan.get('decision_revision')) is not int or plan['decision_revision'] < 1:
        raise ValueError('decision revision required')
    if type(plan.get('owner_correction_revision')) is not int or plan['owner_correction_revision'] < 0:
        raise ValueError('owner correction revision required')
    if type(plan.get('ledger_revision')) is not int or plan['ledger_revision'] < 0:
        raise ValueError('ledger revision required')
    for value in (snapshot['manifest_sha256'], snapshot['sqlite_sha256'], snapshot['snapshot_sha256']):
        if not isinstance(value, str) or not re.fullmatch(r'[0-9a-f]{64}', value):
            raise ValueError('invalid retained snapshot hash')
    for item in inputs:
        if set(item) != {'name', 'path', 'sha256'} or not isinstance(item['sha256'], str) or not re.fullmatch(r'[0-9a-f]{64}', item['sha256']):
            raise ValueError('invalid retained input binding')
    recovered = plan.get('recovered_packets', [])
    if not isinstance(recovered, list):
        raise ValueError('invalid recovered packet bindings')
    sources = []
    for item in recovered:
        if (not isinstance(item, dict) or set(item) !=
                {'source_name', 'path', 'sha256', 'receipt_sha256', 'original_sha256'}
                or not isinstance(item['source_name'], str)
                or not re.fullmatch(r'[A-Za-z0-9_.-]+', item['source_name'])
                or item['source_name'] in ('.', '..')
                or not all(isinstance(item[key], str) and re.fullmatch(r'[0-9a-f]{64}', item[key])
                           for key in ('sha256', 'receipt_sha256', 'original_sha256'))):
            raise ValueError('invalid recovered packet binding')
        sources.append(item['source_name'])
    if len(sources) != len(set(sources)):
        raise ValueError('duplicate recovered packet source')
    return plan


def verified_file(path, expected):
    path = Path(path)
    if path.is_symlink() or not path.is_file() or digest(path) != expected:
        raise ValueError(f'retained input changed: {path.name}')
    return path


def required_aida_assets(manifest, source_names):
    """Locate every packet, adjacent receipt, and original used in AIDA replay."""
    assets = set()
    for source_name in source_names:
        item = manifest.get('inputs', {}).get(source_name)
        if not item or item.get('source_schema') != 'aida-selected-html-packet/v1':
            raise ValueError('AIDA frozen source absent from snapshot')
        packet = Path(item['path'])
        if not packet.is_file():
            continue  # The adapter reports this frozen packet as an explicit gap.
        name = packet.name
        if name == 'packet.json':
            receipt = packet.with_name('receipt.json')
        elif name.startswith('packet-') and name.endswith('.json'):
            receipt = packet.with_name('receipt-' + name[len('packet-'):])
        else:
            raise ValueError('unsupported AIDA packet path')
        if not receipt.is_file():
            raise ValueError('AIDA receipt missing')
        body = json.loads(receipt.read_text()).get('body') or {}
        if not isinstance(body.get('path'), str) or not body['path']:
            raise ValueError('AIDA original path missing')
        original = (receipt.parent / body['path']).resolve()
        if not original.is_file():
            raise ValueError('AIDA original HTML missing')
        assets.update((packet.resolve(), receipt.resolve(), original))
    return assets


def retained_adapter(snapshot, observations, plan, output, recovered_packets=None,
                     canonical_history=None):
    argv = [sys.executable, str(ROOT / 'retained_aida_cohort.py'),
             str(snapshot), str(observations), str(plan), str(output),
             '--observations-sha256', digest(observations), '--plan-sha256', digest(plan)]
    for source, path in sorted((recovered_packets or {}).items()):
        argv.extend(('--recovered-packet', f'{source}={path}'))
    if canonical_history is not None:
        argv.extend(('--canonical-history', str(canonical_history)))
    command(argv)


def recovered_aida_assets(manifest, bindings, source_names):
    """Check private recovery bytes against the frozen source and plan."""
    assets = {}
    for binding in bindings:
        name = binding['source_name']
        item = manifest.get('inputs', {}).get(name)
        if name not in source_names or not item or item.get('source_schema') != 'aida-selected-html-packet/v1':
            raise ValueError('unknown recovered AIDA source')
        if item['sha256'] != binding['sha256'] or Path(item['path']).is_file():
            raise ValueError('recovered AIDA packet differs from frozen source')
        packet = verified_file(binding['path'], binding['sha256'])
        if packet.name != 'packet.json':
            raise ValueError('unsupported recovered AIDA packet path')
        receipt = verified_file(packet.with_name('receipt.json'), binding['receipt_sha256'])
        body = json.loads(receipt.read_text()).get('body') or {}
        relative = Path(body.get('path', ''))
        if (not body.get('path') or relative.is_absolute() or '..' in relative.parts
                or str(relative) in ('.', '')):
            raise ValueError('unsafe recovered AIDA original path')
        original = verified_file(receipt.parent / relative, binding['original_sha256'])
        if original.resolve().is_relative_to(receipt.parent.resolve()) is False:
            raise ValueError('unsafe recovered AIDA original path')
        assets[name] = (packet, receipt, original, relative, binding)
    return assets


def private_stage_directory(path):
    if path.is_symlink():
        raise ValueError('recovered AIDA staging directory is a symlink')
    path.mkdir(mode=0o700, parents=False, exist_ok=True)
    if not path.is_dir() or path.is_symlink():
        raise ValueError('invalid recovered AIDA staging directory')


def verified_staged_recovery(bundle, recovered):
    base = bundle / 'recovered-packets'
    if not recovered:
        return
    if bundle.is_symlink() or base.is_symlink() or not base.is_dir():
        raise ValueError('invalid recovered AIDA staging directory')
    for name, binding in recovered.items():
        if (name in ('.', '..') or not re.fullmatch(r'[A-Za-z0-9_.-]+', name)
                or not isinstance(binding, dict)):
            raise ValueError('invalid recovered AIDA staging binding')
        directory = base / name
        relative = Path(binding['original_path'])
        if (directory.is_symlink() or not directory.is_dir() or relative.is_absolute()
                or '..' in relative.parts or str(relative) in ('.', '')):
            raise ValueError('invalid recovered AIDA staging path')
        parent = directory
        for part in relative.parts[:-1]:
            parent = parent / part
            if parent.is_symlink() or not parent.is_dir():
                raise ValueError('invalid recovered AIDA staging path')
        for path, key in ((directory / 'packet.json', 'packet_sha256'),
                          (directory / 'receipt.json', 'receipt_sha256'),
                          (directory / relative, 'original_sha256')):
            verified_file(path, binding[key])


def apply_retained_canonical(run_dir, state, *, apply=None):
    if not os.environ.get('FREEDIVING_REVIEW_URL') or not os.environ.get('FREEDIVING_APP_URL'):
        raise ValueError('canonical JDBC environment incomplete')
    cohort = run_dir / 'reconciliation' / 'aida-cohort.json'
    expected = state['reconciliation']['export_sha256']
    verified_file(cohort, expected)
    receipt = run_dir / 'reconciliation' / 'canonical-receipt.json'
    try:
        if apply:
            apply(cohort, expected, receipt)
        else:
            command(['clojure', '-M', '-m', 'freediving.retained-aida-apply',
                     'apply', str(cohort), expected, str(receipt)])
        result = json.loads(receipt.read_text())
        if (result.get('schema') != 'retained-aida-canonical-receipt/v1'
                or result.get('cohort-sha256') != expected
                or type(result.get('identity-revision')) is not int
                or type(result.get('human-correction-revision')) is not int
                or result['human-correction-revision'] < 0):
            raise ValueError('canonical receipt binding changed')
    except Exception:
        state['canonical'] = {'status': 'failed', 'cohort_sha256': expected}
        state['reconciliation']['canonical_status'] = 'failed'
        atomic_json(run_dir / 'state.json', state)
        raise
    state['canonical'] = {'status': 'complete', 'receipt_sha256': digest(receipt),
                          'cohort_sha256': expected,
                          'identity_revision': result['identity-revision'],
                          'owner_correction_revision': result['human-correction-revision'],
                          'accepted_group_count': result['accepted-group-count'],
                          'human_negative_pair_count': result['human-negative-pair-count'],
                          'provider_calls': result['provider-calls']}
    state['reconciliation']['canonical_status'] = 'applied'
    atomic_json(run_dir / 'state.json', state)


def run_retained(plan_path, run_dir, *, adapter=None, canonical_apply=False, canonical=None):
    """Stage one immutable real AIDA cohort and checkpoint its verified export.

    The export is a proposal for the separate canonical route. This function
    never mutates the frozen snapshot, PG store, DecisionStore, or owner ledger.
    """
    plan = checked_retained_plan(plan_path)
    source = Path(plan['snapshot']['path'])
    snapshot_files = {'manifest.json': plan['snapshot']['manifest_sha256'],
                      'snapshot.sqlite': plan['snapshot']['sqlite_sha256']}
    for name, expected in snapshot_files.items():
        verified_file(source / name, expected)
    manifest = json.loads((source / 'manifest.json').read_text())
    if manifest.get('snapshot_sha256') != plan['snapshot']['snapshot_sha256']:
        raise ValueError('retained snapshot identity changed')
    command([sys.executable, str(ROOT / 'unified_evidence_snapshot.py'), 'verify',
             '--output-dir', str(source)])
    for item in plan['inputs']:
        verified_file(item['path'], item['sha256'])
    bound = {item['name']: item for item in plan['inputs']}
    frozen_observations = json.loads(Path(bound['aida_observations']['path']).read_text())
    if not isinstance(frozen_observations, list) or any(
            not isinstance(row, dict) or not isinstance(row.get('source_name'), str)
            for row in frozen_observations):
        raise ValueError('invalid frozen AIDA observations')
    source_names = {row['source_name'] for row in frozen_observations}
    supplied_paths = {Path(item['path']).resolve() for item in plan['inputs']}
    recovered_assets = recovered_aida_assets(manifest, plan.get('recovered_packets', []), source_names)
    if not required_aida_assets(manifest, source_names - set(recovered_assets)) <= supplied_paths:
        raise ValueError('AIDA replay source bytes absent from retained bundle')
    store_path = next(Path(item['path']) for item in plan['inputs'] if item['name'] == 'decision_store')
    with store_path.open('rb') as store_stream:
        header = store_stream.read(16)
    if header != b'SQLite format 3\x00':
        raise ValueError('DecisionStore SQLite required')
    import sqlite3
    try:
        with sqlite3.connect(store_path.as_uri() + '?mode=ro&immutable=1', uri=True) as store:
            if store.execute('PRAGMA quick_check').fetchone()[0] != 'ok':
                raise ValueError('DecisionStore integrity check failed')
            actual_revision = int(store.execute("SELECT value FROM meta WHERE key='revision'").fetchone()[0])
            if actual_revision != plan['decision_revision']:
                raise ValueError('retained decision revision changed')
    except (sqlite3.DatabaseError, TypeError, AttributeError) as error:
        raise ValueError('DecisionStore SQLite required') from error
    sys.path.insert(0, str(ROOT))
    from reconciliation_corpus_test import build_pg_binding_report, build_report
    from affiliate_name_query import AffiliateNameQuery
    from unified_evidence_query import SnapshotQuery
    corpus_report = build_report(source, bound['name_evidence']['path'],
                                 bound['name_evidence']['sha256'])
    pg_report = build_pg_binding_report(source, bound['pg_export']['path'],
                                        snapshot_files['manifest.json'], snapshot_files['snapshot.sqlite'],
                                        bound['pg_export']['sha256'])
    with SnapshotQuery(source) as snapshot:
        name_listing = AffiliateNameQuery(bound['name_evidence']['path'],
                                          bound['name_evidence']['sha256'], source, snapshot).listing()
    preflight = {'corpus_report_sha256': hashlib.sha256(json.dumps(corpus_report, sort_keys=True).encode()).hexdigest(),
                 'pg_report_sha256': hashlib.sha256(json.dumps(pg_report, sort_keys=True).encode()).hexdigest(),
                 'checked_name_assertions': len(name_listing['assertions']),
                 'checked_name_gaps': len(name_listing['gaps']),
                 'pg_observation_versions': pg_report['pg_observation_versions'],
                 'snapshot_structured_pg_refs': pg_report['snapshot_structured_pg_refs']}
    plan_hash = digest(plan_path)
    run_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    if run_dir.is_symlink() or run_dir.stat().st_mode & 0o077:
        raise ValueError('run directory must be private')
    state_path = run_dir / 'state.json'
    if state_path.exists():
        state = json.loads(state_path.read_text())
        if state.get('schema') != 'retained-aida-local-run/v1' or state.get('plan_sha256') != plan_hash:
            raise ValueError('retained run plan changed; use a new run directory')
    else:
        state = {'schema': 'retained-aida-local-run/v1', 'run_id': str(uuid.uuid4()),
                 'plan_sha256': plan_hash, 'reconciliation': {'status': 'pending'},
                 'remote': {'status': 'pending'}}
        atomic_json(state_path, state)
    staged_snapshot = run_dir / 'snapshot'
    bundle = run_dir / 'cohort-bundle'
    if bundle.is_symlink():
        raise ValueError('retained cohort bundle is a symlink')
    bundle.mkdir(mode=0o700, exist_ok=True)
    staged_snapshot.mkdir(mode=0o700, exist_ok=True)
    for name, expected in snapshot_files.items():
        target = staged_snapshot / name
        if not target.exists():
            shutil.copyfile(source / name, target)
            target.chmod(0o600)
        verified_file(target, expected)
    staged = {}
    for item in plan['inputs']:
        target = bundle / item['name']
        if not target.exists():
            shutil.copyfile(item['path'], target)
            target.chmod(0o600)
        verified_file(target, item['sha256'])
        staged[item['name']] = target
    staged_recovered = {}
    if recovered_assets:
        private_stage_directory(bundle / 'recovered-packets')
    for name, (packet, receipt, original, relative, binding) in recovered_assets.items():
        recovery_dir = bundle / 'recovered-packets' / name
        private_stage_directory(recovery_dir)
        for source_path, target, expected in (
                (packet, recovery_dir / 'packet.json', binding['sha256']),
                (receipt, recovery_dir / 'receipt.json', binding['receipt_sha256']),
                (original, recovery_dir / relative, binding['original_sha256'])):
            parent = recovery_dir
            for part in target.relative_to(recovery_dir).parts[:-1]:
                parent = parent / part
                private_stage_directory(parent)
            if target.is_symlink():
                raise ValueError('recovered AIDA staging file is a symlink')
            if not target.exists():
                shutil.copyfile(source_path, target)
                target.chmod(0o600)
            verified_file(target, expected)
        staged_recovered[name] = recovery_dir / 'packet.json'
    bundle_manifest = {'schema': 'retained-aida-cohort-bundle/v1',
                       'snapshot': {**snapshot_files, 'snapshot_sha256': manifest['snapshot_sha256']},
                       'inputs': {item['name']: item['sha256'] for item in plan['inputs']},
                       'recovered_packets': {name: {'packet_sha256': binding['sha256'],
                                                   'receipt_sha256': binding['receipt_sha256'],
                                                   'original_sha256': binding['original_sha256'],
                                                   'original_path': str(relative)}
                                             for name, (_, _, _, relative, binding) in recovered_assets.items()}}
    bundle_path = bundle / 'manifest.json'
    if bundle_path.exists():
        if json.loads(bundle_path.read_text()) != bundle_manifest:
            raise ValueError('retained cohort bundle changed')
    else:
        atomic_json(bundle_path, bundle_manifest)
    verified_staged_recovery(bundle, bundle_manifest['recovered_packets'])
    output = run_dir / 'reconciliation' / 'aida-cohort.json'
    if state['reconciliation'].get('status') == 'complete':
        verified_file(output, state['reconciliation']['export_sha256'])
        if digest(bundle_path) != state['reconciliation']['source_bundle_sha256']:
            raise ValueError('retained cohort bundle changed')
        if canonical_apply:
            apply_retained_canonical(run_dir, state, apply=canonical)
        return state
    output.parent.mkdir(mode=0o700, exist_ok=True)
    state['reconciliation'] = {'status': 'running', 'decision_revision': plan['decision_revision'],
                               'owner_correction_revision': plan['owner_correction_revision']}
    atomic_json(state_path, state)
    try:
        canonical_history = None
        if canonical_apply:
            canonical_history = output.parent / 'canonical-history.json'
            command(['clojure', '-M', '-m', 'freediving.retained-aida-apply',
                     'history', manifest['snapshot_sha256'], str(canonical_history)])
        if adapter:
            adapter(staged_snapshot, staged['aida_observations'], staged['aida_plan'], output)
        else:
            retained_adapter(staged_snapshot, staged['aida_observations'],
                             staged['aida_plan'], output, staged_recovered,
                             canonical_history)
        exported = json.loads(output.read_text())
        expected_binding = {'snapshot_sha256': manifest['snapshot_sha256'],
                            'observations_sha256': digest(staged['aida_observations']),
                            'plan_sha256': digest(staged['aida_plan'])}
        if canonical_history is not None:
            observed = json.loads(canonical_history.read_text())
            if (observed.get('schema') != 'retained-aida-canonical-history/v1'
                    or observed.get('snapshot_sha256') != manifest['snapshot_sha256']
                    or type(observed.get('revision')) is not int
                    or not isinstance(observed.get('events'), list)
                    or observed['revision'] != len(observed['events'])):
                raise ValueError('canonical history binding changed')
            expected_binding['identity_revision'] = observed['revision']
            expected_binding['history_event_ids'] = [event['id'] for event in observed['events']]
        if exported.get('schema') != 'retained-aida-cohort/v1' or exported.get('binding') != expected_binding:
            raise ValueError('retained AIDA export binding changed')
        for item in plan['inputs']:
            verified_file(item['path'], item['sha256'])
        state['reconciliation'] = {'status': 'complete', 'mode': 'retained_aida',
                                   'scope': plan['scope'], 'snapshot_sha256': manifest['snapshot_sha256'],
                                   'source_bundle_sha256': digest(bundle_path),
                                   'decision_revision': plan['decision_revision'],
                                   'ledger_revision': plan['ledger_revision'],
                                   'ledger_sha256': digest(staged['ledger']),
                                   'owner_correction_revision': plan['owner_correction_revision'],
                                   'export_sha256': digest(output), 'counts': exported['counts'],
                                   'preflight': preflight,
                                   'provider_calls': 0, 'canonical_status': 'pending'}
        atomic_json(state_path, state)
    except Exception as error:
        state['reconciliation'] = {**state['reconciliation'], 'status': 'failed', 'error': str(error)}
        atomic_json(state_path, state)
        raise
    if canonical_apply:
        apply_retained_canonical(run_dir, state, apply=canonical)
    return state


def command(argv):
    subprocess.run(argv, check=True)


def run(plan_path, run_dir, *, remote_config=None, owner_access_jwt=None,
        publisher_requests_stopped=False, vps_reachable=None, remote_factory=None,
        status_access_jwt=None, status_token=None):
    plan = checked_plan(plan_path)
    if plan.get('reconciliation') and remote_config is not None:
        raise ValueError('synthetic reconciliation cannot activate remote presentation')
    plan_hash = digest(plan_path)
    run_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    if run_dir.is_symlink() or (run_dir.stat().st_mode & 0o077):
        raise ValueError('run directory must be private')
    state_path = run_dir / 'state.json'
    if state_path.exists():
        state = json.loads(state_path.read_text())
        if state['plan_sha256'] != plan_hash:
            raise ValueError('run plan changed; use a new run directory')
    else:
        state = {'schema': 'local-evidence-run/v1', 'run_id': str(uuid.uuid4()), 'plan_sha256': plan_hash, 'stages': {},
                 'local': {'status': 'pending'}, 'remote': {'status': 'pending', 'active': None, 'pending': None, 'failed': None}}
        if plan.get('reconciliation'):
            state['reconciliation'] = {'status': 'pending'}
        atomic_json(state_path, state)
    try:
        if state['local']['status'] == 'complete':
            verify_staging(run_dir, state)
        else:
            for stage in plan['stages']:
                saved = state['stages'].get(stage['name'])
                if saved and saved['status'] == 'complete':
                    if all(Path(path).is_file() and digest(path) == saved['outputs'][path] for path in stage['outputs']):
                        continue
                    raise ValueError(f"checkpoint output changed: {stage['name']}")
                state['stages'][stage['name']] = {'status': 'running'}
                atomic_json(state_path, state)
                command(stage['command'])
                outputs = {path: digest(path) for path in stage['outputs']}
                state['stages'][stage['name']] = {'status': 'complete', 'outputs': outputs}
                atomic_json(state_path, state)
            snapshot = run_dir / 'snapshot'
            if not snapshot.exists():
                build_dir = Path(tempfile.mkdtemp(prefix='.snapshot-stage-', dir=run_dir))
                os.chmod(build_dir, 0o700)
                try:
                    argv = [sys.executable, str(ROOT / 'unified_evidence_snapshot.py'), 'build', '--cutoff', plan['cutoff'], '--output-dir', str(build_dir)]
                    for item in plan['inputs']:
                        argv += ['--input', f"{item['name']}={item['path']}", '--required-input', f"{item['name']}={digest(item['path'])}"]
                    for item in plan['excluded']:
                        argv += ['--excluded', f"{item['name']}={item['path']}:{item['reason']}"]
                    command(argv)
                    command([sys.executable, str(ROOT / 'unified_evidence_snapshot.py'), 'verify', '--output-dir', str(build_dir)])
                    build_dir.rename(snapshot)
                finally:
                    if build_dir.exists():
                        shutil.rmtree(build_dir)
            command([sys.executable, str(ROOT / 'unified_evidence_snapshot.py'), 'verify', '--output-dir', str(snapshot)])
            snapshot_manifest = json.loads((snapshot / 'manifest.json').read_text())
            inventory = json.loads(Path(plan['source_inventory']).read_text())
            sources = inventory['sources']
            for identifier, path, content_type in (
                ('snapshot.sqlite', snapshot / 'snapshot.sqlite', 'application/vnd.sqlite3'),
                ('snapshot-manifest.json', snapshot / 'manifest.json', 'application/json')):
                if any(item['id'] == identifier for item in sources):
                    raise ValueError(f'reserved source id: {identifier}')
                sources.append({'id': identifier, 'sha256': digest(path), 'bytes': path.stat().st_size,
                                'content_type': content_type, 'source_path': str(path),
                                'receipt': {'kind': 'local-evidence-run', 'cutoff': plan['cutoff']},
                                'classification': 'eligible', 'metadata': {}})
            bound_inventory = run_dir / 'bound-inventory.json'
            atomic_json(bound_inventory, inventory)
            bundle = run_dir / 'bundle'
            if not bundle.exists():
                command([sys.executable, str(ROOT / 'private_source_bundle.py'), 'build', '--inventory', str(bound_inventory), '--bundle-dir', str(bundle)])
            command([sys.executable, str(ROOT / 'private_source_bundle.py'), 'verify', '--bundle-dir', str(bundle)])
            bundle_manifest = json.loads((bundle / 'manifest.json').read_text())
            for identifier, path in (('snapshot.sqlite', snapshot / 'snapshot.sqlite'), ('snapshot-manifest.json', snapshot / 'manifest.json')):
                matches = [item for item in bundle_manifest['sources'] if item['id'] == identifier and item['status'] == 'included' and item['sha256'] == digest(path)]
                if len(matches) != 1:
                    raise ValueError(f'bundle missing matching {identifier}')
            state['coverage'] = {'status': 'verified_partial', 'cutoff': plan['cutoff'],
                                 'gaps': [{'name': item['name'], 'reason': item['reason']} for item in plan['excluded']],
                                 'confirmed_distinct_attempts': snapshot_manifest['confirmed_distinct_attempts']}
            state['local'] = {'status': 'complete', 'snapshot_sha256': snapshot_manifest['snapshot_sha256'],
                              'snapshot_manifest_sha256': digest(snapshot / 'manifest.json'),
                              'bundle_manifest_sha256': digest(bundle / 'manifest.json')}
            state['remote']['pending'] = state['local']['snapshot_sha256']
            atomic_json(state_path, state)
    except Exception as error:
        state['local'] = {'status': 'failed', 'error': str(error)}
        atomic_json(state_path, state)
        raise

    if plan.get('reconciliation'):
        try:
            reconcile_local(plan['reconciliation'], run_dir, state)
            atomic_json(state_path, state)
        except Exception as error:
            state['reconciliation'] = {**state['reconciliation'], 'status': 'failed',
                                       'error': str(error)}
            atomic_json(state_path, state)
            raise

    if bool(status_access_jwt) != bool(status_token):
        raise ValueError('private status credentials incomplete')
    if status_access_jwt:
        from private_status_sync import sync_status
        sync_status(run_dir, status_access_jwt, status_token)

    if remote_config is not None:
        from evidence_presentation import present
        from private_evidence_remote import PrivateEvidenceRemote

        binding = {'snapshot_sha256': state['local']['snapshot_sha256'],
                   'bundle_manifest_sha256': state['local']['bundle_manifest_sha256']}
        try:
            if not publisher_requests_stopped:
                raise ValueError('publisher requests must stop before deployment')
            if not owner_access_jwt:
                raise ValueError('owner Access identity session required')
            config = json.loads(Path(remote_config).read_text(encoding='utf-8'))
            keys = {'host', 'root', 'remote_script', 'ssh', 'code_bundle',
                    'activation_script', 'owner_url', 'cited_record_id',
                    'cited_source_sha256'}
            if not isinstance(config, dict) or set(config) != keys:
                raise ValueError('remote configuration requires explicit destination and cited PDF binding')
            remote = (remote_factory or PrivateEvidenceRemote)(
                **config, owner_access_jwt=owner_access_jwt)
            probe = vps_reachable or (lambda: ssh_route_reachable(config['ssh'], config['host']))
            present(run_dir, remote, publisher_requests_stopped=True,
                    vps_reachable=probe,
                    status_sync=(lambda: sync_status(run_dir, status_access_jwt, status_token)) if status_access_jwt else None)
        except Exception as error:
            state = json.loads(state_path.read_text())
            state['remote'].update(status='failed', pending=binding,
                                   failed=binding, error=str(error))
            atomic_json(state_path, state)
            if status_access_jwt:
                try:
                    sync_status(run_dir, status_access_jwt, status_token)
                except Exception:
                    pass
            raise
        state = json.loads(state_path.read_text())
    if status_access_jwt:
        sync_status(run_dir, status_access_jwt, status_token)
    print(json.dumps(state, sort_keys=True))
    return state


def verify_staging(run_dir, state):
    snapshot = run_dir / 'snapshot'
    bundle = run_dir / 'bundle'
    command([sys.executable, str(ROOT / 'unified_evidence_snapshot.py'), 'verify', '--output-dir', str(snapshot)])
    command([sys.executable, str(ROOT / 'private_source_bundle.py'), 'verify', '--bundle-dir', str(bundle)])
    if digest(snapshot / 'manifest.json') != state['local']['snapshot_manifest_sha256'] or digest(bundle / 'manifest.json') != state['local']['bundle_manifest_sha256']:
        raise ValueError('completed staging manifest changed')


def reconcile_local(config, run_dir, state):
    spec = Path(config['spec'])
    if not spec.is_file() or spec.is_symlink():
        raise ValueError('reconciliation specification unavailable')
    spec_hash = digest(spec)
    name = config['name_evidence']
    gaps = []
    if name['status'] == 'checked':
        from affiliate_name_query import AffiliateNameQuery
        from unified_evidence_query import SnapshotQuery
        snapshot_dir = run_dir / 'snapshot'
        AffiliateNameQuery(name['path'], name['sha256'], snapshot_dir,
                           SnapshotQuery(snapshot_dir)).listing()
    else:
        gaps.append({'name': 'affiliate-names', 'reason': name['reason']})
    completed = state.get('reconciliation', {})
    ledger = run_dir / 'reconciliation' / 'flow.edn'
    owner_config = config.get('owner_sync_config')
    if owner_config:
        owner_config = Path(owner_config)
        info = owner_config.lstat()
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                or info.st_mode & 0o077):
            raise ValueError('owner synchronization config must be an owner-only regular file')
        owner_config = owner_config.resolve(strict=True)
    if ledger.is_symlink():
        raise ValueError('reconciliation ledger must be a private file')
    if completed.get('status') == 'complete':
        if (completed.get('spec_sha256') != spec_hash
                or completed.get('snapshot_sha256') != state['local']['snapshot_sha256']
                or not ledger.is_file()
                or digest(ledger) != completed.get('ledger_sha256')):
            raise ValueError('completed reconciliation checkpoint changed')
        if not owner_config:
            return
    ledger.parent.mkdir(mode=0o700, exist_ok=True)
    known_calls = completed.get('metrics', {}).get('provider', {}).get(
        'calls_recorded', completed.get('calls_recorded_before_execution', 0))
    interrupted = completed.get('prior_interrupted', False) or completed.get('status') in ('running', 'failed')
    state['reconciliation'] = {'status': 'running', 'spec_sha256': spec_hash,
                               'calls_recorded_before_execution': known_calls,
                               'prior_interrupted': interrupted}
    atomic_json(run_dir / 'state.json', state)
    argv = ['clojure', '-M', '-m', 'freediving.local-reconciliation',
            str(spec), str(ledger), state['local']['snapshot_sha256']]
    if owner_config:
        argv.append(str(owner_config))
    result = subprocess.run(argv,
                            cwd=ROOT.parent, capture_output=True, text=True, check=True)
    if digest(spec) != spec_hash:
        raise ValueError('reconciliation specification changed during run')
    report = json.loads(result.stdout)
    report['metrics']['provider']['calls_recorded'] = known_calls + report['provider_calls']
    report['metrics']['provider']['interrupted_call_count_unknown'] = interrupted
    report['metrics']['binding']['run_id'] = state['run_id']
    report['metrics']['binding']['stage_checkpoint'] = 'reconciliation-complete'
    report['metrics']['binding']['spec_sha256'] = spec_hash
    report['metrics']['binding']['ledger_sha256'] = digest(ledger)
    report['metrics']['source_gaps'] = len(state['coverage']['gaps']) + len(gaps)
    state['reconciliation'] = {**report, 'status': 'complete', 'mode': 'synthetic',
                               'spec_sha256': spec_hash, 'ledger_sha256': digest(ledger),
                               'gaps': gaps}


def ssh_route_reachable(ssh, host):
    try:
        result = subprocess.run(
            [str(ssh), '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=5',
             '-o', 'ConnectionAttempts=1', '-T', '--', host, 'true'],
            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL, timeout=7, check=False)
        return result.returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['run', 'retained', 'metrics'])
    parser.add_argument('--plan', type=Path)
    parser.add_argument('--run-dir', type=Path, required=True)
    parser.add_argument('--remote-config', type=Path)
    parser.add_argument('--owner-access-jwt-env')
    parser.add_argument('--publisher-requests-stopped', action='store_true')
    parser.add_argument('--status-access-jwt-env')
    parser.add_argument('--status-token-env')
    parser.add_argument('--canonical-apply', action='store_true')
    args = parser.parse_args()
    try:
        if args.command == 'metrics':
            state = json.loads((args.run_dir / 'state.json').read_text())
            if state.get('schema') == 'retained-aida-local-run/v1':
                receipt = state['reconciliation']
                if receipt.get('status') != 'complete' or not (receipt.get('export_sha256') ==
                        digest(args.run_dir / 'reconciliation' / 'aida-cohort.json')) or not (
                        receipt.get('source_bundle_sha256') == digest(args.run_dir / 'cohort-bundle' / 'manifest.json')):
                    raise ValueError('metrics checkpoint binding changed')
                bundle = args.run_dir / 'cohort-bundle'
                verified_staged_recovery(bundle, json.loads((bundle / 'manifest.json').read_text()).get('recovered_packets', {}))
                canonical = state.get('canonical')
                if canonical and (canonical.get('status') != 'complete' or
                                  canonical.get('cohort_sha256') != receipt['export_sha256'] or
                                  canonical.get('receipt_sha256') != digest(args.run_dir / 'reconciliation' / 'canonical-receipt.json')):
                    raise ValueError('canonical checkpoint binding changed')
                print(json.dumps(receipt, sort_keys=True))
                return 0
            receipt = state['reconciliation']['metrics']
            if (state['reconciliation']['status'] != 'complete'
                    or receipt['binding']['run_id'] != state['run_id']
                    or receipt['binding']['snapshot_sha256'] != state['local']['snapshot_sha256']
                    or receipt['binding']['decision_revision'] != state['reconciliation']['decision_revision']
                    or receipt['binding']['spec_sha256'] != state['reconciliation']['spec_sha256']
                    or receipt['binding']['ledger_sha256'] != state['reconciliation']['ledger_sha256']
                    or receipt['binding']['ledger_sha256'] != digest(args.run_dir / 'reconciliation' / 'flow.edn')):
                raise ValueError('metrics checkpoint binding changed')
            print(json.dumps(receipt, sort_keys=True))
            return 0
        if args.plan is None:
            raise ValueError('--plan required for run')
        if args.command == 'retained':
            if args.remote_config is not None or args.status_access_jwt_env or args.status_token_env:
                raise ValueError('retained cohort is local only')
            print(json.dumps(run_retained(args.plan, args.run_dir,
                                          canonical_apply=args.canonical_apply), sort_keys=True))
            return 0
        run(args.plan, args.run_dir, remote_config=args.remote_config,
            owner_access_jwt=os.environ.get(args.owner_access_jwt_env) if args.owner_access_jwt_env else None,
            publisher_requests_stopped=args.publisher_requests_stopped,
            status_access_jwt=os.environ.get(args.status_access_jwt_env) if args.status_access_jwt_env else None,
            status_token=os.environ.get(args.status_token_env) if args.status_token_env else None)
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, subprocess.CalledProcessError, json.JSONDecodeError) as error:
        print(f'local run: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
