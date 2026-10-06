#!/usr/bin/env bash
# Run as root with an extracted release path. Migration failure prevents activation.
set -euo pipefail
umask 077
export PYTHONDONTWRITEBYTECODE=1
release=$(realpath "$1")
[[ "$release" == /opt/freediving/releases/* ]]
[[ -f "$release/REVISION" ]]
existing_deployment=0
if [[ -f /etc/freediving/public.env ]]; then
  existing_deployment=1
fi
if [[ -e /etc/freediving/sporting-authority.env || -L /etc/freediving/sporting-authority.env ]]; then
  python3 "$release/deploy/provision_sporting_authority.py" verify
fi
rollback_checkpoint=''
if [[ "$existing_deployment" == 1 ]]; then
  install -d -m 0700 /var/backups/freediving
  rollback_checkpoint="/var/backups/freediving/public-app-$(basename "$release")-$(date -u +%Y%m%dT%H%M%S)-$$.json"
  python3 "$release/deploy/public_app_rollback.py" capture --candidate "$release" --checkpoint "$rollback_checkpoint"
fi
id freediving >/dev/null 2>&1 || useradd --system --home /nonexistent --shell /usr/sbin/nologin freediving
python3 "$release/deploy/bootstrap.py"
cd "$release"
if [[ "$existing_deployment" == 1 ]]; then
  python3 "$release/deploy/migrate_only.py" --release "$release" --expected-revision "$(cat "$release/REVISION")"
else
  python3 "$release/deploy/prepare_database.py"
fi
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
    python3 "$release/deploy/public_app_rollback.py" rollback --candidate "$release" --checkpoint "$rollback_checkpoint"
    systemctl daemon-reload
    systemctl restart freediving-public.service
    python3 "$previous/deploy/health.py"
  fi
  echo 'Health check failed; guarded previous app restored when available. Schema remains forward-migrated.' >&2
  exit 1
fi
