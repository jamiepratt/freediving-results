#!/usr/bin/env bash
# Run as root with an extracted release path. Migration failure prevents activation.
set -euo pipefail
umask 077
release=$(realpath "$1")
[[ "$release" == /opt/freediving/releases/* ]]
[[ -f "$release/REVISION" ]]
id freediving >/dev/null 2>&1 || useradd --system --home /nonexistent --shell /usr/sbin/nologin freediving
python3 "$release/deploy/bootstrap.py"
cd "$release"
python3 "$release/deploy/prepare_database.py"
chown -R root:root "$release"
chmod -R go-w "$release"
previous=$(readlink -f /opt/freediving/current || true)
ln -sfn "$release" /opt/freediving/current
install -m 0644 deploy/freediving-public.service /etc/systemd/system/
install -m 0644 deploy/freediving-tunnel.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable freediving-public.service
systemctl restart freediving-public.service

if ! python3 "$release/deploy/health.py"; then
  if [[ -n "$previous" && "$previous" != "$release" ]]; then
    ln -sfn "$previous" /opt/freediving/current
    systemctl restart freediving-public.service
  fi
  echo 'Health check failed; previous app restored when available. Schema remains forward-migrated.' >&2
  exit 1
fi
