#!/usr/bin/env python3
"""Replay B45 retained-only parser artifacts against imported printed positions.

The private supplement preserves earlier candidate versions without increasing
the number of physical source positions or claiming distinct sporting attempts.
"""

import argparse
from collections import Counter
from datetime import date
import hashlib
import json
from pathlib import Path
import subprocess
import sys

try:
    from scripts.census_contract import validate
    from scripts.project_worker_positions import EDN_TO_JSON, edn_json_lines
except ModuleNotFoundError:
    from census_contract import validate
    from project_worker_positions import EDN_TO_JSON, edn_json_lines

ARTIFACT_TO_JSON = '''(require '[clojure.edn :as edn] '[cheshire.core :as json])
(with-open [r (clojure.java.io/reader *in*)]
  (doseq [line (line-seq r)]
    (let [d (edn/read-string (slurp line))]
      (println (json/generate-string
        (select-keys d [:job-id :source-sha256 :parser-version :publication
                        :acquisitions :source-context :source-url :status
                        :reconciliation :schema-version :tool :processed-at
                        :config :candidates]))))))'''


def sha(data):
    return hashlib.sha256(data).hexdigest()


def encoded(value):
    return (json.dumps(value, sort_keys=True, ensure_ascii=False, indent=2) + '\n').encode()


def need(condition, message):
    if not condition:
        raise ValueError(message)


def key(source_hash, coordinates, raw):
    need(isinstance(raw, dict) and isinstance(raw.get('line'), str)
         and isinstance(raw.get('fields'), dict), 'candidate lacks printed raw line or fields')
    return (source_hash, json.dumps(coordinates, sort_keys=True, ensure_ascii=False),
            raw['line'], json.dumps(raw['fields'], sort_keys=True, ensure_ascii=False))


def verified_file(payload, files, relative, expected):
    info = files.get(relative)
    need(isinstance(info, dict) and info.get('sha256') == expected,
         f'bundle index lacks expected file: {relative}')
    data = (payload / relative).read_bytes()
    need(sha(data) == expected and len(data) == info.get('bytes'),
         f'{"source object" if "archive/objects/" in relative else "artifact"} hash mismatch: {relative}')
    return data


def build_supplement(projection, artifacts, payload, index_bytes, projection_bytes=None):
    """Join verified retained artifacts to exact imported source citations."""
    validate(projection)
    provenance = projection['projection_provenance']
    need(sha(index_bytes) == provenance['bundle_index_sha256'],
         'bundle index differs from imported projection')
    files = json.loads(index_bytes)['files']
    expected = {item['job_id']: item for item in provenance['retained_only_artifacts']}
    need(len(expected) == provenance['retained_only_jobs'] == len(artifacts),
         'retained-only job membership differs from projection')
    need({artifact['job-id'] for _, artifact in artifacts} == set(expected),
         'retained-only job ids differ from projection')
    sources = {item['id']: item for item in projection['sources']}
    imported = {}
    for position in projection['positions']:
        source_hash = position['source_id'].removeprefix('sha256:')
        raw = position.get('raw_evidence')
        if isinstance(raw, dict) and isinstance(raw.get('line'), str) and isinstance(raw.get('fields'), dict):
            match_key = key(source_hash, position.get('coordinates'), raw)
            need(match_key not in imported, 'ambiguous imported printed position')
            imported[match_key] = position

    candidate_versions = []
    artifact_records = []
    used_positions = set()
    used_sources = set()
    match_counts = Counter()
    for artifact_hash, artifact in sorted(artifacts, key=lambda pair: pair[1]['job-id']):
        job_id = artifact['job-id']
        entry = expected[job_id]
        need(artifact_hash == entry['artifact_sha256'], f'artifact hash differs: {job_id}')
        verified_file(payload, files, f'archive/derived-objects/{artifact_hash}', artifact_hash)
        receipt_path = f'archive/derivations/{job_id}.edn'
        receipt_info = files.get(receipt_path)
        need(isinstance(receipt_info, dict), f'derivation receipt absent: {job_id}')
        receipt_hash = receipt_info['sha256']
        receipt_bytes = verified_file(payload, files, receipt_path, receipt_hash)
        receipt = edn_json_lines([receipt_bytes.decode('utf-8').strip()], EDN_TO_JSON)[0]
        need(receipt == {'job-id': job_id, 'artifact-sha256': artifact_hash},
             f'derivation receipt differs: {job_id}')
        candidates = artifact.get('candidates')
        need(isinstance(candidates, list) and len(candidates) == entry['candidate_count'],
             f'candidate count differs: {job_id}')
        source_hash = artifact['source-sha256']
        config = artifact.get('config')
        need(isinstance(config, dict), f'artifact lacks event config: {job_id}')
        event_name = config.get('event')
        source_dates = config.get('source-dates')
        need(isinstance(event_name, str) and bool(event_name.strip()),
             f'artifact lacks event name: {job_id}')
        need(isinstance(source_dates, list) and bool(source_dates),
             f'artifact lacks source dates: {job_id}')
        for source_date in source_dates:
            need(isinstance(source_date, str) and date.fromisoformat(source_date).isoformat() == source_date,
                 f'artifact source date invalid: {job_id}')
        source_id = 'sha256:' + source_hash
        source = sources.get(source_id)
        need(source is not None and source['original_sha256'] == source_hash,
             f'artifact source absent from imported projection: {job_id}')
        verified_file(payload, files, f'archive/objects/{source_hash}', source_hash)
        acquisitions = artifact.get('acquisitions') or []
        for receipt in acquisitions:
            manifest = receipt.get('manifest') or {}
            need(manifest.get('sha256') == source_hash, f'artifact acquisition source differs: {job_id}')
            matches = [a for a in source.get('acquisitions', [])
                       if a.get('acquisition_id') == receipt.get('acquisition-id')]
            need(len(matches) == 1, f'artifact acquisition absent from projection: {job_id}')
            baseline = matches[0]
            for field, manifest_key in (('discovery_url', 'discovery-url'),
                                        ('final_url', 'final-url'), ('retrieved_at', 'retrieved-at')):
                need(baseline.get(field) == manifest.get(manifest_key),
                     f'artifact acquisition {field} differs: {job_id}')
        used_sources.add(source_id)
        artifact_records.append({'job_id': job_id, 'artifact_sha256': artifact_hash,
                                 'source_id': source_id, 'source_sha256': source_hash,
                                 'parser_version': artifact['parser-version'],
                                 'candidate_count': len(candidates),
                                 'config': config,
                                 'event_name': event_name,
                                 'event_name_basis': 'artifact_config',
                                 'source_dates_context': source_dates,
                                 'derivation_receipt': {'path': receipt_path,
                                                        'sha256': receipt_hash,
                                                        'content': receipt},
                                 'acquisition_receipts': acquisitions,
                                 'publication': artifact.get('publication'),
                                 'status': artifact.get('status'),
                                 'reconciliation': artifact.get('reconciliation')})
        for ordinal, candidate in enumerate(candidates):
            position = imported.get(key(source_hash, candidate.get('coordinates'), candidate.get('raw')))
            need(position is not None,
                 f'retained candidate lacks exact imported printed line: {job_id} ordinal {ordinal}')
            need(position['id'] not in used_positions,
                 f'multiple retained candidates map to one imported position: {position["id"]}')
            need(candidate.get('parsed') == position.get('parsed_fields'),
                 f'parsed fields differ from imported position: {job_id} ordinal {ordinal}')
            need(candidate.get('parse-status') == position.get('status'),
                 f'parse status differs from imported position: {job_id} ordinal {ordinal}')
            refs = position['observation_refs']
            need(refs, f'imported position has no observation reference: {position["id"]}')
            raw_equal = candidate['raw'] == position['raw_evidence']
            basis = 'exact_raw_evidence' if raw_equal else 'same_printed_line_raw_fields'
            match_counts[basis] += 1
            used_positions.add(position['id'])
            parsed = candidate.get('parsed') or {}
            candidate_versions.append({
                'id': f'retained-candidate:{artifact_hash}:{ordinal}',
                'job_id': job_id, 'artifact_sha256': artifact_hash,
                'parser_version': artifact['parser-version'], 'ordinal': ordinal,
                'source_id': source_id, 'imported_position_id': position['id'],
                'citation': position['locator'], 'match_basis': basis,
                'source_lines_equal': candidate.get('source-lines') == position.get('source_lines'),
                'imported_observation_refs': refs,
                'event_date': parsed.get('event-date'),
                'event_name': event_name,
                'event_name_basis': 'artifact_config',
                'source_dates_context': source_dates,
                'session': parsed.get('session'),
                'discipline': parsed.get('discipline'),
                'category': parsed.get('category'),
                'classification': 'result_row_candidate' if
                    candidate.get('parse-status') == 'parsed' and
                    isinstance(candidate.get('parsed'), dict) else 'requires_review',
                'classification_basis': 'printed coordinates, raw fields and parsed candidate fields'
                    if candidate.get('parse-status') == 'parsed' and
                    isinstance(candidate.get('parsed'), dict) else 'candidate needs row review',
                'candidate': candidate,
            })
    need(len(candidate_versions) == provenance['retained_only_candidates'],
         'retained-only candidate total differs from projection')
    selected_positions = [position for position in projection['positions']
                          if position['id'] in used_positions]
    need(len(selected_positions) == len(candidate_versions), 'position mapping is not one to one')
    selected_sources = [source for source in projection['sources'] if source['id'] in used_sources]
    event_ids = {event_id for source in selected_sources for event_id in source['event_ids']}
    census_view = {'schema': 'census-evidence/v1',
                   'scope': 'B45 retained-only earlier parser candidates with imported position citations',
                   'cutoff': projection['cutoff'],
                   'events': [event for event in projection['events'] if event['id'] in event_ids],
                   'sources': selected_sources, 'positions': selected_positions,
                   'gaps': [gap for gap in projection['gaps'] if gap['scope'] == 'search' or
                            (gap['scope'] == 'source' and gap['ref'] in used_sources) or
                            (gap['scope'] == 'event' and gap['ref'] in event_ids)],
                   'relationships': [], 'distinct_attempts': None}
    validate(census_view)
    return {'schema': 'worker-retained-artifact-reconciliation/v1',
            'cutoff': projection['cutoff'],
            'input_sha256': {'projection': sha(projection_bytes if projection_bytes is not None
                                              else encoded(projection)),
                             'bundle_index': sha(index_bytes)},
            'counts': {'existing_source_objects': len(used_sources),
                       'source_positions_reused': len(used_positions),
                       'new_source_positions': 0,
                       'imported_observation_versions': sum(len(p['observation_refs'])
                                                            for p in selected_positions),
                       'retained_candidate_versions': len(candidate_versions),
                       'classification': dict(sorted(Counter(v['classification']
                                                            for v in candidate_versions).items())),
                       'match_basis': dict(sorted(match_counts.items())),
                       'source_lines_differ': sum(not v['source_lines_equal']
                                                  for v in candidate_versions)},
            'artifacts': artifact_records,
            'candidate_versions': candidate_versions,
            'census_view': census_view, 'distinct_attempts': None,
            'limits': ['Candidate classification and printed-position matching do not approve owner review.',
                       'No athlete identity or same-attempt relationship established.']}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('projection', type=Path)
    parser.add_argument('bundle', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args(argv)
    try:
        root = Path(__file__).resolve().parents[1]
        destination = args.output.resolve()
        if destination.is_relative_to(root):
            need(subprocess.run(['git', '-C', str(root), 'check-ignore', '-q', str(destination)],
                                check=False).returncode == 0,
                 'private output inside repository must be ignored by Git')
        projection_bytes = args.projection.read_bytes()
        projection = json.loads(projection_bytes)
        index_bytes = (args.bundle / 'index.json').read_bytes()
        payload = args.bundle / 'payload'
        entries = projection['projection_provenance']['retained_only_artifacts']
        paths = [payload / 'archive/derived-objects' / item['artifact_sha256'] for item in entries]
        artifacts = edn_json_lines([str(path) for path in paths], ARTIFACT_TO_JSON)
        result = build_supplement(projection,
                                  [(path.name, artifact) for path, artifact in zip(paths, artifacts)],
                                  payload, index_bytes, projection_bytes)
        data = encoded(result)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_bytes(data)
        print(json.dumps({'output': str(args.output), 'sha256': sha(data),
                          'counts': result['counts']}, sort_keys=True))
        return 0
    except (OSError, ValueError, KeyError, TypeError, subprocess.CalledProcessError) as error:
        print(f'retained artifact reconciliation rejected: {error}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
