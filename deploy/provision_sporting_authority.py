#!/usr/bin/env python3
"""Host-only independent sporting signer/request capability. Never seeds authority.

Prepare under exact existing environment pins, then install the matching code using
normal public/private activation. Nothing changes source decisions or database ACLs.
"""
import argparse
import base64
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
    public: Path=Path('/etc/freediving/sporting-authority')
    units: Path=Path('/etc/systemd/system')


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
            layout.public.parent/'sporting-authority.env',layout.public.parent/'sporting-owner.env',
            layout.units/'freediving-owner-evidence.service.d/sporting-authority.conf')


def public_der(signing):
    return subprocess.run(['openssl','pkey','-pubout','-outform','DER'],input=signing,
                          stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,check=True,timeout=10).stdout


def verify(layout=Layout(),owner_uid=None,owner_gid=None,public_gid=None):
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
    if record.get('schema')!='sporting-capability-provision/v1' or expected!=record['files']:
        raise ValueError('Capability changed')
    for path in files(layout):
        gid=owner_gid if path.parent==layout.private else public_gid if path.parent==layout.public else root_gid
        mode=0o600 if path.parent==layout.public.parent else 0o644 if path.parent.parent==layout.units else 0o640
        info=path.stat()
        if (info.st_uid,info.st_gid,info.st_mode&0o777)!=(root_uid,gid,mode):raise ValueError('Capability file boundary changed')
    private=json.loads((layout.private/'config.json').read_text())
    public=json.loads((layout.public/'config.json').read_text())
    der=public_der((layout.private/'signing.pem').read_bytes())
    if public['public_key_der']!=base64.b64encode(der).decode() or public['key_id']!=hashlib.sha256(der).hexdigest():
        raise ValueError('Signer differs from pinned verifier')
    if public['request_secret']!=(layout.private/'request.key').read_text().strip():raise ValueError('Request capability differs')
    if private!={'schema':'sporting-authority-service/v1','ledger_path':str(layout.private/'ledger/authority.sqlite'),
                'signing_key_path':str(layout.private/'signing.pem'),'request_key_path':str(layout.private/'request.key')}:
        raise ValueError('Private authority binding differs')
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
    write(dropin/'sporting-authority.conf',('[Service]\nEnvironmentFile='+str(layout.public.parent/'sporting-owner.env')+'\nReadWritePaths='+str(ledger)+'\n').encode(),root_gid,0o644)
    write(layout.public.parent/'sporting-owner.env',('OWNER_EVIDENCE_SPORTING_CONFIG='+str(layout.private/'config.json')+'\n').encode(),root_gid,0o600)
    # Publish public capability last. Absent or incomplete authority always withholds.
    write(layout.public.parent/'sporting-authority.env',('FREEDIVING_SPORTING_AUTHORITY_CONFIG='+str(layout.public/'config.json')+'\n').encode(),root_gid,0o600)
    record={'schema':'sporting-capability-provision/v1','files':{str(p):digest(p) for p in files(layout)}}
    write(layout.private/'provision.json',(json.dumps(record,sort_keys=True)+'\n').encode(),root_gid,0o600)
    return verify(layout,owner_uid,owner_gid,public_gid)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=('prepare','verify'))
    parser.add_argument('--execute',action='store_true')
    parser.add_argument('--public-env-sha256')
    parser.add_argument('--owner-env-sha256')
    args=parser.parse_args()
    try:
        if os.geteuid()!=0:raise ValueError('Root host checkpoint required')
        owner=pwd.getpwnam('freediving-evidence');public=pwd.getpwnam('freediving')
        layout=Layout()
        if args.action=='verify':result=verify(layout,owner.pw_uid,owner.pw_gid,public.pw_gid)
        elif args.execute:
            pins={Path('/etc/freediving/public.env'):args.public_env_sha256,
                  Path('/var/lib/freediving-owner-evidence/active.env'):args.owner_env_sha256}
            if any(p is None for p in pins.values()):raise ValueError('Exact environment pins required')
            result=provision(layout,owner.pw_uid,owner.pw_gid,public.pw_gid,pins)
        else:result={'executed':False,'authority_seeded':False,'public_config':str(layout.public/'config.json'),'owner_config':str(layout.private/'config.json')}
        print(json.dumps(result,sort_keys=True));return 0
    except Exception:
        print('Sporting capability refused; preserve files and inspect current host checkpoint',file=sys.stderr);return 1

if __name__=='__main__':sys.exit(main())
