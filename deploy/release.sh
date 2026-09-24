#!/usr/bin/env bash
# Normal operator workflow. Requires existing SSH and Cloudflare profiles.
set -euo pipefail
cd "$(dirname "$0")/.."
[[ -z "$(git status --porcelain)" ]] || { echo 'Commit deployment changes first' >&2; exit 1; }
revision=$(git rev-parse HEAD)
release="/opt/freediving/releases/$revision"
python3 deploy/build.py
scp data/deploy/freediving.tar.gz bridge-vps:/tmp/freediving-deploy.tar.gz
ssh bridge-vps "set -e; sudo -n mkdir -p '$release'; sudo -n tar xzf /tmp/freediving-deploy.tar.gz -C '$release'; sudo -n bash '$release/deploy/activate.sh' '$release'"
python3 deploy/cloudflare.py
python3 deploy/publish-edge.py
python3 deploy/verify.py
