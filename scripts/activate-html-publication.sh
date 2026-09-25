#!/usr/bin/env bash
# Separate operator checkpoint. Does not deploy, refresh or approve any record.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ $# -ne 2 || "$1" != "hide-existing-public-results" || -z "$2" ]]; then
  echo 'Usage: activate-html-publication.sh hide-existing-public-results "owner reason"' >&2
  echo 'Policy 2 hides existing policy 1 records until explicit revalidation and refresh.' >&2
  exit 1
fi
: "${FREEDIVING_MIGRATION_URL:?Owner migration URL required}"
export FREEDIVING_DATABASE_URL="$FREEDIVING_MIGRATION_URL"
clojure -M:public-results activate-html-policy "$@"
