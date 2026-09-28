import hashlib
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))

from vestico_safe_derivative import canonical_bytes, derive


class VesticoSafeDerivativeTest(unittest.TestCase):
    def test_fixture_retains_only_visible_cited_results(self):
        source = (ROOT / 'test/resources/fixtures/vestico-2025/results.html').read_bytes()
        result = derive(source, source_id='sha256:' + hashlib.sha256(source).hexdigest(),
                        acquisition_id='1' * 64)
        self.assertEqual(result['source_sha256'], hashlib.sha256(source).hexdigest())
        self.assertEqual(result['selected_view']['discipline'], 'DYN')
        self.assertEqual([t['category'] for t in result['tables']], ['Results female', 'Results male'])
        self.assertEqual(result['tables'][0]['rows'][0]['citation'], {'table': 1, 'row': 2})
        self.assertEqual(result['tables'][0]['rows'][0]['cells'][3], 'Törőcsik Zsófia')
        self.assertEqual(sum(len(t['rows']) for t in result['tables']), 12)
        self.assertNotIn('<script', json.dumps(result).lower())

    def test_hidden_credentials_and_hostile_attributes_never_enter_derivative(self):
        source = (ROOT / 'test/resources/fixtures/vestico-2025/results.html').read_text()
        source = source.replace('</title>', '</title><script>window.session="SEED_SCRIPT_SECRET"</script>'
                                '<form><input type="hidden" name="csrf" value="SEED_CSRF_SECRET"></form>'
                                '<p hidden>token=SEED_HIDDEN_SECRET</p>', 1)
        source = source.replace('Törőcsik Zsófia</td>',
                                'Törőcsik Zsófia<img src="https://evil.test" onerror="SEED_EVENT_SECRET"></td>', 1)
        data = source.encode()
        result = derive(data, source_id='sha256:' + hashlib.sha256(data).hexdigest(),
                        acquisition_id='1' * 64)
        rendered = canonical_bytes(result)
        for secret in (b'SEED_SCRIPT_SECRET', b'SEED_CSRF_SECRET', b'SEED_HIDDEN_SECRET',
                       b'SEED_EVENT_SECRET', b'https://evil.test', b'<img', b'<form'):
            self.assertNotIn(secret, rendered)
        self.assertEqual(result['tables'][0]['rows'][0]['cells'][3], 'Törőcsik Zsófia')
        self.assertEqual(result['selected_view']['basis'], 'page_marker_only')

    def test_visible_secret_or_wrong_source_relation_fails_closed(self):
        source = (ROOT / 'test/resources/fixtures/vestico-2025/results.html').read_text()
        source = source.replace('Törőcsik Zsófia', 'token=SEED_VISIBLE_SECRET', 1)
        data = source.encode()
        with self.assertRaisesRegex(ValueError, 'unsafe visible result text'):
            derive(data, source_id='sha256:' + hashlib.sha256(data).hexdigest(),
                   acquisition_id='1' * 64)
        plain = (ROOT / 'test/resources/fixtures/vestico-2025/results.html').read_bytes()
        with self.assertRaisesRegex(ValueError, 'source id must match'):
            derive(plain, source_id='sha256:' + '0' * 64, acquisition_id='1' * 64)
        with self.assertRaisesRegex(ValueError, 'acquisition id'):
            derive(plain, source_id='sha256:' + hashlib.sha256(plain).hexdigest(),
                   acquisition_id='credential text')


if __name__ == '__main__':
    unittest.main()
