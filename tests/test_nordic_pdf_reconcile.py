import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from nordic_pdf_reconcile import build, source_line
from cmas_microplus_finalize import build as finalize, sha
from unified_evidence_snapshot import validate_extension_packet


PRIVATE = Path('/Users/jamiep/.codex/private-corpora/issue55-cmas-microplus-20261001')


class NordicPdfReconcileTest(unittest.TestCase):
    def test_printed_fields_preserve_penalty_and_wrapped_medal(self):
        line = '  3       HOJGAARD Anni        DEN       81          74        8          66          PEN         MARKER'
        parsed = source_line(line, '                                                                                                EARLY TURN, NO',
                             '                                                                                                              BRONZE MEDAL')
        self.assertEqual(('81', '74', '8', '66', 'PEN', 'EARLY TURN, NO MARKER', 'BRONZE MEDAL'),
                         (parsed['declared_depth'], parsed['raw_result'], parsed['penalty'],
                          parsed['final_result'], parsed['status'], parsed['note'], parsed['medal']))
        self.assertEqual(None, source_line('FINAL RESULTS'))

    def test_real_retained_eight_page_source_when_available(self):
        pdf = PRIVATE / 'competition-33-results.pdf'
        packet = PRIVATE / 'cmas-microplus-packet.json'
        receipt = PRIVATE / 'competition-33-results.receipt.json'
        if not all(path.exists() for path in (pdf, packet, receipt)):
            self.skipTest('private retained Nordic source unavailable')
        result = build(pdf, packet, receipt)
        self.assertEqual([11, 5, 16, 6, 12, 8, 13, 5],
                         [page['printed_rows'] for page in result['pages']])
        self.assertEqual(76, len(result['positions']))
        self.assertEqual(76, len(result['relationships']))
        self.assertEqual(16, len(result['api_only']))
        self.assertEqual(43, result['counts']['different_printed_vs_unit_ranks'])
        final_path = PRIVATE / 'cmas-microplus-packet-v2.json'
        if final_path.exists():
            final = json.loads(final_path.read_bytes())
            validate_extension_packet('cmas-microplus-2026', final)
            final['relationships'] = final['relationships'][:-1]
            with self.assertRaisesRegex(ValueError, 'final census mismatch'):
                validate_extension_packet('cmas-microplus-2026', final)

    def test_finalize_rejects_missing_relationship(self):
        api = [{'id': f'api-{i}', 'raw_fields': {'DCCmpID': 33, 'ResID': i}}
               for i in range(92)]
        original = {'schema': 'cmas-microplus-private-census/v1',
                    'sources': [{'id': 'sha256:pdf', 'kind': 'result_pdf_supporting'}],
                    'positions': api,
                    'gaps': [{'id': 'nordic-pdf-positions-unreconciled'}],
                    'counts': {'source_positions': 92}}
        pdf_rows = [{'id': f'pdf-{i}', 'source_object_id': 'sha256:pdf'} for i in range(76)]
        links = [{'pdf_position_id': f'pdf-{i}', 'api_position_id': f'api-{i}', 'api_res_id': i}
                 for i in range(76)]
        ledger = {'schema': 'nordic-final-pdf-reconciliation/v1',
                  'source': {'id': 'sha256:pdf'}, 'source_sha256': 'pdf',
                  'positions': pdf_rows, 'relationships': links,
                  'api_only': [{'id': f'api-{i}'} for i in range(76, 92)],
                  'counts': {'different_printed_vs_unit_ranks': 0}}
        with tempfile.TemporaryDirectory() as tmp:
            p1, p2 = Path(tmp) / 'initial.json', Path(tmp) / 'ledger.json'
            p1.write_text(json.dumps(original))
            p2.write_text(json.dumps(ledger))
            result = finalize(p1, p2, sha(p1.read_bytes()), sha(p2.read_bytes()))
            self.assertEqual(168, result['counts']['source_positions'])
            self.assertEqual([], result['gaps'])
            ledger['relationships'] = links[:-1]
            p2.write_text(json.dumps(ledger))
            with self.assertRaisesRegex(ValueError, 'relationship mismatch'):
                finalize(p1, p2, sha(p1.read_bytes()), sha(p2.read_bytes()))


if __name__ == '__main__':
    unittest.main()
