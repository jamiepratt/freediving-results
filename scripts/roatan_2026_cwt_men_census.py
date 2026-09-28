#!/usr/bin/env python3
"""Export bounded, private #8 Roatan observations for the #55 census."""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path


def digest(data):
    return hashlib.sha256(data).hexdigest()


def canon(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))


def verified_object(path, expected):
    actual = digest(path.read_bytes())
    if actual != expected:
        raise ValueError(f'object hash mismatch: {path}')
    return {'path': str(path.resolve()), 'sha256': expected, 'bytes': path.stat().st_size}


def legacy_from_dump(path):
    sql = subprocess.run(['pg_restore', '-a', '-t', 'extractions', '-t', 'observations', '-f', '-', str(path)],
                         check=True, capture_output=True, text=True).stdout
    extraction = {}
    payloads = []
    table = None
    for line in sql.splitlines():
        if line.startswith('COPY freediving.extractions '):
            table = 'extractions'
        elif line.startswith('COPY freediving.observations '):
            table = 'observations'
        elif line == r'\.':
            table = None
        elif table == 'extractions':
            cells = line.split('\t')
            if len(cells) > 4 and cells[3] == 'cmas-2026-roatan-json/1':
                extraction[cells[0]] = {'artifact_sha256': cells[1], 'source_sha256': cells[2],
                                        'parser_version': cells[3]}
        elif table == 'observations':
            cells = line.split('\t', 5)
            if len(cells) == 6:
                payloads.append(cells)
    payloads = [x for x in payloads if x[0] in extraction]
    edn = '\n'.join(x[5] for x in payloads) + '\n'
    code = '(require (quote [clojure.edn :as edn]) (quote [cheshire.core :as json])) (doseq [line (line-seq (java.io.BufferedReader. *in*))] (println (json/generate-string (edn/read-string line))))'
    converted = subprocess.run(['bb', '-e', code], input=edn, capture_output=True, text=True, check=True).stdout
    values = [json.loads(line) for line in converted.splitlines()]
    return [{'job_id': cells[0], 'ordinal': int(cells[1]), 'candidate_id': cells[2],
             **extraction[cells[0]], 'payload': value} for cells, value in zip(payloads, values)]


def review_rows_from_dump(path):
    sql = subprocess.run(['pg_restore', '-a', '-t', 'extraction_reviews', '-f', '-', str(path)],
                         check=True, capture_output=True, text=True).stdout
    rows = []
    copying = False
    for line in sql.splitlines():
        if line.startswith('COPY freediving.extraction_reviews '):
            copying = True
        elif line == r'\.':
            copying = False
        elif copying:
            cells = line.split('\t')
            if len(cells) != 9:
                raise ValueError('invalid extraction review COPY row')
            rows.append({'id': cells[0], 'job_id': cells[1], 'ordinal': int(cells[2]),
                         'revision': int(cells[3]), 'action': cells[4],
                         'event_id': None if cells[5] == r'\N' else cells[5],
                         'body_edn': cells[6], 'db_role': cells[7], 'recorded_at': cells[8],
                         'row_sha256': digest(line.encode('utf-8'))})
    code = '(require (quote [clojure.edn :as edn]) (quote [cheshire.core :as json])) (doseq [line (line-seq (java.io.BufferedReader. *in*))] (println (json/generate-string (edn/read-string line))))'
    converted = subprocess.run(['bb', '-e', code], input='\n'.join(r['body_edn'] for r in rows) + '\n',
                               capture_output=True, text=True, check=True).stdout
    bodies = [json.loads(line) for line in converted.splitlines()]
    if len(bodies) != len(rows):
        raise ValueError('review body count mismatch')
    for row, body in zip(rows, bodies):
        row['body'] = body
    return rows


def build(args):
    corrected_path = Path(args.corrected_packet)
    corrected_bytes = corrected_path.read_bytes()
    corrected = json.loads(corrected_bytes)
    receipt_path = Path(args.owner_receipt)
    receipt_bytes = receipt_path.read_bytes()
    receipt = json.loads(receipt_bytes)
    stage = Path(args.stage)
    review_rows = (json.loads(Path(args.review_rows).read_text()) if args.review_rows
                   else review_rows_from_dump(stage / 'post-accept-3551.dump'))
    legacy = (json.loads(Path(args.legacy_observations).read_text()) if args.legacy_observations
              else legacy_from_dump(stage / 'post-accept-3551.dump'))
    units = corrected['units']
    if len({u['unit'] for u in units}) != len(units):
        raise ValueError('duplicate unit')
    source_objects = []
    acquisitions = []
    artifacts = []
    for unit in units:
        source_objects.append({'unit': unit['unit'], 'source_id': f"sha256:{unit['source_sha256']}", **verified_object(
            stage / 'archive' / 'objects' / unit['source_sha256'], unit['source_sha256']),
            'view_url': unit['view_url'], 'api_url': unit['api_url']})
    acq_dir = stage / 'archive' / 'acquisitions'
    if acq_dir.exists():
        for path in sorted(acq_dir.glob('*.edn')):
            acquisitions.append(verified_object(path, path.stem))
    artifact_hashes = {u['artifact_sha256'] for u in units} | {x['artifact_sha256'] for x in legacy}
    for artifact_hash in sorted(artifact_hashes):
        artifacts.append(verified_object(stage / 'archive' / 'derived-objects' / artifact_hash, artifact_hash))
    review_event_objects = []
    for path in sorted((stage / 'owner-3551-v2-accept-20260928').glob('event-*.edn')):
        review_event_objects.append(verified_object(path, digest(path.read_bytes())))
    acceptance_objects = []
    for path in sorted((stage / 'owner-3551-v2-accept-20260928').glob('accept-*.edn')):
        acceptance_objects.append(verified_object(path, digest(path.read_bytes())))
    receipt_rows = {(x['json_index_zero_based'], receipt['version']['job_id']): x for x in receipt['rows']}
    if receipt['version']['parser_version'] != 'cmas-2026-roatan-json/2':
        raise ValueError('owner receipt does not name parser v2')
    review_index = {(r['ordinal'], r['job_id']): r for r in review_rows}
    if len(review_index) != len(review_rows) or set(review_index) != set(receipt_rows):
        raise ValueError('database review scope mismatch')
    for key, receipt_row in receipt_rows.items():
        review = review_index[key]
        evidence = review['body']['evidence']
        if (review['id'] != receipt_row['event_id'] or review['action'] != 'accept'
                or review['revision'] != 1 or review['db_role'] != 'reviews_owner'
                or review['body'].get('id') != review['id']
                or review['body'].get('job-id') != review['job_id']
                or review['body'].get('ordinal') != review['ordinal']
                or review['body'].get('action') != 'accept'
                or evidence.get('candidate-id') != receipt_row['candidate_id']
                or evidence.get('job-id') != review['job_id']
                or evidence.get('row-index-zero-based') != key[0]
                or evidence.get('source-sha256') != receipt['version']['source_sha256']
                or evidence.get('parser-version') != receipt['version']['parser_version']
                or evidence.get('artifact-sha256') != receipt['version'].get('artifact_sha256', evidence.get('artifact-sha256'))):
            raise ValueError(f'database review mismatch: {key}')
    positions = []
    versions = []
    version_diffs = []
    v1_index = {(x['source_sha256'], x['ordinal']): x for x in legacy}
    if len(v1_index) != len(legacy):
        raise ValueError('duplicate legacy observation')
    used_v1 = set()
    for unit in sorted(units, key=lambda x: x['unit']):
        for row in unit['rows']:
            index = row['json_index_zero_based']
            key = (unit['source_sha256'], index)
            old = v1_index.get(key)
            if old is None:
                raise ValueError(f'missing v1 observation: {key}')
            used_v1.add(key)
            if row['citation']['source-sha256'] != unit['source_sha256'] or row['citation']['row-index-zero-based'] != index:
                raise ValueError('citation mismatch')
            raw = row['raw']
            positions.append({'unit': unit['unit'], 'json_index_zero_based': index,
                              'event_date': unit['session_date'], 'discipline': 'CWT',
                              'category': row['parsed']['category'], 'name': raw['ParPrintName'],
                              'source_sha256': unit['source_sha256'],
                              'source_id': f"sha256:{unit['source_sha256']}",
                              'parser_version': unit['parser_version'], 'view_url': unit['view_url'],
                              'citation': row['citation'], 'raw_fields': raw,
                              'parsed_fields': row['parsed'], 'visible_display': row['visible_display'],
                              'depths': {'declared': raw.get('DECLLEN_STR'), 'raw': raw.get('ResResult'),
                                         'publisher_final': raw.get('ResResultFinal')},
                              'penalty': raw.get('ResPenality'), 'status': raw.get('ResReasonCode'),
                              'notes': raw.get('ResNotePenality'),
                              'source_notes': {'result_note': raw.get('ResNote'),
                                               'penalty_note': raw.get('ResNotePenality'),
                                               'start_note': raw.get('ResStartNote'),
                                               'record_token': raw.get('ResRecord')},
                              'visible_status': row['visible_display'].get('status'),
                              'visible_notes': row['visible_display'].get('notes'),
                              'selection_status': 'blocked'})
            versions.append({'unit': unit['unit'], 'json_index_zero_based': index,
                             'job_id': old['job_id'], 'candidate_id': old['candidate_id'],
                             'parser_version': old['parser_version'], 'artifact_sha256': old['artifact_sha256'],
                             'source_sha256': old['source_sha256'],
                             'source_id': f"sha256:{old['source_sha256']}",
                             'citation': old['payload'].get('citation'),
                             'raw_fields': old['payload'].get('raw'), 'parsed_fields': old['payload'].get('parsed'),
                             'review_status': 'unreviewed', 'historical': True})
            accepted = receipt_rows.get((index, unit['job_id'])) if unit['unit'] == 3551 else None
            if accepted and (accepted['candidate_id'] != old['candidate_id'] or receipt['version']['source_sha256'] != unit['source_sha256']):
                raise ValueError('receipt version mismatch')
            versions.append({'unit': unit['unit'], 'json_index_zero_based': index,
                             'job_id': unit['job_id'], 'candidate_id': old['candidate_id'],
                             'parser_version': unit['parser_version'], 'artifact_sha256': unit['artifact_sha256'],
                             'source_sha256': unit['source_sha256'],
                             'source_id': f"sha256:{unit['source_sha256']}",
                             'citation': row['citation'],
                             'raw_fields': raw, 'parsed_fields': row['parsed'],
                             'review_status': 'extraction_accepted' if accepted else 'unreviewed',
                             'historical_review_event_id': accepted['event_id'] if accepted else None})
            old_parsed = old['payload'].get('parsed') or {}
            new_parsed = row['parsed']
            changed = {field: {'v1': old_parsed.get(field), 'v2': new_parsed.get(field)}
                       for field in sorted(set(old_parsed) | set(new_parsed))
                       if old_parsed.get(field) != new_parsed.get(field)}
            version_diffs.append({'unit': unit['unit'], 'json_index_zero_based': index,
                                  'v1_job_id': old['job_id'], 'v2_job_id': unit['job_id'],
                                  'parsed_field_changes': changed})
    if used_v1 != set(v1_index):
        raise ValueError('unmatched legacy observations')
    if len(receipt_rows) != len(receipt['rows']) or sum(v['review_status'] == 'extraction_accepted' for v in versions) != len(receipt_rows):
        raise ValueError('receipt scope mismatch')
    result = {'schema': 'roatan-2026-cwt-men-private-census/v1', 'issue_namespace': '#8',
              'corrected_packet_sha256': digest(corrected_bytes), 'corrected_packet_path': str(corrected_path.resolve()),
              'owner_receipt_sha256': digest(receipt_bytes), 'owner_receipt_path': str(receipt_path.resolve()),
              'post_accept_dump_sha256': (digest((stage / 'post-accept-3551.dump').read_bytes())
                                          if not args.review_rows else None),
              'source_objects': source_objects, 'acquisitions': acquisitions,
              'artifact_objects': artifacts, 'review_event_objects': review_event_objects,
              'acceptance_objects': acceptance_objects,
              'database_extraction_reviews': sorted(review_rows, key=lambda x: (x['job_id'], x['ordinal'])),
              'positions': positions, 'observation_versions': versions, 'version_diffs': version_diffs,
              'historical_extraction_decisions': [
                  {'unit': 3551, 'json_index_zero_based': x['json_index_zero_based'],
                   'job_id': receipt['version']['job_id'], 'candidate_id': x['candidate_id'],
                   'event_id': x['event_id'], 'scope': 'extraction accuracy only'}
                  for x in receipt['rows']],
              'counts': {'source_objects': len(source_objects), 'source_positions': len(positions),
                         'observation_versions': len(versions), 'historical_extraction_acceptances': len(receipt_rows),
                         'database_extraction_review_rows': len(review_rows),
                         'confirmed_distinct_attempts': None},
              'confirmed_distinct_attempts': None,
              'uncertainties': ['cross-source overlap unassessed', 'event finality unassessed',
                                'no owner attestation beyond seven historical v2 extraction decisions',
                                'unit 3559 remains unreviewed', 'identity and publication unreviewed']}
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(canon(result) + '\n', encoding='utf-8')
    print(canon({'output': str(out), 'sha256': digest(out.read_bytes()), 'counts': result['counts']}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['build'])
    parser.add_argument('--corrected-packet', required=True)
    parser.add_argument('--stage', required=True)
    parser.add_argument('--legacy-observations')
    parser.add_argument('--owner-receipt', required=True)
    parser.add_argument('--review-rows', help='JSON fixture for testing; default reads isolated dump')
    parser.add_argument('--output', required=True)
    build(parser.parse_args())


if __name__ == '__main__':
    main()
