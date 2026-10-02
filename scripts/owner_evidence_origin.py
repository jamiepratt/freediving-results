#!/usr/bin/env python3
"""Owner evidence origin behind the verified Cloudflare Worker gate."""

import argparse
from hmac import compare_digest
import hmac
from hashlib import sha256
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
from pathlib import Path
import re
import sqlite3
from urllib.parse import parse_qs, urlsplit

from unified_evidence_query import SnapshotQuery
from owner_source_view import OriginalSourceView, SourceViewError
from route_roster_query import RouteRosterQuery
from owner_decision_store import ConflictError


PUBLIC_ORIGIN = 'https://poc.alphacompose.com'
CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; form-action 'none'; base-uri 'none'; frame-ancestors 'none'"
FILTERS = {'source_name', 'collection', 'kind', 'event_name', 'date_from', 'date_to',
           'session', 'discipline', 'category', 'limit', 'offset'}
MAX_RESPONSE = 2 * 1024 * 1024
MAX_ACTION = 16 * 1024
DECISION_PATH = re.compile(r'^/owner-evidence/api/decisions/([A-Za-z0-9_-]{1,128})$')
DECISION_PREVIEW_PATH = re.compile(r'^/owner-evidence/api/decisions/([A-Za-z0-9_-]{1,128})/preview$')
DECISION_ACTION_PATH = re.compile(r'^/owner-evidence/api/decisions/([A-Za-z0-9_-]{1,128})/actions$')
DETAIL_PATH = re.compile(r'^/owner-evidence/api/detail/([a-f0-9]{64})$')
SOURCE_VIEW_PATH = re.compile(r'^/owner-evidence/api/source-view/([a-f0-9]{64})$')
COMPARISON_PATH = re.compile(r'^/owner-evidence/api/comparison/([a-f0-9]{64})$')
ROATAN_PATH = re.compile(r'^/owner-evidence/api/roatan/([1-9][0-9]{0,5})/(0|[1-9][0-9]{0,2})$')
SOURCE_PAGE_PATH = re.compile(r'^/owner-evidence/api/source-view/([a-f0-9]{64})/page/([1-9][0-9]{0,2})$')
SOURCE_IMAGE_PATH = re.compile(r'^/owner-evidence/api/source-view/([a-f0-9]{64})/image$')
HOST_PATTERN = re.compile(r'^[a-z0-9-]+\.alphacompose\.com$')
EMAIL_PATTERN = re.compile(r'^[^\s,@]+@[^\s,@]+\.[^\s,@]+$')
STATIC = {
    '/owner-evidence': ('html', 'text/html; charset=utf-8'),
    '/owner-evidence/': ('html', 'text/html; charset=utf-8'),
    '/owner-evidence/assets/app.js': ('js', 'text/javascript; charset=utf-8'),
    '/owner-evidence/assets/app.css': ('css', 'text/css; charset=utf-8'),
}


def _source_availability(source_view, side):
    if source_view is None:
        return {'status': 'unavailable', 'reason': 'Private source bundle is not configured'}
    item = source_view.item_for(side['source_object_id'])
    if item is None:
        return {'status': 'unavailable', 'reason': 'Original source is absent from this private bundle'}
    if item.get('status') == 'restricted':
        return {'status': 'restricted', 'reason': item.get('reason') or 'Original access restricted',
                'safe_derivative': bool(item.get('derivative'))}
    if item.get('status') != 'included':
        return {'status': 'unavailable', 'reason': item.get('reason') or 'Original unavailable'}
    if side['collection'] == 'candidate_versions':
        return {'status': 'unavailable', 'reason': 'Retained artifact citation cannot be replayed by the original source viewer; inspect the linked imported row'}
    return {'status': 'present', 'reason': 'Original is in the verified private bundle; citation replay is checked when opened'}


def _replace_exact(value, old, new):
    if old not in value:
        raise ValueError('workspace asset contract changed')
    return value.replace(old, new)


def _assets():
    root = Path(__file__).resolve().parents[1] / 'resources'
    html = (root / 'evidence_workspace.html').read_text(encoding='utf-8')
    html = _replace_exact(html, 'href="/assets/app.css"', 'href="/owner-evidence/assets/app.css"')
    html = _replace_exact(html, 'src="/assets/app.js"', 'src="/owner-evidence/assets/app.js"')
    html = re.sub(r'<form action="/logout" method="post">.*?</form>', '', html, count=1)
    if '/logout' in html:
        raise ValueError('workspace logout asset contract changed')
    js = (root / 'evidence_workspace.js').read_text(encoding='utf-8')
    js = _replace_exact(js, "'/api/", "'/owner-evidence/api/")
    js = _replace_exact(js, "location.href='/login';", "throw new Error('Owner access expired');")
    if "'/login'" in js or "'/api/" in js:
        raise ValueError('workspace API asset contract changed')
    css = (root / 'evidence_workspace.css').read_bytes()
    return {'html': html.encode('utf-8'), 'js': js.encode('utf-8'), 'css': css}


def _config(env):
    secret = env.get('OWNER_EVIDENCE_GATEWAY_SECRET')
    host = env.get('OWNER_EVIDENCE_ORIGIN_HOST')
    emails = env.get('OWNER_EVIDENCE_EMAILS')
    digest = env.get('OWNER_EVIDENCE_SNAPSHOT_SHA256')
    if not isinstance(secret, str) or len(secret) < 16 or len(secret) > 256 or not secret.isascii() or any(c.isspace() for c in secret):
        raise ValueError('invalid private origin configuration')
    if not isinstance(host, str) or not HOST_PATTERN.fullmatch(host) or host in ('poc.alphacompose.com', 'poc-origin.alphacompose.com'):
        raise ValueError('invalid private origin configuration')
    if not isinstance(digest, str) or not re.fullmatch(r'[a-f0-9]{64}', digest):
        raise ValueError('invalid private origin configuration')
    if not isinstance(emails, str):
        raise ValueError('invalid private origin configuration')
    owners = emails.split(',')
    if not owners or any(not EMAIL_PATTERN.fullmatch(email) or email != email.lower() for email in owners) or len(set(owners)) != len(owners):
        raise ValueError('invalid private origin configuration')
    return secret, host, frozenset(owners), digest


class PrivateOrigin(HTTPServer):
    def __init__(self, snapshot_dir, env, port=0, canonical_reader=None):
        self.secret, self.expected_host, self.owners, expected_digest = _config(env)
        self.canonical_reader = canonical_reader
        self.assets = _assets()
        try:
            self.query = SnapshotQuery(snapshot_dir)
        except (ValueError, OSError, KeyError, sqlite3.Error) as exc:
            raise ValueError('invalid private snapshot') from exc
        try:
            if not compare_digest(self.query.manifest['snapshot_sha256'], expected_digest):
                raise ValueError('snapshot does not match configured digest')
            # HTTPServer handles one request at a time in its serving thread.
            # Reopen the verified immutable database for that thread.
            self.query.db.close()
            path = (Path(snapshot_dir) / 'snapshot.sqlite').resolve()
            self.query.db = sqlite3.connect(path.as_uri() + '?mode=ro&immutable=1',
                                            uri=True, check_same_thread=False)
            self.query.db.row_factory = sqlite3.Row
            bundle_dir = env.get('OWNER_EVIDENCE_SOURCE_BUNDLE_DIR')
            bundle_sha = env.get('OWNER_EVIDENCE_SOURCE_BUNDLE_SHA256')
            if bool(bundle_dir) != bool(bundle_sha):
                raise ValueError('source bundle configuration incomplete')
            self.source_view = OriginalSourceView(bundle_dir, bundle_sha, expected_digest) if bundle_dir else None
            roster_dir = env.get('OWNER_EVIDENCE_ROSTER_DIR')
            roster_sha = env.get('OWNER_EVIDENCE_ROSTER_SHA256')
            if bool(roster_dir) != bool(roster_sha):
                raise ValueError('route roster configuration incomplete')
            self.roster = (RouteRosterQuery(roster_dir, roster_sha, self.query) if roster_dir else
                           RouteRosterQuery.from_snapshot(self.query)
                           if any(name in self.query.manifest['inputs'] for name in ('route-roster-v4', 'route-roster-v3')) else None)
            decision_db = env.get('OWNER_EVIDENCE_DECISION_DB')
            self.decisions = None
            self.import_token = env.get('OWNER_EVIDENCE_IMPORT_TOKEN')
            self.import_client_id = env.get('OWNER_EVIDENCE_IMPORT_CLIENT_ID')
            if self.import_token is not None and (len(self.import_token) < 24 or
                    len(self.import_token) > 256 or not self.import_token.isascii() or
                    any(char.isspace() for char in self.import_token)):
                raise ValueError('invalid owner import token')
            if self.import_client_id is not None and not re.fullmatch(r'[A-Za-z0-9_-]{8,128}\.access', self.import_client_id):
                raise ValueError('invalid owner import client ID')
            if decision_db:
                from owner_decision_store import DecisionStore
                decision_path = Path(decision_db).resolve()
                if decision_path.is_relative_to(Path(snapshot_dir).resolve()):
                    raise ValueError('decision DB must be independent of snapshot')
                self.decisions = DecisionStore(decision_path)
                if self.decisions.projection()['snapshot_sha256'] != expected_digest:
                    self.decisions.bind_verified_snapshot(snapshot_dir,
                        expected_revision=self.decisions.revision,
                        idempotency_key='snapshot-' + expected_digest)
            super().__init__(('127.0.0.1', port), PrivateOriginHandler)
        except Exception:
            self.query.close()
            raise

    def server_close(self):
        super().server_close()
        self.query.close()
        if self.decisions is not None and hasattr(self.decisions, 'close'):
            self.decisions.close()

    def get_request(self):
        sock, address = super().get_request()
        sock.settimeout(5)
        return sock, address


class PrivateOriginHandler(BaseHTTPRequestHandler):
    server: PrivateOrigin
    protocol_version = 'HTTP/1.1'

    def log_message(self, *_):
        pass

    def _reply(self, status, body=b'', content_type='text/plain; charset=utf-8'):
        if len(body) > MAX_RESPONSE:
            status, body, content_type = 413, b'', 'text/plain; charset=utf-8'
        self.send_response(status)
        for name, value in (
                ('Content-Type', content_type), ('Content-Length', str(len(body))),
                ('Cache-Control', 'no-store'), ('Content-Security-Policy', CSP),
                ('X-Content-Type-Options', 'nosniff'), ('Referrer-Policy', 'no-referrer'),
                ('X-Frame-Options', 'DENY')):
            self.send_header(name, value)
        self.end_headers()
        if self.command != 'HEAD':
            self.wfile.write(body)

    def _one(self, name):
        values = self.headers.get_all(name, [])
        return values[0] if len(values) == 1 else None

    def _authorized(self, body=False):
        if self._one('Host') != self.server.expected_host:
            return False
        if self.headers.get_all('Origin', []) not in ([], [PUBLIC_ORIGIN]):
            return False
        if self.headers.get_all('Transfer-Encoding', []) or (not body and self.headers.get_all('Content-Length', [])):
            return False
        if self.headers.get_all('Cookie', []) or self.headers.get_all('Authorization', []) or self.headers.get_all('Cf-Access-Jwt-Assertion', []):
            return False
        gateway = self._one('X-Freediving-Owner-Gateway')
        if gateway is None or len(gateway) > 256 or not compare_digest(gateway, self.server.secret):
            return False
        if urlsplit(self.path).path == '/owner-evidence/api/decision-events':
            machine = self._one('X-Freediving-Owner-Machine')
            token = self._one('X-Freediving-Import-Token')
            return (not self.headers.get_all('X-Freediving-Owner-Email', []) and
                    self.server.import_client_id is not None and machine is not None and
                    compare_digest(machine, self.server.import_client_id) and
                    self.server.import_token is not None and token is not None and
                    compare_digest(token, self.server.import_token))
        email = self._one('X-Freediving-Owner-Email')
        return email is not None and len(email) <= 254 and email in self.server.owners

    def _csrf(self):
        email = self._one('X-Freediving-Owner-Email')
        return hmac.new(self.server.secret.encode('ascii'),
                        ('decision-csrf-v1:' + email).encode('utf-8'), sha256).hexdigest()

    def _decision_filters(self, query):
        args = parse_qs(query, keep_blank_values=True, strict_parsing=True, max_num_fields=5)
        if set(args) - {'type', 'status', 'source', 'limit', 'offset'} or any(len(v) != 1 for v in args.values()):
            raise ValueError('invalid decision filters')
        values = {key: v[0] for key, v in args.items()}
        result = {'decision_type': values.get('type') or None,
                  'status': (values['status'] or None) if 'status' in values else 'pending',
                  'source_name': values.get('source') or None, 'limit': 50, 'offset': 0}
        for key in ('limit', 'offset'):
            if key in values:
                if not values[key].isdigit() or len(values[key]) > 6:
                    raise ValueError('invalid paging')
                result[key] = int(values[key])
        if not 1 <= result['limit'] <= 100 or result['offset'] > 100000:
            raise ValueError('invalid paging')
        return result

    def _path(self):
        if len(self.path) > 2048 or '%' in self.path.split('?', 1)[0] or '#' in self.path:
            raise ValueError('invalid path')
        parsed = urlsplit(self.path)
        if parsed.scheme or parsed.netloc or parsed.fragment or not parsed.path.startswith('/'):
            raise ValueError('invalid path')
        return parsed

    def _filters(self, query, fixed_kind=None):
        args = parse_qs(query, keep_blank_values=True, strict_parsing=True, max_num_fields=12)
        if set(args) - (FILTERS - ({'kind'} if fixed_kind else set())) or any(len(v) != 1 for v in args.values()):
            raise ValueError('invalid filters')
        if fixed_kind:
            args['kind'] = [fixed_kind]
        result = {key: value[0] for key, value in args.items()}
        for key in ('limit', 'offset'):
            if key in result:
                if not result[key].isdigit() or len(result[key]) > 6:
                    raise ValueError('invalid paging')
                result[key] = int(result[key])
        return result

    def _queue_filters(self, query):
        args = parse_qs(query, keep_blank_values=True, strict_parsing=True, max_num_fields=4)
        if set(args) - {'group', 'source_name', 'limit', 'offset'} or any(len(v) != 1 for v in args.values()):
            raise ValueError('invalid queue filters')
        result = {key: value[0] for key, value in args.items()}
        for key in ('limit', 'offset'):
            if key in result:
                if not result[key].isdigit() or len(result[key]) > 6:
                    raise ValueError('invalid paging')
                result[key] = int(result[key])
        return result

    def _comparison_filters(self, query):
        args = parse_qs(query, keep_blank_values=True, strict_parsing=True, max_num_fields=2)
        if set(args) - {'limit', 'offset'} or any(len(v) != 1 for v in args.values()):
            raise ValueError('invalid comparison filters')
        result = {}
        for key, value in args.items():
            if not value[0].isdigit() or len(value[0]) > 6:
                raise ValueError('invalid comparison paging')
            result[key] = int(value[0])
        return result

    def _route_filters(self, query, routes=False):
        args = parse_qs(query, keep_blank_values=True, strict_parsing=True, max_num_fields=6)
        allowed = {'route_id', 'status', 'limit', 'offset'} if routes else {
            'route_id', 'status', 'year', 'relationship', 'limit', 'offset'}
        if set(args) - allowed or any(len(values) != 1 for values in args.values()):
            raise ValueError('invalid route filters')
        result = {key: values[0] for key, values in args.items()}
        for key in ('limit', 'offset'):
            if key in result:
                if not result[key].isdigit() or len(result[key]) > 6:
                    raise ValueError('invalid route paging')
                result[key] = int(result[key])
        return result

    def _json(self, value):
        self._reply(200, json.dumps(value, ensure_ascii=False).encode('utf-8'), 'application/json; charset=utf-8')

    def do_GET(self):
        if not self._authorized():
            return self._reply(403)
        try:
            parsed = self._path()
        except ValueError:
            return self._reply(404)
        path = parsed.path
        if path in STATIC and not parsed.query:
            key, content_type = STATIC[path]
            return self._reply(200, self.server.assets[key], content_type)
        try:
            query = self.server.query
            if path == '/owner-evidence/api/overview' and not parsed.query:
                result = query.overview()
                result['normalized_federation'] = 'unavailable in this snapshot'
            elif path == '/owner-evidence/api/sources' and not parsed.query:
                result = query.sources()
            elif path == '/owner-evidence/api/source':
                args = parse_qs(parsed.query, keep_blank_values=True, strict_parsing=True, max_num_fields=1)
                if set(args) != {'name'} or len(args['name']) != 1:
                    raise ValueError('invalid source')
                result = query.source(args['name'][0])
            elif path == '/owner-evidence/api/browse':
                result = query.browse(**self._filters(parsed.query))
            elif path == '/owner-evidence/api/queue':
                result = query.queue(**self._queue_filters(parsed.query))
            elif path == '/owner-evidence/api/decisions':
                if self.server.decisions is None:
                    return self._reply(503)
                result = self.server.decisions.queue(**self._decision_filters(parsed.query))
                result['csrf_token'] = self._csrf()
                result['active_snapshot_sha256'] = self.server.decisions.projection()['snapshot_sha256']
                result['canonical_projection_status'] = 'unavailable'
            elif path == '/owner-evidence/api/canonical-projection' and not parsed.query:
                if self.server.canonical_reader is None:
                    return self._reply(503)
                try:
                    result = self.server.canonical_reader()
                except Exception:
                    return self._reply(503)
            elif path == '/owner-evidence/api/decision-events':
                token = self._one('X-Freediving-Import-Token')
                if self.server.import_token is None or token is None or len(token) > 256 or not compare_digest(token, self.server.import_token):
                    return self._reply(403)
                if self.server.decisions is None:
                    return self._reply(503)
                args = parse_qs(parsed.query, strict_parsing=True, max_num_fields=1)
                if set(args) != {'after_revision'} or len(args['after_revision']) != 1 or not re.fullmatch(r'[0-9]{1,12}', args['after_revision'][0]):
                    raise ValueError('invalid event cursor')
                feed = self.server.decisions.human_events(after_revision=int(args['after_revision'][0]))
                payload = json.dumps(feed, ensure_ascii=False, sort_keys=True, separators=(',', ':'))
                result = {'payload_json': payload,
                          'signature': hmac.new(self.server.import_token.encode('ascii'),
                                                payload.encode('utf-8'), sha256).hexdigest()}
            elif path == '/owner-evidence/api/decisions/audit-sample':
                if self.server.decisions is None:
                    return self._reply(503)
                args = parse_qs(parsed.query, strict_parsing=True, max_num_fields=1) if parsed.query else {}
                if set(args) - {'limit'} or any(len(v) != 1 for v in args.values()):
                    raise ValueError('invalid audit sample')
                limit = args.get('limit', ['10'])[0]
                if not limit.isdigit() or not 1 <= int(limit) <= 100:
                    raise ValueError('invalid audit sample')
                result = self.server.decisions.audit_sample(limit=int(limit))
            elif DECISION_PATH.fullmatch(path) and not parsed.query:
                if self.server.decisions is None:
                    return self._reply(503)
                result = self.server.decisions.inspect(DECISION_PATH.fullmatch(path).group(1))
            elif DECISION_PREVIEW_PATH.fullmatch(path):
                if self.server.decisions is None:
                    return self._reply(503)
                args = parse_qs(parsed.query, strict_parsing=True, max_num_fields=1)
                if set(args) != {'action'} or len(args['action']) != 1 or args['action'][0] not in ('approve','reject','reverse'):
                    raise ValueError('invalid preview action')
                result = self.server.decisions.preview(DECISION_PREVIEW_PATH.fullmatch(path).group(1), action=args['action'][0])
            elif path == '/owner-evidence/api/comparisons':
                result = query.comparisons(**self._comparison_filters(parsed.query))
            elif path in ('/owner-evidence/api/routes', '/owner-evidence/api/route-leads'):
                if self.server.roster is None:
                    return self._reply(503)
                if path.endswith('route-leads'):
                    result = self.server.roster.leads(**self._route_filters(parsed.query))
                else:
                    result = self.server.roster.routes(**self._route_filters(parsed.query, routes=True))
            elif path == '/owner-evidence/api/roatan' and not parsed.query:
                result = query.roatan_positions()
            elif ROATAN_PATH.fullmatch(path) and not parsed.query:
                unit, index = ROATAN_PATH.fullmatch(path).groups()
                result = query.roatan_position(int(unit), int(index))
            elif COMPARISON_PATH.fullmatch(path) and not parsed.query:
                result = query.comparison(COMPARISON_PATH.fullmatch(path).group(1))
                if result is not None:
                    for side in result['sides']:
                        side['source_view'] = _source_availability(self.server.source_view, side)
            elif path in ('/owner-evidence/api/gaps', '/owner-evidence/api/relationships'):
                kind = 'gap' if path.endswith('gaps') else 'relationship'
                result = query.browse(**self._filters(parsed.query, fixed_kind=kind))
            elif SOURCE_PAGE_PATH.fullmatch(path) and not parsed.query:
                if self.server.source_view is None:
                    return self._reply(503)
                record_id, page = SOURCE_PAGE_PATH.fullmatch(path).groups()
                image = self.server.source_view.page(query.detail(record_id), int(page))
                return self._reply(200, image, 'image/png')
            elif SOURCE_IMAGE_PATH.fullmatch(path) and not parsed.query:
                if self.server.source_view is None:
                    return self._reply(503)
                record_id = SOURCE_IMAGE_PATH.fullmatch(path).group(1)
                image = self.server.source_view.image(query.detail(record_id))
                return self._reply(200, image, 'image/jpeg')
            elif SOURCE_VIEW_PATH.fullmatch(path) and not parsed.query:
                if self.server.source_view is None:
                    return self._reply(503)
                result = self.server.source_view.inspect(query.detail(SOURCE_VIEW_PATH.fullmatch(path).group(1)))
            elif DETAIL_PATH.fullmatch(path) and not parsed.query:
                result = query.detail(DETAIL_PATH.fullmatch(path).group(1))
            else:
                return self._reply(404)
        except SourceViewError as exc:
            return self._reply(exc.status)
        except KeyError:
            return self._reply(404)
        except ValueError:
            return self._reply(400)
        except (sqlite3.Error, OSError):
            return self._reply(503)
        if result is None:
            return self._reply(404)
        return self._json(result)

    do_HEAD = do_GET

    def _unsupported(self):
        self.close_connection = True
        self._reply(405)

    def do_POST(self):
        if not self._authorized(body=True):
            return self._reply(403)
        try:
            parsed = self._path()
        except ValueError:
            return self._reply(404)
        match = DECISION_ACTION_PATH.fullmatch(parsed.path)
        if match is None or parsed.query:
            return self._unsupported()
        if self.server.decisions is None:
            return self._reply(503)
        if self._one('Origin') != PUBLIC_ORIGIN or self._one('Content-Type') != 'application/json':
            return self._reply(403)
        if self._one('X-Freediving-CSRF') != self._csrf():
            return self._reply(403)
        lengths = self.headers.get_all('Content-Length', [])
        if len(lengths) != 1 or not lengths[0].isdigit():
            return self._reply(400)
        length = int(lengths[0])
        if length > MAX_ACTION:
            return self._reply(413)
        if not length:
            return self._reply(400)
        try:
            body = json.loads(self.rfile.read(length))
            common = {'action', 'expected_revision', 'idempotency_key', 'reason', 'csrf_token'}
            if not isinstance(body, dict) or set(body) != (common | ({'correction'} if body.get('action') == 'correct' else set())):
                raise ValueError('invalid action')
            if not compare_digest(body['csrf_token'], self._csrf()):
                return self._reply(403)
            if body['action'] not in ('approve', 'reject', 'reverse', 'correct'):
                raise ValueError('invalid action')
            correction = body.get('correction')
            if body['action'] == 'correct':
                current = self.server.decisions.inspect(match.group(1))
                options = [current['selected_option'], *current['competing_options']]
                current_option = (current.get('correction') or {}).get('action', current['selected_option'])
                if (not isinstance(correction, dict) or set(correction) != {'action'} or
                        not isinstance(correction['action'], str) or
                        correction['action'] not in options or
                        correction['action'] == current_option):
                    raise ValueError('invalid correction option')
            result = self.server.decisions.act(match.group(1), action=body['action'],
                                               expected_revision=body['expected_revision'],
                                               idempotency_key=body['idempotency_key'], actor=self._one('X-Freediving-Owner-Email'),
                                               reason=body['reason'], **({'correction': correction} if correction else {}))
        except KeyError:
            return self._reply(404)
        except ConflictError:
            return self._reply(409)
        except (ValueError, TypeError, UnicodeDecodeError):
            return self._reply(400)
        except (sqlite3.Error, OSError):
            return self._reply(503)
        return self._json(result)
    do_PUT = _unsupported
    do_PATCH = _unsupported
    do_DELETE = _unsupported
    do_OPTIONS = _unsupported


def make_server(snapshot_dir, env=None, port=0, canonical_reader=None):
    return PrivateOrigin(snapshot_dir, os.environ if env is None else env, port,
                         canonical_reader=canonical_reader)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--snapshot-dir', required=True)
    parser.add_argument('--port', type=int, default=8081)
    args = parser.parse_args()
    with make_server(args.snapshot_dir, port=args.port) as server:
        print(f'Private origin listening on 127.0.0.1:{server.server_port}', flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == '__main__':
    main()
