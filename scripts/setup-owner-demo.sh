#!/usr/bin/env bash
# Creates an isolated synthetic demo. Refuses an existing cluster or directory.
set -euo pipefail
umask 077
cd "$(dirname "$0")/.."
root=${1:-data/owner-demo}
port=${2:-55485}
case "$port" in *[!0-9]*|'') echo 'Numeric PostgreSQL port required' >&2; exit 2;; esac
if [[ -e "$root" ]]; then echo 'Choose a new private demo directory; existing data is never reset' >&2; exit 1; fi
mkdir -p "$root"
root=$(cd "$root" && pwd -P)
scripts/local-postgres.sh start "$root/postgres17" "$port"
psql -h 127.0.0.1 -p "$port" -d postgres -v ON_ERROR_STOP=1 \
  -c 'CREATE ROLE observations_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;' \
  -c 'CREATE ROLE reviews_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;' \
  -c 'CREATE ROLE reviews_public LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;' \
  -c 'CREATE ROLE corrections_submit LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;' \
  -c 'CREATE DATABASE owner_demo;'
export FREEDIVING_DEMO_ADMIN_URL="jdbc:postgresql://127.0.0.1:$port/owner_demo?user=$(id -un)"
export FREEDIVING_DEMO_INGEST_URL="jdbc:postgresql://127.0.0.1:$port/owner_demo?user=observations_app"
clojure -M:owner-demo "$root/fixtures"
echo "Synthetic database ready on 127.0.0.1:$port. Stop with scripts/local-postgres.sh stop '$root/postgres17'."
