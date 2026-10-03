"""Publish one sanitized local checkpoint through the private status route."""
import json
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, build_opener, HTTPRedirectHandler

STATUS_URL = 'https://poc.alphacompose.com/owner-evidence/api/presentation-status'


def _reconciliation_summary(state):
    run = state.get('reconciliation')
    if not run:
        return None
    if run.get('status') != 'complete':
        raise ValueError('reconciliation is not complete')
    receipt = run.get('metrics') or {}
    binding = receipt.get('binding') or {}
    if (receipt.get('schema') != 'local-reconciliation-metrics/v1'
            or binding.get('run_id') != (state.get('run_id') or state['plan_sha256'])
            or binding.get('snapshot_sha256') != state['local']['snapshot_sha256']
            or binding.get('decision_revision') != run.get('decision_revision')):
        raise ValueError('metrics checkpoint binding changed')
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


def _request(opener, method, url, jwt, token, payload=None):
    headers = {'Cf-Access-Jwt-Assertion': jwt, 'X-Freediving-Status-Token': token}
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


def sync_status(run_dir, jwt, token, *, url=STATUS_URL, opener=None):
    if url != STATUS_URL or not jwt or not token:
        raise ValueError('private status credentials or URL missing')
    state = json.loads((Path(run_dir) / 'state.json').read_text(encoding='utf-8'))
    if state['local']['status'] != 'complete':
        raise ValueError('local evidence is not complete')
    current = _request(opener or build_opener(_NoRedirect()), 'GET', url, jwt, token)
    if current.get('status') == 'stale':
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
    reconciliation = _reconciliation_summary(state)
    if reconciliation is not None:
        candidate['schema'] = 'private-presentation-status/v2'
        candidate['reconciliation'] = reconciliation
    if current.get('run_id') == run_id and current.get('local') == local and current.get('remote', {}).get('status') == remote['status'] and current['remote'].get('pending') == pending and current['remote'].get('failed') == failed and current.get('reconciliation') == reconciliation:
        return current
    payload = {**candidate, 'revision': current.get('revision', 0) + 1,
               'expected_revision': current.get('revision', 0)}
    return _request(opener or build_opener(_NoRedirect()), 'POST', url, jwt, token, payload)
