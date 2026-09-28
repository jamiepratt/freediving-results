#!/usr/bin/env python3
"""Build, verify and restore a private, content-addressed source-object bundle."""
import argparse
import hashlib
import json
import os
import re
import shutil
import stat
import sys
import tempfile
import zipfile
from pathlib import Path
from urllib.parse import urlsplit, urlunsplit

SCHEMA = 'private-source-bundle/v1'
HEX = re.compile(r'[0-9a-f]{64}\Z')
SECRET = re.compile(r'(?i)\b(token|session|secret|password|cookie|authorization|api[_-]?key|signature|sig)\s*[:=]\s*[^\s,;]+')
URL = re.compile(r'https?://[^\s"\'<>]+')
SENSITIVE_KEY = re.compile(r'(?i)(token|session|secret|password|cookie|authorization|api[_-]?key|signature|sig)')
JSON_SECRET = re.compile(rb'(?i)[\"](?:api[_-]?key|token|session|secret|password|cookie|authorization|signature|sig)[\"]\s*:\s*[\"]?[^\s,}]+')
BYTE_SECRET = re.compile(rb'(?i)\b(?:api[_-]?key|token|session|secret|password|cookie|authorization|signature|sig)\s*[:=]\s*[^\s,;]+')


def digest(path):
    h = hashlib.sha256()
    size = 0
    with path.open('rb') as stream:
        while data := stream.read(1024 * 1024):
            h.update(data)
            size += len(data)
    return h.hexdigest(), size


def safe_string(value):
    def scrub_url(match):
        parts = urlsplit(match.group())
        host = parts.hostname or ''
        if parts.port:
            host += f':{parts.port}'
        return urlunsplit((parts.scheme, host, parts.path, '', ''))
    return SECRET.sub(r'\1=[redacted]', URL.sub(scrub_url, value))


def safe_value(value):
    if isinstance(value, str):
        return safe_string(value)
    if isinstance(value, list):
        return [safe_value(v) for v in value]
    if isinstance(value, dict):
        return {k: safe_value(v) for k, v in value.items() if not SENSITIVE_KEY.search(k)}
    if value is None or isinstance(value, (bool, int, float)):
        return value
    raise ValueError('receipt or metadata contains unsupported value')


def checked_source(item):
    required = ('id', 'sha256', 'bytes', 'content_type', 'source_path', 'receipt', 'classification', 'metadata')
    if not isinstance(item, dict) or any(k not in item for k in required):
        raise ValueError('source entry missing required field')
    if not isinstance(item['id'], str) or not item['id']:
        raise ValueError('source id required')
    if not isinstance(item['sha256'], str) or not HEX.fullmatch(item['sha256']):
        raise ValueError(f"invalid sha256 for {item['id']}")
    if item['bytes'] is None and item['classification'] != 'eligible':
        pass
    elif type(item['bytes']) is not int or item['bytes'] < 0:
        raise ValueError(f"invalid byte count for {item['id']}")
    if not isinstance(item['content_type'], str) or not item['content_type']:
        raise ValueError(f"content type required for {item['id']}")
    classification = item['classification']
    if classification not in ('eligible', 'restricted', 'excluded', 'missing'):
        raise ValueError(f"invalid classification for {item['id']}")
    if classification != 'eligible' and not item.get('reason'):
        raise ValueError(f"reason required for {item['id']}")
    public = {k: safe_value(item[k]) for k in ('id', 'sha256', 'bytes', 'content_type', 'receipt', 'metadata')}
    public['status'] = 'included' if classification == 'eligible' else classification
    if classification != 'eligible':
        public['reason'] = safe_value(item['reason'])
    if item.get('derivative'):
        public['derivative'] = safe_value(item['derivative'])
    if classification == 'eligible':
        if item['content_type'].lower().split(';')[0].strip() in ('text/html', 'application/xhtml+xml') or str(item['source_path']).lower().endswith(('.html', '.htm')):
            raise ValueError(f"HTML cannot be eligible: {item['id']}")
        public['object'] = 'objects/' + item['sha256']
    return public


def screen_eligible(path, content_type):
    kind = content_type.lower().split(';')[0].strip()
    with path.open('rb') as stream:
        head = stream.read(512).lstrip().lower()
    if head.startswith((b'<!doctype html', b'<html')):
        raise ValueError('eligible HTML rejected')
    if kind in ('application/pdf', 'application/x-pdf') and not head.startswith(b'%pdf-'):
        raise ValueError('declared PDF signature mismatch')
    if kind in ('application/vnd.sqlite3', 'application/x-sqlite3', 'application/sqlite3') and not head.startswith(b'sqlite format 3\x00'):
        raise ValueError('declared SQLite signature mismatch')
    if kind == 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet':
        try:
            with zipfile.ZipFile(path) as archive:
                names = set(archive.namelist())
                if '[Content_Types].xml' not in names or 'xl/workbook.xml' not in names or archive.testzip() is not None:
                    raise ValueError('declared XLSX structure mismatch')
        except zipfile.BadZipFile as error:
            raise ValueError('declared XLSX signature mismatch') from error
    if kind == 'application/json' or kind.endswith('+json'):
        try:
            with path.open('r', encoding='utf-8') as stream:
                json.load(stream)
        except (UnicodeError, json.JSONDecodeError) as error:
            raise ValueError('declared JSON parse mismatch') from error
    textual = kind.startswith('text/') or kind.endswith('+json') or kind in ('application/json', 'application/xml')
    if not textual and kind == 'application/octet-stream':
        textual = head.startswith((b'{', b'['))
    if not textual:
        return
    carry = b''
    with path.open('rb') as stream:
        while chunk := stream.read(1024 * 1024):
            data = carry + chunk
            if JSON_SECRET.search(data) or BYTE_SECRET.search(data):
                raise ValueError('eligible text contains credential/session pattern')
            carry = data[-256:]


def object_path(bundle, item):
    expected = 'objects/' + item['sha256']
    if item.get('object') != expected:
        raise ValueError(f"unsafe object reference: {item['id']}")
    return bundle / expected


def verify(bundle):
    if bundle.is_symlink() or not bundle.is_dir() or stat.S_IMODE(bundle.stat().st_mode) != 0o700:
        raise ValueError('bundle directory permissions unsafe')
    objects_dir = bundle / 'objects'
    if objects_dir.is_symlink() or not objects_dir.is_dir() or stat.S_IMODE(objects_dir.stat().st_mode) != 0o700:
        raise ValueError('objects directory permissions unsafe')
    manifest_path = bundle / 'manifest.json'
    if manifest_path.is_symlink():
        raise ValueError('manifest symlink rejected')
    if not manifest_path.is_file() or stat.S_IMODE(manifest_path.stat().st_mode) != 0o600:
        raise ValueError('manifest permissions unsafe')
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    if manifest.get('schema') != SCHEMA or not isinstance(manifest.get('sources'), list):
        raise ValueError('invalid bundle manifest')
    expected = set()
    for item in manifest['sources']:
        if item.get('status') != 'included':
            if item.get('status') not in ('restricted', 'excluded', 'missing') or 'reason' not in item or 'object' in item:
                raise ValueError('invalid source disposition')
            continue
        if not HEX.fullmatch(item.get('sha256', '')) or type(item.get('bytes')) is not int:
            raise ValueError('invalid source hash or size')
        path = object_path(bundle, item)
        if path.is_symlink() or not path.is_file() or stat.S_IMODE(path.stat().st_mode) != 0o600:
            raise ValueError(f"missing object: {item['id']}")
        observed = digest(path)
        if observed != (item['sha256'], item['bytes']):
            raise ValueError(f"object hash/size mismatch: {item['id']}")
        expected.add(item['sha256'])
    actual = {p.name for p in (bundle / 'objects').iterdir()} if (bundle / 'objects').exists() else set()
    if actual != expected:
        raise ValueError('unexpected or missing objects')
    return manifest


def build(inventory, target):
    if target.exists():
        raise ValueError('bundle destination already exists')
    spec = json.loads(inventory.read_text(encoding='utf-8'))
    if not isinstance(spec.get('sources'), list):
        raise ValueError('inventory sources list required')
    raw = spec['sources']
    entries = [checked_source(item) for item in raw]
    if len({item['id'] for item in entries}) != len(entries):
        raise ValueError('duplicate source id')
    pairs = sorted(zip(raw, entries), key=lambda pair: pair[1]['id'])
    for source, item in pairs:
        if item['status'] != 'included' and not (item['status'] == 'restricted' and source.get('source_path')):
            continue
        path = Path(source['source_path'])
        if path.is_symlink() or not path.is_file():
            raise ValueError(f"missing retained original: {item['id']}")
        if digest(path) != (item['sha256'], item['bytes']):
            raise ValueError(f"retained original hash/size mismatch: {item['id']}")
        if item['status'] == 'included':
            screen_eligible(path, item['content_type'])
    target.parent.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix='.private-source-bundle-', dir=target.parent))
    try:
        os.chmod(stage, 0o700)
        objects = stage / 'objects'
        objects.mkdir(mode=0o700)
        for source, item in pairs:
            if item['status'] != 'included':
                continue
            dest = object_path(stage, item)
            if dest.exists():
                continue
            shutil.copyfile(source['source_path'], dest)
            os.chmod(dest, 0o600)
            if digest(dest) != (item['sha256'], item['bytes']):
                raise ValueError(f"copy changed during build: {item['id']}")
        manifest_path = stage / 'manifest.json'
        manifest_path.write_text(json.dumps({'schema': SCHEMA, 'sources': [item for _, item in pairs]},
                                            ensure_ascii=False, sort_keys=True, separators=(',', ':')) + '\n', encoding='utf-8')
        os.chmod(manifest_path, 0o600)
        verify(stage)
        stage.rename(target)
    finally:
        if stage.exists():
            shutil.rmtree(stage)


def restore(bundle, target):
    if target.exists():
        raise ValueError('restore destination already exists')
    manifest = verify(bundle)
    target.parent.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix='.private-source-restore-', dir=target.parent))
    try:
        os.chmod(stage, 0o700)
        objects = stage / 'objects'
        objects.mkdir(mode=0o700)
        for item in manifest['sources']:
            if item['status'] != 'included':
                continue
            dest = object_path(stage, item)
            if not dest.exists():
                shutil.copyfile(object_path(bundle, item), dest)
                os.chmod(dest, 0o600)
        shutil.copyfile(bundle / 'manifest.json', stage / 'manifest.json')
        os.chmod(stage / 'manifest.json', 0o600)
        verify(stage)
        stage.rename(target)
    finally:
        if stage.exists():
            shutil.rmtree(stage)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    p = commands.add_parser('build')
    p.add_argument('--inventory', type=Path, required=True)
    p.add_argument('--bundle-dir', type=Path, required=True)
    p = commands.add_parser('verify')
    p.add_argument('--bundle-dir', type=Path, required=True)
    p = commands.add_parser('restore')
    p.add_argument('--bundle-dir', type=Path, required=True)
    p.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == 'build':
            build(args.inventory, args.bundle_dir)
        elif args.command == 'verify':
            verify(args.bundle_dir)
        else:
            restore(args.bundle_dir, args.output_dir)
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        print(f'{args.command}: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
