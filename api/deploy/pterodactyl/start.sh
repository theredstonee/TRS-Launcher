#!/bin/bash
# TRS API auf Pterodactyl: .env laden, Cloudflare-Tunnel im selben Container
# starten, API nur lokal (127.0.0.1) – von außen erreichbar ausschließlich über den Tunnel.
set -euo pipefail
cd /home/container

if [ -f .env ]; then set -a; . ./.env; set +a; fi
export NODE_ENV=production
export DATA_DIR="${DATA_DIR:-/home/container/data}"
export HOST=127.0.0.1 NITRO_HOST=127.0.0.1
export PORT="${SERVER_PORT:-3000}" NITRO_PORT="${SERVER_PORT:-3000}"
mkdir -p "$DATA_DIR"

# cloudflared in fester Version, Prüfsumme wird kontrolliert. HTTP/2 statt QUIC,
# weil ausgehendes UDP (Port 7844) aus dem Container blockiert ist.
CF_VERSION=2026.9.1
CF_SHA256=03f1f25d1cc93b9ad6c60569d44060bc4f17ed97075760ed8cfca4b12dcd68cc
if [ ! -x ./cloudflared ] || [ "$(cat .cloudflared-version 2>/dev/null)" != "$CF_VERSION" ]; then
  echo "[start] lade cloudflared ${CF_VERSION} …"
  curl -fsSL -o cloudflared.tmp "https://github.com/cloudflare/cloudflared/releases/download/${CF_VERSION}/cloudflared-linux-amd64"
  echo "${CF_SHA256}  cloudflared.tmp" | sha256sum -c --quiet -
  mv cloudflared.tmp cloudflared
  chmod +x cloudflared
  echo "$CF_VERSION" > .cloudflared-version
fi

if [ -n "${TUNNEL_TOKEN:-}" ]; then
  echo "[start] Cloudflare-Tunnel startet – Ziel im Dashboard: http://127.0.0.1:${PORT}"
  TUNNEL_TOKEN="$TUNNEL_TOKEN" ./cloudflared tunnel --no-autoupdate --protocol http2 run &
else
  echo "[start] WARNUNG: kein TUNNEL_TOKEN in .env – die API läuft nur lokal im Container."
fi
# Der Tunnel-Token gehört nur cloudflared.
unset TUNNEL_TOKEN

exec node .output/server/index.mjs
