"""Run the synthetic owner CLI test in a disposable loopback PostgreSQL cluster."""

import os
import getpass
from pathlib import Path
import socket
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def run(*argv, env=None):
    subprocess.run(argv, cwd=ROOT, env=env, check=True, timeout=120)


def main():
    (ROOT / 'data').mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='postgres-owner-cli.', dir=ROOT / 'data') as directory:
        cluster = Path(directory) / 'cluster'
        with socket.socket() as address:
            address.bind(('127.0.0.1', 0))
            port = address.getsockname()[1]
        run('scripts/local-postgres.sh', 'start', str(cluster), str(port))
        try:
            psql = ['psql', '-h', '127.0.0.1', '-p', str(port), '-d', 'postgres',
                    '-v', 'ON_ERROR_STOP=1']
            for name in ('observations_app', 'reviews_owner', 'reviews_public',
                         'corrections_submit'):
                run(*psql, '-c', f'CREATE ROLE {name} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;')
            run(*psql, '-c', 'CREATE DATABASE observations_test;')
            base = f'jdbc:postgresql://127.0.0.1:{port}/observations_test?user='
            env = os.environ | {
                'FREEDIVING_TEST_ADMIN_URL': base + getpass.getuser(),
                'FREEDIVING_TEST_URL': base + 'observations_app',
                'FREEDIVING_TEST_REVIEW_URL': base + 'reviews_owner',
            }
            bundled_node_modules = (Path.home() / '.cache/codex-runtimes/codex-primary-runtime'
                                    / 'dependencies/node/node_modules')
            if 'NODE_PATH' not in env and bundled_node_modules.is_dir():
                env['NODE_PATH'] = str(bundled_node_modules)
            run('clojure', '-Sdeps', '{:paths ["src" "resources" "test"]}', '-M', '-m',
                'freediving.owner-decision-cli-path-test', env=env)
        finally:
            run('scripts/local-postgres.sh', 'stop', str(cluster), str(port))


if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError as error:
        sys.exit(error.returncode)
