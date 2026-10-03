#!/usr/bin/env python3
"""Bind retained AIDA HTML positions to source-derived, unapproved observations.

This is a private read-only adapter. A source row is not a PostgreSQL import,
an approved person, or a confirmed sporting attempt.
"""

import hashlib
import json
from pathlib import Path

try:
    from scripts.issue55_aida_selected_html import build
    from scripts.unified_evidence_query import SnapshotQuery
except ModuleNotFoundError:
    from issue55_aida_selected_html import build
    from unified_evidence_query import SnapshotQuery


ADAPTER_VERSION = 'aida-snapshot-observation/1'
PACKET_SCHEMA = 'aida-selected-html-packet/v1'


def _sha(data):
    return hashlib.sha256(data).hexdigest()


def _canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':'))


def _require(condition, message):
    if not condition:
        raise ValueError(message)


def _receipt_path(packet_path):
    name = packet_path.name
    _require(name == 'packet.json' or name.startswith('packet-') and name.endswith('.json'),
             'unsupported AIDA packet path')
    return packet_path.with_name('receipt.json' if name == 'packet.json'
                                 else 'receipt-' + name[len('packet-'):])


def _verified_packet(input_data):
    path = Path(input_data['path'])
    if not path.is_file():
        return None
    packet_bytes = path.read_bytes()
    _require(_sha(packet_bytes) == input_data.get('sha256'), 'AIDA packet hash mismatch')
    packet = json.loads(packet_bytes)
    _require(packet.get('schema') == PACKET_SCHEMA, 'AIDA packet schema mismatch')
    receipt_path = _receipt_path(path)
    _require(receipt_path.is_file(), 'AIDA receipt missing')
    receipt = json.loads(receipt_path.read_text(encoding='utf-8'))
    source_path = (receipt_path.parent / receipt['body']['path']).resolve()
    _require(source_path.is_file(), 'AIDA original HTML missing')
    replay = build(source_path, receipt_path)
    _require(replay == packet, 'AIDA packet differs from source replay')
    _require(packet['summary']['source_positions'] == len(packet['positions']),
             'AIDA packet position count mismatch')
    return packet, _sha(packet_bytes)


def _source_fields(row):
    cells = row.get('cells')
    _require(isinstance(cells, dict), 'AIDA parsed cells missing')
    def value(label):
        cell = cells.get(label)
        return cell.get('value') if isinstance(cell, dict) else None
    return {'name': value('Diver'), 'representation_raw': value('Nationality'),
            'gender_raw': value('Gender'), 'discipline_raw': value('Discipline'),
            'start_raw': value('Start'), 'announced_raw': value('AP'),
            'realized_raw': value('RP'), 'card_raw': value('Card'),
            'points_raw': value('Points'), 'remarks_raw': value('Remarks'),
            'line_raw': value('Line'), 'official_top_raw': value('Official Top'),
            'ot_raw': value('OT')}


def load_source_observations(snapshot_dir, source_names):
    """Return exact supported positions and gaps for named AIDA snapshot views.

    Original HTML, browser receipt, packet and snapshot hashes are verified.
    Missing retained packets create explicit per-position gaps. Any conflicting
    available evidence fails the whole operation.
    """
    _require(isinstance(source_names, (list, tuple)) and source_names
             and len(source_names) == len(set(source_names)), 'unique source names required')
    observations, gaps = [], []
    with SnapshotQuery(snapshot_dir) as snapshot:
        for name in source_names:
            item = snapshot.manifest['inputs'].get(name)
            _require(item and item.get('source_schema') == PACKET_SCHEMA,
                     'named source is not an AIDA selected HTML packet')
            rows = snapshot.db.execute(
                "SELECT record_id,collection,record_path,kind,raw_json,citation_json,"
                "source_object_id,event_date,parser_version,observation_version,"
                "event_name,session,category FROM records WHERE source_name=? "
                "ORDER BY record_path", (name,)).fetchall()
            rows = [row for row in rows if row['kind'] == 'candidate_position'
                    and row['collection'] == 'positions']
            _require(len(rows) == item['collections']['positions'] and rows,
                     'AIDA snapshot position count mismatch')
            verified = _verified_packet(item)
            if verified is None:
                gaps.extend({'snapshot_record_id': row['record_id'],
                             'source_name': name, 'citation': json.loads(row['citation_json']),
                             'event_date': row['event_date'],
                             'source_object_id': row['source_object_id'],
                             'reason': 'retained-packet-missing'}
                            for row in rows)
                continue
            packet, packet_sha = verified
            _require(len(packet['positions']) == len(rows),
                     'AIDA packet and snapshot count mismatch')
            source = packet['source']
            packet_rows = {f'positions[{index}]': value
                           for index, value in enumerate(packet['positions'])}
            _require(len(packet_rows) == len(rows), 'AIDA duplicate position')
            for row in rows:
                raw = json.loads(row['raw_json'])
                position = packet_rows.get(row['record_path'])
                citation = json.loads(row['citation_json'])
                _require(position == raw and position.get('position') == citation,
                         'AIDA snapshot citation or row differs from source packet')
                _require(row['record_id'] == _sha(f"{name}:{row['record_path']}".encode())
                         and row['source_object_id'] == 'sha256:' + source['sha256']
                         and row['event_date'] == citation['date'] == source['selected_date']
                         and all(row[key] is None for key in
                                 ('parser_version', 'observation_version', 'event_name',
                                  'session', 'category')),
                         'AIDA snapshot source or unsupported field mismatch')
                _require(position.get('disposition') == 'parsed',
                         'AIDA unparsed position cannot form observation')
                fields = _source_fields(position)
                _require(fields['name'] and fields['discipline_raw'],
                         'AIDA source name or discipline missing')
                version = _sha(_canonical([ADAPTER_VERSION, source['sha256'],
                                           packet_sha, row['record_id'], position]).encode())
                observations.append({
                    'snapshot_record_id': row['record_id'], 'source_name': name,
                    'source_object_id': row['source_object_id'],
                    'source_sha256': source['sha256'], 'packet_sha256': packet_sha,
                    'source_url': source['url'], 'citation': citation,
                    'event_date': row['event_date'], 'event_name': None,
                    'session': None, 'category': None,
                    'observation_version': version, 'adapter_version': ADAPTER_VERSION,
                    'source_fields': fields, 'review_status': 'unreviewed',
                    'pg_observation_ref': None, 'confirmed_attempt_id': None,
                    'approved_athlete_id': None})
    return {'schema': ADAPTER_VERSION,
            'snapshot_sha256': snapshot.manifest['snapshot_sha256'],
            'observations': observations, 'gaps': gaps,
            'summary': {'supported': len(observations), 'source_gaps': len(gaps),
                        'confirmed_distinct_attempts': None,
                        'approved_athletes': None}}
