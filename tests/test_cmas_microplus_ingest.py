import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from cmas_microplus_ingest import build


class MicroplusIngestTest(unittest.TestCase):
    def test_retains_exact_source_row_and_rejects_identity_mismatch(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)

            def save(name, payload, url):
                raw = json.dumps(payload).encode()
                path = directory / name
                path.write_bytes(raw)
                path.with_suffix('.receipt.json').write_text(json.dumps({
                    'status': 200, 'sha256': hashlib.sha256(raw).hexdigest(),
                    'bytes': len(raw), 'final_url': url}))

            for cid in (28, 33, 34, 35):
                uid = 3500 + cid
                units = [{'UtID': uid, 'UtStartDate': '2026-08-07T00:00:00'}]
                if cid == 28:
                    units.append({'UtID': 3628, 'UtStartDate': '2026-08-07T00:00:00'})
                competition = {'CmpID': cid, 'CmpTitLongDescr': f'Event {cid}',
                               'CmpStartDate': '2026-08-07T00:00:00',
                               'CmpEndDate': '2026-08-11T00:00:00',
                               'Disciplines': [{'Categories': [{'Events': [{'Phases': [
                                   {'Units': units}
                               ]}]}]}]}
                save(f'competition-{cid}.json', [competition],
                     f'https://cmas-api.microplustimingservices.com/api/competitions/{cid}')
                save(f'competition-{cid}-documents.json',
                     [{'DocTypeCode': 'RES'}] if cid == 33 else [],
                     f'https://cmas-api.microplustimingservices.com/api/competitions/{cid}/documents')
                view_kind = 'cumulative' if cid in (28, 34) else 'schedule'
                save(f'competition-{cid}-{view_kind}.json',
                     [{'DCCmpID': cid, 'ResResultFinal': '0'}] if view_kind == 'cumulative'
                     else [{'UtID': uid}],
                     f'https://cmas-api.microplustimingservices.com/api/competitions/{cid}/{view_kind}')
                save(f'unit-{uid}-results.json',
                     [{'UtID': uid, 'DCCmpID': cid, 'ResID': uid, 'ResResultFinal': '0'}]
                     + ([{'UtID': 3628, 'DCCmpID': cid, 'ResID': 3628, 'ResResultFinal': '1'}]
                        if cid == 28 else []),
                     f'https://cmas-api.microplustimingservices.com/api/units/{uid}/results')
                if cid == 28:
                    save('unit-3628-results.json',
                         [{'UtID': 3628, 'DCCmpID': 28, 'ResID': 3628, 'ResResultFinal': '1'}],
                         'https://cmas-api.microplustimingservices.com/api/units/3628/results')
            pdf = b'%PDF-1.4\n'
            path = directory / 'competition-33-results.pdf'
            path.write_bytes(pdf)
            path.with_suffix('.receipt.json').write_text(json.dumps({
                'sha256': hashlib.sha256(pdf).hexdigest(), 'bytes': len(pdf),
                'final_url': 'https://example.org/results.pdf'}))

            packet = build(directory)
            self.assertEqual(5, len(packet['positions']))
            self.assertEqual(6, packet['counts']['transport_rows'])
            self.assertEqual(1, packet['counts']['repeated_transport_rows'])
            self.assertEqual(1, sum(len(row['alternate_citations']) for row in packet['positions']))
            self.assertEqual(2, len(packet['aggregate_rows']))
            self.assertEqual('0', packet['positions'][0]['raw_fields']['ResResultFinal'])
            self.assertEqual('/0', packet['positions'][0]['citation']['json_pointer'])
            self.assertEqual('sha256:' + hashlib.sha256(pdf).hexdigest(),
                             next(s['id'] for s in packet['sources'] if s['kind'] == 'result_pdf_supporting'))

            save('unit-3528-results.json', [{'UtID': 3528, 'DCCmpID': 999}],
                 'https://cmas-api.microplustimingservices.com/api/units/3528/results')
            with self.assertRaisesRegex(ValueError, 'row identity mismatch'):
                build(directory)


if __name__ == '__main__':
    unittest.main()
