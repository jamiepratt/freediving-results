"""Rule/source authority is pinned separately from owner decisions."""
import copy
import hashlib
import json
from pathlib import Path
import unittest
import test_sporting_authority as authority_fixture
from sporting_authority import canonical, private_bytes
from sporting_rule_fixture import synthetic_catalog
from sporting_rule_bindings import read_config, apply_catalog

synthetic_proposal = authority_fixture.synthetic_proposal


class RuleBindingAuthorityTest(unittest.TestCase):
    setUp = authority_fixture.SportingAuthorityTest.setUp
    publish = authority_fixture.SportingAuthorityTest.publish
    def test_url_only_rule_cannot_authorize_concrete_scoring(self):
        proposal = synthetic_proposal(self.context)
        self.context['rows'][0].pop('sporting_rule_bindings', None)
        with self.assertRaisesRegex(ValueError, 'rule meaning'):
            self.authority.stage(proposal, expected_revision=0, idempotency_key='legacy-rule')
        self.assertEqual(self.authority.review()['revision'], 0)

    def test_exact_source_date_change_refuses_new_stage_even_with_old_rule_diagnostics(self):
        proposal = synthetic_proposal(self.context)
        self.context['rows'][0]['date_provenance']['parsed_event_date'] = '2026-12-31'
        with self.assertRaisesRegex(ValueError, 'rule meaning'):
            self.authority.stage(proposal, expected_revision=0, idempotency_key='changed-date')

    def test_unknown_final_cannot_hide_a_concrete_distance(self):
        proposal = synthetic_proposal(self.context)
        proposal['publication']['rows'][0]['facts']['final']['value']['basis'] = 'unknown'
        with self.assertRaises(ValueError):
            self.authority.stage(proposal, expected_revision=0, idempotency_key='unknown-positive')


    def pinned_context(self):
        self.document = self.root / 'rule.pdf'
        self.document.write_bytes(b'Isolated synthetic sporting rules edition 2026 v1')
        self.document.chmod(0o600)
        self.document_sha = hashlib.sha256(self.document.read_bytes()).hexdigest()
        proposal = synthetic_proposal(self.context)
        catalog = synthetic_catalog(proposal['publication'], self.context['rows'], self.document_sha,
                                    'https://example.test/rules.pdf')
        catalog['documents'][0]['path'] = str(self.document)
        self.catalog_path = self.root / 'catalog.json'
        self.config_path = self.root / 'rules-service.json'
        self.write_catalog(catalog)
        source = copy.deepcopy(self.context)
        for row in source['rows']:
            row.pop('sporting_rule_bindings', None)
        def current():
            value, pins = read_config(self.config_path, private_bytes)
            return apply_catalog(copy.deepcopy(source), value, pins)
        self.authority.context_reader = current
        for rule in proposal['rules'].values():
            rule['source-sha256'] = self.document_sha
        return proposal

    def write_catalog(self, catalog):
        self.catalog_path.write_bytes(canonical(catalog)); self.catalog_path.chmod(0o600)
        self.config_path.write_bytes(canonical({'schema': 'sporting-rule-bindings-service/v1',
            'catalog': {'path': str(self.catalog_path), 'sha256': hashlib.sha256(self.catalog_path.read_bytes()).hexdigest()}}))
        self.config_path.chmod(0o600)

    def publish_pinned(self):
        self.authority.stage(self.pinned_context(), expected_revision=0, idempotency_key='pinned-stage')
        for revision, action in enumerate(('source-approve', 'select-cohort', 'publish'), 1):
            self.authority.act('synthetic-cohort', action=action, actor='owner@example.test', reason='Synthetic review',
                               expected_revision=revision, idempotency_key=action)
        self.assertIsNotNone(self.authority.current('9' * 64)['payload']['publication'])

    def test_current_rechecks_rule_bytes_and_refuses_missing_or_tampered_original(self):
        self.publish_pinned()
        self.document.write_bytes(b'changed edition')
        self.assertIsNone(self.authority.current('9' * 64)['payload']['publication'])
        self.assertEqual(self.authority.review()['proposals'][0]['authority_status'], 'unavailable')
        self.document.unlink()
        self.assertIsNone(self.authority.current('9' * 64)['payload']['publication'])
        self.assertEqual(self.authority.review()['revision'], 4)

    def test_rule_withdrawal_or_new_edition_invalidates_existing_proposal_without_owner_writes(self):
        self.publish_pinned()
        catalog = json.loads(self.catalog_path.read_bytes())
        catalog['documents'][0]['edition'] = 'synthetic-2026-v2'
        self.write_catalog(catalog)
        self.assertIsNone(self.authority.current('9' * 64)['payload']['publication'])
        self.assertEqual(self.authority.review()['proposals'][0]['authority_status'], 'stale')
        catalog['bindings'] = []
        self.write_catalog(catalog)
        self.assertIsNone(self.authority.current('9' * 64)['payload']['publication'])
        self.assertEqual(self.authority.review()['revision'], 4)

    def test_edition_citation_policy_and_source_coordinate_mismatch_refuse_exact_stage(self):
        for change in ('edition', 'section', 'claim', 'policy', 'coordinates', 'date', 'scope', 'applicability'):
            with self.subTest(change=change):
                proposal = self.pinned_context()
                catalog = json.loads(self.catalog_path.read_bytes())
                if change in ('edition', 'section', 'claim'):
                    proposal['rules']['final'][change] = 'wrong'
                elif change == 'policy':
                    catalog['bindings'][0]['policy'] = 'unverified-policy'
                elif change == 'coordinates':
                    catalog['bindings'][0]['coordinates']['row'] = 99
                elif change == 'date':
                    catalog['documents'][0]['effective_from'] = '2026-07-01'
                elif change == 'scope':
                    catalog['documents'][0]['scope']['discipline'] = 'cwt'
                else:
                    catalog['bindings'][0]['claims'][0]['applicability']['status'] = 'unverified'
                self.write_catalog(catalog)
                with self.assertRaisesRegex(ValueError, 'rule meaning'):
                    self.authority.stage(proposal, expected_revision=0, idempotency_key='mismatch-' + change)
        self.assertEqual(self.authority.review()['revision'], 0)

    def test_scoring_formula_never_completes_an_existing_proposal_publication_gate(self):
        self.context['rows'][0]['upstream'].pop('publication')
        self.authority.stage(synthetic_proposal(self.context), expected_revision=0, idempotency_key='no-eligibility')
        for revision, action in enumerate(('source-approve', 'select-cohort'), 1):
            self.authority.act('synthetic-cohort', action=action, actor='owner@example.test', reason='Synthetic review',
                               expected_revision=revision, idempotency_key=action)
        with self.assertRaisesRegex(ValueError, 'public eligibility absent'):
            self.authority.act('synthetic-cohort', action='publish', actor='owner@example.test', reason='Synthetic review',
                               expected_revision=3, idempotency_key='no-publish')
        self.assertEqual(self.authority.review()['revision'], 3)
        self.assertIsNone(self.authority.current('9' * 64)['payload']['publication'])

    def test_preliminary_source_unknown_finality_stays_unknown_after_rule_support(self):
        proposal = synthetic_proposal(self.context)
        proposal['publication']['rows'][0]['facts']['finality']['value'] = 'unknown'
        self.authority.stage(proposal, expected_revision=0, idempotency_key='preliminary-source')
        for revision, action in enumerate(('source-approve', 'select-cohort', 'publish'), 1):
            self.authority.act('synthetic-cohort', action=action, actor='owner@example.test', reason='Synthetic review',
                               expected_revision=revision, idempotency_key=action)
        public = self.authority.current('9' * 64)['payload']['publication']
        self.assertEqual(public['rows'][0]['facts']['finality']['value'], 'unknown')

    def test_disqualified_achieved_distance_cannot_be_a_real_final_distance(self):
        from sporting_rule_fixture import bind_synthetic
        proposal = synthetic_proposal(self.context)
        row = proposal['publication']['rows'][0]
        row['facts']['outcome']['value'] = 'disqualified'
        row['facts']['final']['value']['basis'] = 'verified-source-achieved'
        catalog = synthetic_catalog(proposal['publication'], self.context['rows'], 'd' * 64, 'https://example.test/rules.pdf')
        bind_synthetic(self.context, catalog)
        with self.assertRaisesRegex(ValueError, 'disqualified.*final'):
            self.authority.stage(proposal, expected_revision=0, idempotency_key='dq-final')

    def test_scoring_rule_cannot_vouch_for_publisher_finality(self):
        self.context['rows'][0]['upstream'].pop('finality', None)
        with self.assertRaisesRegex(ValueError, 'upstream.*finality'):
            self.authority.stage(synthetic_proposal(self.context), expected_revision=0, idempotency_key='no-finality-proof')

    def test_existing_staged_proposal_cannot_review_after_finality_proof_disappears(self):
        from sporting_authority import ConflictError
        self.authority.stage(synthetic_proposal(self.context), expected_revision=0, idempotency_key='staged-finality')
        self.context['rows'][0]['upstream'].pop('finality')
        with self.assertRaisesRegex(ConflictError, 'source binding changed'):
            self.authority.act('synthetic-cohort', action='source-approve', actor='owner@example.test',
                               reason='Synthetic review', expected_revision=1, idempotency_key='no-finality-review')
        self.assertEqual(self.authority.review()['revision'], 1)
        self.assertEqual(self.authority.review()['proposals'][0]['authority_status'], 'stale')
        self.assertIsNone(self.authority.current('9' * 64)['payload']['publication'])
