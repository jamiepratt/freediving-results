#!/usr/bin/env bash
# Run as root with an extracted release path. Migration failure prevents activation.
set -euo pipefail
umask 077
release=$(realpath "$1")
[[ "$release" == /opt/freediving/releases/* ]]
[[ -f "$release/REVISION" ]]
id freediving >/dev/null 2>&1 || useradd --system --home /nonexistent --shell /usr/sbin/nologin freediving
python3 "$release/deploy/bootstrap.py"
install -d -m 0700 /var/backups/freediving
runuser -u postgres -- pg_dump -Fc freediving > "/var/backups/freediving/pre-deploy-$(date -u +%Y%m%dT%H%M%SZ).dump"
set -a
. /etc/freediving/migration.env
set +a
cd "$release"
java -Xmx256m -cp 'src:resources:lib/*' clojure.main -m freediving.deployment
unset FREEDIVING_MIGRATION_URL
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
