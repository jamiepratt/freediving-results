"""Exact append-only private source ingestion preservation contract."""
import copy
import datetime
import hashlib
import unittest
from retained_source_import_guard import build_manifest, snapshot_rows, verify_append, JOBS

SOURCE = '67933b6afa56c7c4cff1df14b9b415d2e32d33f49feb10f24be46d4b59fa3e93'


class RetainedSourceImportGuardTests(unittest.TestCase):
    def setUp(self):
        self.jobs = []
        self.rows = {'extractions': [], 'observations': []}
        for index, job in enumerate(JOBS):
            version = 'aida-html/1'
            artifact = str(index).encode()
            self.jobs.append(job)
            self.rows['extractions'].append({'job_id': job, 'artifact_sha256': hashlib.sha256(artifact).hexdigest(),
                'source_sha256': SOURCE, 'parser_version': version, 'schema_version': 4,
                'artifact_bytes': '\\x' + artifact.hex(), 'imported_at': '2026-10-06T12:30:00+00:00'})
            for ordinal in range(209):
                self.rows['observations'].append({'job_id': job, 'ordinal': ordinal,
                    'candidate_id': hashlib.sha256(str(ordinal).encode()).hexdigest(),
                    'kind': 'result-row', 'classification_reason': 'parsed-or-explicit-result-fields',
                    'payload_edn': '{:synthetic true :ordinal %d}' % ordinal})
        self.manifest = build_manifest('source', self.rows,
            {'selected_date': '2026-06-03', 'filters': {}}, 'a' * 64)
        self.before = snapshot_rows({'source': {'extractions': [], 'observations': [],
            'identity_events': [{'revision': 5}]}, 'canonical': {'canonical_events': [{'revision': 7}]}},
            {'source': 'a' * 64, 'canonical': 'b' * 64})
        values = {'source': {**self.rows, 'identity_events': [{'revision': 5}]},
                  'canonical': {'canonical_events': [{'revision': 7}]}}
        self.after = snapshot_rows(values, {'source': 'a' * 64, 'canonical': 'b' * 64})
        self.start = '2026-10-06T12:29:00+00:00'
        self.end = '2026-10-06T12:31:00+00:00'

    def test_exact_import_and_zero_write_replay_have_precise_receipts(self):
        receipt = verify_append(self.before, self.after, self.manifest, self.start, self.end)
        self.assertEqual(receipt['deltas']['source'], {'extractions': 2, 'observations': 418, 'identity_events': 0})
        self.assertEqual(receipt['deltas']['canonical'], {'canonical_events': 0})
        replay = verify_append(self.after, self.after, self.manifest, self.start, self.end)
        self.assertEqual(replay['deltas']['source']['observations'], 0)
        self.assertEqual(replay['deltas']['source']['extractions'], 0)

    def test_catalog_change_refuses_append_even_with_identical_rows(self):
        import os, subprocess, uuid
        from retained_source_import_guard import capture_databases
        template = os.environ.get('RETAINED_GUARD_TEST_TEMPLATE')
        if not template: self.skipTest('isolated schema-23 PostgreSQL template not configured')
        database = 'guard_' + uuid.uuid4().hex
        subprocess.run(['createdb', '-T', template, database], check=True, capture_output=True)
        self.addCleanup(lambda: subprocess.run(['dropdb', database], check=True, capture_output=True))
        before = capture_databases([database], source_database=database, peer=True)
        subprocess.run(['psql', '-X', '-v', 'ON_ERROR_STOP=1', '-d', database, '-c',
            "CREATE OR REPLACE FUNCTION freediving.guard_catalog_probe() RETURNS integer LANGUAGE SQL AS 'SELECT 1'"],
            check=True, capture_output=True)
        after = capture_databases([database], source_database=database, peer=True)
        self.assertNotEqual(before['databases'][database]['schema_sha256'], after['databases'][database]['schema_sha256'])

    def test_unrelated_writes_existing_changes_schema_and_missing_rows_refused(self):
        mutations = []
        changed = copy.deepcopy(self.after); changed['databases']['source']['tables']['identity_events']['rows'][0]['sha256'] = 'c' * 64
        mutations.append(changed)
        changed = copy.deepcopy(self.after); changed['databases']['canonical']['tables']['canonical_events']['rows'].append({'sha256': 'c' * 64}); changed['databases']['canonical']['tables']['canonical_events']['count'] += 1
        mutations.append(changed)
        changed = copy.deepcopy(self.after); changed['databases']['source']['schema_sha256'] = 'd' * 64
        mutations.append(changed)
        changed = copy.deepcopy(self.after); changed['databases']['source']['tables']['observations']['rows'].pop(); changed['databases']['source']['tables']['observations']['count'] -= 1
        mutations.append(changed)
        changed = copy.deepcopy(self.after); changed['databases']['source']['tables']['observations']['rows'][0]['import_sha256'] = 'e' * 64
        mutations.append(changed)
        for changed in mutations:
            with self.subTest(changed=changed['databases']['source']['schema_sha256']):
                with self.assertRaises(ValueError): verify_append(self.before, changed, self.manifest, self.start, self.end)

    def test_import_timestamps_and_exact_manifest_inputs_refused(self):
        changed = copy.deepcopy(self.after)
        changed['databases']['source']['tables']['extractions']['rows'][0]['imported_at'] = '2026-10-05T12:30:00+00:00'
        with self.assertRaisesRegex(ValueError, 'outside apply window'):
            verify_append(self.before, changed, self.manifest, self.start, self.end)
        for selector in ({'selected_date': '2026-06-04', 'filters': {}}, {'selected_date': '2026-06-03', 'filters': {'gender': 'women'}}):
            with self.assertRaisesRegex(ValueError, 'selector'):
                build_manifest('source', self.rows, selector, 'a' * 64)
        for field, value in [('source_sha256', 'b' * 64), ('artifact_bytes', '\\x00'), ('parser_version', 'aida-html/3')]:
            rows = copy.deepcopy(self.rows); rows['extractions'][0][field] = value
            with self.assertRaises(ValueError): build_manifest('source', rows, {'selected_date': '2026-06-03', 'filters': {}}, 'a' * 64)
        rows = copy.deepcopy(self.rows); rows['observations'].pop()
        with self.assertRaisesRegex(ValueError, '209-position'):
            build_manifest('source', rows, {'selected_date': '2026-06-03', 'filters': {}}, 'a' * 64)

    def test_same_source_public_database_alias_is_explicit_and_exact(self):
        self.manifest['public_database'] = 'source'
        for snapshot in (self.before, self.after):
            tables = snapshot['databases']['source']['tables']
            summary = {}
            for table in ('extractions', 'observations'):
                tables[table]['legacy_sha256'] = digest = hashlib.sha256(str(tables[table]['rows']).encode()).hexdigest()
                summary[table] = {'count': tables[table]['count'], 'sha256': digest}
            snapshot['protected'] = {'authority': {'source_review_tables': {'source': copy.deepcopy(summary)}, 'public_tables': copy.deepcopy(summary), 'owner_tables': {'events': {'count': 227, 'sha256': 'a' * 64}}}, 'protected': {'snapshot': 'b' * 64}}
        receipt = verify_append(self.before, self.after, self.manifest, self.start, self.end)
        self.assertEqual(receipt['checked_guard_aliases'], ['authority.source_review_tables.source', 'authority.public_tables'])
        manifest = copy.deepcopy(self.manifest); manifest['public_database'] = None
        with self.assertRaisesRegex(ValueError, 'public binding'):
            verify_append(self.before, self.after, manifest, self.start, self.end)
        changed = copy.deepcopy(self.after); changed['protected']['authority']['public_tables']['observations']['sha256'] = 'f' * 64
        with self.assertRaisesRegex(ValueError, 'alias'):
            verify_append(self.before, changed, self.manifest, self.start, self.end)
        changed = copy.deepcopy(self.after); changed['protected']['authority']['owner_tables']['events']['count'] += 1
        with self.assertRaisesRegex(ValueError, 'authority'):
            verify_append(self.before, changed, self.manifest, self.start, self.end)

    def test_preflight_declares_exact_deltas_and_refuses_partial_existing_job(self):
        from retained_source_import_guard import preflight
        self.assertEqual(preflight(self.before, self.manifest)['expected_deltas'], {'extractions': 2, 'observations': 418})
        self.assertEqual(preflight(self.after, self.manifest)['expected_deltas'], {'extractions': 0, 'observations': 0})
        partial = copy.deepcopy(self.after)
        partial['databases']['source']['tables']['observations']['rows'].pop()
        partial['databases']['source']['tables']['observations']['count'] -= 1
        with self.assertRaisesRegex(ValueError, 'partial'):
            preflight(partial, self.manifest)

    def test_capture_source23_and_canonical21_without_migration_writes(self):
        import os, subprocess, uuid
        from retained_source_import_guard import capture_databases
        if not os.environ.get('RETAINED_GUARD_TEST_PG'):
            self.skipTest('isolated PostgreSQL inventory test not configured')
        def database(versions):
            name = 'guard_' + uuid.uuid4().hex
            subprocess.run(['createdb', name], check=True, capture_output=True)
            self.addCleanup(lambda: subprocess.run(['dropdb', name], check=True, capture_output=True))
            sql = "CREATE SCHEMA freediving; CREATE TABLE freediving.schema_migrations(version integer PRIMARY KEY,sha256 text NOT NULL);"
            sql += 'INSERT INTO freediving.schema_migrations VALUES ' + ','.join("(%d,'%s')" % (version, 'a'*64) for version in versions)
            subprocess.run(['psql','-X','-v','ON_ERROR_STOP=1','-d',name,'-c',sql],check=True,capture_output=True)
            return name
        source = database(range(1,24)); canonical = database(range(1,22))
        before = capture_databases([source,canonical],source_database=source,peer=True)
        after = capture_databases([source,canonical],source_database=source,peer=True)
        self.assertEqual(before,after)
        self.assertEqual(before['databases'][source]['tables']['schema_migrations']['count'],23)
        self.assertEqual(before['databases'][canonical]['tables']['schema_migrations']['count'],21)
        for versions in (range(1,23), [v for v in range(1,24) if v!=12]):
            wrong = database(versions)
            with self.assertRaisesRegex(ValueError,'source.*23'):
                capture_databases([wrong,canonical],source_database=wrong,peer=True)
        gapped = database([1,2,4])
        with self.assertRaisesRegex(ValueError,'contiguous'):
            capture_databases([source,gapped],source_database=source,peer=True)
