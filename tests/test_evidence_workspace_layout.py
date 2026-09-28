import json
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
CHROME = Path('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome')


@unittest.skipUnless(CHROME.exists(), 'Chrome unavailable for layout check')
class WorkspaceLayoutTest(unittest.TestCase):
    def test_overview_labels_do_not_cover_values(self):
        css = (ROOT / 'resources/evidence_workspace.css').read_text()
        long_label = 'ffessm_printed_field_correspondences'
        fixtures = ''.join(
            f'<div class="fixture" style="width:{width}px"><dl><dt>{long_label}</dt><dd>56</dd></dl></div>'
            for width in (280, 440, 1000)
        )
        script = '''
          const results = [...document.querySelectorAll('.fixture')].map(fixture => {
            const dt = fixture.querySelector('dt');
            const dd = fixture.querySelector('dd');
            const range = document.createRange();
            range.selectNodeContents(dt);
            const labelRight = Math.max(...[...range.getClientRects()].map(rect => rect.right));
            return {width: fixture.style.width, labelRight, valueLeft: dd.getBoundingClientRect().left,
                    labelLines: range.getClientRects().length,
                    labelBottom: dt.getBoundingClientRect().bottom,
                    valueTop: dd.getBoundingClientRect().top};
          });
          document.getElementById('measure').textContent = JSON.stringify({viewport: innerWidth, results});
        '''
        with tempfile.TemporaryDirectory() as directory:
            page = Path(directory) / 'layout.html'
            page.write_text(f'<meta charset="utf-8"><style>{css}</style>{fixtures}<output id="measure"></output><script>{script}</script>')
            def measure(window_width):
                process = subprocess.Popen(
                    [str(CHROME), '--headless', '--disable-gpu', '--no-sandbox',
                     f'--window-size={window_width},800',
                     f'--user-data-dir={directory}/profile-{window_width}',
                     '--dump-dom', page.as_uri()],
                    stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                )
                try:
                    output, _ = process.communicate(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    output, _ = process.communicate()
                markup = output.decode()
                match = re.search(r'<output id="measure">(.*?)</output>', markup)
                self.assertIsNotNone(match, markup[-1000:])
                return json.loads(match.group(1))

            desktop = measure(1100)
            mobile = measure(500)
        self.assertGreater(desktop['viewport'], 640)
        for measured in desktop['results']:
            with self.subTest(width=measured['width']):
                self.assertLessEqual(measured['labelRight'], measured['valueLeft'], measured)
                if measured['width'] == '1000px':
                    self.assertEqual(measured['labelLines'], 1, measured)
        self.assertLessEqual(mobile['viewport'], 640)
        narrow = mobile['results'][0]
        self.assertLessEqual(narrow['labelBottom'], narrow['valueTop'], narrow)
