"""Bounded, record-bound access to verified private PDF and JSON originals."""
import hashlib
import json
from pathlib import Path
import re
import resource
import subprocess
import sys
import tempfile

from private_source_bundle import verify

HEX = re.compile(r'[0-9a-f]{64}\Z')
ROW = re.compile(r'row index zero based ([0-9]{1,6})\Z')
MAX_SOURCE = 100 * 1024 * 1024
MAX_JSON_ROW = 128 * 1024
MAX_IMAGE = 2 * 1024 * 1024
RECEIPT_FIELDS = ('discovery_url', 'final_url', 'retrieved_at', 'selected_view')


def _limit_renderer():
    _, hard = resource.getrlimit(resource.RLIMIT_FSIZE)
    limit = min(MAX_IMAGE, hard) if hard != resource.RLIM_INFINITY else MAX_IMAGE
    resource.setrlimit(resource.RLIMIT_FSIZE, (limit, hard))
    _, cpu_hard = resource.getrlimit(resource.RLIMIT_CPU)
    cpu_limit = min(10, cpu_hard) if cpu_hard != resource.RLIM_INFINITY else 10
    resource.setrlimit(resource.RLIMIT_CPU, (cpu_limit, cpu_hard))
    if sys.platform.startswith('linux'):
        _, memory_hard = resource.getrlimit(resource.RLIMIT_AS)
        memory_limit = min(1024 * 1024 * 1024, memory_hard) if memory_hard != resource.RLIM_INFINITY else 1024 * 1024 * 1024
        resource.setrlimit(resource.RLIMIT_AS, (memory_limit, memory_hard))


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
        receipt = item.get('receipt') or {}
        safe_receipt = {key: receipt[key] for key in RECEIPT_FIELDS
                        if isinstance(receipt.get(key), str) and len(receipt[key]) <= 2048}
        base = {'source_sha256': item['sha256'], 'source_bytes': item['bytes'],
                'receipt': safe_receipt,
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
            with tempfile.TemporaryFile(mode='w+b') as output:
                rendered = subprocess.run(['pdftoppm', '-f', str(requested_page), '-l', str(requested_page),
                                           '-scale-to', '1400', '-singlefile', '-png', '-'],
                                          input=data, stdout=output, stderr=subprocess.DEVNULL,
                                          timeout=15, check=False, preexec_fn=_limit_renderer)
                size = output.seek(0, 2)
                if size >= MAX_IMAGE:
                    raise SourceViewError(413)
                output.seek(0)
                image = output.read(MAX_IMAGE)
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise SourceViewError(503) from exc
        if rendered.returncode or not image.startswith(b'\x89PNG\r\n\x1a\n'):
            raise SourceViewError(422)
        return image
