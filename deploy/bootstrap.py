#!/usr/bin/env python3
"""Run once as root. Credentials stay on the VPS. Refuses an existing unmarked DB."""
import pathlib, secrets, subprocess, os
config = pathlib.Path('/etc/freediving')
config.mkdir(mode=0o700, exist_ok=True)
if (config / 'public.env').exists():
    print('Existing deployment credentials retained')
    raise SystemExit(0)
def sql(statement):
    return subprocess.check_output(['runuser','-u','postgres','--','psql','-XAt','-v','ON_ERROR_STOP=1','-d','postgres'], input=statement, text=True)
assert not sql("SELECT 1 FROM pg_database WHERE datname='freediving';").strip(), 'Existing database requires operator review'
assert not sql("SELECT 1 FROM pg_roles WHERE rolname IN ('freediving_migrator','observations_app','reviews_owner','reviews_public','corrections_submit');").strip(), 'Existing roles require operator review'
passwords = {role: secrets.token_hex(32) for role in ['freediving_migrator','reviews_public','corrections_submit']}
for role in ['freediving_migrator','observations_app','reviews_owner','reviews_public','corrections_submit']:
    auth = "LOGIN PASSWORD '"+passwords[role]+"'" if role in passwords else 'NOLOGIN'
    sql(f'CREATE ROLE {role} {auth} NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;')
sql('CREATE DATABASE freediving OWNER freediving_migrator;')
sql('REVOKE ALL ON DATABASE freediving FROM PUBLIC; GRANT CONNECT ON DATABASE freediving TO freediving_migrator,reviews_public,corrections_submit;')
def url(role):
    return f'jdbc:postgresql://127.0.0.1:5432/freediving?user={role}&password={passwords[role]}&connectTimeout=5&socketTimeout=15'
def write(name, text):
    fd=os.open(config/name,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(fd,'w') as f: f.write(''.join(k+"='"+v+"'\n" for k,v in (line.split('=',1) for line in text.splitlines())))
write('migration.env', 'FREEDIVING_MIGRATION_URL='+url('freediving_migrator')+'\n')
write('public.env', 'FREEDIVING_PUBLIC_DATABASE_URL='+url('reviews_public')+'\nFREEDIVING_SUBMIT_DATABASE_URL='+url('corrections_submit')+'\nFREEDIVING_PUBLIC_ORIGIN=https://poc.alphacompose.com\nFREEDIVING_GATEWAY_SECRET='+secrets.token_hex(32)+'\n')
print('Restricted database roles created; credentials stored root-only')
