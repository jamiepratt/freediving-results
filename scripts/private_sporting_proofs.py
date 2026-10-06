"""Narrow read-only exact source proof transport with pinned persistent runtime."""
import hashlib
import json
import os
from pathlib import Path
import re
import time
from sporting_authority import digest, ConflictError
from private_attempt_inspector import _Runtime, _remaining, READ_BUDGET_SECONDS

SCHEMA = 'private-sporting-proofs/v1'
SOURCES = ('private_sporting_proofs', 'publication', 'event_selections', 'revisions',
           'canonical_attempt_store', 'source_relationships', 'source_scope', 'html_evidence',
           'aida_html', 'archive', 'vestico_2025', 'reconciliation_flow', 'reconciliation_budget',
           'reconciliation_jev', 'reconciliation_policy', 'source_accuracy_review', 'reviews', 'candidates')
JARS = ('clojure-1.12.0.jar', 'data.json-2.5.1.jar', 'postgresql-42.7.8.jar',
        'core.specs.alpha-0.4.74.jar', 'spec.alpha-0.5.238.jar', 'jsoup-1.21.2.jar')
RUNTIME_FILES = frozenset(['src/freediving/' + name + '.clj' for name in SOURCES]
                          + ['lib/' + name for name in JARS])
SERVE = '''(require '[clojure.data.json :as json]
                    '[freediving.private-sporting-proofs :as proofs])
(doseq [line (line-seq (java.io.BufferedReader. *in*))]
  (println (json/write-str
             (try (proofs/command (json/read-str line :key-fn keyword))
                  (catch Exception e {:schema "private-sporting-proofs/v1"
                                      :error "Source accuracy review unavailable"
                                      :status (let [s (:status (ex-data e))]
                                                (if (#{400 409 503} s) s 503))}))))
  (flush))'''


def create_reader(env):
    name = env.get('OWNER_EVIDENCE_SPORTING_PROOF_CONFIG')
    if not name:
        return None
    path = Path(name)
    engine = _Runtime(SCHEMA, 4 * 1024 * 1024, max_waiters=2)
    def verified():
        info = path.lstat()
        if (path.is_symlink() or not path.is_file() or info.st_uid not in (0, os.geteuid())
                or info.st_mode & 0o027 or info.st_size > 65536):
            raise ValueError('private sporting proof configuration permissions invalid')
        body = path.read_bytes(); data = json.loads(body)
        if (set(data) != {'jdbc_url', 'database', 'canonical_jdbc_url', 'canonical_database', 'runtime_path', 'runtime_manifest_sha256'}
                or not re.fullmatch('[a-f0-9]{64}', data['runtime_manifest_sha256'])):
            raise ValueError('invalid private sporting proof configuration')
        scopes = {'source': {'jdbc_url': data['jdbc_url'], 'database': data['database']},
                  'relationships': {'jdbc_url': data['canonical_jdbc_url'], 'database': data['canonical_database']}}
        for mode in ('source', 'relationships'):
            scope = scopes[mode]
            if (not isinstance(scope, dict) or set(scope) != {'jdbc_url', 'database'}
                    or not isinstance(scope['jdbc_url'], str)
                    or not scope['jdbc_url'].startswith('jdbc:postgresql://127.0.0.1:')
                    or not re.fullmatch('[A-Za-z_][A-Za-z0-9_]{0,62}', scope['database'])):
                raise ValueError('invalid private sporting proof scope configuration')
        root = Path(data['runtime_path'])
        manifest = root / 'manifest.json'
        if (not root.is_absolute() or any(p.is_symlink() for p in (manifest, *manifest.parents))
                or hashlib.sha256(manifest.read_bytes()).hexdigest() != data['runtime_manifest_sha256']):
            raise ValueError('private sporting proof runtime changed')
        files = json.loads(manifest.read_bytes())['files']
        if set(files) != RUNTIME_FILES:
            raise ValueError('private sporting proof runtime inventory changed')
        for relative, sha in files.items():
            member = root / relative
            if (member.is_symlink() or not member.is_file()
                    or hashlib.sha256(member.read_bytes()).hexdigest() != sha):
                raise ValueError('private sporting proof runtime member changed')
        return data, root, hashlib.sha256(body).hexdigest()
    verified()
    def read(rows, *, deadline=None):
        deadline = min(deadline if deadline is not None else float('inf'), time.monotonic() + READ_BUDGET_SECONDS)
        _remaining(deadline)
        if not isinstance(rows, list) or len(rows) > 400:
            raise ValueError('exact sporting proof request exceeds bound')
        data, root, config_sha = verified()
        command = ['/usr/bin/java', '-Xmx192m', '-XX:ActiveProcessorCount=1', '-XX:+UseSerialGC', '-cp',
                   str(root / 'src') + os.pathsep + os.pathsep.join(str(root / 'lib' / jar) for jar in JARS),
                   'clojure.main', '-e', SERVE]
        results = {}
        scopes = {'source': {'jdbc_url': data['jdbc_url'], 'database': data['database']},
                  'relationships': {'jdbc_url': data['canonical_jdbc_url'], 'database': data['canonical_database']}}
        for mode in ('source', 'relationships'):
            scope = scopes[mode]
            result = engine.exchange(command, {**scope, 'mode': mode, 'rows': rows}, deadline,
                                     data['runtime_manifest_sha256'])
            if (set(result) != {'schema', 'database', 'binding_sha256', 'rows'}
                    or result['schema'] != SCHEMA or result['database'] != scope['database']
                    or not isinstance(result['binding_sha256'], str)
                    or not re.fullmatch('[a-f0-9]{64}', result['binding_sha256'])
                    or not isinstance(result['rows'], list) or len(result['rows']) != len(rows)):
                raise ValueError('private sporting proof binding changed')
            for expected, returned in zip(rows, result['rows']):
                if (not isinstance(returned, dict) or set(returned) - {'reference', 'coordinates', 'upstream', 'diagnostics', 'public_reference', 'source_review'}
                        or any(returned.get(k) != expected[k] for k in ('reference', 'coordinates'))
                        or not isinstance(returned.get('upstream'), dict) or not isinstance(returned.get('diagnostics'), dict)):
                    raise ValueError('private sporting proof exact row changed')
            results[mode] = result
        merged = []
        for source, relationship in zip(results['source']['rows'], results['relationships']['rows']):
            if relationship['upstream']:
                raise ValueError('canonical relationships cannot grant typed sporting assertions')
            merged.append({**source, 'diagnostics': {**source['diagnostics'],
                           'canonical_relationships': relationship['diagnostics']}})
        bindings = {mode: result['binding_sha256'] for mode, result in results.items()}
        result = {'schema': SCHEMA, 'binding_sha256': digest(bindings),
                  'scope_bindings': bindings, 'rows': merged}
        fresh, _, fresh_sha = verified()
        if data != fresh or config_sha != fresh_sha:
            raise ValueError('private sporting proof pins changed during read')
        _remaining(deadline)
        return {**result, 'config_sha256': config_sha}
    def review(request, config_path, *, deadline=None, replay_only=False):
        deadline = min(deadline if deadline is not None else float('inf'), time.monotonic() + READ_BUDGET_SECONDS)
        _remaining(deadline)
        data, root, _ = verified()
        review_path = Path(config_path)
        info = review_path.lstat()
        if (review_path.is_symlink() or not review_path.is_file() or info.st_uid not in (0, os.geteuid())
                or info.st_mode & 0o027 or info.st_size > 65536):
            raise ValueError('private source review configuration permissions invalid')
        config = json.loads(review_path.read_bytes())
        if (not isinstance(config, dict) or set(config) != {'jdbc_url', 'database', 'runtime_path', 'runtime_manifest_sha256'}
                or not isinstance(config['jdbc_url'], str)
                or not config['jdbc_url'].startswith('jdbc:postgresql://127.0.0.1:')
                or config['database'] != data['database']):
            raise ValueError('invalid private source review configuration')
        if any(config[key] != data[key] for key in ('runtime_path', 'runtime_manifest_sha256')):
            raise ValueError('private source review runtime differs from proof runtime')
        command = ['/usr/bin/java', '-Xmx192m', '-XX:ActiveProcessorCount=1', '-XX:+UseSerialGC', '-cp',
                   str(root / 'src') + os.pathsep + os.pathsep.join(str(root / 'lib' / jar) for jar in JARS),
                   'clojure.main', '-e', SERVE]
        operation = {'op': 'source-review', 'jdbc_url': config['jdbc_url'],
                     'database': config['database'], 'request': request}
        if replay_only:
            operation['replay_only'] = True
        result = engine.exchange(command, operation, deadline,
                                data['runtime_manifest_sha256'])
        if result.get('schema') != SCHEMA:
            raise ValueError('private source review response schema changed')
        if 'error' in result:
            if set(result) != {'schema', 'error', 'status'} or result['status'] not in (400, 409, 503):
                raise ValueError('invalid private source review error')
            error = {409: ConflictError, 400: ValueError, 503: OSError}[result['status']]
            raise error('Source accuracy review unavailable')
        if (set(result) != {'schema', 'database', 'binding_sha256', 'receipt', 'replayed'}
                or result['database'] != config['database'] or not isinstance(result['binding_sha256'], str)
                or not re.fullmatch('[a-f0-9]{64}', result['binding_sha256'])
                or not isinstance(result['receipt'], dict) or not isinstance(result['replayed'], bool)
                or (replay_only and not result['replayed'])):
            raise ValueError('private source review binding changed')
        _remaining(deadline)
        return result
    read.review = review
    read.close = engine.close
    read.verify = lambda: bool(verified())
    read.deadline_supported = True
    read.diagnostics = engine.diagnostics
    return read
