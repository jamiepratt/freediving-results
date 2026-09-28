#!/usr/bin/env python3
"""Derive inert, cited Vestico result text from restricted source HTML bytes."""

import argparse
import hashlib
from html.parser import HTMLParser
import json
import os
from pathlib import Path
import re
import stat
import tempfile

SCHEMA = 'vestico-safe-result-tables/v1'
PARSER_VERSION = 'vestico-safe-derivative/1'
HEADERS = ['Rank', 'OT', 'Lane', 'Competitor', 'M/F', 'Club', 'Result', 'Card', 'IRM']
CATEGORIES = {'Rezultati žene / Results female': 'Results female',
              'Rezultati muškarci / Results male': 'Results male'}
VIEWS = {
    '238dd1a1e5792f9c0ce5deb6be24263470271c4f2396f17399db27fc639ab09b': ('DYN', 'index.php?comp=6', 'DYN - Dinamika s perajom'),
    'ad9788f9b452e54f765617a4d79b30e9429cc878005f99100ae538570780d91b': ('DBF', 'index.php?comp=8', 'DBF - Dinamika s perajama (stereo)'),
    '3e4aa16a9573e3807d0afe2603c05e1540bacb9039755d92cc642635119ec4a9': ('DNF', 'index.php?comp=7', 'DNF - Dinamika bez peraja'),
    '948b40b2fed86cdf5930e1b04104dd58f803c670b02bd65ad46358a10e8e2a2f': ('STA', 'index.php?comp=9', 'STA - Statika'),
    '3ba7aa70dc3da83685f39a3e3d679d56349f871d67f9af479808547f5c26fbbc': ('S&E', 'index.php?comp=10', 'S&E 4x50 Brzinska izdržljivost'),
}
BLOCKED = {'script', 'style', 'template', 'form', 'input', 'button', 'select', 'textarea',
           'iframe', 'object', 'embed', 'svg', 'math', 'noscript', 'meta', 'link'}
VOID = {'area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'param',
        'source', 'track', 'wbr'}
SECRET = re.compile(r'(?i)(?:\b(?:token|session|secret|password|cookie|authorization|api[_-]?key|signature|csrf)\s*[:=]|https?://|[A-Za-z0-9_-]{36,})')
HEX = re.compile(r'[a-f0-9]{64}\Z')


class Node:
    def __init__(self, tag='', attrs=(), parent=None):
        self.tag = tag
        self.attrs = dict(attrs)
        self.parent = parent
        self.children = []

    def walk(self):
        yield self
        for child in self.children:
            if isinstance(child, Node):
                yield from child.walk()

    def text(self):
        if self.tag in BLOCKED or 'hidden' in self.attrs or self.attrs.get('aria-hidden') == 'true':
            return ''
        style = self.attrs.get('style', '').replace(' ', '').lower()
        if 'display:none' in style or 'visibility:hidden' in style:
            return ''
        return ''.join(child.text() if isinstance(child, Node) else child for child in self.children)


class Tree(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.root = Node()
        self.current = self.root

    def handle_starttag(self, tag, attrs):
        node = Node(tag, attrs, self.current)
        self.current.children.append(node)
        if tag not in VOID:
            self.current = node

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)

    def handle_endtag(self, tag):
        node = self.current
        while node is not self.root:
            if node.tag == tag:
                self.current = node.parent
                return
            node = node.parent

    def handle_data(self, data):
        self.current.children.append(data)


def _visible(value):
    value = ' '.join(value.split())
    if len(value) > 4096 or SECRET.search(value):
        raise ValueError('unsafe visible result text')
    return value


def _direct(node, tag):
    return [child for child in node.children if isinstance(child, Node) and child.tag == tag]


def _table_rows(table):
    def visit(node):
        for child in node.children:
            if not isinstance(child, Node) or child.tag == 'table':
                continue
            if child.tag == 'tr':
                yield child
            else:
                yield from visit(child)
    return list(visit(table))


def derive(source_bytes, *, source_id, acquisition_id, expected_sha256=None):
    """Return safe JSON data. Original HTML must stay in private restricted storage."""
    if len(source_bytes) > 2_000_000:
        raise ValueError('HTML source too large')
    digest = hashlib.sha256(source_bytes).hexdigest()
    if expected_sha256 is not None and digest != expected_sha256:
        raise ValueError('source hash mismatch')
    if source_id != 'sha256:' + digest:
        raise ValueError('source id must match source bytes')
    if not isinstance(acquisition_id, str) or not HEX.fullmatch(acquisition_id):
        raise ValueError('acquisition id must be a SHA-256 digest')
    try:
        source = source_bytes.decode('utf-8')
    except UnicodeError as exc:
        raise ValueError('source is not UTF-8') from exc
    parser = Tree()
    parser.feed(source)
    nodes = list(parser.root.walk())
    titles = [n for n in nodes if n.tag == 'title']
    if len(titles) != 1 or _visible(titles[0].text()) != '17. Submania Kup':
        raise ValueError('unsupported event title')
    active = [n for n in nodes if n.tag == 'a' and 'active' in n.attrs.get('class', '').split()
              and n.parent is not None and n.parent.tag == 'li'
              and n.parent.parent is not None and 'discipline' in n.parent.parent.attrs.get('class', '').split()]
    if len(active) != 1:
        raise ValueError('selected view is ambiguous')
    matched = [(name, href, label) for name, href, label in VIEWS.values()
               if active[0].attrs.get('href') == href and _visible(active[0].text()) == label]
    if len(matched) != 1:
        raise ValueError('unsupported selected view')
    discipline, _, label = matched[0]
    if digest in VIEWS and matched[0] != VIEWS[digest]:
        raise ValueError('source hash and selected view disagree')
    tables = [n for n in nodes if n.tag == 'table' and 'rezultati' in n.attrs.get('class', '').split()]
    headings = [n for n in nodes if n.tag == 'h5' and 'card-header' in n.attrs.get('class', '').split()]
    if len(tables) != 2 or len(headings) != 2:
        raise ValueError('unsupported result table layout')
    output = []
    for index, (table, heading) in enumerate(zip(tables, headings), 1):
        heading_text = _visible(heading.text())
        category = CATEGORIES.get(heading_text)
        if category is None or (index == 1 and category != 'Results female') or (index == 2 and category != 'Results male'):
            raise ValueError('unexpected result category')
        rows = _table_rows(table)
        header_rows = [[_visible(cell.text()) for cell in _direct(row, 'th')] for row in rows]
        if header_rows.count(HEADERS) != 1:
            raise ValueError('unexpected result headers')
        candidates = []
        for row_index, row in enumerate(rows, 1):
            cells = _direct(row, 'td')
            if not cells:
                continue
            if len(cells) != len(HEADERS) or any('colspan' in c.attrs or 'rowspan' in c.attrs for c in cells):
                raise ValueError('unsupported result row')
            candidates.append({'citation': {'table': index, 'row': row_index},
                               'cells': [_visible(cell.text()) for cell in cells]})
        output.append({'table': index, 'heading': heading_text, 'category': category,
                       'headers': HEADERS, 'rows': candidates})
    return {'schema': SCHEMA, 'parser_version': PARSER_VERSION, 'source_sha256': digest,
            'source_object_id': source_id, 'acquisition_id': acquisition_id,
            'selected_view': {'discipline': discipline, 'label': label,
                              'basis': 'source_hash_and_page_marker' if digest in VIEWS else 'page_marker_only'},
            'tables': output, 'row_count': sum(len(t['rows']) for t in output),
            'original_replay': 'restricted_original_required'}


def canonical_bytes(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')) + '\n').encode('utf-8')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source', required=True, type=Path)
    p.add_argument('--source-id', required=True)
    p.add_argument('--acquisition-id', required=True)
    p.add_argument('--expected-sha256')
    p.add_argument('--output-dir', required=True, type=Path)
    args = p.parse_args()
    if args.source.is_symlink() or not args.source.is_file():
        p.error('source must be a regular file')
    result = derive(args.source.read_bytes(), source_id=args.source_id,
                    acquisition_id=args.acquisition_id, expected_sha256=args.expected_sha256)
    data = canonical_bytes(result)
    digest = hashlib.sha256(data).hexdigest()
    args.output_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    if args.output_dir.is_symlink() or stat.S_IMODE(args.output_dir.stat().st_mode) != 0o700:
        raise ValueError('output directory must be private')
    objects = args.output_dir / 'objects'
    objects.mkdir(parents=True, exist_ok=True, mode=0o700)
    if objects.is_symlink() or stat.S_IMODE(objects.stat().st_mode) != 0o700:
        raise ValueError('object directory must be private')
    destination = objects / digest
    if destination.is_symlink():
        raise ValueError('derivative symlink rejected')
    if destination.exists():
        if destination.read_bytes() != data:
            raise ValueError('derivative object collision')
    else:
        fd, tmp_name = tempfile.mkstemp(prefix='.derivative-', dir=objects)
        try:
            with os.fdopen(fd, 'wb') as stream:
                stream.write(data)
            os.chmod(tmp_name, 0o600)
            os.replace(tmp_name, destination)
        finally:
            if os.path.exists(tmp_name):
                os.unlink(tmp_name)
    print(json.dumps({'source_sha256': result['source_sha256'], 'source_object_id': result['source_object_id'],
                      'acquisition_id': result['acquisition_id'], 'derivative_sha256': digest,
                      'derivative_bytes': len(data), 'object': str(destination),
                      'row_count': result['row_count'], 'selected_view': result['selected_view']},
                     ensure_ascii=False, sort_keys=True))


if __name__ == '__main__':
    main()
