#!/usr/bin/env python3
"""Private loopback viewer for a verified, immutable evidence snapshot."""

import argparse
from collections import deque
from hmac import compare_digest
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, HTTPServer
import getpass
import json
from pathlib import Path
import re
import secrets
import time
from urllib.parse import parse_qs, urlsplit

from unified_evidence_query import SnapshotQuery
from route_roster_query import RouteRosterQuery
from owner_source_view import OriginalSourceView, SourceViewError


ASSETS = {
    '/': ('evidence_workspace.html', 'text/html; charset=utf-8'),
    '/assets/app.js': ('evidence_workspace.js', 'text/javascript; charset=utf-8'),
    '/assets/app.css': ('evidence_workspace.css', 'text/css; charset=utf-8'),
}
FILTERS = {'source_name', 'collection', 'kind', 'event_name', 'date_from', 'date_to',
           'session', 'discipline', 'category', 'limit', 'offset'}
CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'"
COMPARISON_PATH = re.compile(r'^/api/comparison/([a-f0-9]{64})$')
ROATAN_PATH = re.compile(r'^/api/roatan/([1-9][0-9]{0,5})/(0|[1-9][0-9]{0,2})$')
SOURCE_VIEW_PATH = re.compile(r'^/api/source-view/([a-f0-9]{64})$')
SOURCE_PAGE_PATH = re.compile(r'^/api/source-view/([a-f0-9]{64})/page/([1-9][0-9]{0,2})$')
SOURCE_IMAGE_PATH = re.compile(r'^/api/source-view/([a-f0-9]{64})/image$')


class EvidenceServer(HTTPServer):
    def __init__(self, snapshot_dir, password, roster_dir=None, roster_sha256=None,
                 source_bundle_dir=None, source_bundle_sha256=None):
        if not password:
            raise ValueError('password is required')
        if bool(roster_dir) != bool(roster_sha256):
            raise ValueError('route roster configuration incomplete')
        if bool(source_bundle_dir) != bool(source_bundle_sha256):
            raise ValueError('source bundle configuration incomplete')
        self.snapshot_dir = snapshot_dir
        self.query = None
        self.roster = None
        self.roster_dir = roster_dir
        self.roster_sha256 = roster_sha256
        self.source_bundle_dir = source_bundle_dir
        self.source_bundle_sha256 = source_bundle_sha256
        self.source_view = None
        self.password = password
        self.sessions = {}
        self.login_attempts = deque()
        self.requests = deque()
        self.asset_dir = Path(__file__).resolve().parents[1] / 'resources'
        super().__init__(('127.0.0.1', 0), EvidenceHandler)

    def serve_forever(self, *args, **kwargs):
        try:
            super().serve_forever(*args, **kwargs)
        finally:
            if self.query is not None:
                self.query.close()

    def get_request(self):
        socket, address = super().get_request()
        socket.settimeout(5)
        return socket, address

    def snapshot(self):
        if self.query is None:
            self.query = SnapshotQuery(self.snapshot_dir)
            try:
                if self.roster_dir:
                    self.roster = RouteRosterQuery(self.roster_dir, self.roster_sha256, self.query)
                elif any(name in self.query.manifest['inputs'] for name in ('route-roster-v4', 'route-roster-v3')):
                    self.roster = RouteRosterQuery.from_snapshot(self.query)
                if self.source_bundle_dir:
                    self.source_view = OriginalSourceView(self.source_bundle_dir,
                                                          self.source_bundle_sha256,
                                                          self.query.manifest['snapshot_sha256'])
            except Exception:
                self.query.close()
                self.query = None
                raise
        return self.query


class EvidenceHandler(BaseHTTPRequestHandler):
    server: EvidenceServer

    def log_message(self, *_):
        # URLs, cookies and credentials never enter access logs.
        pass

    def _headers(self, status, content_type='text/plain; charset=utf-8', extra=None):
        self.send_response(status)
        self.send_header('Content-Type', content_type)
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Content-Security-Policy', CSP)
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.send_header('Referrer-Policy', 'no-referrer')
        self.send_header('X-Frame-Options', 'DENY')
        if extra:
            for key, value in extra.items():
                self.send_header(key, value)
        self.end_headers()

    def _reply(self, status, body=b'', content_type='text/plain; charset=utf-8', extra=None):
        self._headers(status, content_type, extra)
        self.wfile.write(body)

    def _json(self, value):
        self._reply(200, json.dumps(value, ensure_ascii=False).encode(), 'application/json; charset=utf-8')

    def _valid_host(self):
        return self.headers.get_all('Host', []) == [f'127.0.0.1:{self.server.server_port}']

    def _valid_origin(self):
        return self.headers.get_all('Origin', []) == [f'http://127.0.0.1:{self.server.server_port}']

    def _rate_allowed(self):
        now = time.monotonic()
        while self.server.requests and self.server.requests[0] < now - 60:
            self.server.requests.popleft()
        if len(self.server.requests) >= 120:
            self._reply(429)
            return False
        self.server.requests.append(now)
        return True

    def _authenticated(self):
        cookie = SimpleCookie()
        try:
            cookie.load(self.headers.get('Cookie', ''))
            token = cookie['session'].value
        except (KeyError, ValueError):
            return False
        expiry = self.server.sessions.get(token)
        if expiry is None or expiry < time.monotonic():
            self.server.sessions.pop(token, None)
            return False
        return True

    def _path(self):
        if len(self.path) > 2048 or '%' in self.path.split('?', 1)[0]:
            raise ValueError('invalid path')
        parsed = urlsplit(self.path)
        if parsed.scheme or parsed.netloc or parsed.fragment:
            raise ValueError('invalid path')
        return parsed

    def _filters(self, query, *, fixed_kind=None):
        values = parse_qs(query, keep_blank_values=True, strict_parsing=True, max_num_fields=12)
        allowed = FILTERS - ({'kind'} if fixed_kind else set())
        if set(values) - allowed or any(len(v) != 1 for v in values.values()):
            raise ValueError('invalid filters')
        if fixed_kind:
            values['kind'] = [fixed_kind]
        result = {key: value[0] for key, value in values.items()}
        for key in ('limit', 'offset'):
            if key in result:
                if not result[key].isdigit() or len(result[key]) > 6:
                    raise ValueError('invalid paging')
                result[key] = int(result[key])
        return result

    def _route_filters(self, query, routes=False):
        values = parse_qs(query, keep_blank_values=True, strict_parsing=True, max_num_fields=6)
        allowed = {'route_id', 'status', 'limit', 'offset'} if routes else {
            'route_id', 'status', 'year', 'relationship', 'limit', 'offset'}
        if set(values) - allowed or any(len(v) != 1 for v in values.values()):
            raise ValueError('invalid route filters')
        result = {key: value[0] for key, value in values.items()}
        for key in ('limit', 'offset'):
            if key in result:
                if not result[key].isdigit() or len(result[key]) > 6:
                    raise ValueError('invalid route paging')
                result[key] = int(result[key])
        return result

    def do_GET(self):
        if not self._rate_allowed():
            return
        if not self._valid_host():
            return self._reply(400)
        try:
            parsed = self._path()
        except ValueError:
            return self._reply(400)
        path = parsed.path
        if path == '/login' and not parsed.query:
            page = b'<!doctype html><meta charset="utf-8"><title>Private evidence login</title><form action="/login" method="post"><label>Password <input name="password" type="password" required autofocus></label><button>Unlock</button></form>'
            return self._reply(200, page, 'text/html; charset=utf-8')
        if not self._authenticated():
            return self._reply(401)
        if path in ASSETS and not parsed.query:
            name, content_type = ASSETS[path]
            return self._reply(200, (self.server.asset_dir / name).read_bytes(), content_type)
        try:
            if path == '/api/overview' and not parsed.query:
                result = self.server.snapshot().overview()
                result['normalized_federation'] = 'unavailable in this snapshot'
            elif path == '/api/sources' and not parsed.query:
                result = self.server.snapshot().sources()
            elif path == '/api/source':
                args = parse_qs(parsed.query, keep_blank_values=True, strict_parsing=True, max_num_fields=1)
                if set(args) != {'name'} or len(args['name']) != 1:
                    raise ValueError('invalid source')
                result = self.server.snapshot().source(args['name'][0])
            elif path == '/api/browse':
                result = self.server.snapshot().browse(**self._filters(parsed.query))
            elif path == '/api/queue':
                args = parse_qs(parsed.query, keep_blank_values=True, strict_parsing=True, max_num_fields=4)
                if set(args) - {'group', 'source_name', 'limit', 'offset'} or any(len(v) != 1 for v in args.values()):
                    raise ValueError('invalid queue filters')
                options = {key: value[0] for key, value in args.items()}
                for key in ('limit', 'offset'):
                    if key in options:
                        if not options[key].isdigit() or len(options[key]) > 6:
                            raise ValueError('invalid paging')
                        options[key] = int(options[key])
                result = self.server.snapshot().queue(**options)
            elif path == '/api/comparisons':
                args = parse_qs(parsed.query, keep_blank_values=True, strict_parsing=True, max_num_fields=2)
                if set(args) - {'limit', 'offset'} or any(len(v) != 1 for v in args.values()):
                    raise ValueError('invalid comparison filters')
                options = {}
                for key, value in args.items():
                    if not value[0].isdigit() or len(value[0]) > 6:
                        raise ValueError('invalid comparison paging')
                    options[key] = int(value[0])
                result = self.server.snapshot().comparisons(**options)
            elif path in ('/api/routes', '/api/route-leads'):
                self.server.snapshot()
                if self.server.roster is None:
                    return self._reply(503)
                if path.endswith('route-leads'):
                    result = self.server.roster.leads(**self._route_filters(parsed.query))
                else:
                    result = self.server.roster.routes(**self._route_filters(parsed.query, routes=True))
            elif path == '/api/roatan' and not parsed.query:
                result = self.server.snapshot().roatan_positions()
            elif ROATAN_PATH.fullmatch(path) and not parsed.query:
                unit, index = ROATAN_PATH.fullmatch(path).groups()
                result = self.server.snapshot().roatan_position(int(unit), int(index))
            elif COMPARISON_PATH.fullmatch(path) and not parsed.query:
                result = self.server.snapshot().comparison(COMPARISON_PATH.fullmatch(path).group(1))
                if result is not None:
                    for side in result['sides']:
                        viewer = self.server.source_view
                        item = viewer.item_for(side['source_object_id']) if viewer else None
                        if item is None:
                            side['source_view'] = {'status': 'unavailable', 'reason': 'Original source is absent from this private bundle'}
                        elif item.get('status') == 'restricted':
                            side['source_view'] = {'status': 'restricted', 'reason': item.get('reason') or 'Original access restricted',
                                                   'safe_derivative': bool(item.get('derivative'))}
                        elif item.get('status') != 'included':
                            side['source_view'] = {'status': 'unavailable', 'reason': item.get('reason') or 'Original unavailable'}
                        elif side['collection'] == 'candidate_versions':
                            side['source_view'] = {'status': 'unavailable', 'reason': 'Open the linked imported row for citation replay'}
                        else:
                            side['source_view'] = {'status': 'present', 'reason': 'Verified private bundle citation replay is available'}
            elif SOURCE_PAGE_PATH.fullmatch(path) and not parsed.query:
                if self.server.source_view is None:
                    self.server.snapshot()
                if self.server.source_view is None:
                    return self._reply(503)
                record_id, page = SOURCE_PAGE_PATH.fullmatch(path).groups()
                image = self.server.source_view.page(self.server.snapshot().detail(record_id), int(page))
                return self._reply(200, image, 'image/png')
            elif SOURCE_IMAGE_PATH.fullmatch(path) and not parsed.query:
                self.server.snapshot()
                if self.server.source_view is None:
                    return self._reply(503)
                record_id = SOURCE_IMAGE_PATH.fullmatch(path).group(1)
                image = self.server.source_view.image(self.server.snapshot().detail(record_id))
                return self._reply(200, image, 'image/jpeg')
            elif SOURCE_VIEW_PATH.fullmatch(path) and not parsed.query:
                self.server.snapshot()
                if self.server.source_view is None:
                    return self._reply(503)
                result = self.server.source_view.inspect(
                    self.server.snapshot().detail(SOURCE_VIEW_PATH.fullmatch(path).group(1)))
            elif path in ('/api/gaps', '/api/relationships'):
                kind = 'gap' if path.endswith('gaps') else 'relationship'
                result = self.server.snapshot().browse(**self._filters(parsed.query, fixed_kind=kind))
            elif path.startswith('/api/detail/') and not parsed.query:
                result = self.server.snapshot().detail(path.removeprefix('/api/detail/'))
            else:
                return self._reply(404)
        except SourceViewError as exc:
            return self._reply(exc.status)
        except (ValueError, KeyError):
            return self._reply(400)
        if result is None:
            return self._reply(404)
        return self._json(result)

    def do_POST(self):
        if not self._rate_allowed():
            return
        length = self.headers.get('Content-Length', '')
        if not length.isdigit() or int(length) > 4096:
            return self._reply(413)
        body = self.rfile.read(int(length))
        if not self._valid_host() or not self._valid_origin():
            return self._reply(403)
        try:
            path = self._path()
        except ValueError:
            return self._reply(400)
        if path.path not in ('/login', '/logout') or path.query:
            return self._reply(405)
        if self.headers.get('Content-Type') != 'application/x-www-form-urlencoded':
            return self._reply(415)
        if path.path == '/logout':
            if not self._authenticated() or body:
                return self._reply(403)
            cookie = SimpleCookie(self.headers['Cookie'])
            self.server.sessions.pop(cookie['session'].value, None)
            return self._reply(303, extra={'Location': '/login', 'Set-Cookie': 'session=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0'})
        now = time.monotonic()
        while self.server.login_attempts and self.server.login_attempts[0] < now - 60:
            self.server.login_attempts.popleft()
        if len(self.server.login_attempts) >= 5:
            return self._reply(429)
        self.server.login_attempts.append(now)
        try:
            values = parse_qs(body.decode('utf-8'), keep_blank_values=True, strict_parsing=True, max_num_fields=1)
        except (UnicodeDecodeError, ValueError):
            return self._reply(400)
        if set(values) != {'password'} or len(values['password']) != 1 or not compare_digest(values['password'][0], self.server.password):
            return self._reply(403)
        token = secrets.token_urlsafe(32)
        self.server.sessions[token] = now + 3600
        return self._reply(303, extra={'Location': '/', 'Set-Cookie': f'session={token}; HttpOnly; SameSite=Strict; Path=/; Max-Age=3600'})

    def do_PUT(self):
        if not self._rate_allowed():
            return
        self._reply(405)

    do_PATCH = do_PUT
    do_DELETE = do_PUT


def make_server(snapshot_dir, password, roster_dir=None, roster_sha256=None,
                source_bundle_dir=None, source_bundle_sha256=None):
    return EvidenceServer(snapshot_dir, password, roster_dir, roster_sha256,
                          source_bundle_dir, source_bundle_sha256)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--snapshot-dir', required=True)
    parser.add_argument('--roster-dir')
    parser.add_argument('--roster-sha256')
    parser.add_argument('--source-bundle-dir')
    parser.add_argument('--source-bundle-sha256')
    args = parser.parse_args()
    password = getpass.getpass('Local evidence password: ')
    with make_server(args.snapshot_dir, password, args.roster_dir, args.roster_sha256,
                     args.source_bundle_dir, args.source_bundle_sha256) as server:
        print(f'Open http://127.0.0.1:{server.server_port}/login', flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == '__main__':
    main()
