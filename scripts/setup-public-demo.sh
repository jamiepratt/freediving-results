#!/usr/bin/env bash
# Own isolated synthetic database. Never resets an existing directory or cluster.
set -euo pipefail
umask 077
export PATH="/Applications/Postgres.app/Contents/Versions/17/bin:$PATH"
cd "$(dirname "$0")/.."
root=${1:-data/public-demo}
port=${2:-55487}
case "$port" in *[!0-9]*|'') echo 'Numeric PostgreSQL port required' >&2; exit 2;; esac
if (( 10#$port < 1 || 10#$port > 65535 )); then echo 'Port must be 1-65535' >&2; exit 2; fi
if [[ -e "$root" ]]; then echo 'Choose a new private demo directory; existing data is never reset' >&2; exit 1; fi
mkdir -p "$root"
root=$(cd "$root" && pwd -P)
started=false
cleanup_failure() {
  if [[ "$started" == true ]]; then scripts/local-postgres.sh stop "$root/postgres17" >/dev/null; fi
}
trap cleanup_failure ERR
scripts/local-postgres.sh start "$root/postgres17" "$port"
started=true
psql -h 127.0.0.1 -p "$port" -d postgres -v ON_ERROR_STOP=1 \
  -c 'CREATE ROLE observations_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;' \
  -c 'CREATE ROLE reviews_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;' \
  -c 'CREATE ROLE reviews_public LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;' \
  -c 'CREATE DATABASE public_demo;'
export FREEDIVING_DEMO_ADMIN_URL="jdbc:postgresql://127.0.0.1:$port/public_demo?user=$(id -un)"
export FREEDIVING_DEMO_INGEST_URL="jdbc:postgresql://127.0.0.1:$port/public_demo?user=observations_app"
export FREEDIVING_DEMO_REVIEW_URL="jdbc:postgresql://127.0.0.1:$port/public_demo?user=reviews_owner"
clojure -M:public-demo "$root/fixtures" > "$root/private-receipt.edn"
echo "Synthetic database ready on 127.0.0.1:$port/public_demo. Private receipt: $root/private-receipt.edn"
echo "Public read-only URL: jdbc:postgresql://127.0.0.1:$port/public_demo?user=reviews_public"
echo "Stop: PATH=/Applications/Postgres.app/Contents/Versions/17/bin:\$PATH scripts/local-postgres.sh stop '$root/postgres17'"
