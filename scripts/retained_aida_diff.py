#!/usr/bin/env python3
"""Deterministic private comparison of retained AIDA source and extraction versions.

Reads pinned original bytes and retained EDN through Clojure's EDN reader. Never
re-extracts, imports, reviews, selects, or grants sporting/publication authority.
"""
import argparse
from collections import Counter
import hashlib
import html
import json
import os
from pathlib import Path
import re
import subprocess

from issue55_aida_selected_html import active_view, chunks, cell, one_element, require, TAG

SCHEMA = 'retained-aida-exact-diff/v1'


def sha(body):
    return hashlib.sha256(body).hexdigest()


def canonical(value):
    return (json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':')) + '\n').encode()


def differences(a, b, path=''):
    if isinstance(a, dict) and isinstance(b, dict):
        result = []
        for key in sorted(set(a) | set(b)):
            name = path + '.' + key if path else key
            if key not in a or key not in b:
                result.append({'field': name, 'version_a_present': key in a, 'version_b_present': key in b,
                               'version_a': a.get(key), 'version_b': b.get(key)})
            else:
                result.extend(differences(a[key], b[key], name))
        return result
    return [] if type(a) is type(b) and a == b else [{'field': path, 'version_a': a, 'version_b': b}]


def compare(source, artifacts, artifact_shas, *, expected_positions=209, expected_selected=103):
    require(len(artifacts) == len(artifact_shas) == 2 and len(set(artifact_shas)) == 2,
            'two distinct retained artifacts required')
    source_sha = sha(source)
    page = source.decode('utf-8')
    active_view(page, 'day_5', '2026-06-03')
    require(re.search(r'<input\b[^>]*id=["\']day_nr["\'][^>]*value=["\']5["\']', page), 'hidden selected day mismatch')
    tables = chunks(page, 'table')
    supported = [t for t in tables if re.search(r'<tbody\b[^>]*id=["\']body_ajax["\']', t)]
    require(len(supported) == 1, 'exactly one body_ajax result table required')
    table = supported[0]
    start = page.index(table)
    table_number = 1 + len(re.findall(r'<table\b', page[:start], re.I))
    body, _ = one_element(table, 'tbody', 'body_ajax')
    headers = [' '.join(html.unescape(TAG.sub('', x)).split()) for x in
               re.findall(r'<th\b[^>]*>(.*?)</th\s*>', table[:table.index(body)], re.I | re.S)]
    raw_rows = chunks(body, 'tr')
    require(len(raw_rows) == expected_positions, 'source position accounting mismatch')
    require(len(set(a['job-id'] for a in artifacts)) == 2, 'duplicate retained job')
    for artifact in artifacts:
        require(artifact['source-sha256'] == source_sha and artifact['raw-html'] == page,
                'artifact original source mismatch')
        require(artifact['context']['event-date'] == '2026-06-03', 'artifact selected date mismatch')
        acquisitions = artifact['acquisitions']
        require(acquisitions and all(a['manifest']['sha256'] == source_sha
                and a['manifest']['provenance']['browser-state']['selected-date'] == '2026-06-03'
                and a['manifest']['provenance']['browser-state']['filters'] == {} for a in acquisitions),
                'artifact selected view provenance mismatch')
        require(len(artifact['candidates']) == len(raw_rows), 'artifact row accounting mismatch')
    rows, all_rows = [], []
    for ordinal, row_html in enumerate(raw_rows):
        coordinate = {'table': table_number, 'row': ordinal + 2}
        fragments = chunks(row_html, 'td')
        require(len(fragments) == len(headers), 'source cell accounting mismatch')
        fields = dict(zip(headers, [cell(x)['raw_text'] for x in fragments]))
        decoded = dict(zip(headers, [cell(x)['value'] for x in fragments]))
        versions = []
        for artifact, artifact_sha in zip(artifacts, artifact_shas):
            candidate = artifact['candidates'][ordinal]
            require(candidate['coordinates'] == coordinate and candidate['raw'] ==
                    {'html': row_html, 'cells': list(fields.values()), 'cell-html': fragments, 'fields': fields},
                    'artifact raw row or coordinates mismatch')
            require(candidate['parse-status'] == 'parsed', 'retained unparsed row')
            reference = artifact.get('_references', {}).get(ordinal)
            require(reference is not None, 'exact retained observation reference missing')
            require(reference['source-sha256'] == source_sha and reference['job-id'] == artifact['job-id']
                    and reference['artifact-sha256'] == artifact_sha and reference['ordinal'] == ordinal
                    and reference['parser-version'] == artifact['parser-version'], 'observation version binding mismatch')
            versions.append({'reference': reference, 'coordinates': coordinate,
                             'parsed': candidate['parsed'], 'raw': candidate['raw'],
                             'fields': candidate.get('fields', {}), 'parse_status': candidate['parse-status'],
                             'retained_review_status': candidate['review-status'], 'flags': candidate['flags']})
        raw_bytes = row_html.encode()
        require(source.count(raw_bytes) == 1, 'source row occurrence not unique')
        offset = source.index(raw_bytes)
        item = {'row_id': f't{table_number}-r{ordinal + 2:03}', 'ordinal': ordinal, 'coordinates': coordinate,
                'source_byte_offset': offset, 'source_line': source[:offset].count(b'\n') + 1,
                'source_row_sha256': sha(raw_bytes), 'source_row_html': row_html,
                'raw_fields': fields, 'source_cells': {k: cell(x) for k, x in zip(headers, fragments)},
                'versions': versions,
                'version_differences': differences({k:versions[0][k] for k in ('parsed','raw','fields','flags','parse_status')},
                                                   {k:versions[1][k] for k in ('parsed','raw','fields','flags','parse_status')})}
        all_rows.append({'row_id':item['row_id'], 'coordinates':coordinate, 'gender':decoded['Gender'],
                         'discipline':decoded['Discipline'], 'selected':decoded['Gender']=='F' and decoded['Discipline']=='DNF',
                         'references':[v['reference'] for v in versions]})
        if all_rows[-1]['selected']:
            rows.append(item)
    require(len(rows) == expected_selected, 'selected position accounting mismatch')
    return {'schema':SCHEMA, 'source':{'sha256':source_sha,'bytes':len(source),'selected_date':'2026-06-03',
                                     'selector':'day_5','filters':{},'headers':headers},
            'versions':[{'job_id':a['job-id'],'parser_version':a['parser-version'],'artifact_sha256':s,
                         'acquisitions':a['acquisitions']} for a,s in zip(artifacts,artifact_shas)],
            'comparison_kind':'same-original-retained-extraction-versions',
            'final_source_history':{'status':'missing-later-exact-final-source','publisher_revision':None,
                                    'explanation':'Same original SHA-256. No later exact final source retained; no final-source revision diff exists.'},
            'authority':{'source_accuracy':None,'sporting_finality':None,'relationship':None,'publication':None},
            'summary':{'source_positions':len(raw_rows),'source_versions':len(raw_rows)*2,
                       'gender_counts':dict(Counter(r['gender'] for r in all_rows)),
                       'selected_positions':len(rows),'selected_versions':len(rows)*2,
                       'card_counts':dict(Counter(r['source_cells']['Card']['value'] for r in rows)),
                       'rows_with_version_differences':sum(bool(r['version_differences']) for r in rows),
                       'field_differences':sum(len(r['version_differences']) for r in rows)},
            'all_position_accounting':all_rows,'rows':rows}


def render(report):
    require(report.get('schema') == SCHEMA, 'unsupported retained comparison report')
    esc = lambda x: html.escape(x if isinstance(x,str) else json.dumps(x,ensure_ascii=False,sort_keys=True), quote=True)
    parts = ['<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>AIDA June 3 exact retained comparison</title><link rel="stylesheet" href="/owner-evidence/assets/sporting.css"></head><body><main><a href="/owner-evidence/sporting">Sporting source reviews</a><h1>AIDA June 3 women DNF: complete retained comparison</h1>',
             '<p class="notice">Private source inspection. Two extraction versions of the same original are not two dives. No review, finality, relationship, selection or publication authority is granted.</p>',
             '<h2>Publisher revision and final-source history</h2><p>'+esc(report['final_source_history']['explanation'])+'</p>',
             '<h2>Complete accounting</h2><pre>'+esc(report['summary'])+'</pre><h2>Exact source and retained versions</h2><pre>'+esc({'source':report['source'],'versions':report['versions']})+'</pre>',
             '<p>RP is the source achieved performance; Points stays its literal source value. No explicit penalty or final-distance column is present. Null penalty and rank mean unknown, never zero. Points/RP arithmetic does not establish post-penalty final-distance rules. No revision order is inferred from artifact IDs.</p>']
    for row in report['rows']:
        parts += ['<article id="'+esc(row['row_id'])+'"><h2>'+esc(row['row_id'])+' - '+esc(row['source_cells']['Diver']['value'])+'</h2>',
                  '<p>Table '+str(row['coordinates']['table'])+', row '+str(row['coordinates']['row'])+', ordinal '+str(row['ordinal'])+', source byte '+str(row['source_byte_offset'])+', line '+str(row['source_line'])+'</p>',
                  '<table><tr><th>Source field</th><th>Original decoded cell</th></tr>']
        parts += ['<tr><th>'+esc(k)+'</th><td>'+esc(v['value'])+'</td></tr>' for k in report['source']['headers'] for v in [row['source_cells'][k]]]
        parts += ['</table><h3>Parser / extraction payload differences</h3><pre>'+esc(row['version_differences'])+'</pre>']
        for i,v in enumerate(row['versions'],1):
            parts += ['<details><summary>Retained version '+str(i)+' exact reference and all parsed fields</summary><pre>'+esc(v)+'</pre></details>']
        parts += ['<details><summary>Original row HTML as escaped text</summary><pre>'+esc(row['source_row_html'])+'</pre></details></article>']
    return ''.join(parts)+'</main></body></html>'



def read_service(config_path):
    """Verify separately pinned private derived report before safe rendering."""
    from sporting_authority import private_bytes
    config = json.loads(private_bytes(config_path,65536))
    require(set(config) == {'schema','report','source_sha256','artifact_sha256s','hashes_sha256',
                            'selected_positions','source_positions'}
            and config['schema'] == 'retained-aida-diff-service/v1', 'invalid retained diff configuration')
    pin = config['report']
    require(set(pin) == {'path','sha256'} and Path(pin['path']).is_absolute(), 'invalid retained diff report pin')
    data = private_bytes(pin['path'])
    require(sha(data) == pin['sha256'], 'retained diff report pin changed')
    report = json.loads(data)
    require(report['schema'] == SCHEMA and report['source']['sha256'] == config['source_sha256']
            and [v['artifact_sha256'] for v in report['versions']] == config['artifact_sha256s']
            and report['input_pins']['hashes_sha256'] == config['hashes_sha256'], 'retained diff source or version pin mismatch')
    require(report['comparison_kind'] == 'same-original-retained-extraction-versions'
            and report['final_source_history']['publisher_revision'] is None
            and set(report['authority']) == {'source_accuracy','sporting_finality','relationship','publication'}
            and all(value is None for value in report['authority'].values()), 'retained diff cannot grant authority')
    require(len(report['rows']) == report['summary']['selected_positions'] == config['selected_positions']
            and len(report['all_position_accounting']) == report['summary']['source_positions'] == config['source_positions']
            and report['summary']['selected_versions'] == len(report['rows'])*2, 'retained diff incomplete accounting')
    for row in report['rows']:
        require(len(row['versions']) == 2, 'retained diff version missing')
        for version,artifact in zip(row['versions'],config['artifact_sha256s']):
            require(version['reference']['artifact-sha256'] == artifact
                    and version['reference']['source-sha256'] == config['source_sha256'], 'retained diff row version mismatch')
    return render(report).encode()


def build(hashes_path, hashes_sha256):
    pin_bytes = Path(hashes_path).read_bytes()
    require(sha(pin_bytes) == hashes_sha256, 'retained pins hash mismatch')
    pins = json.loads(pin_bytes)
    for pin in pins.values():
        data = Path(pin['path']).read_bytes()
        require(len(data) == pin['bytes'] and sha(data) == pin['sha256'], 'retained input pin mismatch')
    artifact_pins = [(k[len('artifact_'):],v) for k,v in pins.items() if k.startswith('artifact_')]
    require(len(artifact_pins) == 2, 'two pinned artifacts required')
    code = "(require '[clojure.edn :as edn] '[clojure.data.json :as json]) (println (json/write-str (mapv #(edn/read-string (slurp %)) (json/read-str (slurp *in*)))))"
    result = subprocess.run(['clojure','-M','-e',code], input=json.dumps([p['path'] for _,p in artifact_pins]),
                            text=True,capture_output=True,check=True,timeout=30)
    artifacts = json.loads(result.stdout)
    manifest = json.loads(Path(pins['import_manifest_b16']['path']).read_bytes())
    for (job_id,pin),artifact in zip(artifact_pins,artifacts):
        selected = [x for x in manifest['selected'] if x['job-id'] == job_id]
        require(len(selected) == 1 and all(selected[0][key] == value for key,value in {
            'source-sha256':artifact['source-sha256'], 'artifact-sha256':pin['sha256'],
            'parser-version':artifact['parser-version'], 'candidate-count':len(artifact['candidates'])}.items()),
            'retained import manifest version mismatch')
    reviews = [json.loads(line) for line in Path(pins['review_index_b16']['path']).read_text().splitlines()]
    for (job_id,pin),artifact in zip(artifact_pins,artifacts):
        require(artifact['job-id'] == job_id, 'pinned artifact job mismatch')
        bound = [r for r in reviews if r['job-id'] == job_id]
        require(len(bound) == len(artifact['candidates']), 'retained review reference accounting mismatch')
        refs = {}
        for r in bound:
            ordinal = r['ordinal']
            require(ordinal not in refs and r['coordinates'] == artifact['candidates'][ordinal]['coordinates'], 'retained reference row mismatch')
            refs[ordinal] = {k:r[k] for k in ('job-id','source-sha256','artifact-sha256','candidate-id','ordinal','parser-version')}
        artifact['_references'] = refs
    source = Path(pins['official-selected-date-source.html']['path']).read_bytes()
    report = compare(source, artifacts, [p['sha256'] for _,p in artifact_pins])
    ledger = [json.loads(line) for line in Path(pins['ledger.jsonl']['path']).read_text().splitlines()]
    require(len(ledger) == len(report['rows']), 'retained selected ledger count mismatch')
    for retained,current in zip(ledger,report['rows']):
        require(retained['row_id'] == current['row_id'] and retained['source_fields'] == current['raw_fields']
                and retained['source_row_html'] == current['source_row_html'] and retained['source_byte_offset'] == current['source_byte_offset']
                and retained['source_line'] == current['source_line'], 'retained ledger source row mismatch')
        for old,new in zip(retained['versions'],current['versions']):
            require(old['parsed'] == new['parsed'] and old['raw'] == new['raw']
                    and old['candidate_id'] == new['reference']['candidate-id'], 'retained ledger version mismatch')
    report['input_pins'] = {'hashes_sha256':hashes_sha256,'verified_files':len(pins),
                            'files':{k:{'bytes':v['bytes'],'sha256':v['sha256']} for k,v in pins.items()}}
    finality = Path(hashes_path).parent / 'FINALITY-SEARCH-20260928.md'
    report['final_source_history']['retained_search_sha256'] = sha(finality.read_bytes())
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--hashes',type=Path,required=True)
    parser.add_argument('--hashes-sha256',required=True)
    parser.add_argument('--output-dir',type=Path,required=True)
    args = parser.parse_args()
    report = build(args.hashes,args.hashes_sha256)
    args.output_dir.mkdir(parents=True,exist_ok=True,mode=0o700)
    outputs = {'report.json':canonical(report),'report.html':render(report).encode()}
    for name,data in outputs.items():
        target = args.output_dir/name
        if target.exists():
            require(target.read_bytes() == data, 'existing report output differs')
        else:
            with target.open('xb') as f: f.write(data)
        os.chmod(target,0o600)
    print(json.dumps({'schema':SCHEMA,'summary':report['summary'],'outputs':{n:{'bytes':len(b),'sha256':sha(b)} for n,b in outputs.items()}},sort_keys=True))


if __name__ == '__main__':
    main()
