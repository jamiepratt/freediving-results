"""Private SSH activation and Access readback for evidence_presentation.present.

The code bundle and guarded activation helper must already be installed on the
host. This adapter does not install code or operate a VPN.
"""
import json
from pathlib import Path
import re
import shlex
import subprocess
import urllib.error
import urllib.request

from private_evidence_ssh import HOST, receipt, ssh_stage
from private_evidence_transfer import verified_input

SHA = re.compile(r'[0-9a-f]{64}\Z')


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


class PrivateEvidenceRemote:
    def __init__(self, host, root, remote_script, ssh, code_bundle, activation_script,
                 owner_url, access_id, access_secret, cited_record_id,
                 cited_source_sha256, *, timeout=10, command=None, http=None):
        if not HOST.fullmatch(host) or host.startswith('-'):
            raise ValueError('invalid SSH host')
        paths = (Path(root), Path(remote_script), Path(code_bundle), Path(activation_script))
        if any(not p.is_absolute() or any(c in str(p) for c in '\r\n\x00') for p in paths):
            raise ValueError('remote paths must be absolute')
        if owner_url != 'https://poc.alphacompose.com/owner-evidence':
            raise ValueError('owner route must be the pinned custom domain')
        if not access_id or not access_secret or any(c in access_id + access_secret for c in '\r\n'):
            raise ValueError('Access credentials required')
        if not SHA.fullmatch(cited_record_id) or not SHA.fullmatch(cited_source_sha256):
            raise ValueError('cited source binding required')
        if not isinstance(timeout, (int, float)) or not 0 < timeout <= 60:
            raise ValueError('invalid timeout')
        self.host, self.root, self.remote_script, self.ssh = host, paths[0], paths[1], Path(ssh)
        self.code_bundle, self.activation_script = paths[2:]
        self.owner_url = owner_url
        self.headers = {'CF-Access-Client-Id': access_id, 'CF-Access-Client-Secret': access_secret}
        self.record_id, self.source_sha256 = cited_record_id, cited_source_sha256
        self.timeout, self.command, self.http = timeout, command or self._command, http or self._http
        self.expected = None
        self.source_bytes = None

    def stage(self, run_dir):
        local, _, _, manifest = verified_input(Path(run_dir))
        cited = next((item for item in manifest['sources']
                      if item.get('status') == 'included' and item.get('sha256') == self.source_sha256), None)
        if cited is None:
            raise ValueError('cited source absent from local bundle')
        expected = receipt(local)
        staged = ssh_stage(Path(run_dir), self.host, self.root, self.remote_script, self.ssh)
        if {key: staged.get(key) for key in expected} != expected:
            raise ValueError('staging receipt mismatch')
        self.expected = expected
        self.source_bytes = cited['bytes']
        return staged

    def _host(self, args):
        remote = shlex.join(['sudo', '-n', 'python3', str(self.activation_script), *args])
        result = self.command([str(self.ssh), '-T', '--', self.host, remote])
        if result not in ('activated', 'unchanged', 'rolled back'):
            raise ValueError('private activation response invalid')
        return result

    def _command(self, argv):
        try:
            result = subprocess.run(argv, capture_output=True, timeout=self.timeout, check=False)
        except subprocess.TimeoutExpired as error:
            raise TimeoutError('private activation timed out') from error
        if result.returncode:
            raise RuntimeError('private activation failed')
        return result.stdout.decode('utf-8', errors='replace').strip()

    def activate(self, staged):
        if staged != self.expected or self.expected is None:
            raise ValueError('activation receipt mismatch')
        target = self.root / staged['staging_path']
        return self._host(['--bundle-dir', str(self.code_bundle),
                           '--snapshot-source', str(target / 'snapshot'),
                           '--expected-sha256', staged['snapshot_sha256'],
                           '--source-bundle', str(target / 'source-bundle'),
                           '--expected-source-manifest-sha256', staged['bundle_manifest_sha256']])

    def rollback(self, staged):
        if staged != self.expected or self.expected is None:
            raise ValueError('rollback receipt mismatch')
        if self._host(['--rollback-candidate-sha256', staged['snapshot_sha256'],
                       '--rollback-source-manifest-sha256', staged['bundle_manifest_sha256']]) != 'rolled back':
            raise ValueError('private rollback response invalid')

    def _http(self, path, headers, timeout):
        request = urllib.request.Request(self.owner_url + path, headers=headers)
        try:
            with urllib.request.build_opener(_NoRedirect).open(request, timeout=timeout) as response:
                if response.status != 200:
                    return response.status, None
                data = response.read(1024 * 1024 + 1)
                if len(data) > 1024 * 1024:
                    raise ValueError('owner response too large')
                return response.status, json.loads(data)
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, ValueError) as error:
            raise ValueError('authenticated owner readback failed') from error

    def owner_overview(self):
        try:
            status, overview = self.http('/api/overview', self.headers, self.timeout)
            source_status, source = self.http('/api/source-view/' + self.record_id,
                                              self.headers, self.timeout)
        except Exception as error:
            raise ValueError('authenticated owner readback failed') from error
        if (status != 200 or source_status != 200 or not isinstance(overview, dict) or
                not isinstance(source, dict) or
                source.get('snapshot_sha256') != overview.get('snapshot_sha256') or
                source.get('source_sha256') != self.source_sha256 or
                (self.source_bytes is not None and source.get('source_bytes') != self.source_bytes) or
                source.get('format') not in ('pdf', 'json', 'jpeg') or
                not source.get('citation')):
            raise ValueError('owner source rendering mismatch')
        return {'status': status, **overview}
