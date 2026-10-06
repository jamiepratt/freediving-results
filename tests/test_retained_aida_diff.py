import copy
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from retained_aida_diff import compare, render, build


def fixture():
    headers = ('Start', 'Diver', 'Nationality', 'Gender', 'Discipline', 'OT', 'AP', 'RP', 'Card', 'Points', 'Remarks')
    raw = '<tr>' + ''.join('<td>' + v + '</td>' for v in ('1', '&lt;script&gt;Synthetic&lt;/script&gt;', 'POL', 'F', 'DNF', '08:30', '1 m', '75 m', 'RED', '0', 'DQ BO')) + '</tr>'
    source = ('<li class="active"><a class="days" id="day_5">2026-06-03</a></li><input id="day_nr" value="5"><table id=""><thead><tr>' + ''.join('<th>'+h+'</th>' for h in headers) + '</tr></thead><tbody id="body_ajax">'+raw+'</tbody></table>').encode()
    cells = ['1', '&lt;script&gt;Synthetic&lt;/script&gt;', 'POL', 'F', 'DNF', '08:30', '1 m', '75 m', 'RED', '0', 'DQ BO']
    parsed = {'source-name': '<script>Synthetic</script>', 'representation': 'POL', 'gender': 'F', 'discipline': 'DNF', 'event-date': '2026-06-03', 'performance': 75, 'announced': 1, 'card': 'RED', 'points': '0', 'remarks': 'DQ BO', 'penalty': None, 'rank': None}
    candidate = {'coordinates': {'table': 1, 'row': 2}, 'raw': {'html': raw, 'cells': cells, 'cell-html': ['<td>'+v+'</td>' for v in cells], 'fields': dict(zip(headers,cells))}, 'parsed': parsed, 'parse-status': 'parsed', 'review-status': 'unreviewed', 'flags': []}
    sha = hashlib.sha256(source).hexdigest()
    artifacts = [{'job-id': str(i)*64, 'parser-version':'aida-html/1', 'source-sha256':sha, 'raw-html':source.decode(), 'context': {'event-date':'2026-06-03'}, 'acquisitions':[{'manifest':{'sha256':sha,'provenance':{'browser-state':{'selected-date':'2026-06-03','filters':{}}}}}], 'candidates':[copy.deepcopy(candidate)]} for i in (1,2)]
    for artifact, artifact_sha in zip(artifacts, ['a'*64,'b'*64]):
        artifact['_references'] = {0:{'job-id':artifact['job-id'],'source-sha256':sha,'artifact-sha256':artifact_sha,'ordinal':0,'parser-version':'aida-html/1','candidate-id':'c'*64}}
    return source, artifacts, ['a'*64,'b'*64]


class RetainedAidaDiffTest(unittest.TestCase):
    def test_complete_comparison_preserves_red_zero_unknowns_and_safe_source(self):
        source, artifacts, shas = fixture()
        report = compare(source, artifacts, shas, expected_positions=1, expected_selected=1)
        self.assertEqual(1, report['summary']['selected_positions'])
        self.assertEqual(2, report['summary']['selected_versions'])
        self.assertEqual({'RED':1}, report['summary']['card_counts'])
        row = report['rows'][0]
        self.assertEqual('0', row['raw_fields']['Points'])
        self.assertIsNone(row['versions'][0]['parsed']['penalty'])
        self.assertEqual([], row['version_differences'])
        self.assertEqual('missing-later-exact-final-source', report['final_source_history']['status'])
        page = render(report)
        self.assertIn('&lt;script&gt;Synthetic&lt;/script&gt;', page)
        self.assertNotIn('<script>', page)
        self.assertIn('Publisher revision', page)
        self.assertEqual(page, render(json.loads(json.dumps(report,sort_keys=True))))

    def test_rejects_source_selector_coordinate_and_version_reference_mismatch(self):
        for mutation in ('source', 'selector', 'coordinate', 'reference'):
            source, artifacts, shas = fixture()
            if mutation == 'source':
                artifacts[0]['source-sha256'] = '0'*64
            elif mutation == 'selector':
                source = source.replace(b'day_5', b'day_4')
            elif mutation == 'coordinate':
                artifacts[1]['candidates'][0]['coordinates']['row'] = 3
            else:
                artifacts[1]['_references'][0]['artifact-sha256'] = '0'*64
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                compare(source, artifacts, shas, expected_positions=1, expected_selected=1)

    def test_parser_change_is_explicit_and_not_publisher_revision(self):
        source, artifacts, shas = fixture()
        artifacts[1]['candidates'][0]['parsed']['points'] = 0
        report = compare(source, artifacts, shas, expected_positions=1, expected_selected=1)
        self.assertEqual([{'field':'parsed.points','version_a':'0','version_b':0}],report['rows'][0]['version_differences'])
        self.assertIsNone(report['final_source_history']['publisher_revision'])

    def test_private_packet_pin_tamper_refuses_before_reading_artifacts(self):
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory)
            source=p/'source'; source.write_bytes(b'tampered')
            pins=p/'hashes.json'
            pins.write_text(json.dumps({'source':{'path':str(source),'bytes':8,'sha256':'0'*64}}))
            with self.assertRaisesRegex(ValueError,'input pin mismatch'):
                build(pins,hashlib.sha256(pins.read_bytes()).hexdigest())
            with self.assertRaisesRegex(ValueError,'pins hash mismatch'):
                build(pins,'0'*64)
