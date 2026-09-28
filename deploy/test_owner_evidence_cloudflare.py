import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from owner_evidence_cloudflare import (check_access, check_worker_bindings,
                                       parse_origin_env, preflight, DOMAIN,
                                       WORKER_SECRETS)


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

    def test_origin_configuration_must_be_complete(self):
        lines = "OWNER_EVIDENCE_GATEWAY_SECRET='1234567890123456'\nOWNER_EVIDENCE_ORIGIN_HOST='owner-origin.alphacompose.com'\nOWNER_EVIDENCE_EMAILS='owner@example.com'\nOWNER_EVIDENCE_SNAPSHOT_SHA256='" + 'a'*64 + "'\n"
        _, emails = parse_origin_env(lines)
        self.assertEqual(emails, ['owner@example.com'])
        with self.assertRaises(ValueError):
            parse_origin_env(lines.replace('owner-origin.alphacompose.com', 'poc-origin.alphacompose.com'))

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


if __name__ == '__main__':
    unittest.main()
