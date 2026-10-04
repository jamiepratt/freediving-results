#!/usr/bin/env bash
# Operator-only stage and migration. No public activation or Worker publication.
set -euo pipefail
cd "$(dirname "$0")/.."
[[ -z "$(git status --porcelain --untracked-files=all)" ]] || { echo 'Commit deployment changes first' >&2; exit 1; }
revision=$(git rev-parse HEAD)
[[ "$revision" =~ ^[0-9a-f]{40}$ ]] || exit 1
python3 deploy/build.py
artifact=data/deploy/freediving.tar.gz
digest=$(shasum -a 256 "$artifact" | cut -d ' ' -f 1)
[[ "$digest" =~ ^[0-9a-f]{64}$ ]] || exit 1
upload="/tmp/freediving-migration-$revision-$$.tar.gz"
scp "$artifact" "bridge-vps:$upload"
ssh bridge-vps sudo -n bash -s -- "$revision" "$digest" "$upload" <<'REMOTE'
set -euo pipefail
umask 077
revision=$1
digest=$2
upload=$3
[[ "$revision" =~ ^[0-9a-f]{40}$ && "$digest" =~ ^[0-9a-f]{64}$ ]]
[[ "$upload" == /tmp/freediving-migration-"$revision"-*.tar.gz ]]
install -d -m 0700 /var/backups/freediving
[[ ! -L /var/backups/freediving && "$(stat -c %u /var/backups/freediving)" == 0 ]]
private_archive=$(mktemp /var/backups/freediving/.migration-stage.XXXXXXXX.tar.gz)
trap 'rm -f "$private_archive" "$upload"' EXIT
install -m 0600 "$upload" "$private_archive"
[[ "$(sha256sum "$private_archive" | cut -d ' ' -f 1)" == "$digest" ]] || { echo 'Stage checksum mismatch' >&2; exit 1; }
release="/opt/freediving/releases/$revision"
if [[ ! -e "$release" ]]; then
  install -d -m 0700 "$release"
  tar xzf "$private_archive" -C "$release"
  [[ "$(cat "$release/REVISION")" == "$revision" ]]
  printf '%s\n' "$digest" > "$release/MIGRATION_STAGE_SHA256"
  chown -R root:root "$release"
  chmod -R go-w "$release"
else
  [[ -d "$release" && ! -L "$release" ]]
  [[ "$(cat "$release/REVISION")" == "$revision" ]]
  [[ "$(cat "$release/MIGRATION_STAGE_SHA256")" == "$digest" ]]
fi
python3 "$release/deploy/migrate_only.py" --release "$release" --expected-revision "$revision"
REMOTE
