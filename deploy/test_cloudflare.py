import unittest

from cloudflare import reconcile_ingress, PUBLIC_INGRESS, PRIVATE_HOST


class TunnelIngressTest(unittest.TestCase):
    def test_new_tunnel_gets_only_public_route(self):
        self.assertEqual(reconcile_ingress(None), [PUBLIC_INGRESS, {'service': 'http_status:404'}])

    def test_normal_release_preserves_activated_private_route(self):
        private = {'hostname': PRIVATE_HOST, 'service': 'http://127.0.0.1:8081',
                   'originRequest': {'httpHostHeader': PRIVATE_HOST}}
        before = [PUBLIC_INGRESS, private, {'service': 'http_status:404'}]
        self.assertEqual(reconcile_ingress(before), before)

    def test_unknown_route_or_changed_public_route_refuses_overwrite(self):
        for route in ({'hostname': 'elsewhere.example.com', 'service': 'http://127.0.0.1:9999'},
                      {'hostname': PUBLIC_INGRESS['hostname'], 'service': 'http://127.0.0.1:1'}):
            with self.subTest(route=route), self.assertRaises(ValueError):
                reconcile_ingress([route, {'service': 'http_status:404'}])

    def test_private_route_shape_is_not_silently_rewritten(self):
        private = {'hostname': PRIVATE_HOST, 'service': 'http://127.0.0.1:8082'}
        with self.assertRaises(ValueError):
            reconcile_ingress([PUBLIC_INGRESS, private, {'service': 'http_status:404'}])


if __name__ == '__main__':
    unittest.main()
