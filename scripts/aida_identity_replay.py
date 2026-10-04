"""Plan a bounded, source-scoped AIDA identity replay from verified observations.

The caller must obtain observations from the hash-verified AIDA adapter. This
planner neither writes a decision ledger nor implies distinct global athletes.
"""

from collections import defaultdict
import re


SHA = re.compile(r'[0-9a-f]{64}\Z')
ADAPTER = 'aida-snapshot-observation/3'
PERSON_KEYS = ('scope', 'id', 'id_kind')


def _need(condition, message):
    if not condition:
        raise ValueError(message)


def _identity_id(record_id):
    return 'source-observation:' + record_id


def _person(observation, snapshot):
    record = observation.get('snapshot_record_id')
    ref = observation.get('source_observation_ref')
    fields = observation.get('source_fields')
    _need(isinstance(record, str) and SHA.fullmatch(record), 'invalid AIDA record ID')
    _need(isinstance(ref, dict) and isinstance(fields, dict), 'missing AIDA source evidence')
    _need(observation.get('adapter_version') == ADAPTER == ref.get('adapter_version'),
          'AIDA profile adapter version required')
    _need(ref.get('kind') == 'source-derived' and ref.get('snapshot_sha256') == snapshot
          and ref.get('snapshot_record_id') == record
          and ref.get('source_name') == observation.get('source_name')
          and isinstance(ref.get('observation_version'), str)
          and SHA.fullmatch(ref['observation_version'])
          and observation.get('observation_version') == ref['observation_version'],
          'AIDA observation reference mismatch')
    source_person, cited_person = fields.get('publisher_person'), ref.get('publisher_person')
    _need(isinstance(source_person, dict) and isinstance(cited_person, dict),
          'missing AIDA publisher person')
    person = tuple(source_person.get(key) for key in PERSON_KEYS)
    _need(person[0] == 'AIDA' and person[2] == 'person'
          and isinstance(person[1], str) and 0 < len(person[1]) <= 128
          and all(cited_person.get(key) == value for key, value in zip(PERSON_KEYS, person)),
          'conflicting AIDA publisher person')
    _need(isinstance(fields.get('name'), str) and bool(fields['name'].strip()),
          'missing AIDA printed name')
    return person[1]


def _prior_pairs(events, known):
    active = set()
    human = set()
    accepted = {}
    for event in events:
        _need(isinstance(event, dict), 'invalid prior identity event')
        action = event.get('action')
        actor = event.get('actor_kind', event.get('actor-kind'))
        pair = event.get('pair')
        if action == 'reverse' and pair is None:
            prior = accepted.get(event.get('event_id', event.get('event-id')))
            _need(prior is not None, 'uncited identity reversal')
            pair = prior
        _need(action in ('accept', 'reverse', 'reject', 'correct')
              and actor in ('automatic', 'human', 'model')
              and isinstance(pair, (list, tuple)) and len(pair) == 2
              and pair[0] != pair[1] and all(item in known for item in pair),
              'invalid prior identity event')
        edge = tuple(sorted(pair))
        if action == 'accept':
            active.add(edge)
            if isinstance(event.get('id'), str):
                accepted[event['id']] = pair
        elif action == 'reverse':
            active.discard(edge)
        if actor == 'human':
            human.add(edge)
    return active, human


def plan_replay(observations, *, expected_snapshot_sha256, prior_events=()):
    """Return deterministic minimal edges and explicit structural denominators.

    Every row must have one source-bound AIDA person ID. A human decision on any
    pair freezes its publisher group so a rerun cannot route around a reversal.
    """
    _need(isinstance(expected_snapshot_sha256, str)
          and SHA.fullmatch(expected_snapshot_sha256), 'verified snapshot SHA256 required')
    _need(isinstance(observations, (list, tuple)) and observations,
          'AIDA observations required')
    groups = defaultdict(list)
    by_id = {}
    for row in observations:
        _need(isinstance(row, dict), 'invalid AIDA observation')
        person = _person(row, expected_snapshot_sha256)
        identity_id = _identity_id(row['snapshot_record_id'])
        _need(identity_id not in by_id, 'duplicate AIDA record ID')
        by_id[identity_id] = row
        groups[person].append(identity_id)
    active, human = _prior_pairs(prior_events, by_id)
    edges = []
    repeated = [sorted(ids) for ids in groups.values() if len(ids) > 1]
    blocked = 0
    already_active = 0
    eligible = 0
    for ids in sorted(repeated):
        membership = set(ids)
        if any(set(pair) <= membership for pair in human):
            blocked += 1
            continue
        if len({by_id[ident]['source_fields']['name'].strip() for ident in ids}) != 1:
            blocked += 1
            continue
        eligible += 1
        parents = {ident: ident for ident in ids}

        def root(ident):
            while parents[ident] != ident:
                ident = parents[ident]
            return ident

        def join(left, right):
            left, right = root(left), root(right)
            if left == right:
                return False
            parents[right] = left
            return True

        for left, right in sorted(active):
            if left in membership and right in membership:
                join(left, right)
                already_active += 1
        anchor = ids[0]
        for member in ids[1:]:
            pair = (anchor, member)
            if not join(*pair):
                continue
            edges.append({'pair': list(pair), 'snapshot_sha256': expected_snapshot_sha256,
                          'publisher_scope': 'AIDA',
                          'publisher_person_id': _person(by_id[anchor], expected_snapshot_sha256),
                          'refs': {ident: by_id[ident]['source_observation_ref'] for ident in pair}})
    return {'schema': 'aida-source-identity-replay-plan/1',
            'scope': 'AIDA source observations',
            'snapshot_sha256': expected_snapshot_sha256,
            'denominator': {'rows': len(by_id), 'publisher_person_ids': len(groups),
                            'repeated_person_groups': len(repeated),
                            'repeated_person_rows': sum(map(len, repeated)),
                            'within_person_pairs': sum(len(ids) * (len(ids) - 1) // 2
                                                       for ids in repeated)},
            'counts': {'eligible_groups': eligible, 'blocked_groups': blocked,
                       'already_active_edges': already_active,
                       'candidate_edges': len(edges)},
            'edges': edges, 'global_accepted_athletes': None,
            'distinct_attempts': None}
