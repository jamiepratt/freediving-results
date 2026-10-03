#!/usr/bin/env python3
"""Stage a pinned private owner snapshot and activate its loopback-only service.

Run on the VPS as root only after the external Access, tunnel and Worker gate
checks in the guarded activation workflow. No public release path is changed.
"""
import argparse
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import pwd
import re
import shutil
import stat
import subprocess
import tempfile
import time
import urllib.error
import urllib.request


SERVICE = 'freediving-owner-evidence.service'
FILES = ('scripts/owner_evidence_origin.py', 'scripts/owner_decision_store.py',
         'scripts/unified_evidence_query.py',
         'scripts/route_roster_query.py', 'scripts/owner_source_view.py',
         'scripts/private_source_bundle.py', 'scripts/vestico_safe_derivative.py',
         'resources/evidence_workspace.html', 'resources/evidence_workspace.js',
         'resources/evidence_workspace.css')
REQUIRED_ENV = frozenset(('OWNER_EVIDENCE_GATEWAY_SECRET', 'OWNER_EVIDENCE_ORIGIN_HOST',
                          'OWNER_EVIDENCE_EMAILS', 'OWNER_EVIDENCE_SNAPSHOT_SHA256'))


@dataclass(frozen=True)
class Layout:
    app: Path
    state: Path
    units: Path
    config: Path


def _regular(path):
    if path.is_symlink() or not path.is_file():
        raise ValueError('missing or linked private activation input')


def _sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def _config(path, owner_uid, expected):
    _regular(path)
    info = path.stat()
    if info.st_uid != owner_uid or info.st_mode & 0o077:
        raise ValueError('private environment file must be owner-only')
    values = {}
    for line in path.read_text(encoding='utf-8').splitlines():
        match = re.fullmatch(r'([A-Z_0-9]+)=([^\s"\']+)', line)
        if not match or match[1] in values:
            raise ValueError('invalid private environment file')
        values[match[1]] = match[2]
    if (not REQUIRED_ENV <= values.keys() or
            set(values) - REQUIRED_ENV - {'OWNER_EVIDENCE_DECISION_API_ENABLED'} or
            values.get('OWNER_EVIDENCE_DECISION_API_ENABLED', '1') != '1' or
            (expected is not None and values['OWNER_EVIDENCE_SNAPSHOT_SHA256'] != expected) or
            not re.fullmatch(r'[a-f0-9]{64}', values['OWNER_EVIDENCE_SNAPSHOT_SHA256'])):
        raise ValueError('private environment does not match snapshot')
    secret = values['OWNER_EVIDENCE_GATEWAY_SECRET']
    host = values['OWNER_EVIDENCE_ORIGIN_HOST']
    emails = values['OWNER_EVIDENCE_EMAILS'].split(',')
    if not (16 <= len(secret) <= 256 and secret.isascii() and
            host == 'owner-origin.alphacompose.com' and
            emails and len(set(emails)) == len(emails) and
            all(re.fullmatch(r'[^\s,@]+@[^\s,@]+\.[^\s,@]+', e) and e == e.lower() for e in emails)):
        raise ValueError('invalid private environment')
    return values


def _inputs(bundle, source, expected):
    if not re.fullmatch(r'[a-f0-9]{64}', expected):
        raise ValueError('invalid expected SHA256')
    if bundle.is_symlink() or source.is_symlink():
        raise ValueError('linked activation directory')
    for name in FILES:
        _regular(bundle / name)
    for name in ('manifest.json', 'snapshot.sqlite'):
        _regular(source / name)
    manifest = json.loads((source / 'manifest.json').read_text(encoding='utf-8'))
    if (manifest.get('schema') != 'unified-evidence-snapshot/v1' or
            manifest.get('snapshot_sha256') != expected or
            _sha(source / 'snapshot.sqlite') != expected):
        raise ValueError('snapshot manifest or content differs from pinned hash')


def _roster_inputs(source, expected, snapshot_digest):
    if not re.fullmatch(r'[a-f0-9]{64}', expected) or source.is_symlink():
        raise ValueError('invalid roster input')
    for name in ('manifest.json', 'roster.json'):
        _regular(source / name)
    manifest = json.loads((source / 'manifest.json').read_text(encoding='utf-8'))
    roster = json.loads((source / 'roster.json').read_text(encoding='utf-8'))
    if (manifest.get('schema') != 'issue55-route-receipts/v1' or
            manifest.get('roster_sha256') != expected or
            manifest.get('snapshot_sqlite_sha256') != snapshot_digest or
            roster.get('schema') != 'issue55-route-roster/v1' or
            _sha(source / 'roster.json') != expected):
        raise ValueError('roster manifest or content differs from pinned snapshot')


def _source_bundle_inputs(code, source, expected, snapshot_digest):
    if not re.fullmatch(r'[a-f0-9]{64}', expected) or source.is_symlink():
        raise ValueError('invalid source bundle input')
    _regular(source / 'manifest.json')
    if _sha(source / 'manifest.json') != expected:
        raise ValueError('source bundle manifest differs from pinned hash')
    subprocess.run(['python3', str(code / 'scripts/private_source_bundle.py'), 'verify',
                    '--bundle-dir', str(source)], check=True, capture_output=True)
    manifest = json.loads((source / 'manifest.json').read_text(encoding='utf-8'))
    included = [item for item in manifest['sources'] if item.get('status') == 'included']
    if not any(item.get('sha256') == snapshot_digest and
               item.get('content_type') == 'application/vnd.sqlite3' for item in included):
        raise ValueError('source bundle lacks selected snapshot')
    return [('manifest.json', source / 'manifest.json')] + [
        ('objects/' + item['sha256'], source / 'objects' / item['sha256'])
        for item in included]


def _atomic_link(link, target):
    link.parent.mkdir(parents=True, exist_ok=True)
    temporary = link.with_name(link.name + '.new')
    temporary.unlink(missing_ok=True)
    temporary.symlink_to(target)
    os.replace(temporary, link)


def _atomic_write(path, content, mode):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix='.write-', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(name, mode)
        os.replace(name, path)
        directory = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        Path(name).unlink(missing_ok=True)


def _stage_directory(parent, name, files, uid, gid, mode):
    parent.mkdir(parents=True, exist_ok=True)
    destination = parent / name
    if destination.exists():
        if destination.is_symlink() or not destination.is_dir():
            raise ValueError('invalid staged directory')
        for relative, source in files:
            staged = destination / relative
            _regular(staged)
            info = staged.stat()
            if (_sha(staged) != _sha(source) or info.st_mode & 0o777 != mode or
                    info.st_uid != uid or info.st_gid != gid):
                raise ValueError('staged content differs from pinned source')
        return destination
    work = Path(tempfile.mkdtemp(prefix='.stage-', dir=parent))
    try:
        for relative, source in files:
            target = work / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target, follow_symlinks=False)
            target.chmod(mode)
            os.chown(target, uid, gid)
        for directory in sorted((p for p in work.rglob('*') if p.is_dir()), reverse=True):
            directory.chmod(0o700 if mode == 0o600 else 0o755)
            os.chown(directory, uid, gid)
        work.chmod(0o700 if mode == 0o600 else 0o755)
        os.chown(work, uid, gid)
        os.replace(work, destination)
    finally:
        if work.exists():
            shutil.rmtree(work)
    return destination


def _health(values, expected, roster_digest=None):
    headers = {
        'Host': values['OWNER_EVIDENCE_ORIGIN_HOST'],
        'X-Freediving-Owner-Gateway': values['OWNER_EVIDENCE_GATEWAY_SECRET'],
        'X-Freediving-Owner-Email': values['OWNER_EVIDENCE_EMAILS'].split(',')[0],
    }
    url = 'http://127.0.0.1:8081/owner-evidence/api/overview'
    request = urllib.request.Request(url, headers=headers)
    for attempt in range(20):
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                if response.status != 200 or json.load(response).get('snapshot_sha256') != expected:
                    raise RuntimeError('private origin health check failed')
            break
        except urllib.error.HTTPError:
            raise
        except urllib.error.URLError:
            if attempt == 19:
                raise
            time.sleep(0.25)
    if roster_digest:
        route_request = urllib.request.Request(
            'http://127.0.0.1:8081/owner-evidence/api/routes', headers=headers)
        with urllib.request.urlopen(route_request, timeout=5) as response:
            if response.status != 200 or json.load(response).get('roster_sha256') != roster_digest:
                raise RuntimeError('private route roster health check failed')
    for change in ({'Host': 'poc.alphacompose.com'},
                   {'X-Freediving-Owner-Gateway': 'invalid'},
                   {'X-Freediving-Owner-Email': 'unlisted@example.invalid'}):
        request = urllib.request.Request(url, headers={**headers, **change})
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                raise RuntimeError('private origin accepted invalid identity')
        except urllib.error.HTTPError as exc:
            if exc.code != 403:
                raise RuntimeError('private origin returned unexpected denial') from exc


def _checkpoint_dir(layout):
    path = layout.state / 'activation-checkpoint'
    if path.is_symlink() or (path.exists() and not path.is_dir()):
        raise ValueError('invalid activation checkpoint')
    if path.exists() and (path.stat().st_uid != os.geteuid() or
                          path.stat().st_mode & 0o077):
        raise ValueError('activation checkpoint is not root-private')
    return path


def _checkpoint_status(path, status, candidate, previous):
    _atomic_write(path / 'status.json', json.dumps({
        'status': status, 'candidate': candidate, 'previous': previous,
    }, sort_keys=True).encode(), 0o600)


def _restore_checkpoint(layout, command):
    try:
        path = _checkpoint_dir(layout)
        status_path = path / 'status.json'
        if not status_path.exists():
            return
        _regular(status_path)
        if status_path.stat().st_mode & 0o077:
            raise ValueError('activation checkpoint is not root-private')
        record = json.loads(status_path.read_text())
        if record['status'] not in ('pending', 'failed', 'active'):
            raise ValueError('unknown activation checkpoint status')
        if record['status'] == 'pending' and not isinstance(record['previous'], dict):
            raise ValueError('invalid activation checkpoint previous state')
    except (ValueError, OSError, KeyError, json.JSONDecodeError):
        command('systemctl', 'stop', SERVICE)
        raise RuntimeError('private activation checkpoint invalid; service stopped')
    if record['status'] != 'pending':
        return
    previous = record['previous']
    try:
        for name in ('app', 'snapshot', 'roster', 'source'):
            target = previous[name]
            if target is not None and (not Path(target).is_dir() or Path(target).is_symlink()):
                raise ValueError('prior activation target missing')
        for name in ('config', 'env', 'unit'):
            if previous[name]:
                backup = path / ('before-' + name)
                _regular(backup)
                if backup.stat().st_mode & 0o077:
                    raise ValueError('prior activation backup is not private')
        if (type(previous['service_active']) is not bool or
                type(previous['service_enabled']) is not bool):
            raise ValueError('invalid prior service state')
        if (previous['app'] is None) != (previous['snapshot'] is None):
            raise ValueError('incomplete prior activation')
        _config(path / 'before-config', os.geteuid(),
                Path(previous['snapshot']).name if previous['snapshot'] else None)
        if previous['app'] is not None:
            prior_app = Path(previous['app'])
            prior_snapshot = Path(previous['snapshot'])
            prior_digest = prior_snapshot.name
            _inputs(prior_app, prior_snapshot, prior_digest)
            if previous['roster']:
                prior_roster = Path(previous['roster'])
                _roster_inputs(prior_roster, prior_roster.name, prior_digest)
            if previous['source']:
                prior_source = Path(previous['source'])
                _source_bundle_inputs(prior_app, prior_source, prior_source.name, prior_digest)
        for name, link in (('app', layout.app / 'current'),
                           ('snapshot', layout.state / 'current'),
                           ('roster', layout.state / 'current-roster'),
                           ('source', layout.state / 'current-source')):
            target = previous[name]
            if target is None:
                link.unlink(missing_ok=True)
            else:
                _atomic_link(link, Path(target))
        for name, target in (('config', layout.config),
                             ('env', layout.state / 'active.env'),
                             ('unit', layout.units / SERVICE)):
            backup = path / ('before-' + name)
            if previous[name]:
                _atomic_write(target, backup.read_bytes(), 0o644 if name == 'unit' else 0o600)
            else:
                target.unlink(missing_ok=True)
        command('systemctl', 'daemon-reload')
        if previous['service_active']:
            command('systemctl', 'restart', SERVICE)
        else:
            command('systemctl', 'stop', SERVICE)
        command('systemctl', 'enable' if previous['service_enabled'] else 'disable', SERVICE)
        _checkpoint_status(path, 'failed', record['candidate'], previous)
    except Exception:
        try:
            command('systemctl', 'stop', SERVICE)
        finally:
            raise RuntimeError('private activation recovery failed; checkpoint remains pending')


def _begin_checkpoint(layout, candidate, previous):
    path = _checkpoint_dir(layout)
    path.mkdir(mode=0o700, exist_ok=True)
    path.chmod(0o700)
    parent_fd = os.open(layout.state, os.O_RDONLY)
    try:
        os.fsync(parent_fd)
    finally:
        os.close(parent_fd)
    for name, target in (('config', layout.config), ('env', layout.state / 'active.env'),
                         ('unit', layout.units / SERVICE)):
        backup = path / ('before-' + name)
        if previous[name]:
            _regular(target)
            _atomic_write(backup, target.read_bytes(), 0o600)
        else:
            backup.unlink(missing_ok=True)
    _checkpoint_status(path, 'pending', candidate, previous)


def _system_command(*args):
    if args[0] == 'systemctl' and args[1] in ('is-active', 'is-enabled'):
        return subprocess.run(args, check=False, capture_output=True).returncode == 0
    subprocess.run(args, check=True)
    return True


def activate(bundle, source, expected, layout, *, roster_source=None, expected_roster_sha256=None,
             source_bundle=None, expected_source_manifest_sha256=None,
             update_config_pin=False, command=None, health=None,
             owner_uid=0, owner_gid=0):
    """Activate local inputs; raises with old links/unit restored on service failure."""
    bundle, source = Path(bundle), Path(source)
    command = command or _system_command
    if update_config_pin:
        _restore_checkpoint(layout, command)
    values = _config(layout.config, os.geteuid(), None if update_config_pin else expected)
    prior_pin = values['OWNER_EVIDENCE_SNAPSHOT_SHA256']
    if update_config_pin and (not source_bundle or not expected_source_manifest_sha256):
        raise ValueError('candidate pin requires a matching private source bundle')
    candidate_config = layout.config.read_bytes()
    if update_config_pin and prior_pin != expected:
        prior_assignment = ('OWNER_EVIDENCE_SNAPSHOT_SHA256=' + prior_pin).encode()
        new_assignment = ('OWNER_EVIDENCE_SNAPSHOT_SHA256=' + expected).encode()
        candidate_config = b''.join(
            line.replace(prior_assignment, new_assignment, 1)
            if line.startswith(prior_assignment) else line
            for line in candidate_config.splitlines(keepends=True))
    _inputs(bundle, source, expected)
    if bool(roster_source) != bool(expected_roster_sha256):
        raise ValueError('roster staging inputs incomplete')
    if roster_source:
        roster_source = Path(roster_source)
        _roster_inputs(roster_source, expected_roster_sha256, expected)
    if bool(source_bundle) != bool(expected_source_manifest_sha256):
        raise ValueError('source bundle staging inputs incomplete')
    source_files = None
    if source_bundle:
        source_bundle = Path(source_bundle)
        source_files = _source_bundle_inputs(bundle, source_bundle,
                                             expected_source_manifest_sha256, expected)
    unit_source = Path(__file__).with_name(SERVICE)
    _regular(unit_source)
    unit = unit_source.read_bytes()
    health = health or (lambda: _health(values, expected, expected_roster_sha256))
    code_hash = hashlib.sha256()
    for name in FILES:
        code_hash.update(name.encode())
        code_hash.update(bytes.fromhex(_sha(bundle / name)))
    app_version = code_hash.hexdigest()
    app_parent = layout.app / 'versions'
    snapshot_parent = layout.state / 'snapshots'
    roster_parent = layout.state / 'rosters'
    source_parent = layout.state / 'sources'
    layout.app.parent.mkdir(parents=True, exist_ok=True)
    layout.app.parent.chmod(0o755)
    layout.app.mkdir(exist_ok=True)
    layout.app.chmod(0o755)
    app_parent.mkdir(exist_ok=True)
    app_parent.chmod(0o755)
    layout.state.mkdir(parents=True, exist_ok=True)
    layout.state.chmod(0o711)
    os.chown(layout.state, os.geteuid(), os.getegid())
    decision_dir = layout.state / 'decisions'
    if decision_dir.is_symlink() or (decision_dir.exists() and not decision_dir.is_dir()):
        raise ValueError('invalid durable decision directory')
    decision_dir.mkdir(exist_ok=True)
    decision_dir.chmod(0o700)
    os.chown(decision_dir, owner_uid, owner_gid)
    decision_db = decision_dir / 'ledger.sqlite'
    if decision_db.is_symlink() or (decision_db.exists() and not decision_db.is_file()):
        raise ValueError('invalid durable decision DB')
    app = _stage_directory(app_parent, app_version,
                           [(name, bundle / name) for name in FILES], os.geteuid(),
                           os.getegid(), 0o644)
    snapshot = _stage_directory(snapshot_parent, expected,
                                [(name, source / name) for name in ('manifest.json', 'snapshot.sqlite')],
                                owner_uid, owner_gid, 0o600)
    snapshot_parent.chmod(0o711)
    roster = None
    if roster_source:
        roster = _stage_directory(roster_parent, expected_roster_sha256,
                                  [(name, roster_source / name) for name in ('manifest.json', 'roster.json')],
                                  owner_uid, owner_gid, 0o600)
        roster_parent.chmod(0o711)
        _roster_inputs(roster, expected_roster_sha256, expected)
    staged_source = None
    if source_files:
        staged_source = _stage_directory(source_parent, expected_source_manifest_sha256,
                                         source_files, owner_uid, owner_gid, 0o600)
        source_parent.chmod(0o711)
        _source_bundle_inputs(bundle, staged_source, expected_source_manifest_sha256, expected)
    _inputs(app, snapshot, expected)
    staged_code_hash = hashlib.sha256()
    for name in FILES:
        staged_code_hash.update(name.encode())
        staged_code_hash.update(bytes.fromhex(_sha(app / name)))
    if staged_code_hash.hexdigest() != app_version:
        raise ValueError('staged application hash mismatch')
    old_app = (layout.app / 'current').resolve() if (layout.app / 'current').exists() else None
    old_snapshot = (layout.state / 'current').resolve() if (layout.state / 'current').exists() else None
    old_roster = (layout.state / 'current-roster').resolve() if (layout.state / 'current-roster').exists() else None
    old_source = (layout.state / 'current-source').resolve() if (layout.state / 'current-source').exists() else None
    unit_path = layout.units / SERVICE
    old_unit = unit_path.read_bytes() if unit_path.exists() else None
    active_env = layout.state / 'active.env'
    old_env = active_env.read_bytes() if active_env.exists() else None
    new_env = candidate_config
    if values.get('OWNER_EVIDENCE_DECISION_API_ENABLED') == '1':
        new_env += f'OWNER_EVIDENCE_DECISION_DB={decision_db}\n'.encode()
    if roster:
        new_env += (f'OWNER_EVIDENCE_ROSTER_DIR={roster}\n'
                    f'OWNER_EVIDENCE_ROSTER_SHA256={expected_roster_sha256}\n').encode()
    if staged_source:
        new_env += (f'OWNER_EVIDENCE_SOURCE_BUNDLE_DIR={staged_source}\n'
                    f'OWNER_EVIDENCE_SOURCE_BUNDLE_SHA256={expected_source_manifest_sha256}\n').encode()
    if (old_app == app.resolve() and old_snapshot == snapshot.resolve() and
            old_roster == (roster.resolve() if roster else None) and
            old_source == (staged_source.resolve() if staged_source else None) and
            old_unit == unit and old_env == new_env):
        if not command('systemctl', 'is-active', '--quiet', SERVICE):
            raise RuntimeError('private origin service inactive')
        return 'unchanged'
    layout.units.mkdir(parents=True, exist_ok=True)
    checkpoint = None
    if update_config_pin:
        service_active = bool(command('systemctl', 'is-active', '--quiet', SERVICE))
        service_enabled = bool(command('systemctl', 'is-enabled', '--quiet', SERVICE))
        previous = {
            'app': str(old_app) if old_app else None,
            'snapshot': str(old_snapshot) if old_snapshot else None,
            'roster': str(old_roster) if old_roster else None,
            'source': str(old_source) if old_source else None,
            'config': True, 'env': old_env is not None, 'unit': old_unit is not None,
            'service_active': service_active, 'service_enabled': service_enabled,
        }
        _begin_checkpoint(layout, expected, previous)
        checkpoint = _checkpoint_dir(layout)
    try:
        if update_config_pin:
            _atomic_write(layout.config, candidate_config, 0o600)
        _atomic_link(layout.app / 'current', app)
        _atomic_link(layout.state / 'current', snapshot)
        if roster:
            _atomic_link(layout.state / 'current-roster', roster)
        else:
            (layout.state / 'current-roster').unlink(missing_ok=True)
        if staged_source:
            _atomic_link(layout.state / 'current-source', staged_source)
        else:
            (layout.state / 'current-source').unlink(missing_ok=True)
        _atomic_write(active_env, new_env, 0o600)
        _atomic_write(unit_path, unit, 0o644)
        command('systemctl', 'daemon-reload')
        command('systemctl', 'restart', SERVICE)
        health()
        command('systemctl', 'enable', SERVICE)
        if checkpoint:
            _checkpoint_status(checkpoint, 'active', expected, previous)
    except Exception:
        if checkpoint:
            _restore_checkpoint(layout, command)
            raise
        for link, previous in ((layout.app / 'current', old_app),
                               (layout.state / 'current', old_snapshot),
                               (layout.state / 'current-roster', old_roster),
                               (layout.state / 'current-source', old_source)):
            if previous is None:
                link.unlink(missing_ok=True)
            else:
                _atomic_link(link, previous)
        if old_unit is None:
            unit_path.unlink(missing_ok=True)
        else:
            _atomic_write(unit_path, old_unit, 0o644)
        if old_env is None:
            active_env.unlink(missing_ok=True)
        else:
            _atomic_write(active_env, old_env, 0o600)
        command('systemctl', 'daemon-reload')
        if old_app is not None and old_snapshot is not None:
            command('systemctl', 'restart', SERVICE)
        else:
            command('systemctl', 'stop', SERVICE)
        raise
    return 'activated'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle-dir', type=Path, required=True)
    parser.add_argument('--snapshot-source', type=Path, required=True)
    parser.add_argument('--expected-sha256', required=True)
    parser.add_argument('--roster-source', type=Path)
    parser.add_argument('--expected-roster-sha256')
    parser.add_argument('--source-bundle', type=Path, required=True)
    parser.add_argument('--expected-source-manifest-sha256', required=True)
    args = parser.parse_args()
    if os.geteuid() != 0:
        parser.error('root required')
    layout = Layout(Path('/opt/freediving/owner-evidence/app'),
                    Path('/var/lib/freediving-owner-evidence'),
                    Path('/etc/systemd/system'), Path('/etc/freediving/owner-evidence.env'))
    try:
        _restore_checkpoint(layout, _system_command)
        _config(layout.config, os.geteuid(), None)
        _inputs(args.bundle_dir, args.snapshot_source, args.expected_sha256)
        if bool(args.roster_source) != bool(args.expected_roster_sha256):
            raise ValueError('roster staging inputs incomplete')
        if args.roster_source:
            _roster_inputs(args.roster_source, args.expected_roster_sha256, args.expected_sha256)
        _source_bundle_inputs(args.bundle_dir, args.source_bundle,
                              args.expected_source_manifest_sha256, args.expected_sha256)
    except (ValueError, OSError, KeyError, json.JSONDecodeError,
            subprocess.CalledProcessError, RuntimeError):
        parser.exit(1, 'Private origin activation prerequisites missing or invalid\n')
    try:
        identity = pwd.getpwnam('freediving-evidence')
    except KeyError:
        subprocess.run(['useradd', '--system', '--home', '/nonexistent',
                        '--shell', '/usr/sbin/nologin', 'freediving-evidence'], check=True)
        identity = pwd.getpwnam('freediving-evidence')
    if (identity.pw_uid == 0 or identity.pw_dir != '/nonexistent' or
            identity.pw_shell not in ('/usr/sbin/nologin', '/sbin/nologin')):
        parser.exit(1, 'Private origin service account is not restricted\n')
    try:
        result = activate(args.bundle_dir, args.snapshot_source, args.expected_sha256,
                          layout, roster_source=args.roster_source,
                          expected_roster_sha256=args.expected_roster_sha256,
                          source_bundle=args.source_bundle,
                          expected_source_manifest_sha256=args.expected_source_manifest_sha256,
                          update_config_pin=True,
                          owner_uid=identity.pw_uid, owner_gid=identity.pw_gid)
    except Exception as exc:
        parser.exit(1, f'Private origin activation refused or rolled back: {type(exc).__name__}\n')
    print(result)


if __name__ == '__main__':
    main()
