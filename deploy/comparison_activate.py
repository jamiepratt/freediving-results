#!/usr/bin/env python3
"""Guarded private comparison app activation; never restore sporting authority/data.

Capture the complete live guard before staging. Activation rereads it immediately
before swapping derived app/config files. Rollback restores only those files and
refuses to overwrite another deployment. Human status/history are never backed up
or restored by this helper. Input packets and runtimes are staged separately.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import sys
import urllib.request
from owner_evidence_activate import (Layout, SERVICE, _atomic_link, _atomic_write,
    _regular, _sha, _stage_directory, _system_command, _health)


def _tree(root):
    if root.is_symlink() or not root.is_dir():
        raise ValueError('guard directory missing or linked')
    result={}
    for path in sorted(root.rglob('*')):
        if path.is_symlink():raise ValueError('linked guard input')
        if path.is_file():result[str(path.relative_to(root))]=_sha(path)
    return result


def _pg_tables(database):
    if not re.fullmatch(r'[A-Za-z0-9_]+',database):raise ValueError('invalid database')
    def query(sql):
        result=subprocess.run(['sudo','-n','-u','postgres','psql','-X','-v','ON_ERROR_STOP=1','-d',database,'-Atc',sql],capture_output=True,text=True,timeout=60)
        if result.returncode:raise ValueError('read-only table guard refused')
        return result.stdout.strip()
    names=query("SELECT tablename FROM pg_tables WHERE schemaname='freediving' ORDER BY tablename").splitlines()
    if any(not re.fullmatch(r'[A-Za-z0-9_]+',x) for x in names):raise ValueError('invalid table name')
    sql=['BEGIN READ ONLY']
    for name in names:
        sql.append("SELECT json_build_object('table','"+name+"','count',count(*),'rows_text',COALESCE(json_agg(row_to_json(t) ORDER BY row_to_json(t)::text)::text,'[]')) FROM freediving."+name+' t')
    sql.append('COMMIT')
    result={}
    for line in query(';'.join(sql)).splitlines():
        if line in ('BEGIN','COMMIT'):continue
        row=json.loads(line)
        # PostgreSQL's stable row JSON representation matches prior host audits.
        result[row['table']]={'count':row['count'],'sha256':hashlib.sha256(row['rows_text'].encode()).hexdigest()}
    return result


def capture_guard(layout, public_database, *, public_app=Path('/opt/freediving/current'),
                  public_configs=(Path('/etc/freediving/public.env'),Path('/etc/freediving/migration.env')),
                  table_reader=None):
    """Read hashes only, including all current history and public/canonical tables."""
    table_reader=table_reader or _pg_tables
    app=(layout.app/'current').resolve(strict=True)
    ledger=layout.state/'decisions/ledger.sqlite';_regular(ledger)
    with sqlite3.connect('file:'+str(ledger)+'?mode=ro',uri=True) as db:
        db.execute('BEGIN')
        owner={}
        for (name,) in db.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").fetchall():
            rows=db.execute('SELECT * FROM "'+name.replace('"','""')+'"').fetchall()
            owner[name]={'count':len(rows),'sha256':hashlib.sha256(json.dumps(sorted(rows,key=repr),ensure_ascii=False,separators=(',',':')).encode()).hexdigest()}
        db.rollback()
    reader_path=layout.state/'canonical-reader/config.json';_regular(reader_path)
    reader=json.loads(reader_path.read_text());runtime=Path(reader['runtime_path'])
    protected_paths=[layout.state/name for name in ('current/snapshot.sqlite','current/manifest.json','current-source/manifest.json','status/presentation-status.json')]
    protected_paths += [reader_path]+[Path(x['path']) for x in reader.get('exports',{}).values()]
    public_current=public_app.resolve(strict=True)
    for path in protected_paths+list(public_configs):_regular(path)
    comparison_path=layout.state/'comparison/config.json'
    return {'schema':'private-comparison-activation-guard/v1',
            'app':{'path':str(app),'files':_tree(app)},
            'derived':{**{name:_sha(path) for name,path in [('config',layout.config),('env',layout.state/'active.env'),('unit',layout.units/SERVICE)]},
                       'comparison':_sha(comparison_path) if comparison_path.exists() else None},
            'protected':{'files':{str(path):_sha(path) for path in protected_paths},'canonical_runtime':{'path':str(runtime),'files':_tree(runtime)},
                         'public_app':{'path':str(public_current),'files':_tree(public_current)},'public_configs':{str(path):_sha(path) for path in public_configs}},
            'authority':{'owner_tables':owner,'canonical_tables':table_reader('freediving_canonical'),'public_tables':table_reader(public_database)}}


def _comparison_config(source, value=None):
    _regular(source)
    value=value if value is not None else json.loads(source.read_text())
    required={'packet','runtime_path','runtime_manifest_sha256','authority_evidence'}
    if not required<=set(value) or set(value)-required-{'source_objects'}:raise ValueError('invalid comparison configuration')
    pins=[(Path(value['packet']['path']),value['packet']['sha256']),
          (Path(value['runtime_path'])/'manifest.json',value['runtime_manifest_sha256'])]
    if value['authority_evidence'] is not None:
        pins.append((Path(value['authority_evidence']['path']),value['authority_evidence']['sha256']))
    sources=value.get('source_objects',{})
    if not isinstance(sources,dict):raise ValueError('invalid comparison source objects')
    for source_sha,source in sources.items():
        if (not re.fullmatch(r'[a-f0-9]{64}',source_sha) or set(source)!={'path','sha256','mime_type'} or
                source['sha256']!=source_sha or source['mime_type']!='application/pdf'):
            raise ValueError('invalid comparison source pin')
        pins.append((Path(source['path']),source_sha))
    for path,digest in pins:
        _regular(path)
        if not re.fullmatch(r'[a-f0-9]{64}',digest) or _sha(path)!=digest:raise ValueError('comparison evidence pin changed')
    runtime=Path(value['runtime_path']);_tree(runtime)
    manifest=json.loads((runtime/'manifest.json').read_text())
    for name,digest in manifest['files'].items():
        if Path(name).is_absolute() or '..' in Path(name).parts:raise ValueError('invalid runtime path')
        _regular(runtime/name)
        if _sha(runtime/name)!=digest:raise ValueError('comparison runtime changed')
    return value


def _read_values(data):
    return dict(line.split('=',1) for line in data.decode().splitlines())


def stage_payload(config, state, uid, gid, expected_config_sha256):
    """Install immutable private packet/runtime versions without changing active pins."""
    config=Path(config)
    if _sha(config)!=expected_config_sha256:raise ValueError('staged comparison config changed')
    value=json.loads(config.read_text())
    # Packaged config pins retain their original local paths. Relocate only the
    # fixed package layout; arbitrary outside references are never substituted.
    recorded_root=Path(value['packet']['path']).parent
    if value['packet']['path']!=str(recorded_root/'packet.edn') or value['runtime_path']!=str(recorded_root/'runtime'):
        raise ValueError('comparison package layout invalid')
    if not Path(value['packet']['path']).exists():
        value={**value,'packet':{**value['packet'],'path':str(config.parent/'packet.edn')},'runtime_path':str(config.parent/'runtime')}
        if value['authority_evidence'] is not None:raise ValueError('relocated package cannot include sporting authority')
        if 'source_objects' in value:
            relocated={}
            for digest,source in value['source_objects'].items():
                if source['path']!=str(recorded_root/'sources'/(digest+'.pdf')):raise ValueError('comparison source package layout invalid')
                relocated[digest]={**source,'path':str(config.parent/'sources'/(digest+'.pdf'))}
            value['source_objects']=relocated
    value=_comparison_config(config,value)
    parent=Path(state)/'comparison-payloads'
    if parent.is_symlink():raise ValueError('linked comparison payload directory')
    parent.mkdir(mode=0o750,exist_ok=True);parent.chmod(0o750);os.chown(parent,uid,gid)
    runtime_source=Path(value['runtime_path'])
    files=[(name,runtime_source/name) for name in _tree(runtime_source)]
    runtime=_stage_directory(parent/'runtimes',value['runtime_manifest_sha256'],files,uid,gid,0o640)
    packet_source=Path(value['packet']['path'])
    packet=_stage_directory(parent/'packets',value['packet']['sha256'],[('packet.edn',packet_source)],uid,gid,0o640)/'packet.edn'
    output_value={**value,'packet':{**value['packet'],'path':str(packet)},'runtime_path':str(runtime)}
    if value['authority_evidence'] is not None:
        proof=value['authority_evidence']
        evidence=_stage_directory(parent/'authority',proof['sha256'],[('evidence.edn',Path(proof['path']))],uid,gid,0o640)/'evidence.edn'
        output_value['authority_evidence']={**proof,'path':str(evidence)}
    if 'source_objects' in value:
        output_value['source_objects']={}
        for digest,source in value['source_objects'].items():
            object_path=_stage_directory(parent/'sources',digest,[('source.pdf',Path(source['path']))],uid,gid,0o640)/'source.pdf'
            output_value['source_objects'][digest]={**source,'path':str(object_path)}
    data=(json.dumps(output_value,sort_keys=True)+'\n').encode()
    digest=hashlib.sha256(data).hexdigest()
    output_parent=parent/'configs'/digest
    output_parent.mkdir(mode=0o750,parents=True,exist_ok=True)
    output=output_parent/'config.json'
    if output.exists():
        _regular(output)
        if output.read_bytes()!=data:raise ValueError('installed comparison config changed')
    else:_atomic_write(output,data,0o640)
    os.chown(output,uid,gid)
    for directory in sorted((p for p in parent.rglob('*') if p.is_dir()),reverse=True):
        if directory.is_symlink():raise ValueError('linked comparison payload directory')
        directory.chmod(0o750);os.chown(directory,uid,gid)
    _comparison_config(output)
    return output


def _checkpoint(layout):return layout.state/'comparison-activation-checkpoint'


def _service_probe(app, config, uid, gid):
    code=('import sys,json,hashlib;sys.path.insert(0,sys.argv[1]);'
          'from private_attempt_inspector import create_reader;'
          'r=create_reader({"OWNER_EVIDENCE_COMPARISON_CONFIG":sys.argv[2]});v=r({},None);'
          'assert v["counts"]["source_positions"]==138;'
          'assert v["counts"]["retained_observation_versions"]==276;'
          'assert v["counts"]["distinct_sporting_attempts"] is None;'
          'assert v["counts"]["eligible_peer_cohorts"]==0;'
          'assert v["coverage"]["ranked"]==0;'
          'sources=json.load(open(sys.argv[2])).get("source_objects",{});'
          'row=r({"federation":"CMAS","limit":1},None)["rows"][0] if sources else None;'
          'body=r.source_bytes(row) if row else None;'
          'assert not sources or (body.startswith(b"%PDF-") and hashlib.sha256(body).hexdigest()==row["source_id"]);'
          'print("verified")')
    identity={}
    if uid!=os.geteuid() or gid!=os.getegid():identity={'user':uid,'group':gid,'extra_groups':[]}
    result=subprocess.run(['/usr/bin/python3','-I','-c',code,str(app/'scripts'),str(config)],capture_output=True,timeout=60,env={'PATH':'/usr/bin:/bin'},**identity)
    if result.returncode or result.stdout.strip()!=b'verified':raise ValueError('service comparison reader preflight refused')


def _comparison_health(values):
    _health(values,values['OWNER_EVIDENCE_SNAPSHOT_SHA256'])
    headers={'Host':values['OWNER_EVIDENCE_ORIGIN_HOST'],
             'X-Freediving-Owner-Gateway':values['OWNER_EVIDENCE_GATEWAY_SECRET'],
             'X-Freediving-Owner-Email':values['OWNER_EVIDENCE_EMAILS'].split(',')[0]}
    url='http://127.0.0.1:8081/owner-evidence/api/attempt-inspector?limit=1'
    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(urllib.request.Request(url,headers=headers),timeout=60) as response:
        value=json.load(response)
        if (response.status!=200 or 'no-store' not in response.headers.get('Cache-Control','') or value.get('schema')!='private-attempt-inspector/v1' or
            value.get('counts')!={'source_positions':138,'retained_observation_versions':276,'distinct_sporting_attempts':None,'eligible_peer_cohorts':0} or
            value.get('coverage',{}).get('ranked')!=0 or value.get('pagination',{}).get('total')!=138):
            raise ValueError('private comparison readiness refused')


def rollback_comparison(layout, *, command=None, owner_uid=0, owner_gid=0):
    """Restore derived app/config only, irrespective of newer genuine human events."""
    command=command or _system_command
    checkpoint=_checkpoint(layout);_regular(checkpoint/'record.json')
    record=json.loads((checkpoint/'record.json').read_text())
    if record['status'] not in ('active','pending'):raise ValueError('comparison checkpoint not rollbackable')
    if _sha(layout.units/SERVICE)!=record['unit_sha256']:raise ValueError('service unit changed; rollback refused')
    installed=layout.state/'comparison/config.json'
    current_app=str((layout.app/'current').resolve())
    allowed=(record['before'],record['after']) if record['status']=='pending' else (record['after'],)
    if (current_app not in [r['app'] for r in allowed] or
        any((_sha(path) if path.exists() else None) not in [r[name] for r in allowed] for name,path in [('config',layout.config),('env',layout.state/'active.env'),('comparison',installed)])):
        raise ValueError('derived state changed; rollback refused')
    for state in (record['before'],record['after']):
        if _tree(Path(state['app']))!=state['app_files']:raise ValueError('rollback app changed')
    for name in ('config','env','comparison'):
        if record['before'][name] is not None:
            backup=checkpoint/('before-'+name);_regular(backup)
            if _sha(backup)!=record['before'][name]:raise ValueError('rollback backup changed')
    _atomic_link(layout.app/'current',Path(record['before']['app']))
    for name,path in [('config',layout.config),('env',layout.state/'active.env'),('comparison',installed)]:
        if record['before'][name] is None:path.unlink(missing_ok=True)
        else:
            _atomic_write(path,(checkpoint/('before-'+name)).read_bytes(),0o640 if name=='comparison' else 0o600)
            if name=='comparison':os.chown(path,os.geteuid(),owner_gid)
    command('systemctl','restart',SERVICE)
    _atomic_write(checkpoint/'record.json',json.dumps({**record,'status':'rolled_back'},sort_keys=True).encode(),0o600)


def activate_comparison(bundle, config, layout, expected_guard, *, guard,
                        command=None, health=None, owner_uid=0, owner_gid=0,
                        bundle_manifest_sha256=None, config_sha256=None, service_probe=None):
    command=command or _system_command
    bundle,config=Path(bundle),Path(config)
    manifest_path=bundle/'private-owner-manifest.json';_regular(manifest_path)
    if bundle_manifest_sha256 and _sha(manifest_path)!=bundle_manifest_sha256:raise ValueError('staged app manifest changed')
    if config_sha256 and _sha(config)!=config_sha256:raise ValueError('staged comparison config changed')
    manifest=json.loads(manifest_path.read_text());_comparison_config(config)
    if not re.fullmatch(r'[a-f0-9]{40}',manifest['candidate']):raise ValueError('invalid code candidate')
    files=[]
    for name,digest in manifest['files'].items():
        if Path(name).is_absolute() or '..' in Path(name).parts:raise ValueError('invalid app path')
        _regular(bundle/name)
        if _sha(bundle/name)!=digest:raise ValueError('staged app changed')
        files.append((name,bundle/name))
    if expected_guard.get('schema')!='private-comparison-activation-guard/v1' or guard()!=expected_guard:raise ValueError('live guard changed')
    app=_stage_directory(layout.app/'versions',_sha(manifest_path),files,os.geteuid(),os.getegid(),0o644)
    installed=layout.state/'comparison/config.json'
    before={'app':str((layout.app/'current').resolve()),'app_files':expected_guard['app']['files'],'config':_sha(layout.config),'env':_sha(layout.state/'active.env'),'comparison':_sha(installed) if installed.exists() else None}
    pin=('OWNER_EVIDENCE_COMPARISON_CONFIG='+str(installed)+'\n').encode()
    contents={name:b''.join(line for line in path.read_bytes().splitlines(keepends=True) if not line.startswith(b'OWNER_EVIDENCE_COMPARISON_CONFIG='))+pin for name,path in [('config',layout.config),('env',layout.state/'active.env')]}
    checkpoint=_checkpoint(layout)
    if checkpoint.is_symlink():raise ValueError('linked comparison checkpoint')
    if (checkpoint/'record.json').exists() and json.loads((checkpoint/'record.json').read_text())['status']=='pending':raise ValueError('pending comparison activation requires reviewed recovery')
    checkpoint.mkdir(mode=0o700,exist_ok=True);checkpoint.chmod(0o700)
    for name,path in [('config',layout.config),('env',layout.state/'active.env'),('comparison',installed)]:
        if before[name] is not None:_atomic_write(checkpoint/('before-'+name),path.read_bytes(),0o600)
    after={'app':str(app.resolve()),'app_files':_tree(app),'config':hashlib.sha256(contents['config']).hexdigest(),'env':hashlib.sha256(contents['env']).hexdigest(),'comparison':_sha(config)}
    probe_config=_stage_directory(layout.state/'comparison-staged',_sha(config),[('config.json',config)],os.geteuid(),owner_gid,0o640)/'config.json'
    probe_config.parent.chmod(0o750)
    (service_probe or _service_probe)(app,probe_config,owner_uid,owner_gid)
    # Runtime and packet are revalidated after the service-user read/execute probe.
    _comparison_config(config)
    record={'schema':'private-comparison-activation-checkpoint/v1','status':'pending','before':before,'after':after,'candidate':manifest['candidate'],'unit_sha256':expected_guard['derived']['unit'],'guard_sha256':hashlib.sha256(json.dumps(expected_guard,sort_keys=True).encode()).hexdigest()}
    # Last full authority/CAS read occurs after staging and immediately before swaps.
    if guard()!=expected_guard:raise ValueError('live guard changed before activation')
    _atomic_write(checkpoint/'record.json',json.dumps(record,sort_keys=True).encode(),0o600)
    try:
        installed.parent.mkdir(mode=0o750,exist_ok=True)
        if installed.parent.is_symlink():raise ValueError('linked comparison configuration directory')
        installed.parent.chmod(0o750);os.chown(installed.parent,os.geteuid(),owner_gid)
        _atomic_write(installed,config.read_bytes(),0o640);os.chown(installed,os.geteuid(),owner_gid)
        _atomic_write(layout.config,contents['config'],0o600)
        _atomic_write(layout.state/'active.env',contents['env'],0o600)
        _atomic_link(layout.app/'current',app)
        command('systemctl','restart',SERVICE)
        if health:health()
        else:
            values=_read_values(contents['config']);_comparison_health(values)
        current=guard()
        if (current['authority']!=expected_guard['authority'] or current['protected']!=expected_guard['protected'] or
            current['derived']['unit']!=expected_guard['derived']['unit'] or
            current['app']!={'path':after['app'],'files':after['app_files']} or
            any(current['derived'][key]!=after[key] for key in ('config','env','comparison'))):
            raise ValueError('live authority changed during activation')
    except BaseException:
        rollback_comparison(layout,command=command,owner_uid=os.geteuid(),owner_gid=owner_gid)
        raise
    _atomic_write(checkpoint/'record.json',json.dumps({**record,'status':'active'},sort_keys=True).encode(),0o600)
    return 'activated'


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=('capture','stage','activate','rollback'))
    parser.add_argument('--public-database',required=True)
    parser.add_argument('--guard',type=Path)
    parser.add_argument('--bundle',type=Path);parser.add_argument('--config',type=Path)
    parser.add_argument('--bundle-manifest-sha256');parser.add_argument('--config-sha256')
    args=parser.parse_args()
    layout=Layout(Path('/opt/freediving/owner-evidence/app'),Path('/var/lib/freediving-owner-evidence'),Path('/etc/systemd/system'),Path('/etc/freediving/owner-evidence.env'))
    try:
        import pwd
        account=pwd.getpwnam('freediving-evidence')
        guard=lambda:capture_guard(layout,args.public_database)
        if args.action=='capture':print(json.dumps(guard(),sort_keys=True));return 0
        if os.geteuid()!=0:raise ValueError('host activation requires root')
        if args.action=='stage':
            if not args.config or not args.config_sha256:raise ValueError('stage requires exact configuration pin')
            config=stage_payload(args.config,layout.state,0,account.pw_gid,args.config_sha256)
            print(json.dumps({'config':str(config),'config_sha256':_sha(config),'data_writes':0}));return 0
        if args.action=='rollback':rollback_comparison(layout,owner_uid=0,owner_gid=account.pw_gid)
        else:
            if not all((args.guard,args.bundle,args.config,args.bundle_manifest_sha256,args.config_sha256)):raise ValueError('activation requires exact stage and live guard pins')
            activate_comparison(args.bundle,args.config,layout,json.loads(args.guard.read_text()),guard=guard,owner_uid=account.pw_uid,owner_gid=account.pw_gid,bundle_manifest_sha256=args.bundle_manifest_sha256,config_sha256=args.config_sha256)
        print(json.dumps({'result':'PASS','action':args.action,'data_writes':0}))
        return 0
    except Exception:
        print('Private comparison activation refused; preserve checkpoint and inspect current state',file=sys.stderr)
        return 1

if __name__=='__main__':sys.exit(main())
