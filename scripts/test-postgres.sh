#!/usr/bin/env bash
# Each invocation owns a new cluster; a trap stops and removes only that directory.
set -euo pipefail
umask 077
cd "$(dirname "$0")/.."
mkdir -p data
test_root=$(mktemp -d "$(pwd -P)/data/postgres-test.XXXXXX")
cleanup() {
  if [[ -f "$test_root/cluster/postmaster.pid" ]]; then
    pg_ctl -D "$test_root/cluster" -m immediate -w stop >/dev/null
  fi
  rm -rf "$test_root"
}
trap cleanup EXIT
test_port=$(python3 - <<'PY'
import socket
with socket.socket() as s:
    s.bind(('127.0.0.1', 0))
    print(s.getsockname()[1])
PY
)
scripts/local-postgres.sh start "$test_root/cluster" "$test_port"
psql -h 127.0.0.1 -p "$test_port" -d postgres -v ON_ERROR_STOP=1 \
  -c 'CREATE ROLE observations_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;' \
  -c 'CREATE DATABASE observations_test;'
export FREEDIVING_TEST_ADMIN_URL="jdbc:postgresql://127.0.0.1:$test_port/observations_test?user=$(id -un)"
export FREEDIVING_TEST_URL="jdbc:postgresql://127.0.0.1:$test_port/observations_test?user=observations_app"
clojure -M:test-postgres
