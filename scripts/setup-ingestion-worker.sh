#!/usr/bin/env bash
# Idempotent, private #16 worker setup on the existing OVH PostgreSQL server.
# Never reads the public application's configuration or database.
set -euo pipefail
set +x
umask 077

account=freediving-ingest
database=freediving_ingest
owner_role=freediving_ingest_owner
app_role=freediving_ingest_app
state=/var/lib/freediving-ingest
root=/srv/freediving-ingest
socket=/var/run/postgresql

describe() {
  printf '%s\n' \
    "account=$account" "database=$database" "owner_role=$owner_role" "app_role=$app_role" \
    "state=$state" "archive=$root/archive" "bundle=$root/bundles" \
    "lease=$state/acquisition.sqlite3"
}

case "${1:-}" in
  --describe) [[ $# == 1 ]] || exit 2; describe; exit 0 ;;
  --apply) [[ $# == 1 ]] || exit 2 ;;
  *) echo 'Usage: setup-ingestion-worker.sh --describe|--apply' >&2; exit 2 ;;
esac

[[ $(id -u) == 0 ]] || { echo 'Run --apply as root' >&2; exit 1; }
for command in getent useradd install sudo psql createdb openssl stat grep; do
  command -v "$command" >/dev/null || { echo "Missing command: $command" >&2; exit 1; }
done
[[ -d $socket ]] || { echo 'PostgreSQL Unix socket directory missing' >&2; exit 1; }

if getent passwd "$account" >/dev/null; then
  IFS=: read -r _ _ _ _ _ home shell < <(getent passwd "$account")
  [[ $home == "$state" && $shell == /usr/sbin/nologin ]] || {
    echo 'Existing worker account has unexpected home or shell' >&2; exit 1;
  }
else
  useradd --system --user-group --no-create-home --home-dir "$state" --shell /usr/sbin/nologin "$account"
fi

for path in "$state" "$root" "$root/archive" "$root/bundles" "$root/runs"; do
  [[ ! -L $path ]] || { echo "Refusing symlink: $path" >&2; exit 1; }
  if [[ -e $path ]]; then
    [[ -d $path && $(stat -c %U "$path") == "$account" ]] || {
      echo "Unexpected owner or type: $path" >&2; exit 1;
    }
  fi
  install -d -m 0700 -o "$account" -g "$account" "$path"
  chmod 0700 "$path"
done

admin_psql() {
  sudo -n -u postgres env -i PATH=/usr/bin:/bin HOME=/var/lib/postgresql \
    psql -h "$socket" -X -A -t -v ON_ERROR_STOP=1 -d postgres "$@"
}

for role in "$owner_role" "$app_role"; do
  role_state=$(admin_psql -c "SELECT rolcanlogin AND NOT rolinherit AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls FROM pg_roles WHERE rolname = '$role'")
  case $role_state in
    '') admin_psql -c "CREATE ROLE $role LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS" >/dev/null ;;
    t) ;;
    *) echo "Existing worker role has unexpected privileges: $role" >&2; exit 1 ;;
  esac
done
app_memberships=$(admin_psql -c "SELECT count(*) FROM pg_auth_members WHERE member = (SELECT oid FROM pg_roles WHERE rolname = '$app_role')")
[[ $app_memberships == 0 ]] || { echo 'Worker import role has memberships' >&2; exit 1; }

ensure_password() {
  local role=$1 file=$2 value
  if [[ -e $file ]]; then
    [[ ! -L $file && $(stat -c %U:%a "$file") == "$account:600" ]] || {
      echo 'Unexpected worker password file owner or mode' >&2; exit 1;
    }
  else
    openssl rand -hex 32 > "$file"
    chown "$account:$account" "$file"
    chmod 0600 "$file"
  fi
  value=$(<"$file")
  [[ $value =~ ^[0-9a-f]{64}$ ]] || { echo 'Invalid worker password file' >&2; exit 1; }
  printf "ALTER ROLE %s PASSWORD '%s';\n" "$role" "$value" | admin_psql >/dev/null
}
ensure_password "$owner_role" "$state/db-owner-password"
ensure_password "$app_role" "$state/db-app-password"

database_owner=$(admin_psql -c "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = '$database'")
case $database_owner in
  '') sudo -n -u postgres env -i PATH=/usr/bin:/bin HOME=/var/lib/postgresql \
       createdb -h "$socket" --owner="$owner_role" --encoding=UTF8 --template=template0 "$database" ;;
  "$owner_role") ;;
  *) echo 'Existing worker database has unexpected owner' >&2; exit 1 ;;
esac
admin_psql -c "REVOKE CONNECT, TEMPORARY ON DATABASE $database FROM PUBLIC" >/dev/null
admin_psql -c "GRANT CONNECT ON DATABASE $database TO $app_role" >/dev/null

port=$(admin_psql -c 'SHOW port')
[[ $port =~ ^[0-9]+$ ]] || { echo 'Invalid PostgreSQL port' >&2; exit 1; }
for role in "$owner_role" "$app_role"; do
  if [[ $role == "$owner_role" ]]; then
    password=$(<"$state/db-owner-password")
    password_file=$state/db-owner-password
    env_file=$state/database-owner.env
  else
    password=$(<"$state/db-app-password")
    password_file=$state/db-app-password
    env_file=$state/database.env
  fi
  sudo -n -u "$account" bash -c '
    PGPASSWORD=$(<"$1"); export PGPASSWORD
    exec psql -h 127.0.0.1 -p "$4" -U "$2" -d "$3" -X -A -t -v ON_ERROR_STOP=1 -c "SELECT current_database(), current_user"
  ' _ "$password_file" "$role" "$database" "$port" | grep -qx "$database|$role" || {
      echo "Worker database login failed: $role" >&2; exit 1;
    }
  printf "export FREEDIVING_DATABASE_URL='jdbc:postgresql://127.0.0.1:%s/%s?user=%s&password=%s'\n" \
    "$port" "$database" "$role" "$password" > "$env_file"
  chown "$account:$account" "$env_file"
  chmod 0600 "$env_file"
done
echo "Worker ready: $account; database $database; private paths $state and $root"
