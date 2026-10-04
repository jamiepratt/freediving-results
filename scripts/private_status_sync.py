"""Publish one sanitized local checkpoint through the private status route."""
import json
import hashlib
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, build_opener, HTTPRedirectHandler

STATUS_URL = 'https://poc.alphacompose.com/owner-evidence/api/presentation-status'
STATUS_USER_AGENT = 'freediving-status-sync/1.0'


def _reconciliation_summary(state, run_dir):
    run = state.get('reconciliation')
    if not run:
        return None
    if run.get('status') != 'complete':
        raise ValueError('reconciliation is not complete')
    receipt = run.get('metrics') or {}
    binding = receipt.get('binding') or {}
    ledger = Path(run_dir) / 'reconciliation' / 'flow.edn'
    if ledger.is_symlink() or not ledger.is_file():
        raise ValueError('metrics checkpoint binding changed')
    ledger_sha256 = hashlib.sha256(ledger.read_bytes()).hexdigest()
    if (receipt.get('schema') != 'local-reconciliation-metrics/v1'
            or binding.get('run_id') != (state.get('run_id') or state['plan_sha256'])
            or binding.get('stage_checkpoint') != 'reconciliation-complete'
            or binding.get('snapshot_sha256') != state['local']['snapshot_sha256']
            or binding.get('decision_revision') != run.get('decision_revision')
            or binding.get('spec_sha256') != run.get('spec_sha256')
            or binding.get('ledger_sha256') != run.get('ledger_sha256')
            or binding.get('ledger_sha256') != ledger_sha256):
        raise ValueError('metrics checkpoint binding changed')
    if type(run.get('remote_store_revision')) is not int or run['remote_store_revision'] < 0:
        raise ValueError('authoritative owner decision revision unavailable')
    coverage = receipt['coverage']
    provider = receipt['provider']
    sample = receipt.get('sampled_error')
    if sample is not None:
        sample = {key: sample[key] for key in
                  ('sampling_frame', 'numerator', 'denominator', 'selection', 'selection_bias')}
    return {'snapshot_sha256': binding['snapshot_sha256'],
            'decision_revision': binding['decision_revision'],
            'owner_store_revision': run.get('remote_store_revision'),
            'metrics': {'decision_denominator': coverage['decision_denominator'],
                        'automatic_approved': coverage['automatic_approved'],
                        'unknown': coverage['unknown'], 'error': coverage['error'],
                        'conflict': coverage['conflict'],
                        'pending_review': coverage['pending_review'],
                        'source_gaps': receipt['source_gaps'],
                        'provider_calls_recorded': provider['calls_recorded'],
                        'sampled_error': sample,
                        'accepted_athletes': None, 'distinct_attempts': None,
                        'actual_monetary_cost': None}}


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *_):
        return None


def _request(opener, method, url, client_id, client_secret, token, payload=None):
    headers = {'CF-Access-Client-Id': client_id,
               'CF-Access-Client-Secret': client_secret,
               'X-Freediving-Status-Token': token,
               'User-Agent': STATUS_USER_AGENT}
    data = None
    if payload is not None:
        data = json.dumps(payload, sort_keys=True, separators=(',', ':')).encode('utf-8')
        if len(data) > 4096:
            raise ValueError('status payload too large')
        headers['Content-Type'] = 'application/json'
    try:
        with opener.open(Request(url, data=data, headers=headers, method=method), timeout=10) as response:
            if response.status != 200:
                raise RuntimeError('private status sync unavailable')
            body = response.read(4097)
            if len(body) > 4096:
                raise RuntimeError('private status response too large')
            return json.loads(body)
    except HTTPError as error:
        if error.code == 409:
            raise RuntimeError('private status revision conflict') from error
        raise RuntimeError('private status sync unavailable') from error


def sync_status(run_dir, client_id, client_secret, token, *, url=STATUS_URL, opener=None):
    if url != STATUS_URL or not client_id or not client_secret or not token:
        raise ValueError('private status credentials or URL missing')
    state = json.loads((Path(run_dir) / 'state.json').read_text(encoding='utf-8'))
    if state['local']['status'] != 'complete':
        raise ValueError('local evidence is not complete')
    current = _request(opener or build_opener(_NoRedirect()), 'GET', url, client_id, client_secret, token)
    if current.get('status') == 'stale':
        if (type(current.get('revision')) is not int or current['revision'] < 1
                or not isinstance(current.get('run_id'), str)
                or not isinstance(current.get('cutoff'), str)
                or current['run_id'] == (state.get('run_id') or state.get('plan_sha256'))
                or state['coverage']['cutoff'] <= current['cutoff']):
            raise RuntimeError('private status is stale after owner decision or active snapshot change')
    local = {'snapshot_sha256': state['local']['snapshot_sha256'],
             'cutoff': state['coverage']['cutoff'],
             'gap_count': len(state['coverage']['gaps'])}
    remote_state = state['remote']
    pending = remote_state.get('pending')
    failed = remote_state.get('failed')
    if isinstance(pending, dict):
        pending = pending.get('snapshot_sha256')
    if isinstance(failed, dict):
        failed = failed.get('snapshot_sha256')
    active = current['remote']['active']
    remote = {'status': remote_state['status'], 'pending': pending,
              'failed': failed, 'active': active if active and active.get('bundle_manifest_sha256') else None}
    run_id = state.get('run_id') or state['plan_sha256']
    candidate = {'schema': 'private-presentation-status/v1', 'run_id': run_id,
                 'local': local, 'remote': remote}
    reconciliation = _reconciliation_summary(state, run_dir)
    if reconciliation is not None:
        candidate['schema'] = 'private-presentation-status/v2'
        candidate['reconciliation'] = reconciliation
    if current.get('run_id') == run_id and current.get('local') == local and current.get('remote', {}).get('status') == remote['status'] and current['remote'].get('pending') == pending and current['remote'].get('failed') == failed and current.get('reconciliation') == reconciliation:
        return current
    payload = {**candidate, 'revision': current.get('revision', 0) + 1,
               'expected_revision': current.get('revision', 0)}
    return _request(opener or build_opener(_NoRedirect()), 'POST', url, client_id, client_secret, token, payload)


def sync_application_status(run_dir, application, client_id, client_secret, token,
                            *, url=STATUS_URL, opener=None):
    """Commit a private apply readback after both stores have been verified."""
    if url != STATUS_URL or not client_id or not client_secret or not token:
        raise ValueError('private status credentials or URL missing')
    state = json.loads((Path(run_dir) / 'state.json').read_text(encoding='utf-8'))
    if state['local']['status'] != 'complete':
        raise ValueError('local evidence is not complete')
    local = {'snapshot_sha256': state['local']['snapshot_sha256'],
             'cutoff': state['coverage']['cutoff'],
             'gap_count': len(state['coverage']['gaps'])}
    if application.get('snapshot_sha256') != local['snapshot_sha256']:
        raise ValueError('application snapshot binding mismatch')
    opener = opener or build_opener(_NoRedirect())
    current = _request(opener, 'GET', url, client_id, client_secret, token)
    run_id = state.get('run_id') or state['plan_sha256']
    if current.get('status') == 'stale' and not (
            current.get('schema') == 'private-presentation-status/v2'
            and current.get('run_id') == run_id
            and current.get('cutoff') == local['cutoff']
            and type(current.get('revision')) is int
            and current['revision'] >= 1):
        raise RuntimeError('private status is stale after owner decision or active snapshot change')
    active = current.get('remote', {}).get('active')
    if (not isinstance(active, dict) or active.get('snapshot_sha256') != local['snapshot_sha256']
            or not active.get('bundle_manifest_sha256')):
        raise ValueError('active snapshot binding mismatch')
    candidate = {'schema': 'private-presentation-status/v3', 'run_id': run_id,
                 'local': local, 'remote': {'status': 'active', 'pending': None,
                                           'failed': None, 'active': active},
                 'application': application}
    if all(current.get(key) == value for key, value in candidate.items()):
        return current
    payload = {**candidate, 'revision': current.get('revision', 0) + 1,
               'expected_revision': current.get('revision', 0)}
    return _request(opener, 'POST', url, client_id, client_secret, token, payload)
