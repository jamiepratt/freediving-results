import hashlib
import copy
import json
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

from tests.test_retained_aida_owner_bridge import inputs


ROOT = Path(__file__).resolve().parents[1]


class RetainedAidaFlowTest(unittest.TestCase):
    def _package(self):
        package, loaded, _, _, _ = inputs()
        by_id = {'source-observation:' + row['snapshot_record_id']: row
                 for row in loaded['observations']}
        for row in package['source_rows']:
            source = by_id[row['observation-id']]
            person = source['source_fields']['publisher_person']
            row.update({'source-name': source['source_fields']['name'],
                        'parse-status': 'parsed', 'publisher-scope': person['scope'],
                        'publisher-athlete-id': person['id'],
                        'publisher-id-kind': person['id_kind']})
        return package

    def test_exact_active_intent_generates_private_source_decision_and_flow(self):
        package = self._package()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / 'preflight.json'
            output = root / 'flow.json'
            source.write_text(json.dumps(package))
            digest = hashlib.sha256(source.read_bytes()).hexdigest()
            run = subprocess.run(['clojure', '-M', '-m', 'freediving.retained-aida-flow',
                                  str(source), digest, str(output)], cwd=ROOT,
                                 capture_output=True, text=True)
            self.assertEqual(run.returncode, 0, run.stderr)
            result = json.loads(output.read_text())
            self.assertEqual(stat.S_IMODE(output.stat().st_mode), 0o600)
            self.assertEqual(result['schema'], 'retained-aida-flow-export/v1')
            self.assertEqual(result['counts']['active_intents'], 1)
            self.assertEqual(result['counts']['supported'], 1)
            self.assertEqual(result['counts']['unresolved'], 0)
            self.assertEqual(result['flow']['version'], 'reconciliation-flow/1')
            self.assertEqual(result['flow']['events'][0]['reason'], 'source-context-unverified')
            decision = result['decisions'][0]
            self.assertEqual(decision['subject']['pair'], package['owner']['candidate_intents'][0]['pair'])
            self.assertEqual(len(decision['candidates']), len(decision['evidence']))

    def test_full_candidate_group_is_kept(self):
        package = self._package()
        pair = package['owner']['candidate_intents'][0]['pair']
        extra = copy.deepcopy(next(row for row in package['source_rows']
                                   if row['observation-id'] == pair[1]))
        extra['observation-id'] = 'source-observation:' + '4' * 64
        extra['source-observation-ref']['snapshot_record_id'] = '4' * 64
        extra['citation']['snapshot_record_id'] = '4' * 64
        package['source_rows'].append(extra)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / 'preflight.json'
            output = root / 'flow.json'
            source.write_text(json.dumps(package))
            digest = hashlib.sha256(source.read_bytes()).hexdigest()
            run = subprocess.run(['clojure', '-M', '-m', 'freediving.retained-aida-flow',
                                  str(source), digest, str(output)], cwd=ROOT,
                                 capture_output=True, text=True)
            self.assertEqual(run.returncode, 0, run.stderr)
            result = json.loads(output.read_text())
            self.assertEqual(result['counts']['supported'], 1)
            self.assertEqual(len(result['decisions'][0]['candidates']), 4)
            self.assertEqual(len(result['flow']['events'][0]['evidence']), 4)

    def test_wrong_preflight_hash_leaves_no_output(self):
        package = self._package()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / 'preflight.json'
            output = root / 'flow.json'
            source.write_text(json.dumps(package))
            run = subprocess.run(['clojure', '-M', '-m', 'freediving.retained-aida-flow',
                                  str(source), '0' * 64, str(output)], cwd=ROOT,
                                 capture_output=True, text=True)
            self.assertNotEqual(run.returncode, 0)
            self.assertFalse(output.exists())


if __name__ == '__main__':
    unittest.main()
