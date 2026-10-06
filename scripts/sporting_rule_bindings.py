"""Private exact sporting rule meanings, independent of human review authority.

A retained formula supports only its named claim at an exact source position.
It cannot establish publication finality, distinctness or source selection.
"""
from datetime import date
import hashlib
import json
from pathlib import Path
import re
from urllib.parse import urlsplit

HASH = re.compile(r'[a-f0-9]{64}\Z')
SCOPE = {'federation', 'year', 'environment', 'discipline', 'gender'}
SEMANTIC = {'final', 'outcome', 'scoring-policy', 'comparable-category', 'hypothetical'}


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False, allow_nan=False).encode()


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def fields(value, required, optional=()):
    if not isinstance(value, dict) or set(value) - set(optional) != set(required):
        raise ValueError('invalid sporting rule meaning fields')


def text(value):
    if not isinstance(value, str) or not value.strip() or len(value) > 2000:
        raise ValueError('invalid sporting rule meaning text')


def day(value):
    if value is not None and (not isinstance(value, str) or date.fromisoformat(value).isoformat() != value):
        raise ValueError('invalid sporting rule applicability date')


def scope(value, complete=False):
    if not isinstance(value, dict) or set(value) - SCOPE or complete and set(value) != SCOPE:
        raise ValueError('invalid sporting rule applicability scope')
    for item in value.values():
        text(item)


def read_config(path, private_read):
    """Read every catalog/object pin afresh; never cache positive rule authority."""
    body = private_read(path, 65536)
    config = json.loads(body)
    fields(config, {'schema', 'catalog'})
    if config['schema'] != 'sporting-rule-bindings-service/v1':
        raise ValueError('invalid sporting rule meaning service')
    fields(config['catalog'], {'path', 'sha256'})
    if not Path(config['catalog']['path']).is_absolute() or not HASH.fullmatch(config['catalog']['sha256']):
        raise ValueError('invalid sporting rule meaning catalog pin')
    raw = private_read(config['catalog']['path'], 16 * 1024 * 1024)
    if hashlib.sha256(raw).hexdigest() != config['catalog']['sha256']:
        raise ValueError('sporting rule meaning catalog changed')
    catalog = json.loads(raw)
    fields(catalog, {'schema', 'documents', 'bindings'})
    if catalog['schema'] != 'sporting-rule-bindings/v1' or not isinstance(catalog['documents'], list) or not isinstance(catalog['bindings'], list):
        raise ValueError('invalid sporting rule meaning catalog')
    docs = {}
    for document in catalog['documents']:
        fields(document, {'id', 'path', 'sha256', 'url', 'issuer', 'edition', 'effective_from',
                          'effective_until', 'scope', 'citations'})
        for key in ('id', 'issuer', 'edition'):
            text(document[key])
        if document['id'] in docs or not HASH.fullmatch(document['sha256']) or not Path(document['path']).is_absolute():
            raise ValueError('invalid sporting rule meaning object')
        url = urlsplit(document['url'])
        if url.scheme != 'https' or not url.hostname or url.username or url.password or url.fragment:
            raise ValueError('invalid sporting rule meaning citation URL')
        if hashlib.sha256(private_read(document['path'], 32 * 1024 * 1024)).hexdigest() != document['sha256']:
            raise ValueError('sporting rule meaning object changed')
        day(document['effective_from']); day(document['effective_until']); scope(document['scope'])
        if document['effective_until'] is not None and (document['effective_from'] is None or document['effective_until'] < document['effective_from']):
            raise ValueError('invalid sporting rule meaning edition dates')
        citations = set()
        if not isinstance(document['citations'], list):
            raise ValueError('invalid sporting rule meaning citations')
        for citation in document['citations']:
            fields(citation, {'id', 'section', 'claim', 'interpretation'}, {'page'})
            for key in ('id', 'section', 'claim', 'interpretation'):
                text(citation[key])
            if citation['id'] in citations or 'page' in citation and (type(citation['page']) is not int or citation['page'] < 1):
                raise ValueError('invalid sporting rule meaning citation coordinates')
            citations.add(citation['id'])
        docs[document['id']] = document
    ids = set()
    for binding in catalog['bindings']:
        fields(binding, {'id', 'reference', 'coordinates', 'source_view', 'event_date', 'scope',
                         'policy', 'claims', 'unknowns', 'conflicts'})
        text(binding['id']); text(binding['policy']); day(binding['event_date']); scope(binding['scope'], True)
        if binding['id'] in ids:
            raise ValueError('duplicate sporting rule meaning binding')
        ids.add(binding['id'])
        fields(binding['reference'], {'job-id', 'candidate-id', 'ordinal', 'source-sha256', 'artifact-sha256', 'parser-version'})
        if (type(binding['reference']['ordinal']) is not int or binding['reference']['ordinal'] < 0
                or any(not HASH.fullmatch(binding['reference'][key]) for key in ('source-sha256', 'artifact-sha256'))):
            raise ValueError('invalid sporting rule meaning source reference')
        for key in ('job-id', 'candidate-id', 'parser-version'):
            text(binding['reference'][key])
        if not isinstance(binding['coordinates'], dict) or not binding['coordinates'] or not isinstance(binding['source_view'], dict):
            raise ValueError('invalid sporting rule meaning source coordinates or view')
        for key in ('page', 'line', 'table', 'row'):
            if key in binding['coordinates'] and (type(binding['coordinates'][key]) is not int or binding['coordinates'][key] < 1):
                raise ValueError('invalid sporting rule meaning source coordinates')
        for key in ('unknowns', 'conflicts'):
            if not isinstance(binding[key], list):
                raise ValueError('invalid sporting rule meaning unresolved evidence')
            for value in binding[key]:
                text(value)
        if not isinstance(binding['claims'], list):
            raise ValueError('invalid sporting rule meaning claims')
        seen = set()
        for claim in binding['claims']:
            fields(claim, {'claim', 'value', 'document_id', 'citation_id', 'interpretation', 'applicability'})
            text(claim['claim']); text(claim['interpretation'])
            fields(claim['applicability'], {'status', 'basis'}); text(claim['applicability']['basis'])
            if claim['applicability']['status'] not in ('verified', 'unverified'):
                raise ValueError('invalid sporting rule meaning applicability')
            document = docs.get(claim['document_id'])
            citation = next((c for c in document['citations'] if c['id'] == claim['citation_id']), None) if document else None
            if citation is None or citation['claim'] != claim['claim'] or claim['claim'] in seen:
                raise ValueError('sporting rule meaning claim citation mismatch')
            seen.add(claim['claim'])
    canonical(catalog)
    return catalog, {'sporting_rule_config_sha256': hashlib.sha256(body).hexdigest(),
                     'sporting_rule_catalog_sha256': hashlib.sha256(raw).hexdigest(),
                     'sporting_rule_objects_sha256': digest([{k: v for k, v in d.items() if k != 'path'} for d in catalog['documents']])}


def apply_catalog(context, catalog, pins):
    """Expose readable evidence only. This adds no upstream or owner authority."""
    context['pins'].update(pins)
    documents = {d['id']: {k: v for k, v in d.items() if k != 'path'} for d in catalog['documents']}
    for document in documents.values():
        context['rules'][document['sha256']] = document['url']
    for row in context['rows']:
        provenance = row.get('date_provenance', {})
        matched = []
        mismatches = []
        for binding in catalog['bindings']:
            if binding['reference'] != row['reference']:
                continue
            expected_scope = {k: row.get(k) for k in SCOPE}
            if (binding['coordinates'] != row['coordinates'] or binding['scope'] != expected_scope
                    or binding['source_view'] != provenance.get('source_view', {})
                    or binding['event_date'] != provenance.get('parsed_event_date')):
                mismatches.append({'binding_id': binding['id'], 'reason': 'Exact coordinates, source view, date or scope changed'})
                continue
            resolved = []
            for claim in binding['claims']:
                document = documents[claim['document_id']]
                citation = next(c for c in document['citations'] if c['id'] == claim['citation_id'])
                applicable = (claim['applicability']['status'] == 'verified'
                              and binding['event_date'] is not None and document['effective_from'] is not None
                              and document['effective_from'] <= binding['event_date']
                              and (document['effective_until'] is None or binding['event_date'] <= document['effective_until'])
                              and all(binding['scope'].get(k) == v for k, v in document['scope'].items()))
                resolved.append({**claim, 'document': {k: v for k, v in document.items() if k != 'citations'}, 'citation': citation,
                                 'supported': applicable and not binding['conflicts']})
            matched.append({**binding, 'binding_sha256': digest(binding), 'claims': resolved})
        row['sporting_rule_bindings'] = {'state': 'pinned' if matched else 'mismatch' if mismatches else 'missing',
                                         'bindings': matched, 'mismatches': mismatches,
                                         'reason': 'Cited meanings do not establish final publication, source selection or distinct attempts'}
    return context


def require_meaning(row, name, value, rule, policy):
    """Concrete public interpretations require this exact rule/source claim."""
    unknown = (value in (None, 'unknown') or name in ('final', 'hypothetical') and isinstance(value, dict) and value.get('basis') == 'unknown'
               or name == 'comparable-category' and isinstance(value, dict) and all(v == 'unknown' for v in value.values()))
    if unknown:
        return
    supports = []
    for binding in row.get('sporting_rule_bindings', {}).get('bindings', []):
        provenance = row.get('date_provenance', {})
        if (binding['policy'] != policy or binding['reference'] != row['reference']
                or binding['coordinates'] != row['coordinates']
                or binding['scope'] != {k: row.get(k) for k in SCOPE}
                or binding['source_view'] != provenance.get('source_view', {})
                or binding['event_date'] != provenance.get('parsed_event_date')
                or binding['conflicts']):
            continue
        for claim in binding['claims']:
            document, citation = claim['document'], claim['citation']
            if (claim['claim'] == name and claim['value'] == value and claim['supported'] is True
                    and rule == {'url': document['url'], 'source-sha256': document['sha256'],
                                 'locator': citation['section'], 'edition': document['edition'],
                                 'section': citation['section'], 'claim': name}):
                supports.append(claim)
    if len(supports) != 1:
        raise ValueError('exact applicable sporting rule meaning absent or changed')
