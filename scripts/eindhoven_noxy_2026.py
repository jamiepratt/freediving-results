#!/usr/bin/env python3
"""Build a private, source-cited Eindhoven nOxy accounting packet."""

import argparse
import hashlib
import json
import os
import tempfile
from collections import Counter
from pathlib import Path


KEYS = ('noxy5-competition', 'noxy5-sessions', 'noxy5-results-snapshot', 'noxy5-attempts')
COMMON_FIELDS = ('attempt_id', 'session_id', 'discipline', 'entry_id', 'entry_sess_id',
                 'result_val', 'result_final_val', 'card_status', 'attempt_status',
                 'penalties_json', 'aida_points', 'cmas_points', 'noxy_points',
                 'heat_number', 'lane', 'ot_time', 'judge_names', 'assistant_names')


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def canon(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')) + '\n'


def load_json(path):
    data = path.read_bytes()
    return json.loads(data), sha256(data)


def verified_receipt(corpus, fetch):
    if fetch['http_status'] != 200 or not fetch['content_type'].lower().startswith('application/json'):
        raise ValueError(f"invalid response receipt: {fetch['key']}")
    receipt = {'key': fetch['key'], 'requested_url': fetch['requested_url'],
               'final_url': fetch['final_url'], 'http_status': fetch['http_status'],
               'content_type': fetch['content_type'], 'observed_at': fetch['observed_at']}
    for kind in ('body', 'headers'):
        item = fetch[kind]
        path = (corpus / item['path']).resolve()
        if not path.is_relative_to(corpus.resolve()):
            raise ValueError('source path escapes corpus')
        data = path.read_bytes()
        if sha256(data) != item['sha256'] or len(data) != item['bytes']:
            raise ValueError(f"source hash mismatch: {fetch['key']} {kind}")
        receipt[kind] = {'path': str(path), 'sha256': item['sha256'], 'bytes': len(data)}
        if kind == 'body':
            value = json.loads(data)
    return receipt, value


def citation(receipt, pointer):
    return {'url': receipt['final_url'], 'source_sha256': receipt['body']['sha256'],
            'json_pointer': pointer}


def session_metadata(row, sessions, session_receipt, event_date):
    session_id = row.get('session_id')
    if session_id is None:
        return None
    session = sessions.get(session_id)
    if session is None or session[1]['discipline'] != row.get('discipline'):
        raise ValueError(f'session/discipline mismatch: {session_id}')
    index, raw = session
    return {'session_id': session_id, 'discipline': raw['discipline'],
            'organization': raw.get('organization'), 'official_start_time': raw['official_start_time'],
            'event_date': event_date, 'citation': citation(session_receipt, f'/{index}'),
            'raw_fields': raw}


def build(corpus):
    manifest, manifest_hash = load_json(corpus / 'manifest.json')
    roster, roster_hash = load_json(corpus / 'roster.json')
    fetches = {x['key']: x for x in manifest['fetches'] if x['key'] in KEYS}
    if set(fetches) != set(KEYS):
        raise ValueError('missing source receipt')
    sources = {}
    receipts = {}
    for key in KEYS:
        receipts[key], sources[key] = verified_receipt(corpus, fetches[key])
    lead = next(x for x in roster['leads'] if x['id'] == 'eindhoven-2026:noxy-snapshot')
    snap_receipt = receipts['noxy5-results-snapshot']
    if lead['citation']['sha256'] != snap_receipt['body']['sha256'] or lead['citation']['url'] != snap_receipt['final_url']:
        raise ValueError('selected view source mismatch')
    competition = sources['noxy5-competition']
    snapshot = sources['noxy5-results-snapshot']
    if (competition['competition_id'] != 5 or snapshot['snapshot']['competition_id'] != 5
            or snapshot['snapshot']['comp_start_date'] != competition['start_date']
            or lead['competition_date'] != competition['start_date']):
        raise ValueError('competition date/id mismatch')
    event_date = competition['start_date']
    sessions = {}
    for i, row in enumerate(sources['noxy5-sessions']):
        sid = row['session_id']
        if sid in sessions or row['competition_id'] != 5 or row['official_start_time'][:10] != event_date:
            raise ValueError('session metadata mismatch')
        sessions[sid] = (i, row)
    result_rows = []
    overall_rows = []
    result_by_attempt = {}
    row_ids = set()
    for i, row in enumerate(snapshot['rows']):
        if row['row_id'] in row_ids or row['snapshot_id'] != snapshot['snapshot']['snapshot_id']:
            raise ValueError('duplicate row or snapshot mismatch')
        row_ids.add(row['row_id'])
        kind = row['row_type']
        item = {'row_id': row['row_id'], 'json_index_zero_based': i,
                'citation': citation(snap_receipt, f'/rows/{i}'), 'raw_fields': row,
                'event_date': event_date, 'category': None, 'gender': row.get('gender'),
                'discipline': row['discipline'],
                'status': {'card_status': row.get('card_status'), 'attempt_status': row.get('attempt_status')},
                'penalties': row.get('penalties_json'),
                'rank': {'open': row.get('rank_open'), 'gender': row.get('rank_gender')},
                'session': session_metadata(row, sessions, receipts['noxy5-sessions'], event_date)}
        if kind == 'RESULT':
            aid = row['attempt_id']
            if aid is None or aid in result_by_attempt:
                raise ValueError('missing or duplicate RESULT attempt_id')
            item['attempt_id'] = aid
            item['disposition'] = 'unlinked'
            result_by_attempt[aid] = item
            result_rows.append(item)
        elif kind == 'OVERALL':
            if row.get('attempt_id') is not None or row.get('session_id') is not None:
                raise ValueError('OVERALL row has attempt/session id')
            item['disposition'] = 'aggregate_not_attempt'
            overall_rows.append(item)
        else:
            raise ValueError(f'unknown snapshot row type: {kind}')
    endpoint_records = []
    endpoint_by_attempt = {}
    for i, row in enumerate(sources['noxy5-attempts']):
        aid = row['attempt_id']
        if aid is None or aid in endpoint_by_attempt:
            raise ValueError('missing or duplicate endpoint attempt_id')
        item = {'attempt_id': aid, 'json_index_zero_based': i,
                'citation': citation(receipts['noxy5-attempts'], f'/{i}'),
                'raw_fields': row, 'event_date': event_date,
                'discipline': row['discipline'],
                'status': {'card_status': row.get('card_status'), 'attempt_status': row.get('attempt_status')},
                'notes': row.get('judge_notes'), 'penalties': row.get('penalties_json'),
                'session': session_metadata(row, sessions, receipts['noxy5-sessions'], event_date),
                'disposition': 'endpoint_only'}
        endpoint_by_attempt[aid] = item
        endpoint_records.append(item)
    relationships = []
    conflicts = []
    for aid, item in result_by_attempt.items():
        other = endpoint_by_attempt.get(aid)
        if other is None:
            item['disposition'] = 'result_only'
            continue
        a, b = item['raw_fields'], other['raw_fields']
        different = [field for field in COMMON_FIELDS if field in a and field in b and a[field] != b[field]]
        if different:
            item['disposition'] = other['disposition'] = 'shared_id_conflicting_fields'
            conflicts.append({'attempt_id': aid, 'fields': different,
                              'result_pointer': item['citation']['json_pointer'],
                              'endpoint_pointer': other['citation']['json_pointer']})
        else:
            item['disposition'] = other['disposition'] = 'linked_by_attempt_id'
            relationships.append({'basis': 'equal attempt_id and exact common fields', 'attempt_id': aid,
                                  'result_pointer': item['citation']['json_pointer'],
                                  'endpoint_pointer': other['citation']['json_pointer']})
    counts = {'result_rows': len(result_rows), 'overall_rows': len(overall_rows),
              'endpoint_records': len(endpoint_records),
              'linked_result_endpoint_records': len(relationships),
              'endpoint_only_records': sum(x['disposition'] == 'endpoint_only' for x in endpoint_records),
              'confirmed_distinct_attempts': None}
    uncertainties = ['No independently confirmed distinct sporting attempt count.',
                     'Cross-publisher DYN pairing and rank agreement were not established.',
                     'Owner review, import, and publication remain unassessed.']
    if conflicts:
        uncertainties.append('Shared attempt IDs with conflicting common fields are not linked.')
    return {'schema': 'eindhoven-2026-noxy-private-accounting/v1',
            'parser_version': 'eindhoven-noxy-2026/1',
            'manifest': {'path': str((corpus / 'manifest.json').resolve()), 'sha256': manifest_hash},
            'roster': {'path': str((corpus / 'roster.json').resolve()), 'sha256': roster_hash},
            'source_receipts': [receipts[k] for k in KEYS],
            'selected_view': lead.get('selected_view'),
            'organizer_event_url': next((x['url'] for x in roster['leads'] if x['id'] == 'eindhoven-2026:organizer'), None),
            'competition': {'citation': citation(receipts['noxy5-competition'], ''), 'raw_fields': competition},
            'snapshot': {'citation': citation(snap_receipt, '/snapshot'), 'raw_fields': snapshot['snapshot']},
            'sessions': [session_metadata({'session_id': sid, 'discipline': raw['discipline']},
                        sessions, receipts['noxy5-sessions'], event_date) for sid, raw in
                        ((x['session_id'], x) for x in sources['noxy5-sessions'])],
            'result_rows': result_rows, 'overall_rows': overall_rows,
            'endpoint_records': endpoint_records, 'relationships': relationships,
            'conflicts': conflicts, 'result_rows_by_discipline': dict(sorted(Counter(x['discipline'] for x in result_rows).items())),
            'counts': counts, 'confirmed_distinct_attempts': None, 'uncertainties': uncertainties,
            'review_status': 'unreviewed', 'import_status': 'not_imported', 'publication_status': 'not_published'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    command = sub.add_parser('build')
    command.add_argument('--corpus', type=Path, required=True)
    command.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    packet = build(args.corpus)
    args.output.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    descriptor, temporary = tempfile.mkstemp(prefix='.packet-', dir=args.output.parent)
    try:
        with os.fdopen(descriptor, 'w', encoding='utf-8') as handle:
            handle.write(canon(packet))
        os.replace(temporary, args.output)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    print(canon({'output': str(args.output.resolve()), 'sha256': sha256(args.output.read_bytes()),
                 'counts': packet['counts'], 'result_rows_by_discipline': packet['result_rows_by_discipline']}), end='')


if __name__ == '__main__':
    main()
