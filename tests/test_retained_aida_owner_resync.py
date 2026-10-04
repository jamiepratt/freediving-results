import copy
import hashlib
import json
import unittest

from scripts.retained_aida_owner_resync import verify_noop_resync, run_read_only_preflight


def sha(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def document(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':')) + '\n').encode()


class RetainedOwnerResyncTest(unittest.TestCase):
    def setUp(self):
        self.snapshot = 'a' * 64
        self.retained_bundle = None
        self.production_bundle = None
        self.source = 'd' * 64
        self.row = {'observation-id': 'source-observation:' + 'e' * 64,
                    'source-name': 'synthetic', 'parse-status': 'parsed',
                    'citation': {'snapshot_sha256': self.snapshot},
                    'source-observation-ref': {'snapshot_sha256': self.snapshot},
                    'publisher-scope': 'AIDA', 'publisher-athlete-id': 'person-1',
                    'publisher-id-kind': 'person'}
        self.event = {'id': 'accept', 'action': 'accept', 'actor-kind': 'automatic',
                      'base-revision': 0,
                      'source-binding': {'snapshot-sha256': self.snapshot, 'refs': {}},
                      'pair': ['one', 'two'], 'rule-version': 'athlete-identity/1'}
        self.target = {'schema': 'retained-aida-target-state/v1', 'snapshot_sha256': self.snapshot,
                       'revision': 1, 'source_rows': [copy.deepcopy(self.row)],
                       'events': [{'id': 'accept', 'request': copy.deepcopy(self.event)}],
                       'non_source_row_count': 0}
        self.owner = {'snapshot_sha256': self.snapshot, 'store_revision': 3,
                      'binding_revision': 1, 'proposal_count': 2, 'human_event_count': 2,
                      'operation_count': 2, 'observation_refs': {}}
        self.retained_manifest = {'schema': 'retained-aida-cohort-bundle/v1',
                                  'snapshot': {'snapshot_sha256': self.snapshot},
                                  'inputs': {'aida_packet': self.source,
                                             'aida_receipt': 'f' * 64,
                                             'aida_original': '1' * 64},
                                  'recovered_packets': {}}
        self.production_manifest = {'schema': 'private-source-bundle/v1', 'sources': [
            {'id': 'packet', 'sha256': self.source, 'status': 'included'},
            {'id': 'receipt', 'sha256': 'f' * 64, 'status': 'included'},
            {'id': 'original', 'sha256': '1' * 64, 'status': 'restricted'}]}
        self.retained_bundle = hashlib.sha256(document(self.retained_manifest)).hexdigest()
        self.production_bundle = hashlib.sha256(document(self.production_manifest)).hexdigest()
        self.status = {'schema': 'private-presentation-status/v3', 'revision': 2,
                       'run_id': 'active-run',
                       'local': {'snapshot_sha256': self.snapshot, 'cutoff': '2026-10-03T00:00:00Z',
                                 'gap_count': 62},
                       'remote': {'status': 'active', 'pending': None, 'failed': None,
                                  'active': {'snapshot_sha256': self.snapshot,
                                             'bundle_manifest_sha256': self.production_bundle}},
                       'application': {'snapshot_sha256': self.snapshot, 'canonical_revision': 1,
                                       'canonical_readback_sha256': hashlib.sha256(document(self.target)).hexdigest(),
                                       'owner_store_revision': 3, 'pending_proposals': 2,
                                       'unresolved_exclusions': 0, 'provider_calls_recorded': 0,
                                       'publication_status': 'private'}}
        self.retained = {'schema': 'retained-aida-private-status/v1', 'run_id': 'retained-run',
                         'binding': {'snapshot_sha256': self.snapshot,
                                     'source_bundle_sha256': self.retained_bundle,
                                     'export_sha256': None},
                         'cutoff': self.status['local']['cutoff'], 'source_gaps': 0,
                         'provider_calls': 0, 'counts': {'source_rows': 1},
                         'canonical': {'status': 'applied', 'identity_revision': 1}}
        self.snapshot_manifest = {'snapshot_sha256': self.snapshot,
                                  'inputs': {'aida': {'status': 'included', 'sha256': self.source,
                                                       'source_schema': 'aida-selected-html-packet/v1'}}}
        self.mapping = {'schema': 'retained-aida-production-source-map/v1',
                        'snapshot_sha256': self.snapshot,
                        'retained_bundle_sha256': self.retained_bundle,
                        'production_bundle_sha256': self.production_bundle,
                        'sources': [
                            {'retained_source': 'aida', 'snapshot_input': 'aida', 'role': 'packet', 'production_source_id': 'packet', 'sha256': self.source},
                            {'retained_source': 'aida', 'snapshot_input': 'aida', 'role': 'receipt', 'production_source_id': 'receipt', 'sha256': 'f' * 64},
                            {'retained_source': 'aida', 'snapshot_input': 'aida', 'role': 'original', 'production_source_id': 'original', 'sha256': '1' * 64}]}
        self.export = {'schema': 'retained-aida-cohort/v1',
                       'binding': {'snapshot_sha256': self.snapshot,
                                   'identity_revision': 0, 'history_event_ids': []},
                       'registration': {'snapshot_sha256': self.snapshot,
                                        'rows': [{'observation_id': self.row['observation-id'],
                                                  'source_name': 'synthetic', 'parse_status': 'parsed',
                                                  'citation': {'snapshot_sha256': self.snapshot},
                                                  'source_observation_ref': {'snapshot_sha256': self.snapshot},
                                                  'publisher_scope': 'AIDA',
                                                  'publisher_athlete_id': 'person-1',
                                                  'publisher_id_kind': 'person'}]},
                       'events': [{'id': 'accept', 'action': 'accept', 'actor_kind': 'automatic',
                                   'source_binding': {'snapshot_sha256': self.snapshot, 'refs': {}},
                                   'pair': ['one', 'two'], 'rule_version': 'athlete-identity/1'}]}
        self.retained['binding']['export_sha256'] = hashlib.sha256(document(self.export)).hexdigest()
        self.readback = {'schema': 'retained-aida-canonical-readback/v1',
                         'snapshot-sha256': self.snapshot,
                         'non-source-row-count': 0,
                         'projection': {'revision': 1},
                         'source-rows': [copy.deepcopy(self.row)],
                         'events': [{'id': 'accept', 'base-revision': 0,
                                     'request': copy.deepcopy(self.event)}]}
        self.pin = {'schema': 'retained-aida-owner-resync/v1', 'snapshot_sha256': self.snapshot,
                    'cutoff': self.status['local']['cutoff'],
                    'retained_bundle_sha256': self.retained_bundle,
                    'production_bundle_sha256': self.production_bundle,
                    'source_map_sha256': sha(self.mapping),
                    'isolated_readback_sha256': hashlib.sha256(document(self.readback)).hexdigest(),
                    'canonical_revision': 1, 'canonical_state_sha256': sha(self.target),
                    'canonical_readback_sha256': hashlib.sha256(document(self.target)).hexdigest(),
                    'owner_revision': 3, 'owner_binding_revision': 1,
                    'owner_state_sha256': sha(self.owner),
                    'status_revision': 2, 'status_sha256': sha(self.status)}

    def check(self):
        return verify_noop_resync(self.pin, self.retained, document(self.export),
                                  document(self.readback),
                                  document(self.retained_manifest),
                                  self.snapshot_manifest, document(self.production_manifest),
                                  self.mapping, self.status,
                                  document(self.target), self.owner)

    def test_exact_replay_is_an_unchanged_noop(self):
        self.assertEqual(self.check()['outcome'], 'unchanged')

    def test_divergent_canonical_event_fails_even_when_re_pinned(self):
        self.target['events'][0]['request']['rule-version'] = 'different-rule'
        self.pin['canonical_state_sha256'] = sha(self.target)
        raw = hashlib.sha256(document(self.target)).hexdigest()
        self.pin['canonical_readback_sha256'] = raw
        self.status['application']['canonical_readback_sha256'] = raw
        self.pin['status_sha256'] = sha(self.status)
        with self.assertRaisesRegex(ValueError, 'retained canonical event'):
            self.check()

    def test_divergent_source_row_fails_even_when_re_pinned(self):
        self.target['source_rows'][0]['source-name'] = 'other'
        self.pin['canonical_state_sha256'] = sha(self.target)
        raw = hashlib.sha256(document(self.target)).hexdigest()
        self.pin['canonical_readback_sha256'] = raw
        self.status['application']['canonical_readback_sha256'] = raw
        self.pin['status_sha256'] = sha(self.status)
        with self.assertRaisesRegex(ValueError, 'retained canonical source rows'):
            self.check()

    def test_changed_isolated_readback_cannot_authorize_re_pinned_target(self):
        self.readback['events'][0]['request']['rule-version'] = 'different-rule'
        self.pin['isolated_readback_sha256'] = hashlib.sha256(document(self.readback)).hexdigest()
        with self.assertRaisesRegex(ValueError, 'retained canonical event'):
            self.check()

    def test_status_canonical_raw_readback_must_match_fresh_target_file(self):
        self.status['application']['canonical_readback_sha256'] = '9' * 64
        self.pin['status_sha256'] = sha(self.status)
        with self.assertRaisesRegex(ValueError, 'canonical readback bytes'):
            self.check()

    def test_replay_receipt_is_stable_after_interrupted_read(self):
        first = self.check()
        self.assertEqual(self.check(), first)
        self.assertEqual(self.status['revision'], 2)
        self.assertEqual(self.owner['proposal_count'], 2)
        self.assertEqual(self.target['revision'], 1)

    def test_stale_local_export_is_rejected(self):
        self.retained['binding']['source_bundle_sha256'] = '9' * 64
        with self.assertRaisesRegex(ValueError, 'retained bundle identity'):
            self.check()

    def test_new_human_correction_is_preserved(self):
        self.owner['store_revision'] += 1
        self.owner['human_event_count'] += 1
        with self.assertRaisesRegex(ValueError, 'owner'):
            self.check()
        self.assertEqual(self.owner['store_revision'], 4)

    def test_newer_status_wins(self):
        self.status['revision'] += 1
        with self.assertRaisesRegex(ValueError, 'remote status'):
            self.check()
        self.assertEqual(self.status['local']['gap_count'], 62)

    def test_bundle_hashes_cannot_be_relabelled(self):
        self.pin['production_bundle_sha256'] = self.retained_bundle
        with self.assertRaisesRegex(ValueError, 'bundle manifest bytes'):
            self.check()

    def test_incomplete_mapping_rejects_restricted_original(self):
        self.mapping['sources'].pop()
        self.pin['source_map_sha256'] = sha(self.mapping)
        with self.assertRaisesRegex(ValueError, 'mapping incomplete'):
            self.check()

    def test_changed_production_source_rejected(self):
        self.production_manifest['sources'][-1]['sha256'] = '2' * 64
        self.production_bundle = hashlib.sha256(document(self.production_manifest)).hexdigest()
        self.pin['production_bundle_sha256'] = self.production_bundle
        self.status['remote']['active']['bundle_manifest_sha256'] = self.production_bundle
        self.mapping['production_bundle_sha256'] = self.production_bundle
        self.pin['source_map_sha256'] = sha(self.mapping)
        self.pin['status_sha256'] = sha(self.status)
        with self.assertRaisesRegex(ValueError, 'retained source differs'):
            self.check()

    def test_historical_recovery_requires_its_own_exact_triple(self):
        older = {'packet_sha256': '2' * 64, 'receipt_sha256': '3' * 64,
                 'original_sha256': '4' * 64}
        self.retained_manifest['recovered_packets']['older'] = older
        self.retained_bundle = hashlib.sha256(document(self.retained_manifest)).hexdigest()
        self.retained['binding']['source_bundle_sha256'] = self.retained_bundle
        self.pin['retained_bundle_sha256'] = self.retained_bundle
        self.mapping['retained_bundle_sha256'] = self.retained_bundle
        self.snapshot_manifest['inputs']['older-snapshot'] = {
            'status': 'included', 'source_schema': 'aida-selected-html-packet/v1',
            'sha256': older['packet_sha256']}
        for role in ('packet', 'receipt', 'original'):
            self.production_manifest['sources'].append({
                'id': 'older-' + role, 'sha256': older[role + '_sha256'],
                'status': 'restricted' if role == 'original' else 'included'})
            self.mapping['sources'].append({
                'retained_source': 'older', 'snapshot_input': 'older-snapshot',
                'role': role, 'production_source_id': 'older-' + role,
                'sha256': older[role + '_sha256']})
        self.production_bundle = hashlib.sha256(document(self.production_manifest)).hexdigest()
        self.pin['production_bundle_sha256'] = self.production_bundle
        self.mapping['production_bundle_sha256'] = self.production_bundle
        self.status['remote']['active']['bundle_manifest_sha256'] = self.production_bundle
        self.pin['status_sha256'] = sha(self.status)
        self.pin['source_map_sha256'] = sha(self.mapping)
        self.assertEqual(self.check()['outcome'], 'unchanged')
        self.mapping['sources'].pop()
        self.pin['source_map_sha256'] = sha(self.mapping)
        with self.assertRaisesRegex(ValueError, 'mapping incomplete'):
            self.check()

    def test_private_preflight_writes_one_receipt_after_two_matching_status_reads(self):
        import tempfile
        from pathlib import Path
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            root.chmod(0o700)
            def write(name, value):
                path = root / name
                path.write_bytes(value if isinstance(value, bytes) else document(value))
                path.chmod(0o600)
                return path
            paths = {
                'pin': write('pin.json', self.pin),
                'export': write('export.json', self.export),
                'isolated_readback': write('readback.json', self.readback),
                'retained_manifest': write('retained.json', document(self.retained_manifest)),
                'snapshot_manifest': write('snapshot.json', self.snapshot_manifest),
                'production_manifest': write('production.json', document(self.production_manifest)),
                'source_map': write('source-map.json', self.mapping),
                'target': write('target.json', self.target),
                'owner': write('owner.json', self.owner),
            }
            reads = []
            def current():
                reads.append(1)
                return copy.deepcopy(self.status)
            output = root / 'receipt.json'
            receipt = run_read_only_preflight(root, paths, output,
                                              status_reader=current,
                                              retained_reader=lambda _: copy.deepcopy(self.retained))
            self.assertEqual(receipt['outcome'], 'unchanged')
            self.assertEqual(len(reads), 2)
            self.assertEqual(output.stat().st_mode & 0o777, 0o600)
            self.assertEqual(run_read_only_preflight(root, paths, output,
                             status_reader=current,
                             retained_reader=lambda _: copy.deepcopy(self.retained)), receipt)

    def test_status_change_during_preflight_leaves_no_receipt(self):
        import tempfile
        from pathlib import Path
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            root.chmod(0o700)
            paths = {}
            for name, value in [('pin', self.pin), ('export', self.export),
                                ('isolated_readback', self.readback),
                                ('retained_manifest', self.retained_manifest),
                                ('snapshot_manifest', self.snapshot_manifest),
                                ('production_manifest', self.production_manifest),
                                ('source_map', self.mapping), ('target', self.target),
                                ('owner', self.owner)]:
                path = root / (name + '.json')
                path.write_bytes(document(value))
                path.chmod(0o600)
                paths[name] = path
            changed = copy.deepcopy(self.status)
            changed['revision'] += 1
            status_reads = iter((self.status, changed))
            output = root / 'receipt.json'
            with self.assertRaisesRegex(ValueError, 'status changed during preflight'):
                run_read_only_preflight(root, paths, output,
                                        status_reader=lambda: next(status_reads),
                                        retained_reader=lambda _: self.retained)
            self.assertFalse(output.exists())


if __name__ == '__main__':
    unittest.main()
