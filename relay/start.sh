#!/bin/bash
# TRS Relay auf Pterodactyl: .env sicher laden (nur KEY=VALUE, nichts wird ausgeführt),
# dann Node direkt auf den TypeScript-Quellen starten (Type Stripping, keine Abhängigkeiten).
# Bewusste Ausnahme zur 127.0.0.1-Regel: das Relay MUSS öffentlich erreichbar sein (0.0.0.0),
# Zugang gibt es nur mit einem gültigen, kurzlebigen Token der TRS API.
set -euo pipefail
cd "$(dirname "$0")"

if [ -f .env ]; then
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"
    case "$line" in ''|'#'*) continue ;; esac
    if [[ "$line" =~ ^([A-Z_][A-Z0-9_]*)=(.*)$ ]]; then
      key="${BASH_REMATCH[1]}"
      value="${BASH_REMATCH[2]}"
      # Umschließende Anführungszeichen entfernen (keine Auswertung von $, `, \).
      if [[ "$value" =~ ^\"(.*)\"$ ]] || [[ "$value" =~ ^\'(.*)\'$ ]]; then value="${BASH_REMATCH[1]}"; fi
      export "$key=$value"
    else
      echo "[start] WARNUNG: ungültige Zeile in .env übersprungen"
    fi
  done < .env
fi

if [ -z "${RELAY_SECRET:-}" ]; then
  echo "[start] FEHLER: RELAY_SECRET fehlt in .env (gleicher Wert wie in der .env der TRS API)."
  exit 1
fi

export NODE_ENV=production
export BIND_HOST="${BIND_HOST:-0.0.0.0}"
# Primärer Port des Servers = TCP; UDP auf dem zweiten zugewiesenen Port.
export TCP_PORT="${TCP_PORT:-${SERVER_PORT:-25503}}"
export UDP_PORT="${UDP_PORT:-25504}"

echo "[start] TRS Relay – TCP ${TCP_PORT}, UDP ${UDP_PORT}, Node $(node --version)"
exec node --disable-warning=ExperimentalWarning src/main.ts
