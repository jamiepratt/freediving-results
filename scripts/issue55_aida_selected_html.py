#!/usr/bin/env python3
"""Build a private, cited source-position packet from one AIDA selected-date HTML response."""

import argparse
import hashlib
import html
import json
import os
import re
import tempfile
from datetime import date, datetime
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlsplit


SCHEMA = 'aida-selected-html-packet/v1'
HEADERS = ('Start', 'Diver', 'Nationality', 'Gender', 'Discipline', 'OT',
           'AP', 'RP', 'Card', 'Points', 'Remarks')
TAG = re.compile(r'<[^>]*>', re.S)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def attr(open_tag, name):
    match = re.search(r'\b' + re.escape(name) + r'\s*=\s*(["\'])(.*?)\1', open_tag, re.I | re.S)
    return html.unescape(match.group(2)) if match else None


def one_element(source, tag, element_id):
    opening = list(re.finditer(r'<' + tag + r'\b[^>]*>', source, re.I | re.S))
    selected = [match for match in opening if attr(match.group(), 'id') == element_id]
    require(len(selected) == 1, f'exactly one {tag}#{element_id} required')
    start = selected[0].start()
    close = re.search(r'</' + tag + r'\s*>', source[selected[0].end():], re.I)
    require(close is not None, f'truncated {tag}#{element_id}')
    end = selected[0].end() + close.end()
    fragment = source[start:end]
    require(len(re.findall(r'<' + tag + r'\b', fragment, re.I)) == 1,
            f'nested {tag} unsupported')
    return fragment, start


class Text(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts = []

    def handle_data(self, data):
        self.parts.append(data)

    def handle_entityref(self, name):
        self.parts.append(html.unescape('&' + name + ';'))

    def handle_charref(self, name):
        self.parts.append(html.unescape('&#' + name + ';'))


def cell(source):
    opening = re.match(r'<td\b[^>]*>', source, re.I | re.S)
    require(opening is not None and re.search(r'</td\s*>\Z', source, re.I), 'malformed td')
    inner = re.sub(r'</td\s*>\Z', '', source[opening.end():], flags=re.I)
    raw_text = TAG.sub('', inner)
    parser = Text()
    parser.feed(inner)
    return {'source_html': source, 'raw_text': raw_text,
            'decoded_text': ''.join(parser.parts),
            'value': ' '.join(''.join(parser.parts).split())}


def chunks(source, tag):
    opens = list(re.finditer(r'<' + tag + r'\b[^>]*>', source, re.I | re.S))
    closes = list(re.finditer(r'</' + tag + r'\s*>', source, re.I))
    require(len(opens) == len(closes), f'truncated {tag} sequence')
    chunks_ = []
    for opening, closing in zip(opens, closes):
        require(opening.end() <= closing.start(), f'malformed {tag} order')
        chunks_.append(source[opening.start():closing.end()])
    require(not any(re.search(r'<' + tag + r'\b', part[1:], re.I) for part in chunks_),
            f'nested {tag} unsupported')
    return chunks_


class ActiveDays(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.li = []
        self.anchor = None
        self.active = []

    def handle_starttag(self, tag, attrs):
        values = dict(attrs)
        if tag == 'li':
            self.li.append('active' in values.get('class', '').split())
        elif tag == 'a' and self.li and self.li[-1] and 'days' in values.get('class', '').split():
            self.anchor = [values.get('id'), []]

    def handle_data(self, data):
        if self.anchor is not None:
            self.anchor[1].append(data)

    def handle_endtag(self, tag):
        if tag == 'a' and self.anchor is not None:
            self.active.append((self.anchor[0], ' '.join(''.join(self.anchor[1]).split())))
            self.anchor = None
        elif tag == 'li' and self.li:
            self.li.pop()


def active_view(source, selector, selected_date):
    parser = ActiveDays()
    parser.feed(source)
    require(parser.active == [(selector, selected_date)], 'active selected date/selector mismatch')


def validate_receipt(receipt, source_path, source_bytes):
    require(receipt.get('schema') == 'aida-selected-html-browser-receipt/v1', 'receipt schema mismatch')
    require(receipt.get('http_status') == 200, 'receipt HTTP status must be 200')
    require((receipt.get('content_type') or '').lower().startswith('text/html'), 'receipt content type must be HTML')
    for field in ('requested_url', 'final_url'):
        parts = urlsplit(receipt.get(field) or '')
        require(parts.scheme == 'https' and parts.netloc == 'www.aidainternational.org'
                and parts.path.startswith('/EventPage/'), f'invalid {field}')
    require(receipt['final_url'] == receipt['source_citation']['url'], 'source citation URL mismatch')
    require(receipt['selected_view']['date'] == receipt['source_citation']['selected_date'],
            'source citation date mismatch')
    require(receipt['source_citation']['table'] == 'table_ajax'
            and receipt['source_citation']['tbody'] == 'body_ajax', 'unsupported result table')
    try:
        date.fromisoformat(receipt['selected_view']['date'])
        at = datetime.fromisoformat(receipt['response_time'].replace('Z', '+00:00'))
    except (ValueError, KeyError, TypeError) as exc:
        raise ValueError('invalid selected date or response time') from exc
    require(at.tzinfo is not None and at.utcoffset().total_seconds() == 0,
            'response time must be UTC')
    require(re.fullmatch(r'day_[1-9][0-9]*', receipt['selected_view']['selector']) is not None,
            'invalid selected selector')
    body = receipt['body']
    require((source_path.parent.parent / body['path']).resolve() == source_path.resolve(),
            'source path differs from receipt')
    require(type(body.get('bytes')) is int and len(source_bytes) == body['bytes'],
            'source bytes differ from receipt')
    sha = hashlib.sha256(source_bytes).hexdigest()
    require(sha == body.get('sha256'), 'source sha256 differs from receipt')
    return sha


def build(source_path, receipt_path):
    receipt = json.loads(receipt_path.read_text(encoding='utf-8'))
    source_bytes = source_path.read_bytes()
    source_hash = validate_receipt(receipt, source_path, source_bytes)
    source = source_bytes.decode('utf-8')
    selected = receipt['selected_view']
    active_view(source, selected['selector'], selected['date'])
    table, table_start = one_element(source, 'table', 'table_ajax')
    table_number = 1 + len(re.findall(r'<table\b', source[:table_start], re.I))
    tbody, _ = one_element(table, 'tbody', 'body_ajax')
    headers = re.findall(r'<th\b[^>]*>(.*?)</th\s*>', table[:table.index(tbody)], re.I | re.S)
    names = tuple(' '.join(html.unescape(TAG.sub('', item)).split()) for item in headers)
    require(names == HEADERS, 'unsupported table header/width')
    rows = chunks(tbody, 'tr')
    require(rows, 'selected table has no data rows')
    positions = []
    for ordinal, row in enumerate(rows, 1):
        cells = chunks(row, 'td')
        position = {'table': 'table_ajax', 'table_number': table_number,
                    'tbody': 'body_ajax', 'row': ordinal + 1, 'tbody_row': ordinal,
                    'selector': selected['selector'], 'date': selected['date']}
        item = {'position': position, 'source_html': row,
                'disposition': 'parsed' if len(cells) == len(HEADERS) else 'unresolved',
                'review_status': 'unreviewed',
                'source_cells': cells,
                'cells': {name: cell(fragment) for name, fragment in zip(HEADERS, cells)}
                if len(cells) == len(HEADERS) else None,
                'penalty': None, 'category': None}
        if len(cells) != len(HEADERS):
            item['reason'] = f'expected 11 cells, found {len(cells)}'
        positions.append(item)
    parsed = sum(row['disposition'] == 'parsed' for row in positions)
    return {'schema': SCHEMA, 'source': {'url': receipt['final_url'],
                                        'receipt_sha256': hashlib.sha256(receipt_path.read_bytes()).hexdigest(),
                                        'response_time': receipt['response_time'],
                                        'sha256': source_hash, 'bytes': len(source_bytes),
                                        'http_status': receipt['http_status'],
                                        'content_type': receipt['content_type'],
                                        'selected_date': selected['date'],
                                        'selector': selected['selector'],
                                        'table': 'table_ajax', 'table_number': table_number,
                                        'tbody': 'body_ajax'},
            'summary': {'source_positions': len(positions), 'parsed': parsed,
                        'parse_unresolved': len(positions) - parsed,
                        'review_unresolved': len(positions),
                        'observation_versions': 0, 'confirmed_distinct_attempts': None},
            'positions': positions}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('receipt', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    try:
        packet = build(args.source, args.receipt)
        payload = json.dumps(packet, ensure_ascii=False, sort_keys=True, indent=2) + '\n'
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=args.output.parent,
                                         prefix='.aida-packet-', suffix='.tmp', delete=False) as stream:
            temp_path = Path(stream.name)
            os.chmod(temp_path, 0o600)
            stream.write(payload)
        try:
            os.replace(temp_path, args.output)
        finally:
            temp_path.unlink(missing_ok=True)
    except (OSError, ValueError, KeyError, TypeError, UnicodeError, json.JSONDecodeError) as exc:
        parser.exit(2, f'error: {exc}\n')


if __name__ == '__main__':
    main()
