#!/usr/bin/env bash
# Run as root on Ubuntu 24.04. Does not open any ingress ports.
set -euo pipefail
. /etc/os-release
[[ "$ID" == ubuntu && "$VERSION_ID" == 24.04 ]]
install -d -m 0755 /usr/share/keyrings
curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc -o /usr/share/keyrings/freediving-postgresql.asc
printf '%s\n' 'deb [signed-by=/usr/share/keyrings/freediving-postgresql.asc] https://apt.postgresql.org/pub/repos/apt noble-pgdg main' > /etc/apt/sources.list.d/freediving-postgresql.list
curl -fsSL https://pkg.cloudflare.com/cloudflare-main.gpg -o /usr/share/keyrings/cloudflare-main.gpg
printf '%s\n' 'deb [signed-by=/usr/share/keyrings/cloudflare-main.gpg] https://pkg.cloudflare.com/cloudflared any main' > /etc/apt/sources.list.d/cloudflared.list
apt-get update -qq
DEBIAN_FRONTEND=noninteractive apt-get install -y postgresql-17 cloudflared
