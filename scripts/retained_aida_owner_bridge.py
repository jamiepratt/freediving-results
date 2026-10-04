"""Build pending owner identity proposals from exact private AIDA replay evidence.

This pure bridge needs a verified preflight, a fresh source adapter result, the
current owner binding, and a real private reconciliation ledger. It cannot
approve a source-derived edge or invent a missing flow event.
"""

import re


SHA = re.compile(r'[0-9a-f]{64}\Z')
SOURCE = re.compile(r'source-observation:([0-9a-f]{64})\Z')


def _need(condition, reason):
    if not condition:
        raise ValueError(reason)


def _pair(value):
    _need(isinstance(value, list) and len(value) == 2 and value[0] != value[1]
          and all(isinstance(item, str) and SOURCE.fullmatch(item) for item in value),
          'invalid source identity pair')
    return tuple(value)


def build_owner_export(preflight, loaded, owner, flow, decisions, coverage=None,
                       expected_preflight_sha256=None):
    """Return a store-compatible envelope; caller must verify and register it.

    The source adapter result must come from a hash-verified private replay.
    This function rechecks its exact refs and source meaning against both
    ledgers. The owner adapter performs the final snapshot and revision check.
    """
    _need(isinstance(preflight, dict)
          and preflight.get('schema') == 'retained-aida-promotion-preflight/v1'
          and isinstance(loaded, dict) and isinstance(owner, dict)
          and isinstance(flow, dict) and isinstance(decisions, list),
          'verified bridge inputs required')
    snapshot = preflight.get('snapshot_sha256')
    _need(isinstance(snapshot, str) and SHA.fullmatch(snapshot)
          and loaded.get('snapshot_sha256') == snapshot and not loaded.get('gaps')
          and isinstance(loaded.get('observations'), list)
          and preflight.get('owner', {}).get('status') == 'requires_reconciliation_binding'
          and preflight.get('expected_production_revision') == 0
          and owner.get('snapshot_sha256') == snapshot
          and owner.get('binding_revision') == preflight['owner'].get('binding_revision')
          and owner.get('store_revision') == preflight['owner'].get('store_revision')
          and owner.get('proposal_count') == 0
          and type(owner.get('store_revision')) is int
          and type(owner.get('binding_revision')) is int,
          'source snapshot or owner revision changed')
    source = {}
    for row in loaded['observations']:
        record = row.get('snapshot_record_id')
        ref = row.get('source_observation_ref')
        _need(isinstance(record, str) and SHA.fullmatch(record)
              and record not in source and isinstance(ref, dict)
              and ref.get('kind') == 'source-derived'
              and ref.get('snapshot_sha256') == snapshot
              and ref.get('snapshot_record_id') == record
              and ref.get('source_name') == row.get('source_name')
              and ref.get('adapter_version') == 'aida-snapshot-observation/3'
              and ref.get('citation') == row.get('citation')
              and isinstance(row.get('observation_version'), str)
              and SHA.fullmatch(row['observation_version'])
              and ref.get('observation_version') == row['observation_version']
              and (owner.get('observation_refs') or {}).get(record, {}).get('source_derived_ref') == ref,
              'source observation differs from owner binding')
        source['source-observation:' + record] = row
    registered = preflight.get('source_rows')
    _need(isinstance(registered, list) and len(registered) == len(source)
          and len({row.get('observation-id') for row in registered}) == len(source)
          and all(row.get('observation-id') in source
                  and row.get('source-observation-ref') == source[row['observation-id']]['source_observation_ref']
                  and row.get('citation') == source[row['observation-id']]['source_observation_ref']
                  for row in registered), 'canonical source registration changed')
    replay = preflight.get('canonical_replay')
    _need(isinstance(replay, list) and replay, 'canonical event replay required')
    accepted = {}
    reversed_events = set()
    blocked_people = set()
    event_ids = set()
    for index, event in enumerate(replay):
        _need(isinstance(event, dict) and isinstance(event.get('id'), str)
              and event['id'] and event['id'] not in event_ids
              and event.get('base-revision') == preflight.get('expected_production_revision', 0) + index
              and isinstance(event.get('source-binding'), dict)
              and event['source-binding'].get('snapshot-sha256') == snapshot,
              'canonical event lineage changed')
        event_ids.add(event['id'])
        if event.get('action') == 'accept':
            pair = _pair(event.get('pair'))
            _need(event.get('actor-kind') == 'automatic'
                  and isinstance(event.get('rule-version'), str) and event['rule-version']
                  and event['source-binding'].get('refs') == {
                      ident: source[ident]['source_observation_ref'] for ident in pair},
                  'canonical automatic edge differs from source')
            accepted[event['id']] = pair
        elif event.get('action') == 'reverse':
            prior = accepted.get(event.get('event-id'))
            _need(event.get('actor-kind') == 'human' and prior
                  and event['event-id'] not in reversed_events
                  and isinstance(event.get('reason'), str) and event['reason'].strip()
                  and event['source-binding'].get('refs') == {
                      ident: source[ident]['source_observation_ref'] for ident in prior},
                  'canonical human reversal changed')
            reversed_events.add(event['event-id'])
            blocked_people.update(source[ident]['source_fields']['publisher_person']['id']
                                  for ident in prior)
        else:
            raise ValueError('unsupported canonical event')
    intents = preflight['owner'].get('candidate_intents')
    _need(isinstance(intents, list) and len(intents) == len(accepted) - len(reversed_events)
          and len({intent.get('source_event_id') for intent in intents}) == len(intents),
          'canonical active intent set changed')
    unresolved = {}
    if coverage is not None:
        _need(isinstance(coverage, dict)
              and coverage.get('schema') == 'retained-aida-flow-export/v1'
              and isinstance(expected_preflight_sha256, str)
              and SHA.fullmatch(expected_preflight_sha256)
              and coverage.get('preflight_sha256') == expected_preflight_sha256
              and coverage.get('snapshot_sha256') == snapshot
              and coverage.get('decisions') == decisions
              and coverage.get('flow') == flow
              and isinstance(coverage.get('unresolved'), list)
              and coverage.get('counts') == {
                  'active_intents': len(intents), 'supported': len(decisions),
                  'unresolved': len(coverage['unresolved'])},
              'private flow coverage changed')
        for item in coverage['unresolved']:
            _need(isinstance(item, dict) and set(item) == {'source_event_id', 'reason'}
                  and item.get('reason') == 'human-correction'
                  and item.get('source_event_id') not in unresolved,
                  'unproved private flow omission')
            unresolved[item['source_event_id']] = item
    _need(flow.get('version') == 'reconciliation-flow/1'
          and isinstance(flow.get('events'), list)
          and len({event.get('id') for event in flow['events']}) == len(flow['events'])
          and len(decisions) + len(unresolved) == len(intents)
          and len({decision.get('id') for decision in decisions}) == len(decisions),
          'private reconciliation lineage missing')
    by_pair = {_pair(decision.get('subject', {}).get('pair')): decision for decision in decisions}
    _need(len(by_pair) == len(decisions), 'duplicate source identity decision')
    missing_ids = {intent.get('source_event_id') for intent in intents
                   if _pair(intent.get('pair')) not in by_pair}
    _need(set(unresolved) == missing_ids, 'private flow coverage omits active intent')
    proposals = []
    for intent in intents:
        event_id = intent.get('source_event_id')
        canonical_pair = accepted.get(event_id)
        _need(canonical_pair and event_id not in reversed_events
              and intent.get('status') == 'pending'
              and intent.get('pair') == list(canonical_pair)
              and intent.get('source_binding') == next(event['source-binding']
                                                       for event in replay if event['id'] == event_id),
              'candidate intent does not cite active canonical event')
        decision = by_pair.get(canonical_pair)
        if decision is None:
            omitted_rows = [source[ident] for ident in canonical_pair]
            omitted_people = [row['source_fields']['publisher_person'] for row in omitted_rows]
            _need(event_id in unresolved
                  and all(isinstance(person, dict) and person.get('scope') == 'AIDA'
                          and person.get('id_kind') == 'person' for person in omitted_people)
                  and omitted_people[0] == omitted_people[1]
                  and omitted_people[0].get('id') in blocked_people
                  and omitted_rows[0]['source_fields'].get('name')
                      == omitted_rows[1]['source_fields'].get('name'),
                  'unproved private flow omission')
            continue
        pair = canonical_pair
        candidates = decision.get('candidates')
        _need(isinstance(candidates, list) and len(candidates) >= 2
              and candidates[0] == pair[0] and pair[1] in candidates
              and len(set(candidates)) == len(candidates)
              and all(isinstance(ident, str) and SOURCE.fullmatch(ident)
                      and ident in source for ident in candidates),
              'source identity decision differs from canonical refs')
        rows = [source[ident] for ident in candidates]
        people = [row.get('source_fields', {}).get('publisher_person') for row in rows]
        names = [row.get('source_fields', {}).get('name') for row in rows]
        _need(all(isinstance(person, dict) and person.get('scope') == 'AIDA'
                  and person.get('id_kind') == 'person'
                  and person == row['source_observation_ref'].get('publisher_person')
                  for person, row in zip(people, rows))
              and len({person['id'] for person in people}) == 1
              and people[0]['id'] not in blocked_people
              and all(isinstance(name, str) and name.strip() for name in names)
              and len({name.strip() for name in names}) == 1,
              'AIDA publisher person, printed name, or human correction conflicts')
        refs = {ident: row['source_observation_ref'] for ident, row in zip(candidates, rows)}
        evidence = decision.get('evidence')
        _need(decision.get('family') == 'identity'
              and decision.get('action') == 'same-person'
              and decision.get('choices') == ['same-person', 'different-person', 'unknown']
              and decision.get('candidates') == candidates
              and decision.get('dependencies') == []
              and decision.get('evidence-adequate?') is True
              and decision.get('subject') == {'pair': list(pair), 'target-id': pair[0],
                                              'observation-versions': refs}
              and isinstance(evidence, list) and len(evidence) == len(candidates)
              and all(item.get('evidence-id') == 'identity-' + ident
                      and item.get('citation') == refs[ident]
                      for item, ident in zip(evidence, candidates)),
              'source identity decision differs from canonical refs')
        matching = [event for event in flow['events'] if event.get('decision-id') == decision['id']]
        _need(len(matching) == 1, 'source decision lacks unique flow event')
        event = matching[0]
        _need(event.get('family') == 'identity' and event.get('action') == 'same-person'
              and event.get('origin') == 'deterministic'
              and event.get('status') == 'unresolved'
              and event.get('reason') == 'source-context-unverified'
              and event.get('rule-version') == 'source-identity/1'
              and isinstance(event.get('policy-version'), str) and event['policy-version']
              and event.get('evidence') == evidence
              and event.get('dependencies') == [],
              'source flow event is not current unresolved lineage')
        bindings = [{'evidence_id': item['evidence-id'],
                     'snapshot_record_id': row['snapshot_record_id'],
                     'observation_revision': row['source_observation_ref']}
                    for item, row in zip(evidence, rows)]
        proposals.append({
            'id': decision['id'], 'type': 'identity', 'subject_id': pair[0],
            'source_name': ' + '.join(dict.fromkeys(row['source_name'] for row in rows)),
            'source_names': list(dict.fromkeys(row['source_name'] for row in rows)),
            'original': {'athlete_names': names},
            'proposed': {'action': 'same_person', 'subject': {'pair': list(pair),
                                                            'target-id': pair[0]}},
            'selected_option': 'same_person',
            'competing_options': ['different_person', 'unknown'],
            'evidence': [{'id': row['snapshot_record_id'],
                          'citation': {'evidence_id': item['evidence-id'],
                                       'source_citation': {
                                           'source-sha256': row['source_observation_ref']['source_sha256'],
                                           'locator': row['source_observation_ref']['citation']},
                                       'observation_revision': row['source_observation_ref']},
                          'version': row['source_observation_ref']}
                         for item, row in zip(evidence, rows)],
            'supporting_evidence': [], 'conflicting_evidence': [], 'depends_on': [],
            'groups': [], 'score': None, 'provider_confidence': None,
            'rule_version': next(item['rule-version'] for item in replay if item['id'] == event_id),
            'model_version': None, 'policy_version': event['policy-version'],
            'status': 'pending',
            'canonical_binding': {'decision_id': decision['id'],
                                  'reconciliation_run_revision': len(flow['events']),
                                  'reconciliation_event_id': event['id'],
                                  'source_event_id': event_id,
                                  'observation_revisions': [row['source_observation_ref'] for row in rows],
                                  'evidence_bindings': bindings}})
    return {'snapshot_sha256': snapshot,
            'binding_revision': owner['binding_revision'],
            'store_revision': owner['store_revision'],
            'reconciliation_run_revision': len(flow['events']),
            'counts': {'active_intents': len(intents), 'supported': len(proposals),
                       'unresolved': len(unresolved)},
            'proposals': proposals}
