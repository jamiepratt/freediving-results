#!/usr/bin/env python3
"""Guarded derived public app rollback. Never restore a database or evidence."""
import argparse
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
sys.dont_write_bytecode=True
from prepare_database import endpoint, read_config

VIEW='freediving.public_sporting_comparison'
BASE_SELECT=['freediving.public_event_coverage','freediving.public_results']
PUBLIC_SPORTING_DIRECTORY=Path('/var/lib/freediving-sporting-authority')
BRIDGE_TABLES={'public_sporting_bridge_receipts'}
NEW_TABLES={'public_sporting_policy_events','public_sporting_authority_events','public_sporting_members'}

@dataclass
class Layout:
    releases: Path=Path('/opt/freediving/releases')
    current: Path=Path('/opt/freediving/current')
    config: Path=Path('/etc/freediving')
    unit: Path=Path('/etc/systemd/system/freediving-public.service')

def sha(path):
    if not path.is_file() or path.is_symlink():raise ValueError('Checkpoint file missing or linked')
    return hashlib.sha256(path.read_bytes()).hexdigest()

def tree_digest(root):
    files={}
    for path in sorted(root.rglob('*')):
        if path.is_symlink():raise ValueError('Linked release input')
        if path.is_file():files[str(path.relative_to(root))]=sha(path)
    return hashlib.sha256(json.dumps(files,sort_keys=True).encode()).hexdigest()

def release(path,layout):
    path=Path(path)
    if path.is_symlink() or path.parent.resolve()!=layout.releases.resolve() or not re.fullmatch('[0-9a-f]{40}',path.name):raise ValueError('Exact release path required')
    if (path/'REVISION').read_text().strip()!=path.name:raise ValueError('Release revision mismatch')
    return path.resolve(strict=True)

def connection(layout):
    values=read_config(layout.config/'public.env')
    port,database=endpoint(values['FREEDIVING_PUBLIC_DATABASE_URL'],'reviews_public')
    if endpoint(read_config(layout.config/'migration.env')['FREEDIVING_MIGRATION_URL'],'freediving_migrator')!=(port,database):raise ValueError('Database configuration differs')
    if values['FREEDIVING_PUBLIC_ORIGIN']!='https://poc.alphacompose.com':raise ValueError('Custom origin differs')
    return port,database

def query(layout,sql,write=False):
    port,database=connection(layout)
    env={k:v for k,v in os.environ.items() if not k.startswith('PG')}
    r=subprocess.run(['runuser','-u','postgres','--','psql','-XAt','-v','ON_ERROR_STOP=1','-h','/var/run/postgresql','-p',port,'-d',database],input=('BEGIN'+('' if write else ' READ ONLY')+';'+sql+';COMMIT;').encode(),stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,env=env,check=True,timeout=60)
    return '\n'.join(x for x in r.stdout.decode().splitlines() if x not in ('BEGIN','COMMIT','REVOKE','LOCK TABLE','DO'))

def rows_expression(name):
    return "COALESCE((SELECT json_agg(row_to_json(t) ORDER BY row_to_json(t)::text)::text FROM freediving."+name+" t),'[]')"

def read_state(layout):
    port,database=connection(layout)
    def j(sql):return json.loads(query(layout,sql))
    names=query(layout,"SELECT tablename FROM pg_tables WHERE schemaname='freediving' AND tablename<>'schema_migrations' ORDER BY tablename").splitlines()
    if any(not re.fullmatch('[a-z_]+',name) for name in names):raise ValueError('Unexpected table name')
    tables={name:j("SELECT json_build_object('count',(SELECT count(*) FROM freediving."+name+"),'sha256',encode(sha256(convert_to("+rows_expression(name)+",'UTF8')),'hex'))") for name in names}
    role=j("""SELECT json_build_object('view_column_acl',EXISTS(SELECT 1 FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace CROSS JOIN LATERAL aclexplode(a.attacl) acl WHERE n.nspname='freediving' AND c.relname='public_sporting_comparison' AND acl.grantee=(SELECT oid FROM pg_roles WHERE rolname='reviews_public')),'select',COALESCE((SELECT json_agg(n.nspname||'.'||c.relname ORDER BY n.nspname,c.relname) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema' AND c.relkind IN ('r','v','m','p','f') AND (has_table_privilege('reviews_public',c.oid,'SELECT') OR has_any_column_privilege('reviews_public',c.oid,'SELECT'))),'[]'),'unsafe',
 EXISTS(SELECT 1 FROM pg_roles WHERE rolname='reviews_public' AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolbypassrls OR rolreplication)) OR
 EXISTS(SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname='reviews_public')) OR
 EXISTS(SELECT 1 FROM pg_database WHERE datname=current_database() AND pg_has_role('reviews_public',datdba,'MEMBER')) OR has_database_privilege('reviews_public',current_database(),'CREATE') OR
 EXISTS(SELECT 1 FROM pg_namespace WHERE nspname NOT LIKE 'pg_%' AND nspname <> 'information_schema' AND (pg_has_role('reviews_public',nspowner,'MEMBER') OR has_schema_privilege('reviews_public',oid,'CREATE'))) OR
 EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema' AND c.relkind IN ('r','v','m','p','f') AND (pg_has_role('reviews_public',c.relowner,'MEMBER') OR has_table_privilege('reviews_public',c.oid,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') OR has_any_column_privilege('reviews_public',c.oid,'INSERT,UPDATE,REFERENCES'))) OR
 EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema' AND p.prosecdef AND has_function_privilege('reviews_public',p.oid,'EXECUTE')))""")
    view=j("SELECT COALESCE((SELECT json_build_object('kind',relkind,'owner',pg_get_userbyid(relowner)) FROM pg_class WHERE oid=to_regclass('"+VIEW+"')),'null'::json)")
    policy=j("SELECT COALESCE(json_agg(json_build_object('revision',revision,'policy_version',policy_version,'db_role',db_role) ORDER BY revision),'[]') FROM freediving.public_sporting_policy_events") if 'public_sporting_policy_events' in names else []
    versions=j("SELECT json_object_agg(version::text,sha256 ORDER BY version) FROM freediving.schema_migrations")
    return {'database':database,'port':port,'owner':query(layout,'SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()'),'versions':versions,'tables':tables,'role':role,'view':view,'policy':policy}

def role_guard(state):
    if state['role'].get('view_column_acl',False):raise ValueError('Sporting view column grant requires separate assessment')
    if state['owner']!='freediving_migrator' or state['role']['unsafe'] or sorted(state['role']['select']) not in (BASE_SELECT, sorted(BASE_SELECT+[VIEW])):raise ValueError('Public role boundary changed')
    if state['view'] is not None and state['view']!={'kind':'v','owner':'freediving_migrator'}:raise ValueError('Sporting view owner changed')

def bridge_files(layout):
    paths=[layout.config/'sporting-authority.env']
    for directory in (layout.config/'sporting-authority',PUBLIC_SPORTING_DIRECTORY):
        if directory.is_symlink():raise ValueError('Linked sporting capability directory')
        if directory.exists():paths.extend(sorted(directory.rglob('*')))
    if any(p.is_symlink() for p in paths):raise ValueError('Linked sporting capability input')
    return {str(p):{'sha256':sha(p),'uid':p.stat().st_uid,'gid':p.stat().st_gid,'mode':p.stat().st_mode&0o777} for p in paths if p.exists() if not p.is_dir()}

def capture(candidate,layout=Layout(),reader=None):
    candidate=release(candidate,layout)
    if not layout.current.is_symlink():raise ValueError('Prior app pointer absent')
    previous=release(layout.current.resolve(strict=True),layout)
    prior_unit=sha(previous/'deploy/freediving-public.service')
    if sha(layout.unit)!=prior_unit:raise ValueError('Installed prior unit differs')
    state=(reader or (lambda:read_state(layout)))();role_guard(state)
    if set(state['versions']) not in tuple({str(n) for n in range(1,end)} for end in (22,23,24)):raise ValueError('Exact supported migration set required')
    return {'schema':'public-derived-rollback/v1','candidate':str(candidate),'previous':str(previous),'candidate_tree':tree_digest(candidate),'previous_tree':tree_digest(previous),'candidate_migration_22':sha(candidate/'resources/migrations/022-public-sporting-comparison.sql'),'candidate_migration_23':sha(candidate/'resources/migrations/023-sporting-authority-bridge.sql') if (candidate/'resources/migrations/023-sporting-authority-bridge.sql').exists() else None,'bridge_files':bridge_files(layout),'candidate_unit':sha(candidate/'deploy/freediving-public.service'),'previous_unit':prior_unit,'configs':{name:sha(layout.config/name) for name in ('public.env','migration.env')},'state':state}

def assess(checkpoint,candidate,layout=Layout(),reader=None):
    candidate=release(candidate,layout)
    if checkpoint.get('schema')!='public-derived-rollback/v1' or checkpoint['candidate']!=str(candidate):raise ValueError('Candidate checkpoint mismatch')
    previous=release(Path(checkpoint['previous']),layout)
    if not layout.current.is_symlink() or layout.current.resolve(strict=True)!=candidate:raise ValueError('Active release differs from candidate')
    if tree_digest(candidate)!=checkpoint['candidate_tree'] or tree_digest(previous)!=checkpoint['previous_tree']:raise ValueError('Release tree changed')
    if sha(layout.unit)!=checkpoint['candidate_unit'] or sha(candidate/'deploy/freediving-public.service')!=checkpoint['candidate_unit']:raise ValueError('Candidate unit changed')
    if sha(previous/'deploy/freediving-public.service')!=checkpoint['previous_unit']:raise ValueError('Previous unit changed')
    if {name:sha(layout.config/name) for name in checkpoint['configs']}!=checkpoint['configs']:raise ValueError('Configuration changed')
    if sha(candidate/'resources/migrations/022-public-sporting-comparison.sql')!=checkpoint['candidate_migration_22']:raise ValueError('Candidate migration changed')
    if bridge_files(layout)!=checkpoint.get('bridge_files',{}):raise ValueError('Sporting capability configuration changed')
    current=(reader or (lambda:read_state(layout)))();role_guard(current)
    before=checkpoint['state'];role_guard(before)
    if current['database']!=before['database'] or current['port']!=before['port']:raise ValueError('Database changed')
    expected={**before['versions'],'22':checkpoint['candidate_migration_22']}
    bridge=checkpoint.get('candidate_migration_23')
    if bridge:
        if sha(candidate/'resources/migrations/023-sporting-authority-bridge.sql')!=bridge:raise ValueError('Candidate bridge migration changed')
        expected['23']=bridge
    if current['versions']!=expected:raise ValueError('Migration checksum changed')
    if bridge and not (previous/'resources/migrations/023-sporting-authority-bridge.sql').exists():
        if checkpoint.get('bridge_files') or any(current['tables'].get(name,{}).get('count',0)>0 for name in ('public_sporting_authority_events','public_sporting_members','public_sporting_bridge_receipts')):
            raise ValueError('Prior unguarded app cannot serve live sporting authority; keep guarded candidate')
    if not set(before['tables'])<=set(current['tables']):raise ValueError('Retained table missing')
    if any(current['tables'][name]!=value for name,value in before['tables'].items()):raise ValueError('Retained authority/data changed')
    added=set(current['tables'])-set(before['tables'])
    if added not in (set(),NEW_TABLES,BRIDGE_TABLES,NEW_TABLES|BRIDGE_TABLES):raise ValueError('Unexpected added table')
    if sorted(before['role']['select'])==BASE_SELECT:
        if current['policy']!=[{'revision':1,'policy_version':'aida-baseline-v1','db_role':'freediving_migrator'}]:raise ValueError('Sporting policy changed')
        if current['tables'].get('public_sporting_policy_events',{}).get('count')!=1 or any(current['tables'].get(name,{}).get('count')!=0 for name in NEW_TABLES-{'public_sporting_policy_events'}):raise ValueError('Sporting facts require a new rollback assessment')
    return current

def revoke_view(layout,state):
    # Protect exact inspected rows against a concurrent writer before changing ACL.
    tables=state['tables']
    checks=[]
    for name,value in tables.items():
        if not re.fullmatch('[a-z_]+',name) or not re.fullmatch('[0-9a-f]{64}',value['sha256']):raise ValueError('Invalid digest guard')
        checks.append("IF encode(sha256(convert_to("+rows_expression(name)+",'UTF8')),'hex') <> '"+value['sha256']+"' THEN RAISE EXCEPTION 'Public data drift'; END IF;")
    sql='LOCK TABLE '+','.join('freediving.'+name for name in sorted(tables))+' IN SHARE MODE;DO $$ BEGIN '+''.join(checks)+" END $$;REVOKE SELECT ON "+VIEW+' FROM reviews_public'
    query(layout,sql,write=True)

def rollback(checkpoint,candidate,layout=Layout(),reader=None,revoke=None):
    state=assess(checkpoint,candidate,layout,reader)
    if VIEW not in checkpoint['state']['role']['select'] and VIEW in state['role']['select']:
        (revoke or (lambda:revoke_view(layout,state)))()
        after=(reader or (lambda:read_state(layout)))()
        if after['role']!=checkpoint['state']['role'] or after['tables']!=state['tables'] or after['versions']!=state['versions']:raise ValueError('Post-revocation guard changed; keep candidate pointer')
    # ACL revocation is fail-closed. If a later file switch fails, do not regrant it.
    previous=Path(checkpoint['previous'])
    temp=layout.current.with_name(layout.current.name+'.rollback-'+str(os.getpid()))
    temp.symlink_to(previous,target_is_directory=True)
    os.replace(temp,layout.current)
    unit_tmp=layout.unit.with_name(layout.unit.name+'.rollback-'+str(os.getpid()))
    with unit_tmp.open('xb') as f:f.write((previous/'deploy/freediving-public.service').read_bytes())
    unit_tmp.chmod(0o644);os.replace(unit_tmp,layout.unit)
    return {'result':'PASS','previous':str(previous),'data_writes':0,'schema_restored':False,'new_view_grant_revoked':VIEW not in checkpoint['state']['role']['select']}

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=('capture','assess','rollback'));parser.add_argument('--candidate',type=Path,required=True);parser.add_argument('--checkpoint',type=Path,required=True)
    args=parser.parse_args()
    try:
        if os.geteuid()!=0:raise ValueError('Root required')
        path=args.checkpoint
        if path.is_symlink() or path.parent.is_symlink() or path.parent.stat().st_uid!=0 or path.parent.stat().st_mode&0o077:raise ValueError('Root-private checkpoint directory required')
        if args.action=='capture':
            record=capture(args.candidate)
            fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
            with os.fdopen(fd,'w') as f:json.dump(record,f,sort_keys=True);f.write('\n')
            print(json.dumps({'checkpoint':str(path),'sha256':sha(path),'data_writes':0}));return 0
        if path.stat().st_uid!=0 or path.stat().st_mode&0o077:raise ValueError('Root-private checkpoint required')
        record=json.loads(path.read_text())
        result=rollback(record,args.candidate) if args.action=='rollback' else {'result':'PASS','tables':len(assess(record,args.candidate)['tables']),'data_writes':0,'action':'read-only-assessment'}
        print(json.dumps(result,sort_keys=True));return 0
    except Exception:
        print('Public derived rollback refused; preserve checkpoint and inspect current state',file=sys.stderr);return 1

if __name__=='__main__':sys.exit(main())
