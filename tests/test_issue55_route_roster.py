import hashlib
import json
import subprocess
import sys
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / 'scripts' / 'issue55_route_roster.py'
HASH = hashlib.sha256(b'official page').hexdigest()


def cite(url='https://official.example/events', locator='event card 1'):
    return {'url': url, 'sha256': HASH, 'locator': locator}


def route(**changes):
    item = {'id': 'aida-2025', 'authority': 'AIDA International', 'role': 'primary',
            'discovery_url': 'https://official.example/events', 'status': 'checked',
            'checked_at': '2026-09-28T10:00:00Z',
            'receipt': {'url': 'https://official.example/events', 'final_url': 'https://official.example/events',
                        'http_status': 200, 'content_type': 'text/html', 'bytes': 13, 'sha256': HASH},
            'citation': cite(), 'gaps': ['Session list unavailable']}
    item.update(changes)
    return item


def lead(**changes):
    item = {'id': 'event-2025', 'route_id': 'aida-2025', 'title': 'Official event',
            'competition_date': '2025-06-01', 'competition_date_to': None, 'competition_year': 2025,
            'date_evidence': cite(locator='event card 1 date'), 'session': None,
            'discipline': None, 'category': None, 'relationship': 'primary',
            'status': 'checked', 'url': 'https://official.example/events/1',
            'citation': cite(locator='event card 1'), 'snapshot_ids': [],
            'candidate_snapshot_ids': [], 'gaps': ['Category list unavailable']}
    item.update(changes)
    return item


def invoke(tmp_path, payload, name='input'):
    source = tmp_path / f'{name}.json'
    output = tmp_path / f'{name}-out.json'
    source.write_text(json.dumps(payload))
    result = subprocess.run([sys.executable, str(SCRIPT), str(source), '--output', str(output)],
                            capture_output=True, text=True)
    return result, output


def roster(routes=None, leads=None):
    return {'schema': 'issue55-route-roster/v1', 'cutoff': '2026-09-28T12:00:00Z',
            'routes': [route()] if routes is None else routes,
            'leads': [lead()] if leads is None else leads}


def test_normalizes_roster_and_counts_without_completeness_claim(tmp_path):
    result, output = invoke(tmp_path, roster())
    assert result.returncode == 0, result.stderr
    data = json.loads(output.read_text())
    assert data['schema'] == 'issue55-route-roster/v1'
    assert data['summary']['routes_by_status'] == {'checked': 1, 'acquired': 0, 'missing': 0,
                                                    'inaccessible': 0, 'unchecked': 0}
    assert data['summary']['leads_by_year'] == {'2025': 1, '2026': 0, 'unknown': 0}
    assert data['summary']['confirmed_distinct_attempts'] is None
    assert data['leads'][0]['citation'] == cite(locator='event card 1')


def test_normalized_roster_replays_identically_and_sorts_records(tmp_path):
    second_route = route(id='z-route', status='unchecked', checked_at=None, receipt=None,
                         citation=cite(locator='calendar link'))
    second_lead = lead(id='z-lead', route_id='z-route', status='unchecked',
                       competition_date=None, competition_year=None, date_evidence=None,
                       citation=cite(locator='calendar link'), relationship='unknown')
    result, first = invoke(tmp_path, roster([second_route, route()], [second_lead, lead()]))
    assert result.returncode == 0, result.stderr
    second_result, second = invoke(tmp_path, json.loads(first.read_text()), 'replay')
    assert second_result.returncode == 0, second_result.stderr
    assert first.read_bytes() == second.read_bytes()
    data = json.loads(first.read_text())
    assert [x['id'] for x in data['routes']] == ['aida-2025', 'z-route']
    assert data['summary']['leads_by_year']['unknown'] == 1


def test_calendar_only_year_keeps_unknown_date_and_exact_year_citation(tmp_path):
    item = lead(competition_date=None, date_evidence=None, competition_year=2025)
    result, output = invoke(tmp_path, roster(leads=[item]))
    assert result.returncode == 0, result.stderr
    normalized = json.loads(output.read_text())['leads'][0]
    assert normalized['competition_date'] is None
    assert normalized['date_evidence'] == cite(locator='event card 1')


def test_rejects_invalid_status_history_and_accepts_access_recovery(tmp_path):
    invalid = route(status='acquired', status_history=[
        {'status': 'acquired', 'at': '2026-09-28T09:00:00Z'},
        {'status': 'checked', 'at': '2026-09-28T10:00:00Z'},
    ])
    result, _ = invoke(tmp_path, roster(routes=[invalid], leads=[]), 'invalid')
    assert result.returncode != 0
    assert 'invalid transition' in result.stderr
    recovered = route(status_history=[
        {'status': 'unchecked', 'at': '2026-09-27T09:00:00Z'},
        {'status': 'inaccessible', 'at': '2026-09-27T10:00:00Z'},
        {'status': 'checked', 'at': '2026-09-28T10:00:00Z'},
    ])
    result, output = invoke(tmp_path, roster(routes=[recovered]), 'recovered')
    assert result.returncode == 0, result.stderr
    assert json.loads(output.read_text())['routes'][0]['status_history'][-1]['status'] == 'checked'


def test_rejects_mismatched_citation_and_out_of_scope_competition_date(tmp_path):
    bad_citation = lead(citation={'url': 'https://official.example/events/1',
                                  'sha256': hashlib.sha256(b'other').hexdigest(),
                                  'locator': 'event card 1'})
    result, _ = invoke(tmp_path, roster(leads=[bad_citation]), 'citation')
    assert result.returncode != 0
    assert 'citation must match route receipt' in result.stderr
    result, _ = invoke(tmp_path, roster(leads=[lead(competition_date='2024-12-31', competition_year=None)]), 'date')
    assert result.returncode != 0
    assert 'outside 2025-2026' in result.stderr


def test_event_key_groups_calendar_links_without_attempt_equivalence(tmp_path):
    second = lead(id='event-2025-dynamic', event_key='event-2025',
                  title='Official event dynamic', discipline='DYN',
                  url='https://official.example/events/1/dyn',
                  citation=cite(locator='dynamic link'),
                  candidate_snapshot_ids=['snapshot:dyn'])
    first = lead(event_key='event-2025', snapshot_ids=['snapshot:sta'])
    result, output = invoke(tmp_path, roster(leads=[second, first]))
    assert result.returncode == 0, result.stderr
    data = json.loads(output.read_text())
    assert {x['event_key'] for x in data['leads']} == {'event-2025'}
    assert data['summary']['lead_count'] == 2
    assert data['summary']['confirmed_distinct_attempts'] is None


def test_summary_queries_leads_by_route_status_and_year(tmp_path):
    result, output = invoke(tmp_path, roster())
    assert result.returncode == 0, result.stderr
    by_route = json.loads(output.read_text())['summary']['leads_by_route']
    assert by_route['aida-2025']['count'] == 1
    assert by_route['aida-2025']['by_status']['checked'] == 1
    assert by_route['aida-2025']['by_year']['2025'] == 1


def test_private_output_is_not_world_readable(tmp_path):
    result, output = invoke(tmp_path, roster())
    assert result.returncode == 0, result.stderr
    assert output.stat().st_mode & 0o077 == 0


def test_candidate_event_key_does_not_assert_proven_same_event(tmp_path):
    item = lead(event_key='calendar-a', candidate_event_keys=['calendar-b'])
    result, output = invoke(tmp_path, roster(leads=[item]))
    assert result.returncode == 0, result.stderr
    normalized = json.loads(output.read_text())['leads'][0]
    assert normalized['candidate_event_keys'] == ['calendar-b']
    assert normalized['event_key'] == 'calendar-a'
