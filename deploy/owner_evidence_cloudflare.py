#!/usr/bin/env python3
"""Explicit private-route activation. Never called by the normal public release."""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess

from cloudflare import (ACCOUNT, ZONE, PRIVATE_HOST, PRIVATE_INGRESS, PUBLIC_INGRESS,
                        FALLBACK, api, token_from_profile)

WORKER_SECRETS = ('ACCESS_ISSUER', 'ACCESS_AUDIENCE', 'OWNER_EVIDENCE_EMAILS',
                  'OWNER_EVIDENCE_UPSTREAM', 'OWNER_EVIDENCE_GATEWAY_SECRET')
DOMAIN = 'poc.alphacompose.com/owner-evidence*'


def check_access(app, policies, emails):
    if app.get('type') != 'self_hosted' or app.get('domain') != DOMAIN:
        raise ValueError('Access app must protect the exact owner evidence path')
    destinations = app.get('destinations') or []
    if destinations and (len(destinations) != 1 or
                         destinations[0].get('uri') != DOMAIN or
                         destinations[0].get('overrides')):
        raise ValueError('Access app has additional or bypassed destinations')
    domains = app.get('self_hosted_domains') or []
    if domains and domains != [DOMAIN]:
        raise ValueError('Access app has additional protected domains')
    aud = app.get('aud')
    if not isinstance(aud, str) or not re.fullmatch(r'[A-Za-z0-9_-]{32,128}', aud):
        raise ValueError('Access audience missing or malformed')
    if len(policies) != 1 or policies[0].get('decision') != 'allow':
        raise ValueError('Require one owner-only Allow policy')
    policy = policies[0]
    if policy.get('require') or policy.get('exclude'):
        raise ValueError('Review complex Access policy manually before activation')
    allowed = []
    for rule in policy.get('include', []):
        selector = rule.get('email') if isinstance(rule, dict) else None
        if not isinstance(selector, dict) or not isinstance(selector.get('email'), str):
            raise ValueError('Access policy must list exact owner emails only')
        allowed.append(selector['email'])
    if sorted(allowed) != sorted(emails) or len(allowed) != len(set(allowed)):
        raise ValueError('Access policy and origin owner allowlists differ')
    return aud


def parse_origin_env(content):
    values = dict(line.split('=', 1) for line in shlex.split(content))
    required = {'OWNER_EVIDENCE_GATEWAY_SECRET', 'OWNER_EVIDENCE_ORIGIN_HOST',
                'OWNER_EVIDENCE_EMAILS', 'OWNER_EVIDENCE_SNAPSHOT_SHA256'}
    if set(values) != required or values['OWNER_EVIDENCE_ORIGIN_HOST'] != PRIVATE_HOST:
        raise ValueError('Private origin configuration incomplete')
    emails = values['OWNER_EVIDENCE_EMAILS'].split(',')
    if not emails or any(not re.fullmatch(r'[^\s,@]+@[^\s,@]+\.[^\s,@]+', e) or e != e.lower() for e in emails):
        raise ValueError('Owner allowlist malformed')
    secret = values['OWNER_EVIDENCE_GATEWAY_SECRET']
    if len(secret) < 16 or len(secret) > 256 or not secret.isascii() or any(c.isspace() for c in secret):
        raise ValueError('Private gateway secret malformed')
    if not re.fullmatch(r'[a-f0-9]{64}', values['OWNER_EVIDENCE_SNAPSHOT_SHA256']):
        raise ValueError('Private snapshot digest malformed')
    return values, emails


def check_origin(values):
    """Probe loopback on the VPS with positive and negative gateway requests."""
    script = '''import json, urllib.request, urllib.error
values = json.loads(%r)
url = 'http://127.0.0.1:8081/owner-evidence/api/overview'
headers = {'Host': values['OWNER_EVIDENCE_ORIGIN_HOST'],
           'X-Freediving-Owner-Gateway': values['OWNER_EVIDENCE_GATEWAY_SECRET'],
           'X-Freediving-Owner-Email': values['OWNER_EVIDENCE_EMAILS'].split(',')[0]}
with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=5) as response:
    assert response.status == 200
    assert json.load(response)['snapshot_sha256'] == values['OWNER_EVIDENCE_SNAPSHOT_SHA256']
for bad in ({'Host': 'poc-origin.alphacompose.com'},
            {'X-Freediving-Owner-Gateway': 'invalid-gateway-secret'},
            {'X-Freediving-Owner-Email': 'other@example.invalid'}):
    attempt = dict(headers); attempt.update(bad)
    try:
        urllib.request.urlopen(urllib.request.Request(url, headers=attempt), timeout=5)
    except urllib.error.HTTPError as error:
        assert error.code == 403
    else:
        raise AssertionError('Private origin accepted invalid identity or gateway')
''' % json.dumps(values)
    subprocess.run(['ssh', 'bridge-vps', 'python3 -'], input=script, text=True, check=True,
                   capture_output=True)


def check_worker_bindings():
    root = Path(__file__).resolve().parents[1]
    output = subprocess.check_output(['wrangler', '--profile', 'alphacompose', '--config',
                                      'deploy/wrangler.jsonc', 'secret', 'list', '--format', 'json'],
                                     cwd=root, text=True)
    listed = json.loads(output)
    names = {item['name'] for item in listed}
    private = names.intersection(WORKER_SECRETS)
    if 'GATEWAY_SECRET' not in names or private not in (set(), set(WORKER_SECRETS)):
        raise ValueError('Worker secret bindings incomplete or public gateway missing')
    return names


def preflight(app_id, issuer):
    if not re.fullmatch(r'[0-9a-f-]{36}', app_id):
        raise ValueError('Access application ID must be a UUID')
    if not re.fullmatch(r'https://[a-z0-9-]+\.cloudflareaccess\.com', issuer):
        raise ValueError('Access issuer must be the verified Cloudflare team domain')
    auth = token_from_profile()
    # This read is intentionally first. A 403 must stop all Cloudflare mutation.
    app = api(f'accounts/{ACCOUNT}/access/apps/{app_id}', auth)
    policies = api(f'accounts/{ACCOUNT}/access/apps/{app_id}/policies', auth)
    content = subprocess.check_output(['ssh', 'bridge-vps', 'sudo -n cat /etc/freediving/owner-evidence.env'], text=True)
    values, emails = parse_origin_env(content)
    audience = check_access(app, policies, emails)
    subprocess.run(['ssh', 'bridge-vps', 'sudo -n systemctl is-active --quiet freediving-owner-evidence.service'], check=True)
    check_origin(values)
    check_worker_bindings()
    base = f'accounts/{ACCOUNT}/cfd_tunnel'
    tunnels = [x for x in api(base + '?is_deleted=false', auth) if x['name'] == 'freediving-results-poc']
    if len(tunnels) != 1:
        raise ValueError('Expected one existing public tunnel')
    tunnel = tunnels[0]['id']
    path = f'{base}/{tunnel}/configurations'
    config = api(path, auth)['config']
    ingress = config.get('ingress')
    desired = [PUBLIC_INGRESS, PRIVATE_INGRESS, FALLBACK]
    if ingress not in ([PUBLIC_INGRESS, FALLBACK], desired):
        raise ValueError('Tunnel ingress drift; refusing activation')
    dns_token = subprocess.check_output(['security', 'find-generic-password', '-s', 'cloudflare-api-token',
                                         '-a', 'alphacompose-dns', '-w'], text=True).strip()
    dns_path = f'zones/{ZONE}/dns_records'
    records = api(dns_path + '?name=' + PRIVATE_HOST, dns_token)
    if records and (len(records) != 1 or records[0].get('type') != 'CNAME' or
                    records[0].get('content') != tunnel + '.cfargotunnel.com' or
                    records[0].get('proxied') is not True):
        raise ValueError('Private DNS drift; refusing activation')
    bindings = {'ACCESS_ISSUER': issuer, 'ACCESS_AUDIENCE': audience,
                'OWNER_EVIDENCE_EMAILS': ','.join(emails),
                'OWNER_EVIDENCE_UPSTREAM': 'https://' + PRIVATE_HOST,
                'OWNER_EVIDENCE_GATEWAY_SECRET': values['OWNER_EVIDENCE_GATEWAY_SECRET']}
    return auth, dns_token, path, ingress, desired, dns_path, records, tunnel, bindings


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--access-app-id', required=True)
    parser.add_argument('--issuer', required=True)
    parser.add_argument('--activate', action='store_true', help='Apply after all read-only checks')
    args = parser.parse_args()
    auth, dns_token, path, ingress, desired, dns_path, records, tunnel, bindings = preflight(args.access_app_id, args.issuer)
    if not args.activate:
        print('Preflight passed. No changes made. Re-run with --activate after review.')
        return
    if ingress != desired:
        api(path, auth, 'PUT', {'config': {'ingress': desired}})
    if not records:
        api(dns_path, dns_token, 'POST', {'type': 'CNAME', 'name': PRIVATE_HOST,
                                         'content': tunnel + '.cfargotunnel.com', 'proxied': True, 'ttl': 1})
    # One Worker version, no secret file or shell expansion. Public binding stays present.
    subprocess.run(['wrangler', '--profile', 'alphacompose', '--config', 'deploy/wrangler.jsonc',
                    'secret', 'bulk'], input=json.dumps(bindings), text=True, check=True,
                   cwd=Path(__file__).resolve().parents[1])
    print('Private bindings submitted. Verify owner login and public route before declaring activation complete.')


if __name__ == '__main__':
    main()
