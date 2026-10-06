#!/usr/bin/env python3
"""Host-only independent sporting signer/request capability. Never seeds authority.

Prepare under exact existing environment pins, then install the matching code using
normal public/private activation. Nothing changes source decisions or database ACLs.
"""
import argparse
import base64
import copy
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import pwd
import re
import secrets
import subprocess
import sys

@dataclass(frozen=True)
class Layout:
    private: Path=Path('/var/lib/freediving-owner-evidence/sporting-bridge')
    public: Path=Path('/var/lib/freediving-sporting-authority')
    units: Path=Path('/etc/systemd/system')
    env_dir: Path=Path('/etc/freediving')


def digest(path):
    unlinked(path)
    if not path.is_file():raise ValueError('Missing capability file')
    return hashlib.sha256(path.read_bytes()).hexdigest()


def unlinked(path):
    if any(p.is_symlink() for p in (path,*path.parents)):
        raise ValueError('Linked capability path')


def files(layout):
    return (layout.private/'signing.pem',layout.private/'request.key',
            layout.private/'config.json',layout.public/'config.json',
            layout.env_dir/'sporting-authority.env',layout.env_dir/'sporting-owner.env',
            layout.units/'freediving-owner-evidence.service.d/sporting-authority.conf')


def public_der(signing):
    return subprocess.run(['openssl','pkey','-pubout','-outform','DER'],input=signing,
                          stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,check=True,timeout=10).stdout


def service_access(path,uid,gid,*,boundary=None):
    """Check actual ancestor modes and, as root, read as the exact service identity."""
    unlinked(path)
    for ancestor in path.parents:
        info=ancestor.stat();bits=(info.st_mode>>6)&7 if info.st_uid==uid else (info.st_mode>>3)&7 if info.st_gid==gid else info.st_mode&7
        if uid!=0 and not bits&1:raise ValueError('Service traversal refused')
        if boundary is not None and ancestor==boundary:break
    info=path.stat();bits=(info.st_mode>>6)&7 if info.st_uid==uid else (info.st_mode>>3)&7 if info.st_gid==gid else info.st_mode&7
    if uid!=0 and not bits&4:raise ValueError('Service read refused')
    if os.geteuid()==0 and boundary is None:
        result=subprocess.run([sys.executable,'-I','-c','import pathlib,sys;pathlib.Path(sys.argv[1]).read_bytes();print("verified")',str(path)],
                              user=uid,group=gid,extra_groups=[],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=10)
        if result.returncode or result.stdout.strip()!=b'verified':raise ValueError('Actual service read refused')


def atomic_write(path,data,mode,gid):
    unlinked(path)
    temporary=path.with_name(path.name+'.relocating')
    fd=os.open(temporary,os.O_WRONLY|os.O_CREAT|os.O_EXCL,mode)
    try:
        with os.fdopen(fd,'wb') as stream:
            stream.write(data);stream.flush();os.fsync(stream.fileno())
        os.chown(temporary,os.geteuid(),gid);temporary.chmod(mode)
        os.replace(temporary,path)
        directory=os.open(path.parent,os.O_RDONLY)
        try:os.fsync(directory)
        finally:os.close(directory)
    finally:temporary.unlink(missing_ok=True)


def relocate_public(layout,owner_uid,owner_gid,public_gid,receipt_sha,config_sha,env_sha,*,public_uid=None):
    """Relocate an exact installed capability without regenerating any authority."""
    receipt=layout.private/'provision.json';environment=layout.env_dir/'sporting-authority.env'
    for path,pin in ((receipt,receipt_sha),(environment,env_sha)):
        if not isinstance(pin,str) or not re.fullmatch('[0-9a-f]{64}',pin) or digest(path)!=pin:raise ValueError('Relocation pin changed')
    if not isinstance(config_sha,str) or not re.fullmatch('[0-9a-f]{64}',config_sha):raise ValueError('Exact config pin required')
    record=json.loads(receipt.read_text())
    if str(layout.public/'config.json') in record['files']:
        if digest(layout.public/'config.json')!=config_sha:raise ValueError('Relocated config changed')
        return verify(layout,owner_uid,owner_gid,public_gid,public_uid=public_uid)
    old=Layout(layout.private,layout.env_dir/'sporting-authority',layout.units,layout.env_dir)
    if digest(old.public/'config.json')!=config_sha:raise ValueError('Original config changed')
    new_env=('FREEDIVING_SPORTING_AUTHORITY_CONFIG='+str(layout.public/'config.json')+'\n').encode()
    verify(old,owner_uid,owner_gid,public_gid,relocated_env_hash=hashlib.sha256(new_env).hexdigest())
    unlinked(layout.public)
    if layout.public.exists():
        info=layout.public.stat()
        if not layout.public.is_dir() or (info.st_uid,info.st_gid,info.st_mode&0o777)!=(os.geteuid(),public_gid,0o750):raise ValueError('Unsafe relocation directory')
    else:
        layout.public.mkdir(mode=0o750);os.chown(layout.public,os.geteuid(),public_gid);layout.public.chmod(0o750)
    destination=layout.public/'config.json'
    if destination.exists():
        info=destination.stat()
        if digest(destination)!=config_sha or (info.st_uid,info.st_gid,info.st_mode&0o777)!=(os.geteuid(),public_gid,0o640):raise ValueError('Relocation target changed')
    else:atomic_write(destination,(old.public/'config.json').read_bytes(),0o640,public_gid)
    if public_uid is not None:service_access(destination,public_uid,public_gid)
    atomic_write(environment,new_env,0o600,os.getegid())
    updated={'schema':'sporting-capability-provision/v1','files':{str(p):digest(p) for p in files(layout)}}
    atomic_write(receipt,(json.dumps(updated,sort_keys=True)+'\n').encode(),0o600,os.getegid())
    return verify(layout,owner_uid,owner_gid,public_gid,public_uid=public_uid)


def verify(layout=Layout(),owner_uid=None,owner_gid=None,public_gid=None,*,public_uid=None,relocated_env_hash=None):
    root_uid,root_gid=os.geteuid(),os.getegid()
    for directory,uid,gid,mode in ((layout.private,root_uid,owner_gid,0o750),
                                  (layout.public,root_uid,public_gid,0o750),
                                  (layout.private/'ledger',owner_uid,owner_gid,0o700)):
        unlinked(directory)
        info=directory.stat()
        if not directory.is_dir() or (info.st_uid,info.st_gid,info.st_mode&0o777)!=(uid,gid,mode):
            raise ValueError('Capability directory boundary changed')
    dropin=layout.units/'freediving-owner-evidence.service.d'
    unlinked(dropin)
    if dropin.stat().st_uid!=root_uid or dropin.stat().st_mode&0o022:raise ValueError('Unsafe private unit directory')
    record_path=layout.private/'provision.json'
    unlinked(record_path)
    info=record_path.stat()
    if info.st_uid!=root_uid or info.st_mode&0o777!=0o600:raise ValueError('Capability receipt boundary changed')
    record=json.loads(record_path.read_text())
    expected={str(p):digest(p) for p in files(layout)}
    environment=str(layout.env_dir/'sporting-authority.env')
    if relocated_env_hash is not None and expected.get(environment)==relocated_env_hash:
        expected[environment]=record['files'].get(environment)
    if record.get('schema')!='sporting-capability-provision/v1' or expected!=record['files']:
        raise ValueError('Capability changed')
    for path in files(layout):
        gid=owner_gid if path.parent==layout.private else public_gid if path.parent==layout.public else root_gid
        mode=0o600 if path.parent==layout.env_dir else 0o644 if path.parent.parent==layout.units else 0o640
        info=path.stat()
        if (info.st_uid,info.st_gid,info.st_mode&0o777)!=(root_uid,gid,mode):raise ValueError('Capability file boundary changed')
    private=json.loads((layout.private/'config.json').read_text())
    public=json.loads((layout.public/'config.json').read_text())
    der=public_der((layout.private/'signing.pem').read_bytes())
    if public['public_key_der']!=base64.b64encode(der).decode() or public['key_id']!=hashlib.sha256(der).hexdigest():
        raise ValueError('Signer differs from pinned verifier')
    if public['request_secret']!=(layout.private/'request.key').read_text().strip():raise ValueError('Request capability differs')
    relationship=private.pop('relationship_ledger_path',None)
    if relationship is not None and relationship!=str(layout.private/'ledger/relationships.sqlite'):
        raise ValueError('Private relationship ledger binding differs')
    if private!={'schema':'sporting-authority-service/v1','ledger_path':str(layout.private/'ledger/authority.sqlite'),
                'signing_key_path':str(layout.private/'signing.pem'),'request_key_path':str(layout.private/'request.key')}:
        raise ValueError('Private authority binding differs')
    if public_uid is not None:service_access(layout.public/'config.json',public_uid,public_gid)
    return {'result':'PASS','authority_seeded':False,'key_id':public['key_id'],
            'public_config':str(layout.public/'config.json'),'owner_config':str(layout.private/'config.json')}


def provision(layout,owner_uid,owner_gid,public_gid,pins):
    # CLI requires root. Isolated synthetic tests use their own uid/gid.
    root_uid,root_gid=os.geteuid(),os.getegid()
    for path,pin in pins.items():
        if not re.fullmatch('[0-9a-f]{64}',pin) or digest(path)!=pin:raise ValueError('Environment prerequisite changed')
    for path in (layout.private,layout.public,layout.private/'ledger',*files(layout)):
        unlinked(path)
    if (layout.private/'provision.json').exists():return verify(layout,owner_uid,owner_gid,public_gid)
    if any(p.exists() for p in files(layout)) or (layout.private/'ledger').exists():raise ValueError('Incomplete capability requires independent assessment')
    for directory,gid in ((layout.private,owner_gid),(layout.public,public_gid)):
        directory.mkdir(parents=True,exist_ok=True,mode=0o750)
        os.chown(directory,root_uid,gid);directory.chmod(0o750)
    ledger=layout.private/'ledger';ledger.mkdir(mode=0o700)
    os.chown(ledger,owner_uid,owner_gid)
    signing=subprocess.run(['openssl','genpkey','-algorithm','Ed25519'],stdout=subprocess.PIPE,
                           stderr=subprocess.DEVNULL,check=True,timeout=10).stdout
    der=public_der(signing)
    request=secrets.token_urlsafe(48)
    private={'schema':'sporting-authority-service/v1','ledger_path':str(ledger/'authority.sqlite'),
             'signing_key_path':str(layout.private/'signing.pem'),'request_key_path':str(layout.private/'request.key')}
    public={'endpoint':'http://127.0.0.1:8081/owner-evidence/api/sporting-authority/current',
            'request_secret':request,'public_key_der':base64.b64encode(der).decode(),
            'key_id':hashlib.sha256(der).hexdigest()}
    def write(path,data,gid,mode):
        fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,mode)
        with os.fdopen(fd,'wb') as stream:
            stream.write(data);stream.flush();os.fsync(stream.fileno())
        os.chown(path,root_uid,gid);path.chmod(mode)
    write(layout.private/'signing.pem',signing,owner_gid,0o640)
    write(layout.private/'request.key',(request+'\n').encode(),owner_gid,0o640)
    write(layout.private/'config.json',(json.dumps(private,sort_keys=True)+'\n').encode(),owner_gid,0o640)
    write(layout.public/'config.json',(json.dumps(public,sort_keys=True)+'\n').encode(),public_gid,0o640)
    dropin=layout.units/'freediving-owner-evidence.service.d'
    unlinked(dropin);dropin.mkdir(parents=True,exist_ok=True,mode=0o755)
    info=dropin.stat()
    if info.st_uid!=root_uid or info.st_mode&0o022:raise ValueError('Unsafe private unit directory')
    write(dropin/'sporting-authority.conf',('[Service]\nEnvironmentFile='+str(layout.env_dir/'sporting-owner.env')+'\nReadWritePaths='+str(ledger)+'\n').encode(),root_gid,0o644)
    write(layout.env_dir/'sporting-owner.env',('OWNER_EVIDENCE_SPORTING_CONFIG='+str(layout.private/'config.json')+'\n').encode(),root_gid,0o600)
    # Publish public capability last. Absent or incomplete authority always withholds.
    write(layout.env_dir/'sporting-authority.env',('FREEDIVING_SPORTING_AUTHORITY_CONFIG='+str(layout.public/'config.json')+'\n').encode(),root_gid,0o600)
    record={'schema':'sporting-capability-provision/v1','files':{str(p):digest(p) for p in files(layout)}}
    write(layout.private/'provision.json',(json.dumps(record,sort_keys=True)+'\n').encode(),root_gid,0o600)
    return verify(layout,owner_uid,owner_gid,public_gid)


def attach_relationships(layout,owner_uid,owner_gid,public_gid,bundle,manifest_sha,receipt_sha,
                         config_sha,expected_guard,guard):
    """Attach an independently empty immutable review ledger under exact live pins.

    No sporting or source decision is submitted; existing history is never restored.
    On interruption preserve capability files and obtain a fresh reviewed checkpoint.
    """
    for path,pin in ((layout.private/'provision.json',receipt_sha),
                     (layout.private/'config.json',config_sha),
                     (bundle/'private-owner-manifest.json',manifest_sha)):
        if not isinstance(pin,str) or not re.fullmatch('[0-9a-f]{64}',pin) or digest(path)!=pin:
            raise ValueError('Relationship attachment input pin changed')
    verify(layout,owner_uid,owner_gid,public_gid)
    record=json.loads((bundle/'private-owner-manifest.json').read_text())
    if not re.fullmatch('[0-9a-f]{40}',record['candidate']):raise ValueError('Invalid relationship code candidate')
    for relative,pin in record['files'].items():
        if Path(relative).is_absolute() or '..' in Path(relative).parts or digest(bundle/relative)!=pin:
            raise ValueError('Relationship code pin changed')
    config=json.loads((layout.private/'config.json').read_text())
    ledger=layout.private/'ledger/relationships.sqlite';unlinked(ledger)
    if guard()!=expected_guard:raise ValueError('Live authority changed before relationship attachment')
    if config.get('relationship_ledger_path')==str(ledger):
        if not ledger.is_file():raise ValueError('Missing attached relationship ledger')
        return {'result':'PASS','attached':True,'authority_seeded':False,'relationship_ledger':str(ledger)}
    identity={} if (owner_uid,owner_gid)==(os.geteuid(),os.getegid()) else {'user':owner_uid,'group':owner_gid,'extra_groups':[]}
    code=('import sys;sys.path.insert(0,sys.argv[1]);from private_sporting_relationships import RelationshipReviews;'
          'r=RelationshipReviews(sys.argv[2],lambda:{});assert r.history()==[];r.close();print("verified")')
    result=subprocess.run([sys.executable,'-I','-c',code,str(bundle/'scripts'),str(ledger)],
                          capture_output=True,timeout=15,env={'PATH':'/usr/bin:/bin'},**identity)
    if result.returncode or result.stdout.strip()!=b'verified':raise ValueError('Empty relationship capability preparation refused')
    current=copy.deepcopy(guard());expected=copy.deepcopy(expected_guard)
    # This one schema initialization is the only authorized live guard difference.
    if 'sporting' in current and current['sporting'] is not None:
        current['sporting']['relationships']=expected['sporting']['relationships']
    if current!=expected:raise ValueError('Live authority changed during relationship attachment')
    config['relationship_ledger_path']=str(ledger)
    atomic_write(layout.private/'config.json',(json.dumps(config,sort_keys=True)+'\n').encode(),0o640,owner_gid)
    updated={'schema':'sporting-capability-provision/v1','files':{str(p):digest(p) for p in files(layout)}}
    atomic_write(layout.private/'provision.json',(json.dumps(updated,sort_keys=True)+'\n').encode(),0o600,os.getegid())
    verify(layout,owner_uid,owner_gid,public_gid)
    return {'result':'PASS','attached':True,'authority_seeded':False,'relationship_events':0,'relationship_ledger':str(ledger)}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=('prepare','verify','relocate-public','attach-relationships'))
    parser.add_argument('--execute',action='store_true')
    parser.add_argument('--public-env-sha256')
    parser.add_argument('--owner-env-sha256')
    parser.add_argument('--provision-sha256')
    parser.add_argument('--public-config-sha256')
    parser.add_argument('--authority-env-sha256')
    parser.add_argument('--owner-config-sha256')
    parser.add_argument('--bundle',type=Path)
    parser.add_argument('--bundle-manifest-sha256')
    parser.add_argument('--guard',type=Path)
    parser.add_argument('--guard-sha256')
    parser.add_argument('--public-database')
    args=parser.parse_args()
    try:
        if os.geteuid()!=0:raise ValueError('Root host checkpoint required')
        owner=pwd.getpwnam('freediving-evidence');public=pwd.getpwnam('freediving')
        layout=Layout()
        if args.action=='verify':result=verify(layout,owner.pw_uid,owner.pw_gid,public.pw_gid,public_uid=public.pw_uid)
        elif args.action=='attach-relationships':
            if not args.execute or not all((args.bundle,args.bundle_manifest_sha256,args.guard,args.guard_sha256,args.public_database)):
                raise ValueError('Explicit pinned relationship attachment required')
            if digest(args.guard)!=args.guard_sha256:raise ValueError('Relationship guard pin changed')
            from comparison_activate import capture_guard
            from owner_evidence_activate import Layout as OwnerLayout
            owner_layout=OwnerLayout(Path('/opt/freediving/owner-evidence/app'),Path('/var/lib/freediving-owner-evidence'),layout.units,layout.env_dir/'owner-evidence.env')
            result=attach_relationships(layout,owner.pw_uid,owner.pw_gid,public.pw_gid,args.bundle,args.bundle_manifest_sha256,args.provision_sha256,args.owner_config_sha256,json.loads(args.guard.read_text()),lambda:capture_guard(owner_layout,args.public_database))
        elif args.action=='relocate-public':
            if not args.execute:raise ValueError('Explicit pinned relocation required')
            result=relocate_public(layout,owner.pw_uid,owner.pw_gid,public.pw_gid,args.provision_sha256,args.public_config_sha256,args.authority_env_sha256,public_uid=public.pw_uid)
        elif args.execute:
            pins={Path('/etc/freediving/public.env'):args.public_env_sha256,
                  Path('/var/lib/freediving-owner-evidence/active.env'):args.owner_env_sha256}
            if any(p is None for p in pins.values()):raise ValueError('Exact environment pins required')
            provision(layout,owner.pw_uid,owner.pw_gid,public.pw_gid,pins)
            result=verify(layout,owner.pw_uid,owner.pw_gid,public.pw_gid,public_uid=public.pw_uid)
        else:result={'executed':False,'authority_seeded':False,'public_config':str(layout.public/'config.json'),'owner_config':str(layout.private/'config.json')}
        print(json.dumps(result,sort_keys=True));return 0
    except Exception:
        print('Sporting capability refused; preserve files and inspect current host checkpoint',file=sys.stderr);return 1

if __name__=='__main__':sys.exit(main())
