#!/usr/bin/env python3
"""Bind retained AIDA HTML positions to source-derived, unapproved observations.

This is a private read-only adapter. A source row is not a PostgreSQL import,
an approved person, or a confirmed sporting attempt.
"""

import hashlib
import html
import json
import re
from pathlib import Path
from urllib.parse import urlsplit

try:
    from scripts.issue55_aida_selected_html import build
    from scripts.unified_evidence_query import SnapshotQuery
except ModuleNotFoundError:
    from issue55_aida_selected_html import build
    from unified_evidence_query import SnapshotQuery


ADAPTER_VERSION = 'aida-snapshot-observation/2'
LEGACY_ADAPTER_VERSION = 'aida-snapshot-observation/1'
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


def _verified_packet(input_data, recovered_path=None):
    path = Path(recovered_path) if recovered_path is not None else Path(input_data['path'])
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
    return packet, _sha(packet_bytes), source_path


def _event_context(source_path, source):
    """Read only page-specific event labels from the hash-verified original."""
    source_bytes = source_path.read_bytes()
    _require(_sha(source_bytes) == source['sha256'], 'AIDA original changed after packet replay')
    page = re.sub(r'<!--.*?-->', '', source_bytes.decode('utf-8'), flags=re.S)
    path = urlsplit(source['url']).path
    if re.fullmatch(r'/EventPage/[0-9]+', path):
        pattern = r'<div\b[^>]*class=["\'][^"\']*\bevent-title--description\b[^"\']*["\'][^>]*>(.*?)</div\s*>'
        locator = 'div.event-title--description'
    elif re.fullmatch(r'/Events/EventResults-[0-9]+', path):
        pattern = (r'<h2\b[^>]*>\s*Event Results\s*</h2\s*>\s*'
                   r'<p\b[^>]*class=["\'][^"\']*\bu-type--medium\b[^"\']*["\'][^>]*>(.*?)</p\s*>')
        locator = 'h2[Event Results] + p.u-type--medium'
    else:
        raise ValueError('unsupported AIDA event URL')
    values = [' '.join(html.unescape(re.sub(r'<[^>]*>', '', match)).split())
              for match in re.findall(pattern, page, re.I | re.S)]
    _require(len(values) <= 1 and all(values), 'ambiguous AIDA event heading')
    if not values:
        return None
    return {'source_sha256': source['sha256'], 'locator': locator, 'value': values[0]}


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


def load_source_observations(snapshot_dir, source_names, *, adapter_version=ADAPTER_VERSION,
                             recovered_packet_paths=None):
    """Return exact supported positions and gaps for named AIDA snapshot views.

    Original HTML, browser receipt, packet and snapshot hashes are verified.
    Missing retained packets create explicit per-position gaps. An exact copy
    at a new path may recover a missing packet; its frozen hash still applies.
    Any conflicting available evidence fails the whole operation.
    """
    _require(isinstance(source_names, (list, tuple)) and source_names
             and len(source_names) == len(set(source_names)), 'unique source names required')
    _require(adapter_version in (ADAPTER_VERSION, LEGACY_ADAPTER_VERSION),
             'unsupported AIDA observation adapter version')
    recovered_packet_paths = ({} if recovered_packet_paths is None
                              else recovered_packet_paths)
    _require(isinstance(recovered_packet_paths, dict)
             and set(recovered_packet_paths) <= set(source_names),
             'unknown AIDA recovered packet source')
    _require(all(isinstance(path, (str, Path)) for path in recovered_packet_paths.values()),
             'invalid AIDA recovered packet path')
    recovered_paths = {name: Path(path)
                       for name, path in recovered_packet_paths.items()}
    _require(len({path.resolve() for path in recovered_paths.values()}) == len(recovered_paths),
             'duplicate AIDA recovered packet path')
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
            recovered_path = recovered_paths.get(name)
            if recovered_path is not None:
                _require(not Path(item['path']).is_file(),
                         'AIDA manifest packet already present')
                _require(recovered_path.is_file(), 'AIDA recovered packet missing')
            verified = _verified_packet(item, recovered_path)
            if verified is None:
                gaps.extend({'snapshot_record_id': row['record_id'],
                             'source_name': name, 'citation': json.loads(row['citation_json']),
                             'event_date': row['event_date'],
                             'source_object_id': row['source_object_id'],
                             'reason': 'retained-packet-missing'}
                            for row in rows)
                continue
            packet, packet_sha, source_path = verified
            _require(len(packet['positions']) == len(rows),
                     'AIDA packet and snapshot count mismatch')
            source = packet['source']
            event_context = (_event_context(source_path, source)
                             if adapter_version == ADAPTER_VERSION else None)
            packet_rows = {f'positions[{index}]': value
                           for index, value in enumerate(packet['positions'])}
            _require(len(packet_rows) == len(rows), 'AIDA duplicate position')
            citations = [_canonical(value.get('position')) for value in packet['positions']]
            _require(len(citations) == len(set(citations)), 'AIDA duplicate citation')
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
                version_input = [adapter_version, source['sha256'], packet_sha,
                                 row['record_id'], position]
                if adapter_version == ADAPTER_VERSION:
                    version_input.append(event_context)
                version = _sha(_canonical(version_input).encode())
                reference = {
                    'kind': 'source-derived',
                    'snapshot_sha256': snapshot.manifest['snapshot_sha256'],
                    'snapshot_record_id': row['record_id'], 'source_name': name,
                    'source_sha256': source['sha256'], 'packet_sha256': packet_sha,
                    'citation': citation, 'adapter_version': adapter_version,
                    'observation_version': version}
                if adapter_version == ADAPTER_VERSION:
                    reference['event_context'] = event_context
                observations.append({
                    'snapshot_record_id': row['record_id'], 'source_name': name,
                    'source_object_id': row['source_object_id'],
                    'source_sha256': source['sha256'], 'packet_sha256': packet_sha,
                    'source_url': source['url'], 'citation': citation,
                    'event_date': row['event_date'],
                    'event_name': event_context['value'] if event_context else None,
                    'session': None, 'category': None,
                    'observation_version': version, 'adapter_version': adapter_version,
                    'source_observation_ref': reference,
                    'source_fields': fields, 'review_status': 'unreviewed',
                    'pg_observation_ref': None, 'confirmed_attempt_id': None,
                    'approved_athlete_id': None})
    return {'schema': adapter_version,
            'snapshot_sha256': snapshot.manifest['snapshot_sha256'],
            'observations': observations, 'gaps': gaps,
            'summary': {'supported': len(observations), 'source_gaps': len(gaps),
                        'confirmed_distinct_attempts': None,
                        'approved_athletes': None}}
