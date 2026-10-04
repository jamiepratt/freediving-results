#!/usr/bin/env bash
# Stage a merged release and provision only the isolated private canonical DB.
set -euo pipefail
[[ $# == 1 && "$1" =~ ^[0-9a-f]{64}$ ]] || { echo 'Usage: provision_canonical.sh SNAPSHOT_SHA256' >&2; exit 2; }
snapshot=$1
cd "$(dirname "$0")/.."
[[ -z "$(git status --porcelain --untracked-files=all)" ]] || { echo 'Commit deployment changes first' >&2; exit 1; }
revision=$(git rev-parse HEAD)
remote_revision=$(git ls-remote origin refs/heads/main | cut -f 1)
[[ "$revision" =~ ^[0-9a-f]{40}$ && "$revision" == "$remote_revision" ]] || { echo 'Checkout must equal merged remote main' >&2; exit 1; }
python3 deploy/build.py
artifact=data/deploy/freediving.tar.gz
digest=$(shasum -a 256 "$artifact" | cut -d ' ' -f 1)
[[ "$digest" =~ ^[0-9a-f]{64}$ ]] || exit 1
upload="/tmp/freediving-canonical-$revision-$$.tar.gz"
scp "$artifact" "bridge-vps:$upload"
ssh bridge-vps sudo -n bash -s -- "$revision" "$digest" "$upload" "$snapshot" <<'REMOTE'
set -euo pipefail
umask 077
revision=$1
digest=$2
upload=$3
snapshot=$4
[[ "$revision" =~ ^[0-9a-f]{40}$ && "$digest" =~ ^[0-9a-f]{64}$ && "$snapshot" =~ ^[0-9a-f]{64}$ ]]
[[ "$upload" == /tmp/freediving-canonical-"$revision"-*.tar.gz ]]
install -d -m 0700 /var/backups/freediving
[[ ! -L /var/backups/freediving && "$(stat -c %u /var/backups/freediving)" == 0 ]]
private_archive=$(mktemp /var/backups/freediving/.canonical-stage.XXXXXXXX.tar.gz)
trap 'rm -f "$private_archive" "$upload"' EXIT
install -m 0600 "$upload" "$private_archive"
[[ "$(sha256sum "$private_archive" | cut -d ' ' -f 1)" == "$digest" ]] || { echo 'Stage checksum mismatch' >&2; exit 1; }
release="/opt/freediving/releases/$revision"
if [[ ! -e "$release" ]]; then
  install -d -m 0700 "$release"
  tar xzf "$private_archive" -C "$release"
  [[ "$(cat "$release/REVISION")" == "$revision" ]]
  printf '%s\n' "$digest" > "$release/CANONICAL_STAGE_SHA256"
  chown -R root:root "$release"
  chmod -R go-w "$release"
else
  [[ -d "$release" && ! -L "$release" ]]
  [[ "$(cat "$release/REVISION")" == "$revision" ]]
  [[ "$(cat "$release/CANONICAL_STAGE_SHA256")" == "$digest" ]]
fi
python3 "$release/deploy/provision_canonical.py" \
  --release "$release" --expected-revision "$revision" \
  --database freediving_canonical \
  --expected-public-database freediving_release_20260924_11 \
  --snapshot-sha256 "$snapshot"
REMOTE
