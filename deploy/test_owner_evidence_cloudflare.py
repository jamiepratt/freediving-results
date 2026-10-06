import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from owner_evidence_cloudflare import (check_access, check_worker_bindings, main,
                                       resolve_service_token_id,
                                       parse_origin_env, preflight, DOMAIN,
                                       WORKER_SECRETS)
from cloudflare import ACCOUNT, PUBLIC_INGRESS, FALLBACK


class PrivateActivationChecks(unittest.TestCase):
    def test_access_policy_requires_exact_owner_emails(self):
        app = {'type': 'self_hosted', 'domain': DOMAIN, 'aud': 'A'*32}
        policy = [{'decision': 'allow', 'include': [{'email': {'email': 'owner@example.com'}}]}]
        self.assertEqual(check_access(app, policy, ['owner@example.com']), 'A'*32)
        for bad in ([{'decision': 'bypass', 'include': policy[0]['include']}],
                    [{'decision': 'allow', 'include': [{'everyone': {}}]}],
                    [{'decision': 'allow', 'include': [{'email': {'email': 'other@example.com'}}]}]):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                check_access(app, bad, ['owner@example.com'])

    def test_status_service_auth_requires_exact_verified_token_policy(self):
        app = {'type': 'self_hosted', 'domain': DOMAIN, 'aud': 'A'*32}
        owner = {'decision': 'allow', 'include': [{'email': {'email': 'owner@example.com'}}]}
        status = {'decision': 'non_identity',
                  'include': [{'service_token': {'token_id': 'status-token-id'}}]}
        self.assertEqual(check_access(app, [owner, status], ['owner@example.com'],
                                      'status-token-id'), 'A'*32)
        for policies, expected in (([owner], 'status-token-id'),
                                   ([owner, status], None),
                                   ([owner, {**status, 'decision': 'allow'}], 'status-token-id'),
                                   ([owner, {**status, 'include': [{'any_valid_service_token': {}}]}], 'status-token-id'),
                                   ([owner, {**status, 'include': [{'service_token': {'token_id': 'other'}}]}], 'status-token-id'),
                                   ([owner, {**status, 'require': [{'ip': {'ip': '0.0.0.0/0'}}]}], 'status-token-id'),
                                   ([owner, status, status], 'status-token-id')):
            with self.subTest(policies=policies, expected=expected), self.assertRaises(ValueError):
                check_access(app, policies, ['owner@example.com'], expected)

    def test_import_service_auth_requires_exact_separate_policy(self):
        app = {'type': 'self_hosted', 'domain': DOMAIN, 'aud': 'A'*32}
        owner = {'decision': 'allow', 'include': [{'email': {'email': 'owner@example.com'}}]}
        status = {'decision': 'non_identity',
                  'include': [{'service_token': {'token_id': 'status-token-id'}}]}
        importer = {'decision': 'non_identity',
                    'include': [{'service_token': {'token_id': 'import-token-id'}}]}
        self.assertEqual(check_access(app, [owner, status, importer], ['owner@example.com'],
                                      'status-token-id', 'import-token-id'), 'A'*32)
        for policies, status_id, import_id in (
                ([owner, status], 'status-token-id', 'import-token-id'),
                ([owner, status, importer], 'status-token-id', 'status-token-id'),
                ([owner, status, {**importer, 'include': [{'any_valid_service_token': {}}]}],
                 'status-token-id', 'import-token-id'),
                ([owner, status, {**importer, 'include': [{'service_token': {'token_id': 'other'}}]}],
                 'status-token-id', 'import-token-id')):
            with self.subTest(policies=policies), self.assertRaises(ValueError):
                check_access(app, policies, ['owner@example.com'], status_id, import_id)

    def test_import_configuration_requires_separate_token_and_client(self):
        base = ("OWNER_EVIDENCE_GATEWAY_SECRET=1234567890123456\n"
                "OWNER_EVIDENCE_ORIGIN_HOST=owner-origin.alphacompose.com\n"
                "OWNER_EVIDENCE_EMAILS=owner@example.com\n"
                f"OWNER_EVIDENCE_SNAPSHOT_SHA256={'a'*64}\n"
                "OWNER_EVIDENCE_STATUS_FILE=/var/lib/freediving-owner-evidence/status/presentation-status.json\n"
                "OWNER_EVIDENCE_STATUS_TOKEN=abcdefghijklmnopqrstuvwxyz123456\n"
                "OWNER_EVIDENCE_STATUS_CLIENT_ID=status123.access\n")
        importer = ("OWNER_EVIDENCE_IMPORT_TOKEN=independent-import-token-123456\n"
                    "OWNER_EVIDENCE_IMPORT_CLIENT_ID=import123.access\n")
        self.assertEqual(parse_origin_env(base + importer)[0]['OWNER_EVIDENCE_IMPORT_CLIENT_ID'],
                         'import123.access')
        for invalid in (importer.splitlines()[0] + '\n',
                        importer.replace('import123.access', 'status123.access'),
                        importer.replace('independent-import-token-123456', 'abcdefghijklmnopqrstuvwxyz123456'),
                        importer.replace('independent-import-token-123456', 'short')):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                parse_origin_env(base + invalid)

    def test_status_client_id_resolves_to_one_enabled_cloudflare_token(self):
        token_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
        match = {'id': token_id, 'client_id': 'status123.access', 'enabled': True}
        with patch('owner_evidence_cloudflare.api', return_value=[match]) as api:
            self.assertEqual(resolve_service_token_id('status123.access', 'access-read-only'), token_id)
            self.assertEqual(api.call_args.args[-1], 'access-read-only')
        for tokens in ([], [match, match], [{**match, 'enabled': False}],
                       [{**match, 'id': 'not-a-uuid'}],
                       [{**match, 'client_id': 'other.access'}]):
            with self.subTest(tokens=tokens), patch('owner_evidence_cloudflare.api', return_value=tokens), self.assertRaises(ValueError):
                resolve_service_token_id('status123.access', 'access-read-only')

    def test_origin_configuration_must_be_complete(self):
        lines = "OWNER_EVIDENCE_GATEWAY_SECRET='1234567890123456'\nOWNER_EVIDENCE_ORIGIN_HOST='owner-origin.alphacompose.com'\nOWNER_EVIDENCE_EMAILS='owner@example.com'\nOWNER_EVIDENCE_SNAPSHOT_SHA256='" + 'a'*64 + "'\n"
        _, emails = parse_origin_env(lines)
        self.assertEqual(emails, ['owner@example.com'])
        with self.assertRaises(ValueError):
            parse_origin_env(lines.replace('owner-origin.alphacompose.com', 'poc-origin.alphacompose.com'))

    def test_origin_configuration_accepts_only_complete_fixed_queue_pin(self):
        base = ("OWNER_EVIDENCE_GATEWAY_SECRET=1234567890123456\n"
                "OWNER_EVIDENCE_ORIGIN_HOST=owner-origin.alphacompose.com\n"
                "OWNER_EVIDENCE_EMAILS=owner@example.com\n"
                f"OWNER_EVIDENCE_SNAPSHOT_SHA256={'a'*64}\n")
        pin = ("OWNER_EVIDENCE_ISSUE172_QUEUE_FILE=/var/lib/freediving-owner-evidence/issue172-queue/owner-queue-v1.json\n"
               f"OWNER_EVIDENCE_ISSUE172_QUEUE_SHA256={'b'*64}\n")
        self.assertEqual(parse_origin_env(base + pin)[0]['OWNER_EVIDENCE_ISSUE172_QUEUE_SHA256'], 'b'*64)
        for bad in (pin.splitlines()[0] + '\n', pin.replace('b'*64, 'invalid'),
                    pin.replace('/var/lib/freediving-owner-evidence/issue172-queue/owner-queue-v1.json', '/tmp/queue.json')):
            with self.assertRaises(ValueError):
                parse_origin_env(base + bad)

    def test_origin_configuration_accepts_complete_private_status_writer(self):
        base = ("OWNER_EVIDENCE_GATEWAY_SECRET=1234567890123456\n"
                "OWNER_EVIDENCE_ORIGIN_HOST=owner-origin.alphacompose.com\n"
                "OWNER_EVIDENCE_EMAILS=owner@example.com\n"
                f"OWNER_EVIDENCE_SNAPSHOT_SHA256={'a'*64}\n"
                "OWNER_EVIDENCE_DECISION_API_ENABLED=1\n")
        status = ("OWNER_EVIDENCE_STATUS_FILE=/var/lib/freediving-owner-evidence/status/presentation-status.json\n"
                  "OWNER_EVIDENCE_STATUS_TOKEN=abcdefghijklmnopqrstuvwxyz123456\n"
                  "OWNER_EVIDENCE_STATUS_CLIENT_ID=abcdefgh.access\n")
        values, emails = parse_origin_env(base + status)
        self.assertEqual(emails, ['owner@example.com'])
        self.assertEqual(values['OWNER_EVIDENCE_STATUS_CLIENT_ID'], 'abcdefgh.access')
        for extra in ("OWNER_EVIDENCE_STATUS_FILE=/var/lib/freediving-owner-evidence/status/presentation-status.json\n",
                      status.replace('abcdefgh.access', 'invalid-client-id'),
                      status.replace('abcdefghijklmnopqrstuvwxyz123456', 'short'),
                      status.replace('presentation-status.json', 'other.json'),
                      status + 'OWNER_EVIDENCE_UNKNOWN=unexpected\n'):
            with self.subTest(extra=extra), self.assertRaises(ValueError):
                parse_origin_env(base + extra)
        with self.assertRaises(ValueError):
            parse_origin_env(base.replace('OWNER_EVIDENCE_DECISION_API_ENABLED=1',
                                          'OWNER_EVIDENCE_DECISION_API_ENABLED=0') + status)

    def test_missing_access_read_permission_stops_before_host_or_tunnel(self):
        with patch('owner_evidence_cloudflare.token_from_profile', return_value='token'), \
             patch('owner_evidence_cloudflare.api', side_effect=HTTPError('url', 403, 'Forbidden', {}, None)) as api, \
             patch('owner_evidence_cloudflare.subprocess') as process:
            with self.assertRaises(HTTPError):
                preflight('a'*8 + '-' + 'a'*4 + '-' + 'a'*4 + '-' + 'a'*4 + '-' + 'a'*12,
                          'https://team.cloudflareaccess.com')
            self.assertEqual(api.call_count, 1)
            process.check_output.assert_not_called()
            process.run.assert_not_called()

    def test_separate_access_read_token_does_not_replace_wrangler_auth(self):
        app_id = 'a'*8 + '-' + 'a'*4 + '-' + 'a'*4 + '-' + 'a'*4 + '-' + 'a'*12
        with patch('owner_evidence_cloudflare.token_from_profile', return_value='wrangler-oauth') as profile, \
             patch('owner_evidence_cloudflare.api', side_effect=[{}, HTTPError('url', 403, 'Forbidden', {}, None)]) as api, \
             patch('owner_evidence_cloudflare.subprocess') as process:
            with self.assertRaises(HTTPError):
                preflight(app_id, 'https://team.cloudflareaccess.com', 'access-read-only')
            profile.assert_called_once_with()
            self.assertEqual([call.args for call in api.call_args_list], [
                (f'accounts/{ACCOUNT}/access/apps/{app_id}', 'access-read-only'),
                (f'accounts/{ACCOUNT}/access/apps/{app_id}/policies', 'access-read-only')])
            process.check_output.assert_not_called()
            process.run.assert_not_called()

    def test_preflight_rejects_status_binding_without_matching_service_auth(self):
        app_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
        app = {'type': 'self_hosted', 'domain': DOMAIN, 'aud': 'A'*32}
        owner = {'decision': 'allow', 'include': [{'email': {'email': 'owner@example.com'}}]}
        env = ("OWNER_EVIDENCE_GATEWAY_SECRET=1234567890123456\n"
               "OWNER_EVIDENCE_ORIGIN_HOST=owner-origin.alphacompose.com\n"
               "OWNER_EVIDENCE_EMAILS=owner@example.com\n"
               f"OWNER_EVIDENCE_SNAPSHOT_SHA256={'a'*64}\n"
               "OWNER_EVIDENCE_STATUS_FILE=/var/lib/freediving-owner-evidence/status/presentation-status.json\n"
               "OWNER_EVIDENCE_STATUS_TOKEN=abcdefghijklmnopqrstuvwxyz123456\n"
               "OWNER_EVIDENCE_STATUS_CLIENT_ID=status123.access\n")
        token = {'id': app_id, 'client_id': 'status123.access', 'enabled': True}
        with patch('owner_evidence_cloudflare.token_from_profile', return_value='oauth'), \
             patch('owner_evidence_cloudflare.api', side_effect=[app, [owner], [token]]) as api, \
             patch('owner_evidence_cloudflare.subprocess.check_output', return_value=env), \
             patch('owner_evidence_cloudflare.subprocess.run') as run, \
             patch('owner_evidence_cloudflare.check_worker_bindings',
                   return_value={'GATEWAY_SECRET', 'OWNER_EVIDENCE_STATUS_CLIENT_ID'}):
            with self.assertRaises(ValueError):
                preflight(app_id, 'https://team.cloudflareaccess.com', 'access-read-only')
            self.assertEqual(api.call_count, 3)
            run.assert_not_called()
        with patch('owner_evidence_cloudflare.token_from_profile', return_value='oauth'), \
             patch('owner_evidence_cloudflare.api', side_effect=[app, [owner]]) as api, \
             patch('owner_evidence_cloudflare.subprocess.check_output',
                   return_value=env.replace('OWNER_EVIDENCE_STATUS_FILE=/var/lib/freediving-owner-evidence/status/presentation-status.json\n', '').replace('OWNER_EVIDENCE_STATUS_TOKEN=abcdefghijklmnopqrstuvwxyz123456\n', '').replace('OWNER_EVIDENCE_STATUS_CLIENT_ID=status123.access\n', '')), \
             patch('owner_evidence_cloudflare.check_worker_bindings',
                   return_value={'GATEWAY_SECRET', 'OWNER_EVIDENCE_STATUS_CLIENT_ID'}):
            with self.assertRaises(ValueError):
                preflight(app_id, 'https://team.cloudflareaccess.com', 'access-read-only')
            self.assertEqual(api.call_count, 2)

    def test_preflight_prepares_exact_status_client_binding_for_activation(self):
        app_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
        app = {'type': 'self_hosted', 'domain': DOMAIN, 'aud': 'A'*32}
        policies = [{'decision': 'allow', 'include': [{'email': {'email': 'owner@example.com'}}]},
                    {'decision': 'non_identity', 'include': [{'service_token': {'token_id': app_id}}]}]
        env = ("OWNER_EVIDENCE_GATEWAY_SECRET=1234567890123456\n"
               "OWNER_EVIDENCE_ORIGIN_HOST=owner-origin.alphacompose.com\n"
               "OWNER_EVIDENCE_EMAILS=owner@example.com\n"
               f"OWNER_EVIDENCE_SNAPSHOT_SHA256={'a'*64}\n"
               "OWNER_EVIDENCE_STATUS_FILE=/var/lib/freediving-owner-evidence/status/presentation-status.json\n"
               "OWNER_EVIDENCE_STATUS_TOKEN=abcdefghijklmnopqrstuvwxyz123456\n"
               "OWNER_EVIDENCE_STATUS_CLIENT_ID=status123.access\n")
        token = {'id': app_id, 'client_id': 'status123.access', 'enabled': True}
        with patch('owner_evidence_cloudflare.token_from_profile', return_value='oauth'), \
             patch('owner_evidence_cloudflare.api', side_effect=[app, policies, [token],
                   [{'name': 'freediving-results-poc', 'id': 'tunnel-id'}],
                   {'config': {'ingress': [PUBLIC_INGRESS, FALLBACK]}}, []]), \
             patch('owner_evidence_cloudflare.subprocess.check_output', side_effect=[env, 'dns-token']), \
             patch('owner_evidence_cloudflare.subprocess.run'), \
             patch('owner_evidence_cloudflare.check_origin'), \
             patch('owner_evidence_cloudflare.check_worker_bindings', return_value={'GATEWAY_SECRET'}):
            result = preflight(app_id, 'https://team.cloudflareaccess.com', 'access-read-only')
        self.assertEqual(result[-1]['OWNER_EVIDENCE_STATUS_CLIENT_ID'], 'status123.access')

    def test_preflight_binds_import_client_after_verifying_service_policy(self):
        app_id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
        import_id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'
        app = {'type': 'self_hosted', 'domain': DOMAIN, 'aud': 'A'*32}
        policies = [{'decision': 'allow', 'include': [{'email': {'email': 'owner@example.com'}}]},
                    {'decision': 'non_identity',
                     'include': [{'service_token': {'token_id': import_id}}]}]
        env = ("OWNER_EVIDENCE_GATEWAY_SECRET=1234567890123456\n"
               "OWNER_EVIDENCE_ORIGIN_HOST=owner-origin.alphacompose.com\n"
               "OWNER_EVIDENCE_EMAILS=owner@example.com\n"
               f"OWNER_EVIDENCE_SNAPSHOT_SHA256={'a'*64}\n"
               "OWNER_EVIDENCE_IMPORT_TOKEN=independent-import-token-123456\n"
               "OWNER_EVIDENCE_IMPORT_CLIENT_ID=import123.access\n")
        token = {'id': import_id, 'client_id': 'import123.access', 'enabled': True}
        with patch('owner_evidence_cloudflare.token_from_profile', return_value='oauth'), \
             patch('owner_evidence_cloudflare.api', side_effect=[app, policies, [token],
                   [{'name': 'freediving-results-poc', 'id': 'tunnel-id'}],
                   {'config': {'ingress': [PUBLIC_INGRESS, FALLBACK]}}, []]), \
             patch('owner_evidence_cloudflare.subprocess.check_output', side_effect=[env, 'dns-token']), \
             patch('owner_evidence_cloudflare.subprocess.run'), \
             patch('owner_evidence_cloudflare.check_origin'), \
             patch('owner_evidence_cloudflare.check_worker_bindings', return_value={'GATEWAY_SECRET'}):
            result = preflight(app_id, 'https://team.cloudflareaccess.com', 'access-read-only')
        self.assertEqual(result[-1]['OWNER_EVIDENCE_IMPORT_CLIENT_ID'], 'import123.access')

    def test_worker_requires_public_secret_and_no_partial_private_bindings(self):
        def check(names):
            with patch('owner_evidence_cloudflare.subprocess.check_output',
                       return_value=__import__('json').dumps([{'name': name} for name in names])):
                return check_worker_bindings()
        self.assertEqual(check({'GATEWAY_SECRET'}), {'GATEWAY_SECRET'})
        self.assertEqual(check({'GATEWAY_SECRET', *WORKER_SECRETS}),
                         {'GATEWAY_SECRET', *WORKER_SECRETS})
        for names in ({'GATEWAY_SECRET', 'ACCESS_ISSUER'}, {'ACCESS_ISSUER'}):
            with self.subTest(names=names), self.assertRaises(ValueError):
                check(names)

    def test_activation_deploys_private_route_before_enabling_bindings(self):
        app_id = 'a'*8 + '-' + 'a'*4 + '-' + 'a'*4 + '-' + 'a'*4 + '-' + 'a'*12
        ingress = [{'service': 'http_status:404'}]
        result = ('oauth', 'dns', 'tunnel/path', ingress, ingress,
                  'dns/path', [{'id': 'existing'}], 'tunnel-id', {'ACCESS_ISSUER': 'issuer'})
        with patch('owner_evidence_cloudflare.sys.argv', ['activate', '--access-app-id', app_id,
             '--issuer', 'https://team.cloudflareaccess.com', '--activate']), \
             patch('owner_evidence_cloudflare.preflight', return_value=result), \
             patch('owner_evidence_cloudflare.api') as api, \
             patch('owner_evidence_cloudflare.subprocess.run') as run:
            main()
        api.assert_not_called()
        self.assertEqual([call.args[0][-2:] for call in run.call_args_list],
                         [['deploy/wrangler.jsonc', 'deploy'], ['secret', 'bulk']])


if __name__ == '__main__':
    unittest.main()
