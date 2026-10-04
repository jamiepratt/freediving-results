#!/usr/bin/env python3
"""Exact canonical prefix contracts for a future manual private AIDA apply."""


def verify_target_prefix(target, source_rows, requests):
    """Return the committed prefix length, or reject any unrelated target change."""
    snapshot = (source_rows[0].get('source-observation-ref', {}).get('snapshot_sha256')
                if isinstance(source_rows, list) and source_rows
                and isinstance(source_rows[0], dict) else None)
    if (target.get('schema') != 'retained-aida-target-state/v1'
            or not isinstance(snapshot, str) or target.get('snapshot_sha256') != snapshot
            or target.get('non_source_row_count') != 0
            or not isinstance(source_rows, list) or not source_rows
            or not isinstance(requests, list) or not requests
            or len({request.get('id') for request in requests}) != len(requests)
            or not isinstance(target.get('events'), list)
            or not isinstance(target.get('source_rows'), list)):
        raise ValueError('canonical target changed')
    revision = target.get('revision')
    if type(revision) is not int or not 0 <= revision <= len(requests):
        raise ValueError('canonical target changed')
    rows = target['source_rows']
    expected = {row.get('observation-id'): row for row in source_rows}
    actual = {row.get('observation-id'): row for row in rows}
    if (len(expected) != len(source_rows) or None in expected
            or (rows and (len(actual) != len(rows) or actual != expected))
            or (revision > 0 and not rows)
            or len(target['events']) != revision
            or any(event != {'id': request.get('id'), 'request': request}
                   for event, request in zip(target['events'], requests))):
        raise ValueError('canonical target changed')
    return revision


def build_guarded_cohort(preflight, target):
    """Build an exact replay cohort from a verified promotion preflight.

    The caller must first run the source, owner and envelope checks in the
    activation checkpoint. This function refuses a changed canonical target.
    """
    rows = preflight.get('source_rows')
    requests = preflight.get('canonical_replay')
    if (preflight.get('schema') != 'retained-aida-promotion-preflight/v1'
            or preflight.get('expected_production_revision') != 0
            or not isinstance(rows, list) or not isinstance(requests, list)
            or preflight.get('counts', {}).get('source_rows') != len(rows)
            or preflight.get('counts', {}).get('canonical_events') != len(requests)
            or any(type(request.get('base-revision')) is not int
                   or request['base-revision'] != index
                   for index, request in enumerate(requests))):
        raise ValueError('promotion preflight changed')
    verify_target_prefix(target, rows, requests)
    snapshot = preflight.get('snapshot_sha256')
    refs = {row.get('observation-id'): row.get('source-observation-ref') for row in rows}
    if (target['snapshot_sha256'] != snapshot or len(refs) != len(rows)
            or any(row.get('citation') != refs.get(row.get('observation-id'))
                   for row in rows)):
        raise ValueError('promotion source rows changed')
    return {'schema': 'retained-aida-cohort/v1',
            'binding': {'identity_revision': 0, 'history_event_ids': []},
            'registration': {'snapshot_sha256': snapshot, 'rows': rows,
                             'verified_refs': refs},
            'events': requests}
