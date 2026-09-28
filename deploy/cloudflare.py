#!/usr/bin/env python3
"""Idempotent public tunnel and DNS provisioning without private route deletion."""
import json
import subprocess
import urllib.request

ACCOUNT = 'd55b062637980b94f707f6fb05281a88'
ZONE = 'fe7b1dc7d11fd117565553093bb8b8fe'
PUBLIC_HOST = 'poc-origin.alphacompose.com'
PRIVATE_HOST = 'owner-origin.alphacompose.com'
PUBLIC_INGRESS = {'hostname': PUBLIC_HOST, 'service': 'http://127.0.0.1:8785',
                  'originRequest': {'httpHostHeader': '127.0.0.1:8785'}}
PRIVATE_INGRESS = {'hostname': PRIVATE_HOST, 'service': 'http://127.0.0.1:8081',
                   'originRequest': {'httpHostHeader': PRIVATE_HOST}}
FALLBACK = {'service': 'http_status:404'}


def reconcile_ingress(current):
    """Keep a previously activated private route; refuse unknown tunnel edits."""
    if current is None:
        return [PUBLIC_INGRESS, FALLBACK]
    if not isinstance(current, list) or not current or current[-1] != FALLBACK:
        raise ValueError('Unexpected tunnel fallback; refusing overwrite')
    routes = current[:-1]
    if len(routes) not in (1, 2) or routes[0] != PUBLIC_INGRESS:
        raise ValueError('Unexpected public tunnel ingress; refusing overwrite')
    if len(routes) == 2 and routes[1] != PRIVATE_INGRESS:
        raise ValueError('Unexpected private tunnel ingress; refusing overwrite')
    return current


def token_from_profile():
    output = subprocess.check_output(['wrangler', 'auth', 'token', '--profile', 'alphacompose', '--json'], text=True)
    return json.loads(output[output.index('{'):])['token']


def api(path, token, method='GET', body=None):
    request = urllib.request.Request('https://api.cloudflare.com/client/v4/' + path,
                                     method=method,
                                     headers={'Authorization': 'Bearer ' + token,
                                              'Content-Type': 'application/json'},
                                     data=None if body is None else json.dumps(body).encode())
    with urllib.request.urlopen(request, timeout=20) as response:
        result = json.load(response)
    if not result['success']:
        raise RuntimeError('Cloudflare request failed')
    return result['result']


def main():
    auth = token_from_profile()
    dns = subprocess.check_output(['security', 'find-generic-password', '-s', 'cloudflare-api-token',
                                   '-a', 'alphacompose-dns', '-w'], text=True).strip()
    base = 'accounts/' + ACCOUNT + '/cfd_tunnel'
    tunnels = [x for x in api(base + '?is_deleted=false', auth) if x['name'] == 'freediving-results-poc']
    if len(tunnels) > 1:
        raise RuntimeError('Ambiguous tunnel')
    item = tunnels[0] if tunnels else api(base, auth, 'POST', {'name': 'freediving-results-poc', 'config_src': 'cloudflare'})
    tunnel = item['id']
    config_path = base + '/' + tunnel + '/configurations'
    existing = api(config_path, auth)['config']['ingress'] if tunnels else None
    ingress = reconcile_ingress(existing)
    if existing != ingress:
        api(config_path, auth, 'PUT', {'config': {'ingress': ingress}})
    tunnel_token = api(base + '/' + tunnel + '/token', auth)
    subprocess.run(['ssh', 'bridge-vps', 'sudo -n sh -c "umask 077; cat > /etc/freediving/tunnel-token"'],
                   input=tunnel_token, text=True, check=True)
    records = api('zones/' + ZONE + '/dns_records?name=' + PUBLIC_HOST, dns)
    if records:
        if len(records) != 1 or records[0]['type'] != 'CNAME' or records[0]['content'] != tunnel + '.cfargotunnel.com':
            raise RuntimeError('Refusing unrelated DNS overwrite')
    else:
        api('zones/' + ZONE + '/dns_records', dns, 'POST',
            {'type': 'CNAME', 'name': PUBLIC_HOST, 'content': tunnel + '.cfargotunnel.com', 'proxied': True, 'ttl': 1})
    subprocess.run(['ssh', 'bridge-vps', 'sudo -n systemctl enable --now freediving-tunnel.service'], check=True)
    print('Tunnel configured:', tunnel)


if __name__ == '__main__':
    main()
