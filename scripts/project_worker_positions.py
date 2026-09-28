#!/usr/bin/env python3
"""Project the retained worker's immutable database versions into a private census.

Requires local pg_restore and babashka (bb). Reads the portable bundle only; it
never connects to, or writes to, a database. Output contains personal results.
"""

import argparse
from collections import Counter
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

try:
    from scripts.census_contract import validate
except ModuleNotFoundError:  # direct CLI execution
    from census_contract import validate

SHA = re.compile(r'[0-9a-f]{64}\Z')
EDN_TO_JSON = '''(require '[clojure.edn :as edn] '[cheshire.core :as json])
(with-open [r (clojure.java.io/reader *in*)]
  (doseq [line (line-seq r)]
    (println (json/generate-string (edn/read-string line)))))'''
ARTIFACT_TO_JSON = '''(require '[clojure.edn :as edn] '[cheshire.core :as json])
(with-open [r (clojure.java.io/reader *in*)]
  (doseq [line (line-seq r)]
    (let [d (edn/read-string (slurp line))]
      (println (json/generate-string
        (select-keys d [:job-id :source-sha256 :parser-version :publication
                        :acquisitions :source-context :source-url :status
                        :reconciliation :schema-version :tool :processed-at
                        :candidates]))))))'''


def sha_bytes(data):
    return hashlib.sha256(data).hexdigest()


def copy_field(value):
    if value == r'\N':
        return None
    def replacement(match):
        key = match.group(1)
        if key in {'n': '\n', 'r': '\r', 't': '\t', 'b': '\b', 'f': '\f', 'v': '\v', '\\': '\\'}:
            return {'n': '\n', 'r': '\r', 't': '\t', 'b': '\b', 'f': '\f', 'v': '\v', '\\': '\\'}[key]
        if key.startswith('x'):
            return chr(int(key[1:], 16))
        return chr(int(key, 8))
    return re.sub(r'\\(x[0-9a-fA-F]{2}|[0-7]{1,3}|.)', replacement, value)


def dump_rows(dump, table):
    result = subprocess.run(['pg_restore', '-a', '-t', table, '-f', '-', str(dump)],
                            check=True, capture_output=True, text=True)
    header = f'COPY freediving.{table} '
    inside = False
    rows = []
    for line in result.stdout.splitlines():
        if line.startswith(header):
            inside = True
        elif inside and line == r'\.':
            inside = False
        elif inside:
            rows.append([copy_field(field) for field in line.split('\t')])
    if not rows:
        raise ValueError(f'no {table} rows in database dump')
    return rows


def edn_json_lines(lines, program):
    data = '\n'.join(lines) + '\n'
    result = subprocess.run(['bb', '-e', program], input=data, text=True,
                            capture_output=True, check=True)
    parsed = [json.loads(line) for line in result.stdout.splitlines()]
    if len(parsed) != len(lines):
        raise ValueError('EDN conversion dropped rows')
    return parsed


def locator(coordinates, ordinal):
    if not isinstance(coordinates, dict) or not coordinates:
        return f'candidate ordinal {ordinal}'
    parts = []
    for key in ('page', 'table', 'section', 'row', 'line', 'column-start', 'column-end'):
        if coordinates.get(key) is not None:
            parts.append(f'{key.replace("-", " ")} {coordinates[key]}')
    for key in sorted(set(coordinates) - {'page', 'table', 'section', 'row', 'line', 'column-start', 'column-end'}):
        if coordinates[key] is not None:
            parts.append(f'{key.replace("-", " ")} {coordinates[key]}')
    return ' '.join(parts) or f'candidate ordinal {ordinal}'


def project_records(jobs, observations, cutoff, retained_acquisitions=()):
    """Build a queryable projection from verified imported jobs and DB versions."""
    job_map = {job['job_id']: job for job in jobs}
    if len(job_map) != len(jobs):
        raise ValueError('duplicate job')
    sources = {}
    events = {}
    positions = {}
    seen_versions = set()
    for observation in sorted(observations, key=lambda o: (o['job_id'], o['ordinal'])):
        job = job_map.get(observation['job_id'])
        if job is None:
            raise ValueError('observation has missing imported job')
        key = (observation['job_id'], observation['ordinal'])
        if key in seen_versions:
            raise ValueError('duplicate immutable observation version')
        seen_versions.add(key)
        candidate = observation['candidate']
        source_hash = job['source_sha256']
        source_id = f'sha256:{source_hash}'
        if source_id not in sources:
            acquisitions = job.get('acquisitions') or []
            first = acquisitions[0] if acquisitions else {}
            gaps = []
            if not acquisitions:
                gaps.append('No acquisition attached to imported extraction')
            for field in ('acquisition_id', 'discovery_url', 'final_url', 'retrieved_at'):
                if first.get(field) is None:
                    gaps.append(f'{field} unavailable')
            sources[source_id] = {
                'id': source_id, 'event_ids': [], 'authority': 'primary' if first.get('relationship') == 'publisher' else 'community',
                'media': job.get('media') or 'other', 'original_sha256': source_hash,
                'derived_sha256': None, 'acquisition_id': first.get('acquisition_id'),
                'retrieved_at': first.get('retrieved_at'), 'discovery_url': first.get('discovery_url'),
                'final_url': first.get('final_url'), 'selected_view': None,
                'provenance_gaps': gaps + ['Selected public view not established'],
                'publisher': job.get('publisher'), 'acquisitions': acquisitions,
            }
        else:
            known = sources[source_id]['acquisitions']
            for acquisition in job.get('acquisitions') or []:
                if acquisition not in known:
                    known.append(acquisition)
        coordinates = candidate.get('coordinates')
        raw = candidate.get('raw')
        raw_fields = raw.get('fields') if isinstance(raw, dict) else None
        if raw_fields is None and isinstance(raw, dict):
            raw_fields = raw
        parsed = candidate.get('parsed')
        status = str(candidate.get('parse-status') or 'unparsed')
        if status not in {'parsed', 'quarantined', 'unparsed'}:
            status = 'quarantined'
        if observation['kind'] != 'result-row' and status == 'parsed':
            status = 'quarantined'
        reasons = candidate.get('unresolved-reasons') or []
        unresolved = '; '.join(map(str, reasons)) or (observation['classification_reason'] if status != 'parsed' else None)
        if raw_fields is None:
            unresolved = unresolved or 'No structured raw fields in retained candidate'
        event_name = parsed.get('event-name') if isinstance(parsed, dict) else None
        event_date = parsed.get('event-date') if isinstance(parsed, dict) else None
        federation = parsed.get('federation') if isinstance(parsed, dict) else None
        if all(isinstance(value, str) and value for value in (event_name, event_date, federation)):
            try:
                exact_date = datetime.strptime(event_date, '%Y-%m-%d').date().isoformat() == event_date
            except ValueError:
                exact_date = False
            if exact_date:
                event_key = json.dumps([federation, event_name, event_date], ensure_ascii=False)
                event_id = 'parsed-event:' + sha_bytes(event_key.encode('utf-8'))
                events[event_id] = {'id': event_id, 'federation': federation,
                                    'name': event_name, 'held_from': event_date,
                                    'held_to': event_date, 'basis': 'parsed candidate fields'}
                if event_id not in sources[source_id]['event_ids']:
                    sources[source_id]['event_ids'].append(event_id)
        place = locator(coordinates, observation['ordinal'])
        # Coordinates plus raw text distinguish parallel rows sharing a printed line.
        position_key = json.dumps([source_hash, coordinates, raw], sort_keys=True, ensure_ascii=False)
        position_id = 'source-position:' + sha_bytes(position_key.encode('utf-8'))
        if position_id not in positions:
            positions[position_id] = {
                'id': position_id, 'source_id': source_id, 'locator': place,
                'coordinates': coordinates, 'source_lines': candidate.get('source-lines'),
                'raw_fields': raw_fields, 'raw_evidence': raw,
                'parsed_fields': parsed, 'session': (parsed or {}).get('session') if isinstance(parsed, dict) else None,
                'category': (parsed or {}).get('category') if isinstance(parsed, dict) else None,
                'event_date': event_date, 'event_name': event_name,
                'federation': federation,
                'date_scope': ('unknown' if not isinstance(parsed, dict) or not parsed.get('event-date') else
                               'in_scope' if str(parsed['event-date'])[:4] in {'2025', '2026'} else 'out_of_scope'),
                'status': status, 'unresolved_reason': unresolved,
                'observation_refs': [],
            }
        else:
            prior = positions[position_id]
            if prior['parsed_fields'] != parsed:
                prior['parsed_fields'] = None
                prior['unresolved_reason'] = 'Parser versions disagree on parsed fields; inspect observation versions'
        if any(ref['job_id'] == job['job_id'] for ref in positions[position_id]['observation_refs']):
            raise ValueError(f'multiple observations at same source position in one job: {job["job_id"]} {place}')
        positions[position_id]['observation_refs'].append({
            'job_id': job['job_id'], 'ordinal': observation['ordinal'],
            'candidate_id': observation['candidate_id'], 'kind': observation['kind'],
            'classification_reason': observation['classification_reason'],
            'artifact_sha256': job['artifact_sha256'], 'parser_version': job['parser_version'],
            'citation': place, 'parsed_fields': parsed, 'parse_status': candidate.get('parse-status'),
            'review_status': candidate.get('review-status'),
        })
    gaps = [{'id': 'scope-worker', 'scope': 'search', 'ref': '2025-2026 cross-federation census',
             'status': 'unchecked', 'reason': 'Retained worker corpus only; wider discovery and owner review incomplete'}]
    for acquisition in retained_acquisitions:
        source_hash = acquisition['source_sha256']
        source_id = f'sha256:{source_hash}'
        if source_id not in sources:
            content_type = (acquisition.get('content_type') or '').lower()
            media = 'pdf' if 'pdf' in content_type else 'html' if 'html' in content_type else 'json' if 'json' in content_type else 'other'
            sources[source_id] = {
                'id': source_id, 'event_ids': [],
                'authority': 'primary' if acquisition.get('relationship') == 'publisher' else 'unknown',
                'media': media, 'original_sha256': source_hash, 'derived_sha256': None,
                'acquisition_id': acquisition.get('acquisition_id'),
                'retrieved_at': acquisition.get('retrieved_at'),
                'discovery_url': acquisition.get('discovery_url'),
                'final_url': acquisition.get('final_url'), 'selected_view': None,
                'provenance_gaps': ['Selected public view not established'],
                'publisher': acquisition.get('publisher'), 'acquisitions': [],
                'source_status': 'no_imported_extraction',
            }
            gaps.append({'id': 'no-import:' + source_hash, 'scope': 'source', 'ref': source_id,
                         'status': 'unchecked',
                         'reason': 'Acquired bytes retained; no extraction in imported worker database; suitability unresolved'})
        known = sources[source_id]['acquisitions']
        if not any(item.get('acquisition_id') == acquisition.get('acquisition_id') for item in known):
            known.append(acquisition)
    for source in sources.values():
        source.setdefault('source_status', 'imported_extraction')
        source['event_ids'].sort()
        source['acquisitions'].sort(key=lambda a: (a.get('acquisition_id') or ''))
    return {'schema': 'census-evidence/v1', 'scope': 'Retained 2025-2026 worker corpus imported observation versions',
            'cutoff': cutoff, 'events': sorted(events.values(), key=lambda x: x['id']), 'sources': sorted(sources.values(), key=lambda x: x['id']),
            'positions': sorted(positions.values(), key=lambda x: x['id']),
            'gaps': sorted(gaps, key=lambda gap: gap['id']),
            'relationships': [], 'distinct_attempts': None}


def load_bundle(bundle):
    index_path = bundle / 'index.json'
    index_bytes = index_path.read_bytes()
    index = json.loads(index_bytes)
    payload = bundle / 'payload'
    for relative, info in index['files'].items():
        actual = payload / relative
        if not actual.is_file() or sha_bytes(actual.read_bytes()) != info['sha256']:
            raise ValueError(f'bundle file missing or hash mismatch: {relative}')
    dump = payload / 'run/database.dump'
    extraction_rows = dump_rows(dump, 'extractions')
    jobs = {}
    for row in extraction_rows:
        job_id, artifact_hash, source_hash, parser_version = row[:4]
        if not all(SHA.fullmatch(value or '') for value in (job_id, artifact_hash, source_hash)):
            raise ValueError('invalid extraction hash in database dump')
        jobs[job_id] = {'job_id': job_id, 'artifact_sha256': artifact_hash,
                        'source_sha256': source_hash, 'parser_version': parser_version}
    artifact_paths = list((payload / 'archive/derived-objects').iterdir())
    converted = edn_json_lines([str(path) for path in sorted(artifact_paths)], ARTIFACT_TO_JSON)
    retained = {}
    for path, artifact in zip(sorted(artifact_paths), converted):
        if sha_bytes(path.read_bytes()) != path.name:
            raise ValueError(f'derived artifact hash mismatch: {path.name}')
        retained[artifact['job-id']] = (path, artifact)
    for job_id, job in jobs.items():
        if job_id not in retained:
            raise ValueError(f'imported job has no retained artifact: {job_id}')
        path, artifact = retained[job_id]
        if path.name != job['artifact_sha256'] or artifact['source-sha256'] != job['source_sha256']:
            raise ValueError(f'imported job artifact/source mismatch: {job_id}')
        source_object = payload / 'archive/objects' / job['source_sha256']
        if not source_object.is_file() or sha_bytes(source_object.read_bytes()) != job['source_sha256']:
            raise ValueError(f'imported job source bytes unavailable or mismatched: {job_id}')
        if artifact['parser-version'] != job['parser_version']:
            raise ValueError(f'imported job parser mismatch: {job_id}')
        acquisitions = []
        for entry in artifact.get('acquisitions') or []:
            manifest = entry.get('manifest') or {}
            acquisitions.append({
                'acquisition_id': entry.get('acquisition-id'),
                'discovery_url': manifest.get('discovery-url'), 'final_url': manifest.get('final-url'),
                'retrieved_at': manifest.get('retrieved-at'), 'relationship': manifest.get('relationship'),
                'source_sha256': manifest.get('sha256'),
                'content_type': manifest.get('content-type'),
            })
        content_type = (acquisitions[0].get('content_type') or '').lower() if acquisitions else ''
        media = 'pdf' if 'pdf' in content_type else 'html' if 'html' in content_type else 'json' if 'json' in content_type else 'other'
        job.update(acquisitions=acquisitions, publisher=(artifact.get('publication') or {}).get('publisher') if isinstance(artifact.get('publication'), dict) else None, media=media)
    if len(jobs) != len(extraction_rows):
        raise ValueError('duplicate extraction job in database dump')
    rows = dump_rows(dump, 'observations')
    parsed = edn_json_lines([row[5] for row in rows], EDN_TO_JSON)
    observations = []
    for row, candidate in zip(rows, parsed):
        imported = retained.get(row[0])
        if imported is None or candidate != imported[1]['candidates'][int(row[1])]:
            raise ValueError(f'observation payload differs from retained artifact: {row[0]} ordinal {row[1]}')
        observations.append({'job_id': row[0], 'ordinal': int(row[1]), 'candidate_id': row[2],
                             'kind': row[3], 'classification_reason': row[4], 'candidate': candidate})
    by_job = Counter(row['job_id'] for row in observations)
    for job_id, job in jobs.items():
        if len(retained[job_id][1].get('candidates') or []) != by_job[job_id]:
            raise ValueError(f'candidate count differs from immutable observations: {job_id}')
    excluded = [{'job_id': job_id, 'artifact_sha256': path.name,
                 'candidate_count': len(artifact.get('candidates') or []),
                 'reason': 'retained artifact absent from imported extraction table'}
                for job_id, (path, artifact) in sorted(retained.items()) if job_id not in jobs]
    receipt = json.loads((payload / 'run/corpus-status-audit.json').read_text())['corpus_snapshot']
    if len(jobs) != receipt['jobs'] or len(observations) != receipt['observation_versions']:
        raise ValueError('database rows differ from retained corpus receipt')
    if len({job['source_sha256'] for job in jobs.values()}) != receipt['source_hashes']:
        raise ValueError('database source hashes differ from retained corpus receipt')
    acquisition_paths = sorted((payload / 'archive/acquisitions').glob('*.edn'))
    acquisition_docs = edn_json_lines([str(path) for path in acquisition_paths],
                                      '''(require '[clojure.edn :as edn] '[cheshire.core :as json])
(with-open [r (clojure.java.io/reader *in*)]
  (doseq [line (line-seq r)] (println (json/generate-string (edn/read-string (slurp line))))))''')
    all_acquisitions = []
    for path, manifest in zip(acquisition_paths, acquisition_docs):
        source_hash = manifest['sha256']
        source_object = payload / 'archive/objects' / source_hash
        if not source_object.is_file() or sha_bytes(source_object.read_bytes()) != source_hash:
            raise ValueError(f'acquired source bytes unavailable or mismatched: {source_hash}')
        all_acquisitions.append({'acquisition_id': path.stem, 'source_sha256': source_hash,
                                 'discovery_url': manifest.get('discovery-url'),
                                 'final_url': manifest.get('final-url'),
                                 'retrieved_at': manifest.get('retrieved-at'),
                                 'relationship': manifest.get('relationship'),
                                 'content_type': manifest.get('content-type'),
                                 'publisher': manifest.get('publisher')})
    if len(all_acquisitions) != 107 or len({a['source_sha256'] for a in all_acquisitions}) != 105:
        raise ValueError('acquisition count/hash count differs from retained bundle receipt')
    return sorted(jobs.values(), key=lambda job: job['job_id']), observations, excluded, all_acquisitions, sha_bytes(index_bytes), sha_bytes(dump.read_bytes())


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundle', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--cutoff', required=True, help='ISO 8601 dated census cutoff')
    args = parser.parse_args(argv)
    try:
        cutoff_time = datetime.fromisoformat(args.cutoff.replace('Z', '+00:00'))
        if cutoff_time.tzinfo is None:
            raise ValueError('cutoff needs timezone')
        root = Path(__file__).resolve().parents[1]
        destination = args.output.resolve()
        if destination.is_relative_to(root):
            ignored = subprocess.run(['git', '-C', str(root), 'check-ignore', '-q', str(destination)],
                                     check=False)
            if ignored.returncode != 0:
                raise ValueError('private output inside repository must be ignored by Git')
        jobs, observations, excluded, acquisitions, index_hash, dump_hash = load_bundle(args.bundle)
        doc = project_records(jobs, observations, args.cutoff, acquisitions)
        summary = validate(doc)
        source_job_counts = Counter(job['source_sha256'] for job in jobs)
        doc['projection_provenance'] = {'bundle_index_sha256': index_hash,
                                        'database_dump_sha256': dump_hash,
                                        'retained_acquisitions': len(acquisitions),
                                        'retained_artifact_jobs': len(jobs) + len(excluded),
                                        'retained_artifact_candidates': len(observations) + sum(item['candidate_count'] for item in excluded),
                                        'retained_only_jobs': len(excluded),
                                        'retained_only_candidates': sum(item['candidate_count'] for item in excluded),
                                        'retained_source_hashes': len({a['source_sha256'] for a in acquisitions}),
                                        'imported_jobs': len(jobs),
                                        'imported_observation_versions': len(observations),
                                        'retained_only_artifacts': excluded,
                                        'repeated_source_hashes': [{'source_sha256': key, 'job_count': count}
                                                                   for key, count in sorted(source_job_counts.items()) if count > 1],
                                        'reconciliation': summary['counts']}
        encoded = (json.dumps(doc, sort_keys=True, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_bytes(encoded)
        print(json.dumps({'output': str(args.output), 'sha256': sha_bytes(encoded),
                          'counts': summary['counts'], 'retained_only_artifacts': excluded}, sort_keys=True))
        return 0
    except (OSError, ValueError, subprocess.CalledProcessError, json.JSONDecodeError) as error:
        print(f'projection rejected: {error}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
