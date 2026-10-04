import copy
import hashlib
import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

from scripts.retained_aida_promotion import build_promotion_preflight, main


SHA = 'a' * 64
RECORDS = ['1' * 64, '2' * 64, '3' * 64]


def evidence(record):
    ref = {'kind': 'source-derived', 'snapshot_sha256': SHA,
           'snapshot_record_id': record, 'source_name': 'aida',
           'source_sha256': 'b' * 64, 'packet_sha256': 'c' * 64,
           'adapter_version': 'aida-snapshot-observation/3',
           'observation_version': 'd' * 64, 'citation': {'row': record[0]},
           'publisher_person': {'scope': 'AIDA', 'id': 'person', 'id_kind': 'person'}}
    return {'snapshot_record_id': record, 'source_name': 'aida',
            'source_observation_ref': ref,
            'source_fields': {'name': 'Synthetic', 'publisher_person': ref['publisher_person']}}


def fixture():
    rows = [evidence(record) for record in RECORDS]
    ids = ['source-observation:' + record for record in RECORDS]
    refs = {ident: row['source_observation_ref'] for ident, row in zip(ids, rows)}
    def accept(number, pair):
        binding = {'snapshot-sha256': SHA, 'refs': {ident: refs[ident] for ident in pair}}
        request = {'id': 'edge-' + str(number), 'action': 'accept',
                   'actor-kind': 'automatic', 'base-revision': number - 1,
                   'pair': pair, 'rule-version': 'athlete-identity/1',
                   'source-binding': binding}
        return {**request, 'request': request}
    events = [accept(1, ids[:2]), accept(2, [ids[0], ids[2]])]
    reverse_request = {'id': 'reverse-1', 'action': 'reverse', 'actor-kind': 'human',
                       'base-revision': 2, 'event-id': 'edge-1', 'reason': 'Synthetic correction',
                       'source-binding': events[0]['source-binding']}
    events.append({**reverse_request, 'pair': ids[:2], 'request': reverse_request})
    readback = {'schema': 'retained-aida-canonical-readback/v1',
                'database': 'isolated', 'snapshot-sha256': SHA,
                'human-correction-revision': 3, 'non-source-row-count': 0,
                'source-rows': [{'observation-id': ident, 'citation': refs[ident],
                                 'source-observation-ref': refs[ident]}
                                for ident in ids],
                'events': events,
                'projection': {'revision': 3, 'provisional-record-count': 3,
                               'human-negative-pair-count': 1}}
    handoff = {'schema': 'retained-aida-isolated-handoff/v1',
               'authority_scope': 'selected_isolated_store_only',
               'canonical_readback': readback,
               'status': {'binding': {'snapshot_sha256': SHA},
                          'canonical': {'identity_revision': 3,
                                        'human_correction_revision': 3},
                          'provider_calls': 0}}
    owner = {'snapshot_sha256': SHA, 'binding_revision': 1, 'store_revision': 1,
             'proposal_count': 0,
             'observation_refs': {record: {'source_derived_ref': refs[ident]}
                                  for ident, record in zip(ids, RECORDS)}}
    return handoff, rows, owner


class RetainedAidaPromotionTest(unittest.TestCase):
    def test_exact_replay_keeps_human_reverse_and_stays_unapproved(self):
        handoff, rows, owner = fixture()
        package = build_promotion_preflight(handoff, copy.deepcopy(handoff['canonical_readback']),
                                             {'snapshot_sha256': SHA, 'observations': rows,
                                             'gaps': []}, owner,
                                             expected_owner_revision=1,
                                             expected_production_revision=0,
                                             production_history={'schema': 'retained-aida-target-state/v1',
                                                                 'snapshot_sha256': SHA,
                                                                 'revision': 0, 'events': [],
                                                                 'source_rows': [],
                                                                 'non_source_row_count': 0})
        self.assertEqual([event['action'] for event in package['canonical_replay']],
                         ['accept', 'accept', 'reverse'])
        self.assertEqual(package['canonical_replay'][-1]['event-id'], 'edge-1')
        self.assertEqual(package['expected_production_revision'], 0)
        self.assertEqual(package['owner']['store_revision'], 1)
        self.assertEqual(package['owner']['status'], 'requires_reconciliation_binding')
        self.assertEqual(len(package['owner']['candidate_intents']), 1)
        self.assertEqual(package['owner']['candidate_intents'][0]['status'], 'pending')

    def test_missing_source_or_stale_authority_stops_before_package(self):
        handoff, rows, owner = fixture()
        current = copy.deepcopy(handoff['canonical_readback'])
        loaded = {'snapshot_sha256': SHA, 'observations': rows, 'gaps': []}
        history = {'schema': 'retained-aida-target-state/v1',
                   'snapshot_sha256': SHA, 'revision': 0, 'events': [],
                   'source_rows': [], 'non_source_row_count': 0}
        for mutate in (
            lambda h, c, l, o, p: l['observations'].pop(),
            lambda h, c, l, o, p: c['events'].pop(),
            lambda h, c, l, o, p: o.update(store_revision=2),
            lambda h, c, l, o, p: o.update(proposal_count=1),
            lambda h, c, l, o, p: o['observation_refs'][RECORDS[0]].pop('source_derived_ref'),
            lambda h, c, l, o, p: p.update(revision=1, events=[{'id': 'other'}]),
            lambda h, c, l, o, p: p.update(source_rows=[{'observation-id': 'other'}]),
            lambda h, c, l, o, p: l['observations'][0]['source_observation_ref'].update(
                observation_version='e' * 64),
        ):
            args = copy.deepcopy([handoff, current, loaded, owner, history])
            mutate(*args)
            with self.subTest(mutate=mutate), self.assertRaises(ValueError):
                build_promotion_preflight(*args[:4], expected_owner_revision=1,
                                          expected_production_revision=0,
                                          production_history=args[4])

    def test_private_cli_prints_only_checkpoint_and_reuses_exact_package(self):
        handoff, rows, owner = fixture()
        history = {'schema': 'retained-aida-target-state/v1',
                   'snapshot_sha256': SHA, 'revision': 0, 'events': [],
                   'source_rows': [], 'non_source_row_count': 0}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def checked(name, value):
                path = root / name
                data = json.dumps(value).encode()
                path.write_bytes(data)
                return str(path), hashlib.sha256(data).hexdigest()
            handoff_path, handoff_sha = checked('handoff.json', handoff)
            current_path, current_sha = checked('current.json', handoff['canonical_readback'])
            history_path, history_sha = checked('history.json', history)
            output = root / 'private' / 'package.json'
            argv = ['--handoff', handoff_path, '--handoff-sha256', handoff_sha,
                    '--current-readback', current_path,
                    '--current-readback-sha256', current_sha,
                    '--production-history', history_path,
                    '--production-history-sha256', history_sha,
                    '--expected-production-revision', '0', '--owner-db', str(root / 'owner.sqlite'),
                    '--expected-owner-revision', '1', '--snapshot-dir', str(root / 'snapshot'),
                    '--output', str(output)]
            stdout = io.StringIO()
            loaded = {'snapshot_sha256': SHA, 'observations': rows, 'gaps': []}
            with patch('scripts.retained_aida_promotion.load_source_observations',
                       return_value=loaded), patch('scripts.retained_aida_promotion._owner_state',
                                                   return_value=owner), redirect_stdout(stdout):
                self.assertEqual(main(argv), 0)
                self.assertEqual(main(argv), 0)
            report = stdout.getvalue()
            self.assertNotIn('Synthetic', report)
            self.assertNotIn(str(output), report)
            self.assertEqual(output.stat().st_mode & 0o777, 0o600)
            self.assertEqual(json.loads(output.read_text())['counts']['source_rows'], 3)


if __name__ == '__main__':
    unittest.main()
