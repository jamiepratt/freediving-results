#!/usr/bin/env python3
"""Read-only queries for a private unified evidence snapshot."""
import json
import hashlib
from datetime import date
import sqlite3
from pathlib import Path


QUEUE_GROUPS = (
    'extraction_source_semantics', 'source_revision_same_result',
    'same_attempt_relationship', 'athlete_identity', 'coverage_finality',
    'event_publication',
)


def _page(limit, offset):
    if type(limit) is not int or not 1 <= limit <= 100:
        raise ValueError('limit must be an integer from 1 to 100')
    if type(offset) is not int or not 0 <= offset <= 100000:
        raise ValueError('offset must be an integer from 0 to 100000')


def _record_id(record_id):
    if not isinstance(record_id, str) or len(record_id) != 64 or any(c not in '0123456789abcdef' for c in record_id):
        raise ValueError('record_id must be a lowercase SHA256 hex string')


def _comparison_side(detail):
    raw = detail['raw']
    if detail['collection'] == 'candidate_versions':
        candidate = raw.get('candidate') or {}
        raw_fields = (candidate.get('raw') or {}).get('fields') or {}
        parsed_fields = candidate.get('parsed') or {}
        versions = raw.get('imported_observation_refs') or []
        citation = raw.get('citation') or candidate.get('coordinates')
        artifact_sha256 = raw.get('artifact_sha256')
    else:
        raw_fields = detail['raw_fields']
        parsed_fields = detail['parsed_fields']
        versions = raw.get('observation_refs') or []
        citation = detail['citation']
        artifact_sha256 = None
    return {key: detail[key] for key in (
        'record_id', 'source_name', 'collection', 'record_path', 'source_id',
        'source_object_id', 'acquisition_id', 'parser_version', 'observation_version',
        'event_name', 'event_date', 'session', 'discipline', 'category', 'review_status',
        'input_sha256', 'source_sha256', 'snapshot_sha256')} | {
        'citation': citation, 'raw_fields': raw_fields, 'parsed_fields': parsed_fields,
        'artifact_sha256': artifact_sha256, 'observation_refs': versions,
    }


def _queue_group(row, raw):
    if row['kind'] == 'relationship':
        relationship = raw.get('relationship_type')
        if relationship in ('source_revision', 'publisher_revision', 'same_result'):
            return 'source_revision_same_result'
        if relationship == 'same_attempt':
            return 'same_attempt_relationship'
        return None
    if row['collection'] == 'unparsed_rows':
        return 'extraction_source_semantics'
    if row['source_name'] == 'ffessm-correspondences' and row['collection'] == 'unmatched_daily':
        return 'same_attempt_relationship'
    if row['collection'] == 'source_relationship_gaps':
        return 'event_publication'
    if row['collection'] == 'retained_only_artifacts':
        return 'extraction_source_semantics'
    disposition = (raw.get('assessment') or {}).get('disposition')
    if disposition in ('duplicate_rendering', 'supporting_overlap', 'matching_timing_view'):
        return 'source_revision_same_result'
    if disposition in ('unsupported_image_results', 'supplemental_aggregate', 'supplemental_combined_ranking'):
        return 'extraction_source_semantics'
    return 'coverage_finality'


def _queue_item(row, input_data):
    raw = json.loads(row['raw_json'])
    assessment = raw.get('assessment') or {}
    group = _queue_group(row, raw)
    trigger = (assessment.get('failure_reason') or raw.get('reason') or raw.get('value')
               or assessment.get('disposition') or raw.get('relationship_type') or 'Explicit evidence record')
    supporting = [str(value) for value in (assessment.get('evidence'), raw.get('basis')) if value]
    contrary = [str(value) for value in (assessment.get('contrary_evidence'), raw.get('contrary_evidence')) if value]
    unknown = []
    if not supporting:
        unknown.append('Supporting evidence not recorded in this item')
    if not contrary:
        unknown.append('Contrary evidence not recorded in this item')
    if group in ('source_revision_same_result', 'event_publication'):
        unknown.append('Relationship or publication approval not recorded')
    return {
        'id': 'queue:' + row['record_id'], 'group': group, 'trigger': str(trigger),
        'supporting_evidence': supporting, 'contrary_evidence': contrary, 'unknown': unknown,
        'citation': {'record_id': row['record_id'], 'source_name': row['source_name'],
                     'collection': row['collection'], 'record_path': row['record_path'],
                     'source_id': row['source_id'], 'source_object_id': row['source_object_id'],
                     'page': row['page'], 'locator': json.loads(row['citation_json'])},
        'context': {'kind': row['kind'], 'review_status': row['review_status'],
                    'parser_version': row['parser_version'], 'observation_version': row['observation_version'],
                    'input_sha256': input_data['sha256'], 'source_sha256': input_data.get('source_sha256')},
    }


class SnapshotQuery:
    def __init__(self, directory):
        directory = Path(directory)
        self.manifest = json.loads((directory / 'manifest.json').read_text(encoding='utf-8'))
        if self.manifest.get('schema') != 'unified-evidence-snapshot/v1':
            raise ValueError('unsupported snapshot schema')
        path = (directory / 'snapshot.sqlite').resolve()
        digest = hashlib.sha256()
        with path.open('rb') as file:
            for block in iter(lambda: file.read(1024 * 1024), b''):
                digest.update(block)
        if digest.hexdigest() != self.manifest.get('snapshot_sha256'):
            raise ValueError('snapshot hash mismatch')
        self.db = sqlite3.connect(path.as_uri() + '?mode=ro&immutable=1', uri=True)
        self.db.row_factory = sqlite3.Row
        self.has_dive_fields = self.db.execute(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='dive_field_decisions'").fetchone() is not None

    def _dive_fields(self, record_id):
        if not self.has_dive_fields:
            return None
        row = self.db.execute('SELECT projection_json FROM dive_field_decisions WHERE record_id=?',
                              (record_id,)).fetchone()
        return json.loads(row[0]) if row else None

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    def close(self):
        self.db.close()

    def overview(self):
        counts = [dict(row) for row in self.db.execute(
            'SELECT source_name, collection, kind, count(*) AS records FROM records '
            'GROUP BY source_name, collection, kind ORDER BY source_name, collection, kind')]
        return {
            'schema': self.manifest['schema'], 'cutoff': self.manifest['cutoff'],
            'coverage': self.manifest['coverage'],
            'confirmed_distinct_attempts': self.manifest['confirmed_distinct_attempts'],
            'snapshot_sha256': self.manifest['snapshot_sha256'],
            'counts': counts,
        }

    def queue(self, *, group=None, source_name=None, limit=50, offset=0):
        if group is not None and group not in QUEUE_GROUPS:
            raise ValueError('invalid queue group')
        if source_name is not None and (not isinstance(source_name, str) or not source_name or len(source_name) > 200 or '\x00' in source_name):
            raise ValueError('invalid source_name')
        if type(limit) is not int or not 1 <= limit <= 100:
            raise ValueError('limit must be an integer from 1 to 100')
        if type(offset) is not int or not 0 <= offset <= 100000:
            raise ValueError('offset must be an integer from 0 to 100000')
        rows = self.db.execute("SELECT * FROM records WHERE kind IN ('gap','relationship') ORDER BY source_name, collection, record_path, record_id")
        items = []
        for row in rows:
            item = _queue_item(row, self.manifest['inputs'][row['source_name']])
            if item['group'] is not None:
                if row['source_name'] == 'ffessm-correspondences' and row['collection'] == 'unmatched_daily':
                    item['trigger'] = 'Daily position has no matched ranking row; same attempt unknown'
                    item['unknown'] = ['Same attempt with a ranking row is unassessed',
                                       'No owner resolution recorded in this snapshot']
                items.append(item)
        aggregate_groups = {
            ('apnea-file-reconciliation', 'gia_team.rows'): 'club ranking aggregates',
            ('apnea-file-reconciliation', 'san_mauro.rows'): 'team ranking aggregates',
            ('san-mauro-jpg', 'positions'): 'combined ranking aggregates',
        }
        for (aggregate_source, collection), label in aggregate_groups.items():
            rows = self.db.execute(
                "SELECT * FROM records WHERE source_name=? AND collection=? AND kind='aggregate' "
                'ORDER BY record_path, record_id', (aggregate_source, collection)).fetchall()
            if not rows:
                continue
            item = _queue_item(rows[0], self.manifest['inputs'][aggregate_source])
            item['group'] = 'extraction_source_semantics'
            item['trigger'] = f'{len(rows)} {label}; not individual attempts'
            item['supporting_evidence'] = ['Snapshot classifies these source rows as aggregates']
            item['unknown'] = ['Attempt-level equivalence is not recorded for these aggregate rows']
            items.append(item)
        aggregate_parents = set()
        for row in self.db.execute("SELECT * FROM records WHERE kind='aggregate' AND collection='sheets.rows' ORDER BY source_name, record_path"):
            raw = json.loads(row['raw_json'])
            if row['parent_path'] in aggregate_parents or not any('not individual attempts' in str(note).lower() for note in raw.get('ambiguities', [])):
                continue
            aggregate_parents.add(row['parent_path'])
            item = _queue_item(row, self.manifest['inputs'][row['source_name']])
            item['group'] = 'extraction_source_semantics'
            item['trigger'] = '; '.join(map(str, raw['ambiguities']))
            item['supporting_evidence'] = ['Aggregate row ambiguity recorded in retained packet']
            item['unknown'] = ['Attempt-level equivalence is not recorded', 'Contrary evidence not recorded in this item']
            items.append(item)
        for row in self.db.execute("SELECT * FROM records WHERE kind='candidate_position' ORDER BY source_name, collection, record_path, record_id"):
            raw = json.loads(row['raw_json'])
            notes = raw.get('uncertainties') or raw.get('ambiguities') or []
            if not isinstance(notes, list) or not notes:
                continue
            item = _queue_item(row, self.manifest['inputs'][row['source_name']])
            item['group'] = 'athlete_identity' if any('surname' in str(note).lower() or 'athlete identity' in str(note).lower() for note in notes) else 'extraction_source_semantics'
            item['trigger'] = '; '.join(map(str, notes))
            item['supporting_evidence'] = ['Uncertainty explicitly recorded on cited candidate row']
            item['unknown'] = ['No owner resolution recorded in this snapshot', 'Contrary evidence not recorded in this item']
            items.append(item)
        for row in self.db.execute("SELECT * FROM records WHERE source_name='roatan-issue8' AND collection='uncertainties' ORDER BY record_path"):
            item = _queue_item(row, self.manifest['inputs'][row['source_name']])
            value = json.loads(row['raw_json']).get('value', '').lower()
            item['group'] = ('same_attempt_relationship' if 'overlap' in value else
                             'athlete_identity' if 'identity' in value else
                             'event_publication' if 'publication' in value else
                             'coverage_finality')
            item['unknown'] = [item['trigger'], 'No owner resolution recorded in this snapshot']
            items.append(item)
        for name, data in sorted(self.manifest['inputs'].items()):
            if data['status'] != 'excluded':
                continue
            items.append({
                'id': 'queue:source:' + hashlib.sha256(name.encode()).hexdigest(),
                'group': 'coverage_finality', 'trigger': 'Source excluded from this partial snapshot: ' + data.get('reason', 'reason unknown'),
                'supporting_evidence': [], 'contrary_evidence': [],
                'unknown': ['Source records absent from this snapshot', 'Supporting and contrary evidence not recorded in the snapshot'],
                'citation': {'source_name': name, 'record_id': None, 'collection': None, 'record_path': None,
                             'source_id': None, 'source_object_id': None, 'page': None, 'locator': None},
                'context': {'kind': 'excluded_source', 'review_status': None, 'parser_version': None,
                            'observation_version': None, 'input_sha256': data['sha256'],
                            'source_sha256': data.get('source_sha256')},
            })
        items.sort(key=lambda item: (item['group'], item['citation']['source_name'], item['citation']['record_path'] or '', item['id']))
        counts = {name: sum(item['group'] == name for item in items) for name in QUEUE_GROUPS}
        if group:
            items = [item for item in items if item['group'] == group]
        if source_name:
            items = [item for item in items if item['citation']['source_name'] == source_name]
        total = len(items)
        return {'coverage': self.manifest['coverage'], 'cutoff': self.manifest['cutoff'],
                'snapshot_sha256': self.manifest['snapshot_sha256'],
                'denominators': {'candidate_positions': self.db.execute("SELECT count(*) FROM records WHERE kind='candidate_position' AND collection!='observation_versions'").fetchone()[0],
                                 'confirmed_distinct_attempts': self.manifest['confirmed_distinct_attempts']},
                'group_counts': counts, 'total': total, 'limit': limit, 'offset': offset,
                'items': items[offset:offset + limit]}

    def browse(self, *, source_name=None, collection=None, kind=None, event_name=None,
               date_from=None, date_to=None, session=None, discipline=None,
               category=None, limit=50, offset=0):
        if type(limit) is not int or not 1 <= limit <= 100:
            raise ValueError('limit must be an integer from 1 to 100')
        if type(offset) is not int or not 0 <= offset <= 100000:
            raise ValueError('offset must be an integer from 0 to 100000')
        filters = {'source_name': source_name, 'collection': collection, 'kind': kind,
                   'event_name': event_name, 'session': session,
                   'discipline': discipline, 'category': category}
        clauses, args = [], []
        for column, value in filters.items():
            if value is not None:
                if not isinstance(value, str) or not value or len(value) > 200 or '\x00' in value:
                    raise ValueError(f'{column} must be a nonempty string of at most 200 characters')
                clauses.append(f'{column} = ?')
                args.append(value)
        for label, value in (('date_from', date_from), ('date_to', date_to)):
            if value is not None:
                if not isinstance(value, str) or len(value) != 10 or date.fromisoformat(value).isoformat() != value:
                    raise ValueError(f'{label} must be YYYY-MM-DD')
        if date_from and date_to and date_from > date_to:
            raise ValueError('date_from must not exceed date_to')
        if date_from:
            clauses.append('COALESCE(date_to, event_date) >= ?')
            args.append(date_from)
        if date_to:
            clauses.append('COALESCE(date_from, event_date) <= ?')
            args.append(date_to)
        where = (' WHERE ' + ' AND '.join(clauses)) if clauses else ''
        total = self.db.execute('SELECT count(*) FROM records' + where, args).fetchone()[0]
        rows = self.db.execute(
            'SELECT record_id, source_name, collection, record_path, parent_path, kind, '
            'event_name, event_date, date_from, date_to, session, discipline, category, '
            'page, review_status FROM records' + where +
            ' ORDER BY source_name, collection, record_path, record_id LIMIT ? OFFSET ?',
            [*args, limit, offset])
        records = [dict(row) for row in rows]
        for record in records:
            record['dive_fields'] = self._dive_fields(record['record_id'])
        return {'total': total, 'limit': limit, 'offset': offset, 'records': records}

    def detail(self, record_id):
        _record_id(record_id)
        row = self.db.execute('SELECT * FROM records WHERE record_id = ?', (record_id,)).fetchone()
        if row is None:
            return None
        result = dict(row)
        for column, key in (('citation_json', 'citation'), ('date_scope_json', 'date_scope'),
                            ('raw_fields_json', 'raw_fields'), ('parsed_fields_json', 'parsed_fields'),
                            ('raw_json', 'raw')):
            result[key] = json.loads(result.pop(column))
        source = self.manifest['inputs'][result['source_name']]
        result['input_sha256'] = source['sha256']
        result['source_sha256'] = source.get('source_sha256')
        result['source_schema'] = source.get('source_schema')
        result['snapshot_sha256'] = self.manifest['snapshot_sha256']
        result['dive_fields'] = self._dive_fields(record_id)
        return result

    def _roatan_rows(self, collection):
        return [self.detail(row['record_id']) for row in self.db.execute(
            'SELECT record_id FROM records WHERE source_name=? AND collection=? ORDER BY record_path',
            ('roatan-issue8', collection))]

    def roatan_positions(self):
        """List current positions without counting historical versions as new attempts."""
        positions = self._roatan_rows('positions')
        versions = self._roatan_rows('observation_versions')
        decisions = self._roatan_rows('historical_extraction_decisions')
        items = [{'unit': row['raw']['unit'], 'index': row['raw']['json_index_zero_based'],
                  'name': row['raw']['name'], 'record_id': row['record_id'],
                  'parser_version': row['parser_version'], 'source_sha256': row['raw']['source_sha256'],
                  'citation': row['citation'], 'review_status': row['review_status']}
                 for row in positions]
        sources = [{'unit': row['raw'].get('unit'), 'source_object_id': row['source_object_id'],
                    'source_sha256': row['raw'].get('sha256')}
                   for row in self._roatan_rows('source_objects')]
        return {'total': len(items), 'items': items,
                'v1_observations': sum(row['parser_version'] == 'cmas-2026-roatan-json/1' for row in versions),
                'v2_observations': sum(row['parser_version'] == 'cmas-2026-roatan-json/2' for row in versions),
                'source_objects': len(sources), 'source_object_details': sources,
                'historical_extraction_acceptances': len(decisions),
                'confirmed_distinct_attempts': self.manifest['confirmed_distinct_attempts'],
                'snapshot_sha256': self.manifest['snapshot_sha256']}

    def roatan_position(self, unit, index):
        if type(unit) is not int or not 1 <= unit <= 999999 or type(index) is not int or not 0 <= index <= 999:
            raise ValueError('invalid Roatan row locator')
        matches = lambda rows: [row for row in rows if row['raw'].get('unit') == unit
                                and row['raw'].get('json_index_zero_based') == index]
        positions = matches(self._roatan_rows('positions'))
        if len(positions) != 1:
            return None
        position = positions[0]
        versions = matches(self._roatan_rows('observation_versions'))
        by_version = {}
        for row in versions:
            version = row['parser_version'].rsplit('/', 1)[-1]
            if version in ('1', '2') and 'v' + version not in by_version:
                by_version['v' + version] = {
                    'record_id': row['record_id'], 'parser_version': row['parser_version'],
                    'observation_version': row['observation_version'], 'review_status': row['review_status'],
                    'source_sha256': row['raw'].get('source_sha256'), 'source_object_id': row['source_object_id'],
                    'citation': row['citation'], 'raw_fields': row['raw_fields'],
                    'parsed_fields': row['parsed_fields']}
        diff = matches(self._roatan_rows('version_diffs'))
        decisions = matches(self._roatan_rows('historical_extraction_decisions'))
        raw = position['raw']
        return {'unit': unit, 'index': index, 'position_record_id': position['record_id'],
                'position_review_status': position['review_status'],
                'name': raw.get('name'), 'declared_depth': (raw.get('depths') or {}).get('declared'),
                'raw_depth': (raw.get('depths') or {}).get('raw'),
                'final_depth': (raw.get('depths') or {}).get('publisher_final'),
                'penalty': raw.get('penalty'), 'status': raw.get('status'),
                'notes': raw.get('notes') or raw.get('source_notes'),
                'citation': position['citation'], 'source_sha256': raw.get('source_sha256'),
                'source_object_id': position['source_object_id'], 'versions': by_version,
                'parsed_field_changes': diff[0]['raw'].get('parsed_field_changes') if len(diff) == 1 else None,
                'historical_extraction': decisions[0]['raw'] if len(decisions) == 1 else None,
                'same_attempt': 'unknown', 'athlete_identity': 'unknown', 'overlap': 'unknown',
                'snapshot_sha256': self.manifest['snapshot_sha256']}

    def comparisons(self, *, limit=50, offset=0):
        """List only candidate relationships explicitly retained in the snapshot."""
        _page(limit, offset)
        rows = self.db.execute(
            "SELECT record_id, source_name, collection, record_path, raw_json FROM records "
            "WHERE kind='relationship' OR (source_name='retained' AND collection='candidate_versions') "
            "ORDER BY source_name, collection, record_path, record_id").fetchall()
        items = []
        for row in rows:
            raw = json.loads(row['raw_json'])
            if row['collection'] == 'candidate_versions':
                if not raw.get('imported_position_id'):
                    continue
                label = 'Retained artifact versus imported position'
                status = 'candidate_link'
            else:
                label = str(raw.get('relationship_type') or raw.get('kind') or 'Explicit relationship candidate')
                status = str(raw.get('status') or raw.get('state') or 'unknown')
            items.append({'id': row['record_id'], 'source_name': row['source_name'],
                          'collection': row['collection'], 'record_path': row['record_path'],
                          'label': label, 'status': status})
        return {'total': len(items), 'limit': limit, 'offset': offset,
                'snapshot_sha256': self.manifest['snapshot_sha256'],
                'confirmed_distinct_attempts': self.manifest['confirmed_distinct_attempts'],
                'items': items[offset:offset + limit]}

    def _comparison_provenance(self, side):
        source_id = side['source_object_id']
        receipt = None
        if source_id:
            rows = self.db.execute(
                "SELECT raw_json FROM records WHERE source_name=? AND kind='source' "
                "AND (source_id=? OR source_object_id=?)",
                (side['source_name'], source_id, source_id)).fetchall()
            for row in rows:
                raw = json.loads(row['raw_json'])
                if side['source_name'] == 'retained':
                    if raw.get('artifact_sha256') != side['artifact_sha256']:
                        continue
                    receipts = raw.get('acquisition_receipts') or []
                    receipt = (receipts[0].get('manifest') if receipts else None) or {}
                else:
                    acquisitions = raw.get('acquisitions') or []
                    receipt = acquisitions[0] if acquisitions else raw
                break
        side['source_receipt'] = {
            'acquisition_id': (receipt or {}).get('acquisition_id') or (receipt or {}).get('acquisition-id'),
            'retrieved_at': (receipt or {}).get('retrieved_at') or (receipt or {}).get('retrieved-at'),
            'publisher': (receipt or {}).get('publisher'),
            'final_url': (receipt or {}).get('final_url') or (receipt or {}).get('final-url'),
            'acquisition_method': (receipt or {}).get('acquisition-method'),
        }
        side['source_sha256'] = source_id.removeprefix('sha256:') if source_id and source_id.startswith('sha256:') else side['source_sha256']
        return side

    def _cited_ffessm_position(self, citation, source_name, collection):
        if not isinstance(citation, dict) or not citation.get('id') or not citation.get('source_id'):
            return None
        rows = self.db.execute(
            'SELECT record_id FROM records WHERE source_name=? AND collection=? '
            'AND kind=? AND source_id=? AND source_object_id=?',
            (source_name, collection, 'candidate_position', citation['id'], citation['source_id'])
        ).fetchmany(2)
        return self.detail(rows[0]['record_id']) if len(rows) == 1 else None

    def comparison(self, record_id):
        """Compare two cited rows only where one explicit snapshot link names both."""
        _record_id(record_id)
        record = self.detail(record_id)
        if record is None:
            return None
        raw = record['raw']
        if record['kind'] == 'relationship':
            relationship = {'type': raw.get('relationship_type') or raw.get('kind'),
                            'status': raw.get('status') or raw.get('state') or 'unknown',
                            'basis': raw.get('basis'), 'supporting_evidence': raw.get('evidence'),
                            'contrary_evidence': raw.get('contrary_evidence'),
                            'unknown': raw.get('cross_source_equivalence') or 'unassessed',
                            'same_attempt': raw.get('same_attempt'),
                            'matched_daily_date': raw.get('matched_daily_date'),
                            'ranking_row_date': raw.get('ranking_row_date')}
            sides = []
            unavailable = 'No pair of cited row records is identified by this snapshot relationship'
            if record['source_name'] == 'ffessm-correspondences' and raw.get('kind') == 'shared_printed_fields':
                daily = self._cited_ffessm_position(raw.get('daily'), 'ffessm-daily', 'observations')
                ranking = self._cited_ffessm_position(raw.get('ranking'), 'ffessm-rankings', 'positions')
                if daily is not None and ranking is not None:
                    sides = [self._comparison_provenance(_comparison_side(row)) for row in (daily, ranking)]
                    unavailable = None
                else:
                    unavailable = 'Cited daily or ranking row is absent or ambiguous in this snapshot'
        elif record['source_name'] == 'retained' and record['collection'] == 'candidate_versions':
            imported_id = raw.get('imported_position_id')
            if not isinstance(imported_id, str) or not imported_id:
                return None
            imported = self.db.execute(
                "SELECT record_id FROM records WHERE source_name='baseline' AND collection='positions' AND source_id=?",
                (imported_id,)).fetchmany(2)
            sides = [self._comparison_provenance(_comparison_side(record))]
            if len(imported) == 1:
                sides.append(self._comparison_provenance(_comparison_side(self.detail(imported[0]['record_id']))))
            relationship = {'type': 'retained_artifact_imported_position', 'status': 'candidate_link',
                            'basis': raw.get('match_basis'),
                            'supporting_evidence': raw.get('source_lines_equal'),
                            'contrary_evidence': None,
                            'unknown': 'Attempt equivalence and owner decision unassessed'}
            unavailable = (None if len(imported) == 1 else
                           'Linked imported position absent from this snapshot' if not imported else
                           'Linked imported position ID is duplicated in this snapshot; comparison unavailable')
        else:
            return None
        differences = {}
        raw_differences = {}
        if len(sides) == 2 and not (record['source_name'] == 'ffessm-correspondences'
                                    and raw.get('kind') == 'shared_printed_fields'):
            left, right = sides
            for key in sorted(set(left['parsed_fields']) | set(right['parsed_fields'])):
                pair = [left['parsed_fields'].get(key), right['parsed_fields'].get(key)]
                if pair[0] != pair[1]:
                    differences[key] = pair
            for key in sorted(set(left['raw_fields']) | set(right['raw_fields'])):
                pair = [left['raw_fields'].get(key), right['raw_fields'].get(key)]
                if pair[0] != pair[1]:
                    raw_differences[key] = pair
        return {'id': record_id, 'relationship': relationship, 'sides': sides,
                'field_correspondences': raw.get('field_correspondences') or {},
                'field_differences': differences, 'raw_field_differences': raw_differences,
                'unavailable': unavailable,
                'snapshot_sha256': self.manifest['snapshot_sha256'],
                'confirmed_distinct_attempts': self.manifest['confirmed_distinct_attempts']}

    def gaps(self, **filters):
        if 'kind' in filters:
            raise ValueError('kind is fixed for gaps')
        return self.browse(kind='gap', **filters)

    def relationships(self, **filters):
        if 'kind' in filters:
            raise ValueError('kind is fixed for relationships')
        return self.browse(kind='relationship', **filters)

    def sources(self):
        return [{'source_name': name, **data} for name, data in sorted(self.manifest['inputs'].items())]

    def source(self, source_name):
        if not isinstance(source_name, str) or not source_name or len(source_name) > 200 or '\x00' in source_name:
            raise ValueError('source_name must be a nonempty string of at most 200 characters')
        data = self.manifest['inputs'].get(source_name)
        if data is None:
            return None
        result = {'source_name': source_name, **data}
        if data['status'] == 'included':
            row = self.db.execute('SELECT metadata_json FROM source_metadata WHERE source_name = ?', (source_name,)).fetchone()
            result['metadata'] = json.loads(row[0]) if row else None
        return result


def main(argv=None):
    import argparse

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--snapshot-dir', required=True)
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('overview')
    commands.add_parser('sources')
    for name in ('browse', 'gaps', 'relationships'):
        command = commands.add_parser(name)
        for field in ('source_name', 'collection', 'event_name', 'date_from', 'date_to',
                      'session', 'discipline', 'category'):
            command.add_argument('--' + field.replace('_', '-'))
        if name == 'browse':
            command.add_argument('--kind')
        command.add_argument('--limit', type=int, default=50)
        command.add_argument('--offset', type=int, default=0)
    commands.add_parser('detail').add_argument('record_id')
    commands.add_parser('source').add_argument('source_name')
    args = parser.parse_args(argv)
    try:
        with SnapshotQuery(args.snapshot_dir) as query:
            if args.command in ('browse', 'gaps', 'relationships'):
                options = {k: v for k, v in vars(args).items()
                           if k not in ('snapshot_dir', 'command') and v is not None}
                result = getattr(query, args.command)(**options)
            elif args.command == 'detail':
                result = query.detail(args.record_id)
            elif args.command == 'source':
                result = query.source(args.source_name)
            else:
                result = getattr(query, args.command)()
    except (ValueError, OSError, sqlite3.Error, KeyError) as exc:
        parser.error(str(exc))
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))


if __name__ == '__main__':
    main()
