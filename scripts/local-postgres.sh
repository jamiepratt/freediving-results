#!/usr/bin/env bash
# Isolated development cluster. No service registration, existing DB reset or public listener.
set -euo pipefail
umask 077
action=${1:-status}
root=${2:-data/local-postgres}
port=${3:-55480}
case "$port" in *[!0-9]*|'') echo 'Port must be numeric' >&2; exit 2;; esac
case "$action" in start|stop|status) ;; *) echo 'Usage: local-postgres.sh start|stop|status [directory] [port]' >&2; exit 2;; esac
if [[ "$action" == start ]]; then mkdir -p "$root"; fi
root=$(cd "$root" && pwd -P)
if [[ "$action" == start ]]; then
  if [[ ! -f "$root/PG_VERSION" ]]; then
    if [[ -n "$(ls -A "$root")" ]]; then echo 'Refusing nonempty non-cluster directory' >&2; exit 1; fi
    initdb -D "$root" --auth-local=trust --auth-host=trust --encoding=UTF8 --locale=C >/dev/null
    cat >> "$root/postgresql.conf" <<EOF
listen_addresses = '127.0.0.1'
port = $port
unix_socket_directories = ''
EOF
    touch "$root/.freediving-local-cluster"
  fi
  if [[ ! -f "$root/.freediving-local-cluster" ]]; then echo 'Refusing to start unmanaged cluster' >&2; exit 1; fi
  if ! pg_ctl -D "$root" status >/dev/null 2>&1; then
    pg_ctl -D "$root" -l "$root/server.log" -o "-h 127.0.0.1 -p $port -k ''" -w start
  fi
elif [[ "$action" == stop ]]; then
  if [[ ! -f "$root/.freediving-local-cluster" ]]; then echo 'Refusing to stop unmanaged cluster' >&2; exit 1; fi
  pg_ctl -D "$root" -m fast -w stop
else
  pg_ctl -D "$root" status
fi
