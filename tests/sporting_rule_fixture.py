"""Explicit isolated rule claims for synthetic test cohorts, never production."""
from sporting_rule_bindings import apply_catalog, digest, SEMANTIC


def synthetic_catalog(publication, rows, sha, url):
    claims = set().union(*(set(r['facts']) for r in publication['rows'])) & SEMANTIC
    if any(r.get('hypothetical') is not None for r in publication['rows']):
        claims.add('hypothetical')
    document = {'id': 'synthetic-only', 'path': '/synthetic-only/rules.pdf', 'sha256': sha, 'url': url,
                'issuer': 'Isolated synthetic test issuer', 'edition': 'synthetic-2026-v1',
                'effective_from': '2026-01-01', 'effective_until': '2026-12-31',
                'scope': {'environment': 'pool', 'discipline': 'dnf'},
                'citations': [{'id': name, 'section': 'Synthetic section ' + name, 'claim': name,
                               'interpretation': 'Isolated exact synthetic ' + name} for name in sorted(claims)]}
    bindings = []
    for index, (public, row) in enumerate(zip(publication['rows'], rows)):
        row['federation'] = public['source']['federation']
        row['date_provenance'] = {'source_view': public['source'], 'parsed_event_date': '2026-06-01'}
        values = {name: f['value'] for name, f in public['facts'].items() if name in SEMANTIC}
        if public.get('hypothetical') is not None:
            values['hypothetical'] = public['hypothetical']['value']
        bindings.append({'id': 'synthetic-' + str(index), 'reference': row['reference'], 'coordinates': row['coordinates'],
                         'source_view': public['source'], 'event_date': '2026-06-01',
                         'scope': {k: row[k] for k in ('federation', 'year', 'environment', 'discipline', 'gender')},
                         'policy': 'aida-baseline-v1', 'claims': [
                             {'claim': name, 'value': value, 'document_id': document['id'], 'citation_id': name,
                              'interpretation': 'Isolated exact synthetic ' + name,
                              'applicability': {'status': 'verified', 'basis': 'Isolated synthetic applicable edition'}}
                             for name, value in values.items()], 'unknowns': [], 'conflicts': []})
    return {'schema': 'sporting-rule-bindings/v1', 'documents': [document], 'bindings': bindings}


def synthetic_rule_refs(names, sha, url):
    return {name: {'url': url, 'source-sha256': sha, 'locator': 'Synthetic section ' + name,
                   **({'edition': 'synthetic-2026-v1', 'section': 'Synthetic section ' + name, 'claim': name}
                      if name in SEMANTIC else {})} for name in names}


def bind_synthetic(context, catalog):
    return apply_catalog(context, catalog, {'sporting_rule_catalog_sha256': digest(catalog)})
