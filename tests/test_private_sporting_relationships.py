"""Independent typed owner assertions, never inferred from a missing duplicate."""
import copy
import sys
from pathlib import Path
import pytest
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from sporting_authority import digest
from owner_decision_store import ConflictError


def context():
    return {'pins': {'canonical_upstream_sha256': 'a' * 64}, 'rows': [
        {'reference': {'job-id': 'b' * 64, 'ordinal': 1, 'candidate-id': 'synthetic-row',
                       'source-sha256': 'c' * 64, 'artifact-sha256': 'd' * 64,
                       'parser-version': 'synthetic/1'}, 'coordinates': {'table': 1, 'row': 1},
         'upstream': {}, 'diagnostics': {}}], 'rules': {}}


def assertion(c):
    return {'reference': c['rows'][0]['reference'], 'coordinates': c['rows'][0]['coordinates'],
            'inventory_sha256': digest([{'reference': r['reference'], 'coordinates': r['coordinates']} for r in c['rows']]),
            'canonical_binding_sha256': c['pins']['canonical_upstream_sha256'],
            'reviewed_references': [r['reference'] for r in c['rows']],
            'same-attempt': 'distinct', 'source-conflict': 'resolved',
            'reason': 'Synthetic owner reviewed all exact possible repeat versions',
            'citation': {'url': 'https://example.test/official-source', 'locator': 'Synthetic table 1 row 1'}}


def test_relationship_review_requires_full_inventory_and_current_canonical_state(tmp_path):
    from private_sporting_relationships import RelationshipReviews
    current = context()
    ledger = RelationshipReviews(tmp_path / 'reviews.sqlite', lambda: copy.deepcopy(current))
    try:
        assert ledger.proofs(current)['rows'][0]['upstream'] == {}
        bad = assertion(current); bad['reviewed_references'] = []
        with pytest.raises(ValueError, match='inventory'):
            ledger.act(bad, action='review', expected_revision=0, actor='synthetic-owner@example.test', idempotency_key='bad')
        event = ledger.act(assertion(current), action='review', expected_revision=0, actor='synthetic-owner@example.test', idempotency_key='one')
        proven = ledger.proofs(current)
        assert proven['rows'][0]['upstream']['same-attempt']['value'] == 'distinct'
        assert proven['rows'][0]['upstream']['source-conflict']['value'] == 'resolved'
        assert ledger.act(assertion(current), action='review', expected_revision=0, actor='synthetic-owner@example.test', idempotency_key='one') == event
        current['pins']['canonical_upstream_sha256'] = 'e' * 64
        assert ledger.proofs(current)['rows'][0]['upstream'] == {}
        assert ledger.proofs(current)['rows'][0]['diagnostics']['relationship']['state'] == 'stale'
    finally:
        ledger.close()


def test_relationship_reversal_cas_and_immutable_history(tmp_path):
    from private_sporting_relationships import RelationshipReviews
    current = context()
    ledger = RelationshipReviews(tmp_path / 'reviews.sqlite', lambda: copy.deepcopy(current))
    try:
        reviewed = assertion(current)
        ledger.act(reviewed, action='review', expected_revision=0, actor='synthetic-owner@example.test', idempotency_key='review')
        with pytest.raises(ConflictError):
            ledger.act(reviewed, action='reverse', expected_revision=0, actor='synthetic-owner@example.test', idempotency_key='wrong')
        ledger.act(reviewed, action='reverse', expected_revision=1, actor='synthetic-owner@example.test', idempotency_key='reverse')
        assert ledger.proofs(current)['rows'][0]['upstream'] == {}
        assert ledger.proofs(current)['rows'][0]['diagnostics']['relationship']['state'] == 'revoked'
        with pytest.raises(Exception, match='immutable'):
            ledger.db.execute('DELETE FROM relationship_events')
    finally:
        ledger.close()


def test_current_canonical_same_attempt_cannot_be_overridden(tmp_path):
    from private_sporting_relationships import RelationshipReviews
    current = context()
    current['rows'][0]['diagnostics'] = {'canonical_relationships': {'same-attempt': {
        'exact_relationships': [{'type': 'same-attempt', 'action': 'accept', 'current': True}]}}}
    ledger = RelationshipReviews(tmp_path / 'reviews.sqlite', lambda: copy.deepcopy(current))
    try:
        with pytest.raises(ValueError, match='contradicts distinct'):
            ledger.act(assertion(current), action='review', expected_revision=0,
                       actor='synthetic-owner@example.test', idempotency_key='one')
        assert ledger.history() == []
    finally:
        ledger.close()


def test_two_parser_versions_cannot_each_be_distinct_for_one_physical_position(tmp_path):
    from private_sporting_relationships import RelationshipReviews
    current = context()
    older = copy.deepcopy(current['rows'][0]); older['reference']['parser-version'] = 'synthetic/0'
    older['reference']['artifact-sha256'] = 'e' * 64; older['reference']['candidate-id'] = 'older-synthetic'
    current['rows'].append(older)
    ledger = RelationshipReviews(tmp_path / 'reviews.sqlite', lambda: copy.deepcopy(current))
    try:
        first = assertion(current)
        ledger.act(first, action='review', expected_revision=0, actor='synthetic-owner@example.test', idempotency_key='one')
        second = assertion(current); second['reference'] = older['reference']
        with pytest.raises(ConflictError, match='representative'):
            ledger.act(second, action='review', expected_revision=1, actor='synthetic-owner@example.test', idempotency_key='two')
        ledger.act(first, action='reverse', expected_revision=1, actor='synthetic-owner@example.test', idempotency_key='reverse')
        ledger.act(second, action='review', expected_revision=2, actor='synthetic-owner@example.test', idempotency_key='two')
        proof = ledger.proofs(current)
        assert proof['rows'][0]['upstream'] == {}
        assert proof['rows'][1]['upstream']['same-attempt']['value'] == 'distinct'
        assert ledger.history()[0]['actor'] == 'synthetic-owner@example.test'
        assert ledger.history()[0]['created_at'].endswith('Z')
    finally:
        ledger.close()
