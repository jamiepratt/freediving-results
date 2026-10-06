"""Synthetic private authority only. No retained athlete/source decisions."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from sporting_authority import SportingAuthority, ConflictError, canonical, digest, SOURCE_FACTS
from sporting_rule_fixture import synthetic_catalog, synthetic_rule_refs, bind_synthetic


def synthetic_context():
    ref = {'result-id': '4' * 64, 'observation-id': '1' * 64, 'ordinal': 0,
           'source-sha256': '2' * 64, 'artifact-sha256': '3' * 64}
    context = {'pins': {'snapshot_sha256': 'a' * 64, 'owner_revision': 7,
                     'packet_sha256': 'b' * 64, 'owner_authority_sha256': 'c' * 64},
            'rules': {'d' * 64: 'https://example.test/rules.pdf'},
            'rows': [{'reference': {'job-id': '1' * 64, 'candidate-id': 'synthetic',
                                   'ordinal': 0, 'source-sha256': '2' * 64,
                                   'artifact-sha256': '3' * 64, 'parser-version': 'synthetic/1'},
                      'coordinates': {'page': 1, 'row': 1}, 'year': '2026',
                      'environment': 'pool', 'discipline': 'dnf', 'gender': 'women',
                      'upstream': {k: {'value': v, 'event_sha256': 'e' * 64} for k, v in
                                   {'review': 'verified', 'same-attempt': 'distinct',
                                    'source-conflict': 'resolved', 'publication': 'approved'}.items()},
                      'public_reference': ref}]}
    context['rows'][0]['upstream']['publication']['reference'] = ref
    proposal = synthetic_proposal(context)
    for name in SOURCE_FACTS:
        context['rows'][0]['upstream'][name] = {'value': proposal['publication']['rows'][0]['facts'][name]['value'],
                                             'event_sha256': digest(['synthetic-source-claim', name])}
    catalog = synthetic_catalog(proposal['publication'], context['rows'], 'd' * 64, 'https://example.test/rules.pdf')
    return bind_synthetic(context, catalog)


def synthetic_proposal(context):
    ref = {'result-id': '4' * 64, 'observation-id': '1' * 64, 'ordinal': 0,
           'source-sha256': '2' * 64, 'artifact-sha256': '3' * 64}
    citation = {'url': 'https://example.test/results.pdf', 'source-sha256': '2' * 64,
                'artifact-sha256': '3' * 64, 'page': 1, 'row': 1}
    values = {'source-view': {'federation': 'CMAS', 'event-id': 'synthetic',
                             'view-id': 'results', 'kind': 'results', 'environment': 'pool'},
              'source-authority': 'official-results', 'finality': 'verified-final',
              'sanction': 'eligible', 'review': 'verified', 'outcome': 'finally-valid',
              'same-attempt': 'distinct', 'source-conflict': 'resolved',
              'final': {'value': 100, 'unit': 'm', 'basis': 'verified-post-penalty',
                        'decimal-places': 0, 'conversion': 'verified'},
              'scoring-policy': 'aida-baseline-v1', 'source-gender': 'Women',
              'comparable-category': {'group': 'women', 'para-class': 'non-para',
                                     'age-class': 'seniors', 'age-equivalence': 'verified'},
              'represented-country': None, 'listing': {'publisher': 'CMAS', 'kind': 'archive'},
              'international-sanction': {'authority': 'CMAS', 'level': 'international', 'status': 'verified'},
              'official-event-placing': None}
    facts = {key: {'value': value, 'binding': ref, 'policy': 'aida-baseline-v1',
                   'citation': citation} for key, value in values.items()}
    publication = {'schema': 'public-sporting/v1', 'cutoff': '2026-10-06T00:00:00Z',
                   'rows': [{'result-id': ref['result-id'], 'reference': ref,
                             'source': {k: values['source-view'][k] for k in ('federation', 'event-id', 'view-id')},
                             'facts': facts}],
                   'cohort': {'binding': {'cohort-id': '5' * 64, 'policy': 'aida-baseline-v1',
                                          'source-versions': ['3' * 64]},
                              'value': ['4' * 64], 'citation': citation}}
    return {'id': 'synthetic-cohort', 'publication': publication,
            'evidence': [{'reference': ref, 'retained_reference': copy.deepcopy(context['rows'][0]['reference']),
                          'coordinates': copy.deepcopy(context['rows'][0]['coordinates'])}],
            'rules': synthetic_rule_refs([*values, 'cohort'], 'd' * 64, 'https://example.test/rules.pdf'),
            'valid_until': '2026-10-07T00:00:00Z'}


class SportingAuthorityTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name).resolve()
        self.key = self.root / 'key.pem'
        subprocess.run(['openssl', 'genpkey', '-algorithm', 'Ed25519', '-out', str(self.key)],
                       check=True, capture_output=True)
        self.key.chmod(0o600)
        self.context = synthetic_context()
        self.authority = SportingAuthority(self.root / 'ledger' / 'authority.sqlite', self.key,
                                           lambda: copy.deepcopy(self.context),
                                           clock=lambda: '2026-10-06T00:00:00Z')
        self.addCleanup(self.authority.close)

    def test_owner_review_authorizes_exact_projection_and_reversal_withdraws(self):
        proposed = synthetic_proposal(self.context)
        staged = self.authority.stage(proposed, expected_revision=0, idempotency_key='stage-1')
        self.assertEqual(staged['revision'], 1)
        self.assertIsNone(self.authority.current('9' * 64)['payload']['publication'])
        for revision, action in enumerate(('source-approve', 'select-cohort', 'publish'), 1):
            reviewed = self.authority.act('synthetic-cohort', action=action, actor='owner@example.test',
                                         expected_revision=revision, idempotency_key=action, reason='Synthetic review')
            if action != 'publish':
                self.assertIsNone(self.authority.current('9' * 64)['payload']['publication'])
        current = self.authority.current('9' * 64)['payload']
        self.assertEqual(current['publication'], proposed['publication'])
        self.assertEqual(current['revision'], reviewed['revision'])
        self.assertEqual(current['publication_sha256'], digest(proposed['publication']))
        self.authority.act('synthetic-cohort', action='reverse', actor='owner@example.test',
                           expected_revision=4, idempotency_key='reverse-1', reason='Synthetic withdrawal')
        self.assertIsNone(self.authority.current('9' * 64)['payload']['publication'])

    def test_unbound_parser_coordinates_rule_and_private_fact_are_rejected(self):
        for mutate in (
            lambda p: p['evidence'][0]['retained_reference'].update({'parser-version': 'unchecked/2'}),
            lambda p: p['evidence'][0]['coordinates'].update({'row': 99}),
            lambda p: p['rules']['finality'].update({'source-sha256': 'e' * 64}),
            lambda p: p['publication']['rows'][0]['facts'].update({'private-email': {'value': 'secret'}}),
            lambda p: p['publication']['rows'][0]['facts']['finality'].update({'value': 'unchecked-final'}),
            lambda p: p['publication']['rows'][0]['facts']['final']['value'].update({'value': True}),
        ):
            with self.subTest(mutate=mutate):
                proposal = synthetic_proposal(self.context)
                mutate(proposal)
                with self.assertRaises(ValueError):
                    self.authority.stage(proposal, expected_revision=0, idempotency_key='invalid')
        self.assertEqual(self.authority.review()['revision'], 0)

    def publish(self):
        self.authority.stage(synthetic_proposal(self.context), expected_revision=0, idempotency_key='stage-1')
        for revision, action in enumerate(('source-approve', 'select-cohort', 'publish'), 1):
            self.authority.act('synthetic-cohort', action=action, actor='owner@example.test', reason='Synthetic review',
                               expected_revision=revision, idempotency_key=action)

    def test_changed_owner_or_upstream_withdraws_without_new_delivery(self):
        self.publish()
        self.context['pins']['owner_revision'] += 1
        self.assertIsNone(self.authority.current('9' * 64)['payload']['publication'])
        self.assertEqual(self.authority.review()['proposals'][0]['authority_status'], 'stale')
        self.context['pins']['owner_revision'] -= 1
        self.context['rows'][0]['upstream']['same-attempt']['event_sha256'] = 'f' * 64
        self.assertIsNone(self.authority.current('9' * 64)['payload']['publication'])

    def test_unavailable_or_expired_current_evidence_withholds(self):
        self.publish()
        self.authority.clock = lambda: '2026-10-08T00:00:00Z'
        self.assertIsNone(self.authority.current('9' * 64)['payload']['publication'])
        self.authority.context_reader = lambda: None
        value = self.authority.current('9' * 64)['payload']
        self.assertEqual(value['status'], 'unavailable')
        self.assertIsNone(value['publication'])

    def test_owner_can_durably_withdraw_when_source_reader_is_unavailable(self):
        self.publish()
        self.authority.context_reader = lambda: None
        event = self.authority.act('synthetic-cohort', action='reverse', actor='owner@example.test',
                                   reason='Synthetic durable withdrawal', expected_revision=4, idempotency_key='reverse-unavailable')
        self.assertEqual(event['action'], 'reverse')
        self.assertEqual(self.authority.review()['proposals'][0]['action'], 'reverse')

    def test_duplicate_operation_is_idempotent_and_stale_domain_race_rejected(self):
        proposal = synthetic_proposal(self.context)
        first = self.authority.stage(proposal, expected_revision=0, idempotency_key='stage-1')
        self.assertEqual(first, self.authority.stage(proposal, expected_revision=0, idempotency_key='stage-1'))
        with self.assertRaises(ConflictError):
            self.authority.act('synthetic-cohort', action='publish', actor='owner@example.test', reason='Synthetic review',
                               expected_revision=1, idempotency_key='wrong-domain')
        self.authority.act('synthetic-cohort', action='source-approve', actor='owner@example.test', reason='Synthetic review',
                           expected_revision=1, idempotency_key='source-1')
        with self.assertRaises(ConflictError):
            self.authority.act('synthetic-cohort', action='select-cohort', actor='owner@example.test', reason='Synthetic review',
                               expected_revision=1, idempotency_key='stale-select')

    def test_source_review_cannot_grant_independent_upstream_authority(self):
        proposal = synthetic_proposal(self.context)
        self.context['rows'][0]['upstream'] = {}
        with self.assertRaisesRegex(ValueError, 'upstream'):
            self.authority.stage(proposal, expected_revision=0, idempotency_key='stage-1')

    def test_same_physical_source_row_with_changed_parser_is_not_two_attempts(self):
        proposal = synthetic_proposal(self.context)
        other_row = copy.deepcopy(self.context['rows'][0])
        other_row['reference'].update({'job-id': '8' * 64, 'artifact-sha256': '9' * 64,
                                        'parser-version': 'synthetic/2'})
        ref = {**proposal['publication']['rows'][0]['reference'], 'result-id': '6' * 64,
               'observation-id': '7' * 64, 'artifact-sha256': '9' * 64}
        other_row['public_reference'] = ref
        other_row['upstream']['publication']['reference'] = ref
        self.context['rows'].append(other_row)
        public = copy.deepcopy(proposal['publication']['rows'][0])
        public.update({'result-id': ref['result-id'], 'reference': ref})
        for fact in public['facts'].values():
            fact['binding'] = ref
            fact['citation']['artifact-sha256'] = ref['artifact-sha256']
        proposal['publication']['rows'].append(public)
        proposal['evidence'].append({'reference': ref, 'retained_reference': other_row['reference'],
                                     'coordinates': {**other_row['coordinates'], 'kind': 'changed-parser-metadata'}})
        other_row['coordinates']['kind'] = 'changed-parser-metadata'
        proposal['publication']['cohort']['value'].append(ref['result-id'])
        proposal['publication']['cohort']['binding']['source-versions'].append(ref['artifact-sha256'])
        with self.assertRaisesRegex(ValueError, 'duplicate physical'):
            self.authority.stage(proposal, expected_revision=0, idempotency_key='double-position')

    def test_authenticated_review_details_are_committed_without_public_leakage(self):
        self.publish()
        current = self.authority.current('9' * 64)
        self.assertNotIn('owner@example.test', canonical(current).decode())
        self.assertNotIn('Synthetic review', canonical(current).decode())
        # Model physical tamper by dropping the SQL guard. The owned exporter
        # verifies private review commitments even when SQLite itself is altered.
        self.authority.db.execute('DROP TRIGGER sporting_no_update')
        row = self.authority.db.execute('SELECT private_json FROM sporting_events WHERE revision=4').fetchone()
        detail = json.loads(row[0]); detail['actor'] = 'intruder@example.test'
        self.authority.db.execute('UPDATE sporting_events SET private_json=? WHERE revision=4', (canonical(detail).decode(),))
        with self.assertRaisesRegex(ValueError, 'integrity'):
            self.authority.current('9' * 64)

    def test_owner_reversal_racing_current_export_withdraws_before_signing(self):
        self.publish()
        changed = False
        def reader():
            nonlocal changed
            if not changed:
                changed = True
                self.authority.act('synthetic-cohort', action='reverse', actor='owner@example.test',
                                   reason='Synthetic race withdrawal', expected_revision=4, idempotency_key='racing-reverse')
            return copy.deepcopy(self.context)
        self.authority.context_reader = reader
        value = self.authority.current('9' * 64)['payload']
        self.assertIsNone(value['publication'])
        self.assertEqual(value['revision'], 5)


if __name__ == '__main__':
    unittest.main()
