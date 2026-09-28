"""Bounded, record-bound access to verified private PDF and JSON originals."""
import hashlib
import json
from pathlib import Path
import re
import subprocess

from private_source_bundle import verify

HEX = re.compile(r'[0-9a-f]{64}\Z')
ROW = re.compile(r'row index zero based ([0-9]{1,6})\Z')
MAX_SOURCE = 100 * 1024 * 1024
MAX_JSON_ROW = 128 * 1024
MAX_IMAGE = 2 * 1024 * 1024


class SourceViewError(Exception):
    def __init__(self, status):
        self.status = status


class OriginalSourceView:
    def __init__(self, bundle_dir, manifest_sha256, snapshot_sha256):
        self.bundle = Path(bundle_dir)
        if not HEX.fullmatch(manifest_sha256):
            raise ValueError('invalid source bundle digest')
        if hashlib.sha256((self.bundle / 'manifest.json').read_bytes()).hexdigest() != manifest_sha256:
            raise ValueError('source bundle manifest mismatch')
        manifest = verify(self.bundle)
        self.items = {item['id']: item for item in manifest['sources']}
        if not any(item.get('status') == 'included' and item['sha256'] == snapshot_sha256
                   and item.get('content_type') == 'application/vnd.sqlite3'
                   for item in self.items.values()):
            raise ValueError('source bundle does not contain the selected snapshot')

    def _source(self, detail):
        if detail is None:
            raise SourceViewError(404)
        if detail.get('kind') != 'candidate_position':
            raise SourceViewError(422)
        item = self.items.get(detail.get('source_object_id'))
        if item is None:
            raise SourceViewError(404)
        if item.get('status') != 'included':
            raise SourceViewError(403)
        content_type = item.get('content_type', '').split(';', 1)[0].lower()
        if content_type not in ('application/pdf', 'application/json'):
            raise SourceViewError(415)
        if item.get('bytes', MAX_SOURCE + 1) > MAX_SOURCE:
            raise SourceViewError(413)
        path = self.bundle / 'objects' / item['sha256']
        if path.is_symlink():
            raise SourceViewError(503)
        try:
            if not path.is_file() or path.stat().st_size != item['bytes']:
                raise SourceViewError(503)
            data = path.read_bytes()
        except OSError as exc:
            raise SourceViewError(503) from exc
        if len(data) != item['bytes'] or hashlib.sha256(data).hexdigest() != item['sha256']:
            raise SourceViewError(503)
        return item, content_type, data

    def inspect(self, detail):
        item, content_type, data = self._source(detail)
        base = {'source_sha256': item['sha256'], 'source_bytes': item['bytes'],
                'snapshot_sha256': detail['snapshot_sha256'], 'citation': detail['citation'],
                'raw_fields': detail['raw_fields'], 'parsed_fields': detail['parsed_fields']}
        if content_type == 'application/json':
            citation = detail.get('citation')
            match = ROW.fullmatch(citation) if isinstance(citation, str) else None
            if not match:
                raise SourceViewError(422)
            index = int(match.group(1))
            try:
                source = json.loads(data)
            except (UnicodeError, ValueError) as exc:
                raise SourceViewError(503) from exc
            rows = source.get('data') if isinstance(source, dict) else None
            if not isinstance(rows, list) or index >= len(rows) or rows[index] != detail['raw_fields']:
                raise SourceViewError(422)
            row = rows[index]
            if len(json.dumps(row, ensure_ascii=False).encode('utf-8')) > MAX_JSON_ROW:
                raise SourceViewError(413)
            return {**base, 'format': 'json', 'locator': f'data[{index}]', 'source_value': row}
        citation = detail.get('citation')
        page = citation.get('page') if isinstance(citation, dict) else None
        if type(page) is not int or not 1 <= page <= 500 or page != detail.get('page'):
            raise SourceViewError(422)
        return {**base, 'format': 'pdf', 'page': page,
                'region': citation.get('region')}

    def page(self, detail, requested_page):
        info = self.inspect(detail)
        if info['format'] != 'pdf' or requested_page != info['page']:
            raise SourceViewError(404)
        _, _, data = self._source(detail)
        try:
            rendered = subprocess.run(['pdftoppm', '-f', str(requested_page), '-l', str(requested_page),
                                       '-scale-to', '1400', '-singlefile', '-png', '-'],
                                      input=data, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                      timeout=15, check=False)
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise SourceViewError(503) from exc
        if rendered.returncode or not rendered.stdout.startswith(b'\x89PNG\r\n\x1a\n'):
            raise SourceViewError(422)
        if len(rendered.stdout) > MAX_IMAGE:
            raise SourceViewError(413)
        return rendered.stdout
