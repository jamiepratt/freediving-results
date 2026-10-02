import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path

import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / 'scripts' / 'unified_evidence_snapshot.py'
sys.path.insert(0, str(ROOT / 'scripts'))
from unified_evidence_query import SnapshotQuery

ROATAN_SNAPSHOT = Path('/Users/jamiep/.codex/private-corpora/roatan-issue55-snapshot-20260928/snapshot')
V8_SNAPSHOT = Path('/Users/jamiep/.codex/private-corpora/issue55-unified-snapshot-20260928-v8/snapshot')


def snapshot(tmp_path):
    packet = tmp_path / 'packet.json'
    packet.write_text(json.dumps({
        'schema': 'visual/v1', 'source': {'id': 'sha256:original', 'acquisition_id': 'capture-1'},
        'event_date_calendar': '2026-05-08', 'event_title_calendar': 'Sample meet',
        'pages': [{'page': 1, 'session': 'morning', 'category_raw': 'Women',
                   'discipline_raw': 'STA', 'rows': [
                       {'id': 'a', 'fields': {'Name': 'Ada'}, 'parsed_fields': {'score': '3:00'},
                        'citation': {'page': 1, 'line': 3}, 'parser_version': 'parser/1'},
                       {'id': 'b', 'fields': {'Name': 'Bea'}, 'citation': {'page': 1, 'line': 4}}]}],
        'source_relationship_candidates': [{'id': 'rel', 'status': 'unresolved', 'relationship_type': 'calendar_event_link'}],
        'source_gaps': [{'id': 'gap', 'status': 'needs review'}],
    }), encoding='utf-8')
    excluded = tmp_path / 'excluded.json'
    excluded.write_text('{"pages":[]}', encoding='utf-8')
    out = tmp_path / 'out'
    subprocess.run([sys.executable, str(SCRIPT), 'build', '--cutoff', '2026-09-28T12:00:00Z',
                    '--input', f'visual={packet}', '--excluded', f'pending={excluded}:unfinished',
                    '--output-dir', str(out)], check=True, capture_output=True)
    return out


class QueryContractTest(unittest.TestCase):
    def test_dive_field_decisions_are_private_version_bound_and_replayable(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            packet = root / 'packet.json'
            packet.write_text(json.dumps({
                'schema': 'synthetic/v1', 'source': {'sha256': 'a' * 64},
                'positions': [{'id': 'pdf:1:2', 'source_sha256': 'a' * 64,
                               'artifact_sha256': 'b' * 64, 'parser_version': 'pdf/1',
                               'category_raw': 'Women + Masters', 'representation_raw': 'FRA',
                               'citation': 'page 1 line 2', 'raw_fields': {'Country': 'FRA'},
                               'observation_refs': [{'job_id': 'job-1', 'ordinal': 0,
                                                     'artifact_sha256': 'b' * 64,
                                                     'parser_version': 'pdf/1'}]}]}))
            decision = {'schema': 'dive-field-decisions/v1', 'positions': [{
                'source_position_id': 'pdf:1:2', 'job_id': 'job-1', 'ordinal': 0,
                'source_sha256': 'a' * 64, 'artifact_sha256': 'b' * 64,
                'parser_version': 'pdf/1', 'raw_category': 'Women + Masters',
                'raw_representation': 'FRA', 'accepted_categories': ['women', 'masters'],
                'accepted_representation': {'kind': 'country', 'code': 'FRA'},
                'category_status': 'automatic', 'representation_status': 'human',
                'decision_revision': 3, 'category_citation': 'page 1 line 2 heading',
                'representation_citation': 'page 1 line 2 Country',
                'category_decision_id': 'decision:category:1',
                'representation_decision_id': 'decision:representation:1'}]}
            decisions = root / 'decisions.json'
            decisions.write_text(json.dumps(decision))
            out = root / 'out'
            args = [sys.executable, str(SCRIPT), 'build', '--cutoff', '2026-10-02T00:00:00Z',
                    '--input', f'pdf={packet}', '--decisions-file', str(decisions),
                    '--output-dir', str(out)]
            subprocess.run(args, check=True, capture_output=True)
            with SnapshotQuery(out) as query:
                row = query.browse(kind='candidate_position')['records'][0]
                detail = query.detail(row['record_id'])
                self.assertEqual(row['dive_fields']['accepted_categories'], ['women', 'masters'])
                self.assertEqual(detail['dive_fields']['raw_representation'], 'FRA')
                self.assertEqual(detail['dive_fields']['representation_status'], 'human')
                self.assertEqual(detail['dive_fields']['representation_citation'], 'page 1 line 2 Country')
                self.assertEqual(detail['category'], 'Women + Masters')
            first = hashlib.sha256((out / 'snapshot.sqlite').read_bytes()).hexdigest()
            subprocess.run([sys.executable, str(SCRIPT), 'replay', '--output-dir', str(out)],
                           check=True, capture_output=True)
            self.assertEqual(hashlib.sha256((out / 'snapshot.sqlite').read_bytes()).hexdigest(), first)
            decision['positions'][0]['decision_revision'] = 4
            decisions.write_text(json.dumps(decision))
            stale = subprocess.run([sys.executable, str(SCRIPT), 'replay', '--output-dir', str(out)],
                                   capture_output=True, text=True)
            self.assertNotEqual(stale.returncode, 0)
            self.assertIn('decision export hash mismatch', stale.stderr)
            self.assertEqual(hashlib.sha256((out / 'snapshot.sqlite').read_bytes()).hexdigest(), first)
            decision['positions'][0]['parser_version'] = 'pdf/2'
            decisions.write_text(json.dumps(decision))
            rejected = subprocess.run(args, capture_output=True, text=True)
            self.assertNotEqual(rejected.returncode, 0)
            self.assertIn('version mismatch', rejected.stderr)
            self.assertEqual(hashlib.sha256((out / 'snapshot.sqlite').read_bytes()).hexdigest(), first)
            decision['positions'][0]['parser_version'] = 'pdf/1'
            decision['positions'][0]['ordinal'] = 1
            decisions.write_text(json.dumps(decision))
            rejected = subprocess.run(args, capture_output=True, text=True)
            self.assertNotEqual(rejected.returncode, 0)
            self.assertIn('version mismatch', rejected.stderr)

    @unittest.skipUnless(V8_SNAPSHOT.exists(), 'private v8 snapshot unavailable')
    def test_ffessm_printed_field_link_resolves_only_cited_rows(self):
        with SnapshotQuery(V8_SNAPSHOT) as query:
            row = query.browse(source_name='ffessm-correspondences',
                               collection='relationships', limit=1)['records'][0]
            comparison = query.comparison(row['record_id'])
            listed = next(item for offset in range(0, query.comparisons()['total'], 100)
                          for item in query.comparisons(limit=100, offset=offset)['items']
                          if item['id'] == row['record_id'])
        self.assertEqual(listed['label'], 'shared_printed_fields')
        self.assertEqual(comparison['relationship']['type'], 'shared_printed_fields')
        self.assertIsNone(comparison['relationship']['same_attempt'])
        self.assertIsNone(comparison['relationship']['ranking_row_date'])
        self.assertEqual(comparison['relationship']['matched_daily_date'], '2025-06-28')
        self.assertEqual([side['source_name'] for side in comparison['sides']],
                         ['ffessm-daily', 'ffessm-rankings'])
        self.assertEqual(comparison['sides'][0]['record_id'],
                         '6b703d146a7866700a24f87e5772419e6e4f976c0cda12c183db8268d7126b9a')
        self.assertEqual(comparison['sides'][1]['record_id'],
                         'f2d0dc5c9ce947ba2e54f94fb3ae9233dde5fda3a98e70ab5d67285c272f3248')
        self.assertEqual(comparison['field_correspondences']['announced_depth_m'],
                         {'daily_citation': 'page 1 line 16', 'daily_printed': 75,
                          'ranking_citation': 'page 1 line 9 column start 1 column end 119',
                          'ranking_printed': '75 m', 'value': 75})
        self.assertEqual(comparison['raw_field_differences'], {})
        self.assertIsNone(comparison['unavailable'])

    @unittest.skipUnless(V8_SNAPSHOT.exists(), 'private v8 snapshot unavailable')
    def test_v8_queue_keeps_unmatched_attempts_and_aggregates_separate(self):
        with SnapshotQuery(V8_SNAPSHOT) as query:
            ffessm = query.queue(source_name='ffessm-correspondences', limit=100)
            apnea = query.queue(source_name='apnea-file-reconciliation', limit=100)
            san_mauro = query.queue(source_name='san-mauro-jpg', limit=100)
        self.assertEqual(len([item for item in ffessm['items']
                              if item['group'] == 'same_attempt_relationship']), 15)
        self.assertEqual({item['citation']['collection'] for item in apnea['items']
                          if item['group'] == 'extraction_source_semantics'},
                         {'gia_team.rows', 'san_mauro.rows'})
        self.assertTrue(any(item['citation']['collection'] == 'positions'
                            and item['group'] == 'extraction_source_semantics'
                            for item in san_mauro['items']))

    @unittest.skipUnless(ROATAN_SNAPSHOT.exists(), 'private Roatan snapshot unavailable')
    def test_roatan_versions_keep_historical_extraction_scope(self):
        with SnapshotQuery(ROATAN_SNAPSHOT) as query:
            listing = query.roatan_positions()
            first = query.roatan_position(3551, 0)
            other = query.roatan_position(3559, 3)
            queue = query.queue(source_name='roatan-issue8')
            lu = next(query.roatan_position(item['unit'], item['index']) for item in listing['items']
                      if item['name'] == 'LU San-Jen')
        self.assertEqual(listing['total'], 31)
        self.assertEqual(listing['v1_observations'], 31)
        self.assertEqual(listing['v2_observations'], 31)
        self.assertEqual(listing['source_objects'], 2)
        self.assertEqual({source['unit'] for source in listing['source_object_details']}, {3551, 3559})
        self.assertEqual(queue['total'], 5)
        self.assertEqual(listing['historical_extraction_acceptances'], 7)
        with SnapshotQuery(ROATAN_SNAPSHOT) as query:
            rows = [query.roatan_position(item['unit'], item['index']) for item in listing['items']]
        self.assertEqual({row['position_review_status'] for row in rows}, {'unreviewed'})
        self.assertEqual(sum(row['versions']['v1']['review_status'] == 'unreviewed' for row in rows), 31)
        self.assertEqual(sum(row['versions']['v2']['review_status'] == 'extraction_accepted' for row in rows), 7)
        self.assertEqual(sum(row['versions']['v2']['review_status'] == 'unreviewed' for row in rows), 24)
        self.assertEqual({(row['unit'], row['index']) for row in rows if row['historical_extraction']},
                         {(3551, index) for index in range(7)})
        self.assertEqual(first['historical_extraction']['scope'], 'extraction accuracy only')
        self.assertEqual(first['versions']['v1']['review_status'], 'unreviewed')
        self.assertEqual(first['versions']['v2']['review_status'], 'extraction_accepted')
        self.assertIsNone(other['historical_extraction'])
        self.assertEqual(lu['declared_depth'], '95')
        self.assertEqual(lu['raw_depth'], '65')
        self.assertEqual(lu['final_depth'], '34')
        self.assertEqual(lu['penalty'], '31')
        self.assertIn('EARLY TURN, NO MARKER', str(lu['notes']))
        self.assertEqual(lu['same_attempt'], 'unknown')

    def test_comparison_uses_only_explicit_retained_position_link(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            baseline = root / 'baseline.json'
            retained = root / 'retained.json'
            baseline.write_text(json.dumps({'positions': [{
                'id': 'source-position:one', 'source_id': 'sha256:' + 'a' * 64,
                'locator': 'page 1 line 2', 'raw_fields': {'name': 'Ada', 'result': '100'},
                'parsed_fields': {'name': 'Ada', 'result': '100'},
                'observation_refs': [{'parser_version': 'parser/2', 'candidate_id': 'observation-2'}]}]}))
            retained.write_text(json.dumps({'candidate_versions': [{
                'id': 'retained:one', 'imported_position_id': 'source-position:one',
                'source_id': 'sha256:' + 'a' * 64, 'artifact_sha256': 'b' * 64,
                'match_basis': 'exact_raw_evidence', 'parser_version': 'parser/1',
                'candidate': {'raw': {'fields': {'name': 'Ada', 'result': '100'}},
                              'parsed': {'name': 'Ada', 'result': '99'}}}]}))
            out = root / 'out'
            subprocess.run([sys.executable, str(SCRIPT), 'build', '--cutoff', '2026-09-28T12:00:00Z',
                            '--input', f'baseline={baseline}', '--input', f'retained={retained}',
                            '--output-dir', str(out)], check=True, capture_output=True)
            with SnapshotQuery(out) as query:
                listed = query.comparisons(limit=10)
                compared = query.comparison(listed['items'][0]['id'])
                unrelated = query.comparison(query.browse(source_name='baseline')['records'][0]['record_id'])
        self.assertEqual(listed['total'], 1)
        self.assertEqual(compared['relationship']['basis'], 'exact_raw_evidence')
        self.assertEqual(compared['sides'][0]['parsed_fields']['result'], '99')
        self.assertEqual(compared['sides'][1]['parsed_fields']['result'], '100')
        self.assertEqual(compared['field_differences']['result'], ['99', '100'])
        self.assertIsNone(compared['confirmed_distinct_attempts'])
        self.assertIsNone(unrelated)

    def test_duplicate_imported_position_id_is_explicitly_unavailable(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            baseline = root / 'baseline.json'
            retained = root / 'retained.json'
            baseline.write_text(json.dumps({'positions': [
                {'id': 'source-position:duplicate', 'raw_fields': {'result': '10'}},
                {'id': 'source-position:duplicate', 'raw_fields': {'result': '20'}}]}))
            retained.write_text(json.dumps({'candidate_versions': [{
                'id': 'retained:one', 'imported_position_id': 'source-position:duplicate',
                'candidate': {'raw': {'fields': {'result': '10'}}, 'parsed': {'result': '10'}}}]}))
            out = root / 'out'
            subprocess.run([sys.executable, str(SCRIPT), 'build', '--cutoff', '2026-09-28T12:00:00Z',
                            '--input', f'baseline={baseline}', '--input', f'retained={retained}',
                            '--output-dir', str(out)], check=True, capture_output=True)
            with SnapshotQuery(out) as query:
                result = query.comparison(query.comparisons()['items'][0]['id'])
        self.assertEqual(len(result['sides']), 1)
        self.assertIn('duplicated', result['unavailable'])
        self.assertEqual(result['field_differences'], {})

    def test_exception_queue_groups_explicit_evidence_without_decisions(self):
        with tempfile.TemporaryDirectory() as d:
            with SnapshotQuery(snapshot(Path(d))) as query:
                queue = query.queue(limit=1)
                next_page = query.queue(limit=1, offset=1)
                relationship = query.queue(group='event_publication')
                with self.assertRaises(ValueError):
                    query.queue(group='invented')
        self.assertEqual(queue['coverage'], 'dated partial census')
        self.assertEqual(queue['denominators']['candidate_positions'], 2)
        self.assertIsNone(queue['denominators']['confirmed_distinct_attempts'])
        self.assertEqual(queue['total'], 2)  # gap and excluded source; calendar link is browsable separately
        self.assertNotEqual(queue['items'][0]['id'], next_page['items'][0]['id'])
        self.assertEqual(relationship['total'], 0)

    def test_overview_keeps_namespaces_and_distinct_attempts_unknown(self):
        with tempfile.TemporaryDirectory() as d:
            with SnapshotQuery(snapshot(Path(d))) as query:
                result = query.overview()
        self.assertEqual(result['coverage'], 'dated partial census')
        self.assertIsNone(result['confirmed_distinct_attempts'])
        self.assertEqual(result['counts'], [
            {'source_name': 'visual', 'collection': 'pages', 'kind': 'other', 'records': 1},
            {'source_name': 'visual', 'collection': 'pages.rows', 'kind': 'candidate_position', 'records': 2},
            {'source_name': 'visual', 'collection': 'source_gaps', 'kind': 'gap', 'records': 1},
            {'source_name': 'visual', 'collection': 'source_relationship_candidates', 'kind': 'relationship', 'records': 1},
        ])

    def test_browse_filters_and_pages_stably(self):
        with tempfile.TemporaryDirectory() as d:
            with SnapshotQuery(snapshot(Path(d))) as query:
                first = query.browse(source_name='visual', collection='pages.rows', kind='candidate_position',
                                     event_name='Sample meet', date_from='2026-05-08', date_to='2026-05-08',
                                     session='morning', discipline='STA', category='Women', limit=1)
                second = query.browse(kind='candidate_position', limit=1, offset=1)
        self.assertEqual(first['total'], 2)
        self.assertEqual(len(first['records']), 1)
        self.assertEqual(first['records'][0]['record_path'], 'pages[0].rows[0]')
        self.assertEqual(second['records'][0]['record_path'], 'pages[0].rows[1]')
        self.assertNotEqual(first['records'][0]['record_id'], second['records'][0]['record_id'])


    def test_detail_preserves_raw_citation_and_provenance(self):
        with tempfile.TemporaryDirectory() as d:
            out = snapshot(Path(d))
            packet_sha = hashlib.sha256((Path(d) / 'packet.json').read_bytes()).hexdigest()
            with SnapshotQuery(out) as query:
                record_id = query.browse(kind='candidate_position', limit=1)['records'][0]['record_id']
                detail = query.detail(record_id)
                missing = query.detail('0' * 64)
        self.assertIsNone(missing)
        self.assertEqual(detail['raw']['fields'], {'Name': 'Ada'})
        self.assertEqual(detail['raw_fields'], {'Name': 'Ada'})
        self.assertEqual(detail['parsed_fields'], {'score': '3:00'})
        self.assertEqual(detail['citation'], {'page': 1, 'line': 3})
        self.assertEqual(detail['parser_version'], 'parser/1')
        self.assertEqual(detail['source_object_id'], 'sha256:original')
        self.assertEqual(detail['acquisition_id'], 'capture-1')
        self.assertEqual(detail['input_sha256'], packet_sha)

    def test_gaps_relationships_and_source_dispositions(self):
        with tempfile.TemporaryDirectory() as d:
            with SnapshotQuery(snapshot(Path(d))) as query:
                gaps = query.gaps()
                relationships = query.relationships()
                sources = query.sources()
                visual = query.source('visual')
                missing_source = query.source('nonexistent')
        self.assertEqual(gaps['total'], 1)
        self.assertEqual(relationships['total'], 1)
        self.assertEqual(gaps['records'][0]['kind'], 'gap')
        self.assertEqual(relationships['records'][0]['kind'], 'relationship')
        self.assertEqual([s['source_name'] for s in sources], ['pending', 'visual'])
        self.assertEqual(sources[0]['status'], 'excluded')
        self.assertEqual(sources[0]['reason'], 'unfinished')
        self.assertEqual(visual['metadata']['source']['id'], 'sha256:original')
        self.assertIsNone(missing_source)

    def test_rejects_unbounded_and_malformed_queries(self):
        with tempfile.TemporaryDirectory() as d:
            with SnapshotQuery(snapshot(Path(d))) as query:
                for kwargs in ({'limit': 0}, {'limit': 101}, {'limit': True},
                               {'offset': -1}, {'offset': 100001}, {'source_name': 'x' * 201},
                               {'date_from': '2026-02-30'}, {'date_from': '2026-05-09', 'date_to': '2026-05-08'}):
                    with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                        query.browse(**kwargs)
                with self.assertRaises(ValueError):
                    query.detail('x' * 1000)
                with self.assertRaises(ValueError):
                    query.source('x' * 1000)


    def test_cli_emits_json_and_rejects_bad_limit(self):
        with tempfile.TemporaryDirectory() as d:
            out = snapshot(Path(d))
            script = ROOT / 'scripts' / 'unified_evidence_query.py'
            good = subprocess.run([sys.executable, str(script), '--snapshot-dir', str(out),
                                   'browse', '--kind', 'candidate_position', '--limit', '1'],
                                  text=True, capture_output=True)
            bad = subprocess.run([sys.executable, str(script), '--snapshot-dir', str(out),
                                  'browse', '--limit', '101'], text=True, capture_output=True)
        self.assertEqual(good.returncode, 0, good.stderr)
        self.assertEqual(json.loads(good.stdout)['total'], 2)
        self.assertNotEqual(bad.returncode, 0)
        self.assertIn('limit must be', bad.stderr)


    def test_snapshot_hash_mismatch_fails_closed(self):
        with tempfile.TemporaryDirectory() as d:
            out = snapshot(Path(d))
            manifest_path = out / 'manifest.json'
            manifest = json.loads(manifest_path.read_text())
            manifest['snapshot_sha256'] = '0' * 64
            manifest_path.write_text(json.dumps(manifest), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'snapshot hash mismatch'):
                SnapshotQuery(out)


    def test_date_range_overlaps_printed_span_without_inventing_exact_date(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            packet = root / 'gia.json'
            packet.write_text(json.dumps({'schema': 'gia/v1', 'sheets': [
                {'name': 'Napoli', 'date_scope': ['2025-02-01', '2025-02-02', 'cell G4'],
                 'rows': [{'id': 'row-1', 'date_scope': ['2025-02-01', '2025-02-02', 'cell G4']}]}]}),
                encoding='utf-8')
            out = root / 'out'
            subprocess.run([sys.executable, str(SCRIPT), 'build', '--cutoff', '2026-09-28T12:00:00Z',
                            '--input', f'gia={packet}', '--output-dir', str(out)],
                           check=True, capture_output=True)
            with SnapshotQuery(out) as query:
                hit = query.browse(collection='sheets.rows', date_from='2025-02-02', date_to='2025-02-02')
                miss = query.browse(collection='sheets.rows', date_from='2025-02-03', date_to='2025-02-03')
        self.assertEqual(hit['total'], 1)
        self.assertIsNone(hit['records'][0]['event_date'])
        self.assertEqual(miss['total'], 0)



REAL_SNAPSHOT = Path(os.environ['OWNER_EVIDENCE_TEST_SNAPSHOT_DIR']) if os.environ.get('OWNER_EVIDENCE_TEST_SNAPSHOT_DIR') else None


@unittest.skipUnless(REAL_SNAPSHOT and REAL_SNAPSHOT.is_dir(), 'private retained snapshot unavailable')
class RetainedComparisonTest(unittest.TestCase):
    def test_explicit_links_do_not_inflate_attempts_or_guess_calendar_pairs(self):
        with SnapshotQuery(REAL_SNAPSHOT) as query:
            first = query.comparisons(limit=100)
            second = query.comparisons(limit=100, offset=100)
            items = first['items'] + second['items']
            retained = [x for x in items if x['collection'] == 'candidate_versions']
            calendar = next(x for x in items if x['collection'] == 'source_relationship_candidates')
            pair = query.comparison('20c15ebfc7ea36cc2a582a869db19032f8db9c8511f3319a2e11b77aa90a586a')
            absent = query.comparison(calendar['id'])
        self.assertEqual(first['total'], 142)
        self.assertEqual(len(retained), 136)
        self.assertEqual(len(pair['sides']), 2)
        self.assertEqual(pair['sides'][1]['record_id'],
                         '2346e3c756ad505699f08078c0f2ae6d3cafb1bf89b7328115809f5eb6baecff')
        self.assertEqual([s['parser_version'] for s in pair['sides']],
                         ['belgrade-freediving-open-2026/1', 'belgrade-freediving-open-2026/2'])
        self.assertEqual(pair['sides'][0]['source_sha256'], pair['sides'][1]['source_sha256'])
        self.assertEqual(pair['field_differences'], {})
        self.assertIsNone(pair['confirmed_distinct_attempts'])
        self.assertEqual(absent['sides'], [])
        self.assertIsNotNone(absent['unavailable'])


if __name__ == '__main__':
    unittest.main()
