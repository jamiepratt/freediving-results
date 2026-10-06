#!/usr/bin/env python3
"""Stage bounded selected-date AIDA evidence privately, without import or approval."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import tempfile

try:
    from scripts import issue55_aida_selected_html as selected
except ModuleNotFoundError:
    import issue55_aida_selected_html as selected


SCHEMA = 'historical-aida-private-stage/v1'
PARSER_VERSION = 'historical-aida-selected-html/v1'
REPOSITORY = Path(__file__).resolve().parents[1]


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                     separators=(',', ':')).encode('utf-8')).hexdigest()


def make_view(source, receipt, expected_source_sha256):
    packet = selected.build(source, receipt)
    selected.require(packet['source']['sha256'] == expected_source_sha256,
                     'expected source sha256 mismatch')
    packet['parser_version'] = PARSER_VERSION
    packet['id'] = digest([packet['source']['sha256'], PARSER_VERSION])
    packet['source_path'] = str(source.resolve())
    packet['receipt_path'] = str(receipt.resolve())
    receipt_bytes = receipt.read_bytes()
    source_bytes = source.read_bytes()
    selected.require(hashlib.sha256(receipt_bytes).hexdigest()
                     == packet['source']['receipt_sha256'], 'receipt changed during staging')
    selected.require(hashlib.sha256(source_bytes).hexdigest()
                     == packet['source']['sha256'], 'source changed during staging')
    packet['receipt'] = json.loads(receipt_bytes)
    packet['source_html'] = source_bytes.decode('utf-8')
    packet['category'] = None
    packet['finality'] = None
    packet['unknown_fields'] = ['category', 'finality']
    packet['schema'] = 'historical-aida-selected-view/v1'
    for row in packet['positions']:
        row['observation_version'] = (
            'historical-aida:' + digest([packet['source']['sha256'], PARSER_VERSION,
                                         row['position']])
            if row['disposition'] == 'parsed' else None)
    packet['summary']['observation_versions'] = packet['summary']['parsed']
    return packet


def document(views):
    totals = {field: sum(view['summary'][field] for view in views)
              for field in ('source_positions', 'parsed', 'parse_unresolved',
                            'review_unresolved', 'observation_versions')}
    totals.update(source_views=len(views), distinct_attempts=None)
    result = {'schema': SCHEMA, 'summary': totals, 'views': views}
    result['payload_sha256'] = digest(result)
    return result


def read_stage(output):
    previous = json.loads(output.read_text(encoding='utf-8'))
    selected.require(isinstance(previous, dict), 'stage object required')
    selected.require(previous.get('schema') == SCHEMA, 'unsupported stage schema')
    views = previous['views']
    selected.require(isinstance(views, list) and views
                     and all(isinstance(view, dict) for view in views),
                     'stage views required')
    selected.require(previous == document(views), 'stage payload or accounting changed')
    selected.require(len({view['id'] for view in views}) == len(views),
                     'duplicate staged view')
    for stored in views:
        rebuilt = make_view(Path(stored['source_path']), Path(stored['receipt_path']),
                            stored['source']['sha256'])
        selected.require(stored == rebuilt, 'staged view differs from retained evidence')
    return previous


def stage(source, receipt, output, expected_source_sha256):
    """Stage one checked view; serialize calls for a given output.

    Receipt/path bindings are immutable: reacquisition needs a separate stage.
    Returned versions are private evidence, never authority or database imports.
    """
    source, receipt, output = map(Path, (source, receipt, output))
    selected.require(not output.resolve().is_relative_to(REPOSITORY),
                     'private stage must be outside repository')
    selected.require(not output.is_symlink(), 'private stage cannot be a symlink')
    if output.parent.exists():
        selected.require(output.parent.stat().st_mode & 0o077 == 0,
                         'stage directory must already be private')
    if output.exists():
        selected.require(output.is_file() and output.stat().st_mode & 0o077 == 0,
                         'stage file must already be private')
    view = make_view(source, receipt, expected_source_sha256)
    views = []
    if output.exists():
        previous = read_stage(output)
        views = previous['views']
        for stored in views:
            if stored['id'] == view['id']:
                selected.require(stored == view, 'changed staged view or receipt binding')
                return previous
    result = document(sorted(views + [view], key=lambda item: item['id']))
    missing = []
    directory = output.parent
    while not directory.exists():
        missing.append(directory)
        directory = directory.parent
    for directory in reversed(missing):
        directory.mkdir(mode=0o700)
    with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=output.parent,
                                     prefix='.historical-aida-', delete=False) as stream:
        temporary = Path(stream.name)
        os.chmod(temporary, 0o600)
        json.dump(result, stream, ensure_ascii=False, sort_keys=True, indent=2)
        stream.write('\n')
    try:
        os.replace(temporary, output)
    finally:
        temporary.unlink(missing_ok=True)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('receipt', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--expected-source-sha256', required=True)
    args = parser.parse_args()
    try:
        result = stage(args.source, args.receipt, args.output, args.expected_source_sha256)
    except (OSError, ValueError, KeyError, TypeError, UnicodeError) as exc:
        parser.exit(2, f'error: {exc}\n')
    print(json.dumps(result['summary'], sort_keys=True))


if __name__ == '__main__':
    main()
