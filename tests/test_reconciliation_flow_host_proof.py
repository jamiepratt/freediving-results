import copy
import hashlib
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from reconciliation_flow_host_proof import Keyword, LedgerError, _edn_shape, pr_str, verify

SHA = 'a' * 64
BINDINGS = [{'evidence_id': i, 'snapshot_record_id': 'record',
             'observation_revision': {'source_sha256': SHA, 'snapshot_sha256': SHA, 'observation_version': i}}
            for i in ['a', 'b']]
BINDING = {'decision_id': 'attempt', 'reconciliation_event_id': 'proposal',
           'reconciliation_run_revision': 1, 'evidence_bindings': BINDINGS,
           'observation_revisions': [b['observation_revision'] for b in BINDINGS]}
PROPOSAL = _edn_shape({'id': 'proposal', 'decision-id': 'attempt',
                       'candidates': ['a', 'b'], 'dependencies': [],
                       'evidence': [{'evidence-id': b['evidence_id'],
                                     'canonical-subject': {
                                         'version': {'snapshot-record-id': 'record',
                                                     'observation-revision': b['observation_revision']},
                                         'source': {'sha256': SHA},
                                         'position': {'id': b['evidence_id'], 'locator': '/1'}},
                                     'citation': {'source-sha256': SHA,
                                                  'position-id': b['evidence_id'], 'locator': '/1'}}
                                    for b in BINDINGS]})
PROPOSAL.update({Keyword(':origin'): Keyword(':deterministic'),
                 Keyword(':family'): Keyword(':same-attempt'),
                 Keyword(':action'): Keyword(':same-attempt')})
HUMAN = _edn_shape({'id': 'owner-store:2', 'decision-id': 'attempt',
                   'actor': 'owner', 'reason': 'checked', 'remote-store-revision': 2,
                   'remote-binding-revision': 1, 'remote-snapshot-sha256': SHA,
                   'remote-event': {'id': 'owner-store:2', 'decision_id': 'attempt',
                                    'store_revision': 2, 'binding_revision': 1,
                                    'snapshot_sha256': SHA, 'action': 'approve',
                                    'actor': 'owner', 'reason': 'checked',
                                    'proposal': {'selected_option': 'same_attempt',
                                                 'canonical_binding': BINDING}}})
HUMAN.update({Keyword(':origin'): Keyword(':human'), Keyword(':status'): Keyword(':approved'),
              Keyword(':action'): Keyword(':same-attempt')})


class ImmutableProofTest(unittest.TestCase):
    def run_proof(self, events, revision=1):
        with tempfile.TemporaryDirectory() as directory:
            ledger = {Keyword(':version'): 'reconciliation-flow/1', Keyword(':events'): events}
            envelope = {Keyword(':ledger'): ledger,
                        Keyword(':sha256'): hashlib.sha256(pr_str(ledger).encode()).hexdigest()}
            path = Path(directory) / 'flow.edn'
            path.write_text(pr_str(envelope))
            return verify(path, 'attempt', revision, 'proposal', BINDINGS)

    def test_original_proposal_survives_bound_human_suffix(self):
        self.assertEqual({'verified': True, 'event_id': 'proposal', 'run_revision': 1},
                         self.run_proof([PROPOSAL, HUMAN]))

    def test_incompatible_history_cannot_reuse_original_proof(self):
        for location, value in [
                ([':origin'], Keyword(':deterministic')),
                ([':remote-store-revision'], 3),
                ([':remote-event', ':proposal', ':canonical_binding', ':evidence_bindings', 0,
                  ':observation_revision', ':observation_version'], 'changed')]:
            human = copy.deepcopy(HUMAN)
            cursor = human
            for key in location[:-1]:
                cursor = cursor[Keyword(key) if isinstance(key, str) else key]
            key = location[-1]
            cursor[Keyword(key)] = value
            with self.assertRaises(LedgerError):
                self.run_proof([PROPOSAL, human])
        for keys in [[':evidence', 0, ':citation', ':locator'],
                     [':evidence', 0, ':canonical-subject', ':version', ':observation-revision']]:
            original = copy.deepcopy(PROPOSAL)
            cursor = original
            for key in keys[:-1]:
                cursor = cursor[Keyword(key) if isinstance(key, str) else key]
            cursor.pop(Keyword(keys[-1]))
            with self.assertRaises(LedgerError):
                self.run_proof([original, HUMAN])
        replacement = dict(PROPOSAL, **{})
        replacement[Keyword(':id')] = 'replacement'
        with self.assertRaises(LedgerError):
            self.run_proof([PROPOSAL, HUMAN, replacement])

    def test_newest_human_correction_retains_original_proposal_binding(self):
        correction = copy.deepcopy(HUMAN)
        correction.update({Keyword(':id'): 'owner-store:3', Keyword(':remote-store-revision'): 3,
                           Keyword(':action'): Keyword(':distinct-attempts'),
                           Keyword(':correction'): _edn_shape({'action': 'distinct_attempts'})})
        correction[Keyword(':remote-event')].update(_edn_shape({
            'id': 'owner-store:3', 'store_revision': 3, 'action': 'correct',
            'correction': {'action': 'distinct_attempts'}}))
        self.assertEqual({'verified': True, 'event_id': 'proposal', 'run_revision': 1},
                         self.run_proof([PROPOSAL, HUMAN, correction]))

    def test_proof_does_not_relabel_human_revision_or_accept_missing_dependencies(self):
        self.assertEqual({'verified': True, 'event_id': 'proposal', 'run_revision': 1},
                         self.run_proof([PROPOSAL]))
        with self.assertRaises(LedgerError):
            self.run_proof([PROPOSAL, HUMAN], revision=2)
        original = copy.deepcopy(PROPOSAL)
        original[Keyword(':dependencies')] = ['missing-decision']
        with self.assertRaises(LedgerError):
            self.run_proof([original])


if __name__ == '__main__':
    unittest.main()
