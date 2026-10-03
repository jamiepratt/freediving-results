#!/usr/bin/env python3
"""Bind retained Microplus API positions to unapproved source observations."""

import hashlib
import json
from pathlib import Path

try:
    from scripts.cmas_microplus_ingest import build as build_initial
    from scripts.cmas_microplus_finalize import build as build_final
    from scripts.unified_evidence_query import SnapshotQuery
except ModuleNotFoundError:
    from cmas_microplus_ingest import build as build_initial
    from cmas_microplus_finalize import build as build_final
    from unified_evidence_query import SnapshotQuery


ADAPTER_VERSION = 'cmas-microplus-snapshot-observation/1'
SCHEMAS = ('cmas-microplus-private-census/v1', 'cmas-microplus-private-census/v2')


def _sha(raw):
    return hashlib.sha256(raw).hexdigest()


def _canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':'))


def _require(condition, message):
    if not condition:
        raise ValueError(message)


def _source_row(source_dir, citation, sources):
    _require(isinstance(citation, dict), 'Microplus citation missing')
    url = citation.get('url')
    digest = citation.get('source_sha256')
    pointer = citation.get('json_pointer')
    matches = [s for s in sources if s.get('kind') == 'unit_results'
               and s.get('url') == url and s.get('sha256') == digest]
    _require(len(matches) == 1, 'Microplus citation source absent or ambiguous')
    source = matches[0]
    uid = source['unit_id']
    _require(type(uid) is int and url.endswith(f'/api/units/{uid}/results'),
             'Microplus source unit mismatch')
    path = source_dir / f'unit-{uid}-results.json'
    receipt_path = path.with_suffix('.receipt.json')
    _require(path.is_file() and receipt_path.is_file(), 'Microplus source or receipt missing')
    raw = path.read_bytes()
    receipt = json.loads(receipt_path.read_bytes())
    _require(_sha(raw) == digest == receipt.get('sha256')
             and len(raw) == source.get('bytes') == receipt.get('bytes')
             and receipt.get('status') == 200 and receipt.get('final_url') == url,
             'Microplus source receipt mismatch')
    rows = json.loads(raw)
    _require(isinstance(rows, list) and len(rows) == source.get('row_count'),
             'Microplus source row count mismatch')
    _require(isinstance(pointer, str) and pointer.startswith('/')
             and pointer[1:].isdigit() and str(int(pointer[1:])) == pointer[1:],
             'Microplus citation pointer invalid')
    index = int(pointer[1:])
    _require(index < len(rows), 'Microplus citation pointer missing')
    return rows[index], source


def _replay_v2(packet, packet_path, source_dir):
    initial_path = packet_path.with_name('cmas-microplus-packet.json')
    reconciliation_path = packet_path.with_name('nordic-pdf-reconciliation.json')
    _require(initial_path.is_file(), 'Microplus initial packet missing')
    _require(reconciliation_path.is_file(), 'Microplus reconciliation packet missing')
    initial = json.loads(initial_path.read_bytes())
    _require(build_initial(source_dir) == initial,
             'Microplus initial packet differs from source replay')
    _require(build_final(initial_path, reconciliation_path) == packet,
             'Microplus final packet differs from source replay')


def _fields(raw):
    return {
        'athlete_id': raw.get('ParID'), 'name': raw.get('ParPrintName'),
        'competition_id': raw.get('DCCmpID'), 'result_id': raw.get('ResID'),
        'event_id': raw.get('EvID'), 'phase_id': raw.get('PhID'),
        'unit_id': raw.get('UtID'), 'event_date_raw': raw.get('EvStartDate'),
        'phase_raw': raw.get('PhLongDescr'), 'unit_raw': raw.get('UtLongDescr'),
        'discipline_raw': raw.get('EvShortDescr'),
        'category_raw': raw.get('AGCodeDescr'),
        'declared_length_raw': raw.get('DECLLEN_STR'),
        'declared_time_raw': raw.get('DECLTIME_STR'),
        'bottom_time_raw': raw.get('BOTTIME_STR'),
        'result_raw': raw.get('ResResult'),
        'result_final_raw': raw.get('ResResultFinal'),
        'rank_raw': raw.get('ResRnk'),
        'start_time_raw': raw.get('ResStartTime'),
        'penalty_raw': raw.get('ResPenality'),
        'penalty_note_raw': raw.get('ResNotePenality'),
        'reason_raw': raw.get('ResReasonCode'),
        'status_raw': raw.get('UtStatus'),
    }


def load_source_observations(snapshot_dir, source_names, *, source_dir=None,
                             adapter_version=ADAPTER_VERSION):
    """Return cited API rows only; sporting attempt and identity remain unknown."""
    _require(adapter_version == ADAPTER_VERSION, 'unsupported Microplus adapter version')
    _require(isinstance(source_names, (list, tuple)) and source_names
             and len(source_names) == len(set(source_names)),
             'unique Microplus source names required')
    observations = []
    with SnapshotQuery(snapshot_dir) as snapshot:
        for name in source_names:
            item = snapshot.manifest['inputs'].get(name)
            _require(item and item.get('source_schema') in SCHEMAS,
                     'named source is not a Microplus packet')
            packet_path = Path(item['path'])
            retained_dir = Path(source_dir) if source_dir is not None else packet_path.parent
            _require(packet_path.is_file(), 'Microplus packet missing')
            packet_bytes = packet_path.read_bytes()
            _require(_sha(packet_bytes) == item.get('sha256'), 'Microplus packet hash mismatch')
            packet = json.loads(packet_bytes)
            _require(packet.get('schema') == item['source_schema'],
                     'Microplus packet schema mismatch')
            if packet['schema'] == SCHEMAS[1]:
                _replay_v2(packet, packet_path, retained_dir)
            rows = snapshot.db.execute(
                "SELECT record_id,record_path,kind,collection,source_id,source_object_id,"
                "event_name,event_date,session,discipline,category,parser_version,"
                "observation_version,review_status,raw_json,citation_json FROM records "
                "WHERE source_name=? AND collection='positions' ORDER BY record_path",
                (name,)).fetchall()
            _require(len(rows) == item['collections']['positions'] == len(packet['positions'])
                     and rows, 'Microplus snapshot position count mismatch')
            sources = packet.get('sources')
            _require(isinstance(sources, list), 'Microplus packet sources missing')
            seen = set()
            for row in rows:
                path = row['record_path']
                _require(path.startswith('positions[') and path.endswith(']')
                         and path[10:-1].isdigit(), 'Microplus position path invalid')
                index = int(path[10:-1])
                _require(index < len(packet['positions']) and index not in seen,
                         'Microplus position path duplicated or absent')
                seen.add(index)
                position = packet['positions'][index]
                citation = position.get('citation')
                _require(position == json.loads(row['raw_json'])
                         and citation == json.loads(row['citation_json'])
                         and row['record_id'] == _sha(f'{name}:{path}'.encode())
                         and row['kind'] == 'candidate_position'
                         and row['collection'] == 'positions'
                         and row['source_id'] == position['id']
                         and row['source_object_id'] == position['source_object_id']
                         and row['event_name'] == position['event_name']
                         and row['event_date'] == position['event_date']
                         and row['discipline'] == position['discipline']
                         and row['category'] == position['category']
                         and row['review_status'] == position['review_status']
                         and all(row[key] is None for key in
                                 ('session', 'parser_version', 'observation_version')),
                         'Microplus snapshot row or citation mismatch')
                _require(position['disposition'] == 'api_transport_result_row',
                         'Microplus position is not an API result row')
                raw, source = _source_row(retained_dir, citation, sources)
                _require(raw == position['raw_fields']
                         and source['id'] == position['source_object_id']
                         and raw.get('DCCmpID') == source['competition_id']
                         and raw.get('ResID') is not None
                         and raw.get('EvStartDate', '')[:10] == position['event_date'],
                         'Microplus source row or date mismatch')
                alternates = position.get('alternate_citations') or []
                _require(len(alternates) == len({_canonical(c) for c in alternates}),
                         'Microplus duplicate alternate citation')
                for alternate in alternates:
                    other, other_source = _source_row(retained_dir, alternate, sources)
                    _require(other == raw and other_source['competition_id'] == source['competition_id'],
                             'Microplus alternate citation differs from source row')
                fields = _fields(raw)
                _require(all(fields[key] is not None for key in
                             ('athlete_id', 'competition_id', 'result_id',
                              'event_id', 'phase_id', 'unit_id')),
                         'Microplus source-native identity missing')
                version = _sha(_canonical([adapter_version, snapshot.manifest['snapshot_sha256'],
                                           item['sha256'], row['record_id'], position]).encode())
                reference = {'kind': 'source-derived',
                             'snapshot_sha256': snapshot.manifest['snapshot_sha256'],
                             'snapshot_record_id': row['record_id'],
                             'source_name': name, 'source_sha256': source['sha256'],
                             'packet_sha256': item['sha256'], 'citation': citation,
                             'alternate_citations': alternates,
                             'adapter_version': adapter_version,
                             'observation_version': version}
                observations.append({
                    'snapshot_record_id': row['record_id'], 'source_name': name,
                    'source_object_id': row['source_object_id'],
                    'source_sha256': source['sha256'], 'packet_sha256': item['sha256'],
                    'source_url': source['url'], 'citation': citation,
                    'alternate_citations': alternates, 'event_date': row['event_date'],
                    'event_name': row['event_name'], 'session': None,
                    'category': row['category'], 'discipline': row['discipline'],
                    'source_fields': fields, 'adapter_version': adapter_version,
                    'observation_version': version, 'source_observation_ref': reference,
                    'review_status': 'unreviewed', 'pg_observation_ref': None,
                    'confirmed_attempt_id': None, 'approved_athlete_id': None})
            _require(len(seen) == len(rows), 'Microplus snapshot position gap')
    return {'schema': adapter_version,
            'snapshot_sha256': snapshot.manifest['snapshot_sha256'],
            'observations': observations, 'gaps': [],
            'summary': {'supported': len(observations), 'source_gaps': 0,
                        'confirmed_distinct_attempts': None,
                        'approved_athletes': None}}
