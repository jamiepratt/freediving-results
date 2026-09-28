"""Bounded, record-bound access to verified private PDF, JPEG and JSON originals."""
import hashlib
import json
from pathlib import Path
import re
import resource
import subprocess
import sys
import tempfile

from private_source_bundle import verify
from vestico_safe_derivative import (HEADERS as VESTICO_HEADERS, PARSER_VERSION as VESTICO_PARSER,
                                     SCHEMA as VESTICO_SCHEMA, VIEWS as VESTICO_VIEWS)

HEX = re.compile(r'[0-9a-f]{64}\Z')
ROW = re.compile(r'row index zero based ([0-9]{1,6})\Z')
HTML_ROW = re.compile(r'table ([12]) row ([1-9][0-9]{0,3})\Z')
FFESSM_LINE = re.compile(r'page ([1-9][0-9]{0,2}) line ([1-9][0-9]{0,3})(?: column start ([1-9][0-9]{0,3}) column end ([1-9][0-9]{0,3}))?\Z')
MAX_SOURCE = 100 * 1024 * 1024
MAX_JPEG = 2 * 1024 * 1024
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
        self.items_by_sha256 = {item['sha256']: item for item in manifest['sources']
                                if item.get('sha256') and item.get('status') in ('included', 'restricted')}
        if not any(item.get('status') == 'included' and item['sha256'] == snapshot_sha256
                   and item.get('content_type') == 'application/vnd.sqlite3'
                   for item in self.items.values()):
            raise ValueError('source bundle does not contain the selected snapshot')

    def item_for(self, source_object_id):
        if not isinstance(source_object_id, str):
            return None
        return self.items.get(source_object_id) or (
            self.items_by_sha256.get(source_object_id[7:])
            if source_object_id.startswith('sha256:') else None)

    def _source(self, detail):
        if detail is None:
            raise SourceViewError(404)
        item = self.item_for(detail.get('source_object_id'))
        if item is None:
            raise SourceViewError(404)
        if item.get('status') != 'included':
            raise SourceViewError(403)
        content_type = item.get('content_type', '').split(';', 1)[0].lower()
        if content_type == 'application/octet-stream' and detail.get('source_schema') == 'ffessm-2025-daily/v1':
            content_type = 'application/pdf'
        if content_type not in ('application/pdf', 'application/json', 'image/jpeg'):
            raise SourceViewError(415)
        if item.get('bytes', MAX_SOURCE + 1) > MAX_SOURCE:
            raise SourceViewError(413)
        if content_type == 'image/jpeg' and item['bytes'] >= MAX_JPEG:
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
        if content_type == 'application/pdf' and not data.startswith(b'%PDF-'):
            raise SourceViewError(422)
        if content_type == 'image/jpeg' and (not data.startswith(b'\xff\xd8\xff') or not data.endswith(b'\xff\xd9')):
            raise SourceViewError(422)
        return item, content_type, data

    def inspect(self, detail):
        if detail is None:
            raise SourceViewError(404)
        item = self.item_for(detail.get('source_object_id'))
        if detail.get('source_schema') == 'aida-selected-html-packet/v1':
            return self._aida_packet(detail, item)
        if item is not None and item.get('status') == 'restricted' and item.get('derivative'):
            return self._safe_html_derivative(detail, item)
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
            roatan = detail.get('source_schema') == 'roatan-2026-cwt-men-private-census/v1'
            eindhoven = detail.get('source_schema') == 'eindhoven-2026-noxy-private-accounting/v1'
            if roatan:
                raw = detail.get('raw') or {}
                index = citation.get('row-index-zero-based') if isinstance(citation, dict) else None
                if (type(index) is not int or not 0 <= index <= 999
                        or citation.get('unit') != raw.get('unit')
                        or citation.get('source-sha256') != item['sha256']
                        or raw.get('source_sha256') != item['sha256']
                        or raw.get('json_index_zero_based') != index
                        or detail.get('source_object_id') != 'sha256:' + item['sha256']):
                    raise SourceViewError(422)
            elif eindhoven:
                pointer = citation.get('json_pointer') if isinstance(citation, dict) else None
                source_hash = citation.get('source_sha256') if isinstance(citation, dict) else None
                if (not isinstance(pointer, str) or not re.fullmatch(r'/(?:rows/)?(?:0|[1-9][0-9]{0,5})', pointer)
                        or source_hash != item['sha256']
                        or detail.get('source_object_id') != 'sha256:' + item['sha256']
                        or detail.get('raw', {}).get('citation') != citation
                        or detail.get('raw', {}).get('json_index_zero_based') != int(pointer.rsplit('/', 1)[-1])):
                    raise SourceViewError(422)
                index = int(pointer.rsplit('/', 1)[-1])
            elif match:
                index = int(match.group(1))
            else:
                raise SourceViewError(422)
            try:
                source = json.loads(data)
            except (UnicodeError, ValueError) as exc:
                raise SourceViewError(503) from exc
            rows = (source if (roatan or eindhoven and not pointer.startswith('/rows/')) and isinstance(source, list)
                    else source.get('rows') if eindhoven and isinstance(source, dict) and pointer.startswith('/rows/')
                    else source.get('data') if isinstance(source, dict) and not roatan and not eindhoven else None)
            if not isinstance(rows, list) or index >= len(rows) or rows[index] != detail['raw_fields']:
                raise SourceViewError(422)
            row = rows[index]
            if len(json.dumps(row, ensure_ascii=False).encode('utf-8')) > MAX_JSON_ROW:
                raise SourceViewError(413)
            locator = pointer if eindhoven else f'[{index}]' if roatan else f'data[{index}]'
            return {**base, 'format': 'json', 'locator': locator, 'source_value': row}
        if content_type == 'image/jpeg':
            citation = detail.get('citation')
            raw = detail.get('raw') or {}
            bbox = citation.get('bbox') if isinstance(citation, dict) else None
            if (detail.get('source_schema') != 'san-mauro-jpg-supplement/v1'
                    or detail.get('kind') not in ('candidate_position', 'aggregate')
                    or detail.get('source_object_id') != 'sha256:' + item['sha256']
                    or not isinstance(citation, dict)
                    or citation.get('source_sha256') != item['sha256']
                    or raw.get('citation') != citation
                    or not isinstance(bbox, list) or len(bbox) != 4
                    or any(type(v) is not int or not 0 <= v <= 20000 for v in bbox)
                    or bbox[0] >= bbox[2] or bbox[1] >= bbox[3]
                    or type(citation.get('printed_row')) is not int
                    or not 1 <= citation['printed_row'] <= 1000
                    or not isinstance(citation.get('region_id'), str)
                    or not 1 <= len(citation['region_id']) <= 32):
                raise SourceViewError(422)
            return {**base, 'format': 'jpeg', 'original_replay': 'verified_original'}
        citation = detail.get('citation')
        if detail.get('source_schema') in ('ffessm-2025-rankings/v1', 'ffessm-2025-daily/v1'):
            raw = detail.get('raw') or {}
            match = FFESSM_LINE.fullmatch(citation) if isinstance(citation, str) else None
            coords = raw.get('coordinates') or {}
            if (not match or raw.get('citation') != citation
                    or raw.get('source_object_id', detail.get('source_object_id')) != detail.get('source_object_id')
                    or detail.get('source_object_id') != 'sha256:' + item['sha256']
                    or coords.get('page') != int(match.group(1))
                    or coords.get('line') != int(match.group(2))
                    or (match.group(3) is not None and (coords.get('column-start') != int(match.group(3))
                        or coords.get('column-end') != int(match.group(4))))):
                raise SourceViewError(422)
            page = int(match.group(1))
        else:
            page = citation.get('page') if isinstance(citation, dict) else None
        if type(page) is not int or not 1 <= page <= 500 or page != detail.get('page'):
            raise SourceViewError(422)
        return {**base, 'format': 'pdf', 'page': page,
                'region': citation.get('region') if isinstance(citation, dict) else citation}

    def _aida_packet(self, detail, original):
        if detail.get('kind') != 'candidate_position' or original is None or original.get('status') != 'restricted':
            raise SourceViewError(422)
        relation = original.get('derivative')
        packet_item = self.items.get(relation)
        if not isinstance(relation, str) or packet_item is None or packet_item.get('status') != 'included':
            raise SourceViewError(503)
        path = self.bundle / 'objects' / packet_item['sha256']
        try:
            if path.is_symlink() or not path.is_file() or path.stat().st_size != packet_item['bytes']:
                raise SourceViewError(503)
            data = path.read_bytes()
        except OSError as exc:
            raise SourceViewError(503) from exc
        if hashlib.sha256(data).hexdigest() != packet_item['sha256']:
            raise SourceViewError(503)
        try:
            packet = json.loads(data)
            index = int(re.fullmatch(r'positions\[(0|[1-9][0-9]{0,5})\]', detail['record_path']).group(1))
            position = packet['positions'][index]
            citation = detail['citation']
            if (packet.get('schema') != 'aida-selected-html-packet/v1'
                    or packet['source']['sha256'] != original['sha256']
                    or detail['source_object_id'] != 'sha256:' + original['sha256']
                    or position['position'] != citation
                    or position['cells'] != detail['raw_fields']
                    or citation['date'] != packet['source']['selected_date']):
                raise SourceViewError(422)
        except (ValueError, TypeError, KeyError, IndexError, AttributeError) as exc:
            raise SourceViewError(422) from exc
        return {'format': 'cited_html_packet', 'source_sha256': original['sha256'],
                'derivative_sha256': packet_item['sha256'], 'citation': citation,
                'source_value': position['cells'], 'raw_fields': detail['raw_fields'],
                'parsed_fields': detail['parsed_fields'], 'snapshot_sha256': detail['snapshot_sha256'],
                'original_replay': 'restricted_original_required'}

    def _safe_html_derivative(self, detail, original):
        if detail.get('kind') != 'candidate_position':
            raise SourceViewError(422)
        citation = detail.get('citation')
        match = HTML_ROW.fullmatch(citation) if isinstance(citation, str) else None
        if not match:
            raise SourceViewError(422)
        relation = original['derivative']
        expected_id = 'safe-derivative:' + original['sha256']
        if not isinstance(relation, dict) or relation.get('schema') != VESTICO_SCHEMA or relation.get('id') != expected_id:
            raise SourceViewError(503)
        derivative = self.items.get(expected_id)
        if (derivative is None or derivative.get('status') != 'included'
                or derivative.get('content_type') != 'application/json'
                or derivative.get('sha256') != relation.get('sha256')
                or not HEX.fullmatch(derivative.get('sha256', ''))
                or type(derivative.get('bytes')) is not int or derivative['bytes'] > MAX_SOURCE):
            raise SourceViewError(503)
        path = self.bundle / 'objects' / derivative['sha256']
        try:
            if path.is_symlink() or not path.is_file() or path.stat().st_size != derivative['bytes']:
                raise SourceViewError(503)
            data = path.read_bytes()
        except OSError as exc:
            raise SourceViewError(503) from exc
        if hashlib.sha256(data).hexdigest() != derivative['sha256']:
            raise SourceViewError(503)
        try:
            safe = json.loads(data)
            tables = safe['tables']
            table = tables[int(match.group(1)) - 1]
            row = next(x for x in table['rows'] if x.get('citation') ==
                       {'table': int(match.group(1)), 'row': int(match.group(2))})
            cells = row['cells']
        except (ValueError, TypeError, KeyError, IndexError, AttributeError, StopIteration) as exc:
            raise SourceViewError(422) from exc
        expected_view = VESTICO_VIEWS.get(original['sha256'])
        if (not isinstance(safe, dict) or safe.get('schema') != VESTICO_SCHEMA
                or safe.get('parser_version') != VESTICO_PARSER
                or expected_view is None
                or safe.get('selected_view') != {'discipline': expected_view[0], 'label': expected_view[2],
                                                 'basis': 'source_hash_and_page_marker'}
                or safe.get('source_sha256') != original['sha256']
                or safe.get('source_object_id') != original['id']
                or safe.get('acquisition_id') != (original.get('receipt') or {}).get('acquisition_id')
                or not isinstance(tables, list) or len(tables) != 2
                or table.get('table') != int(match.group(1))
                or table.get('headers') != VESTICO_HEADERS
                or not isinstance(cells, list) or len(cells) != len(VESTICO_HEADERS)
                or not all(isinstance(cell, str) and len(cell) <= 4096 for cell in cells)
                or not isinstance(detail.get('raw_fields'), dict)
                or dict(zip(VESTICO_HEADERS, cells)) != {
                    key: ' '.join(value.split()) if isinstance(value, str) else value
                    for key, value in detail['raw_fields'].items()}
                or (detail.get('source_sha256') and detail['source_sha256'] != original['sha256'])):
            raise SourceViewError(422)
        return {'format': 'safe_html_derivative', 'source_sha256': original['sha256'],
                'derivative_sha256': derivative['sha256'], 'parser_version': safe.get('parser_version'),
                'selected_view': safe.get('selected_view'), 'heading': table.get('heading'),
                'category': table.get('category'), 'citation': citation,
                'source_value': dict(zip(VESTICO_HEADERS, cells)),
                'raw_fields': detail['raw_fields'], 'parsed_fields': detail['parsed_fields'],
                'snapshot_sha256': detail['snapshot_sha256'],
                'original_replay': 'restricted_original_required'}

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

    def image(self, detail):
        if self.inspect(detail)['format'] != 'jpeg':
            raise SourceViewError(404)
        return self._source(detail)[2]
