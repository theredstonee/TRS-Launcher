#!/usr/bin/env bash
set -Eeuo pipefail

readonly PORT=3310
readonly UFW_COMMENT='TRS API ClamAV'

fail() {
  printf 'Fehler: %s\n' "$*" >&2
  exit 1
}

[[ ${EUID:-$(id -u)} -eq 0 ]] || fail 'Bitte als root starten, zum Beispiel: sudo bash install-clamav.sh'
[[ -r /etc/os-release ]] || fail 'Ubuntu wurde nicht erkannt.'
. /etc/os-release
[[ ${ID:-} == ubuntu ]] || fail "Ubuntu erwartet, gefunden: ${PRETTY_NAME:-unbekannt}"
command -v docker >/dev/null || fail 'Docker fehlt auf diesem Host.'
command -v ufw >/dev/null || fail 'UFW fehlt. Das Skript offnet clamd nicht ohne aktive Firewall-Regeln.'
ufw status | grep -q '^Status: active$' || fail 'UFW ist nicht aktiv. Bitte zuerst eine sichere Firewall einrichten; dieses Skript aktiviert sie absichtlich nicht.'

mapfile -t containers < <(docker ps --format '{{.ID}} {{.Names}}')
((${#containers[@]})) || fail 'Es lauft kein Docker-Container.'
printf 'Laufende Container:\n'
printf '  %s\n' "${containers[@]}"
read -r -p 'API-Container-ID oder -Name: ' container
[[ -n $container ]] || fail 'Kein API-Container angegeben.'
docker inspect "$container" >/dev/null 2>&1 || fail "Container nicht gefunden: $container"

mapfile -t networks < <(docker inspect --format '{{range $name, $net := .NetworkSettings.Networks}}{{printf "%s %s\n" $name $net.IPAddress}}{{end}}' "$container")
((${#networks[@]})) || fail 'Der API-Container hat keine Docker-Netzwerkadresse.'
printf 'Netzwerke des API-Containers:\n'
printf '  %s\n' "${networks[@]}"
read -r -p 'Netzwerkname (normalerweise pterodactyl_nw): ' network
[[ -n $network ]] || fail 'Kein Docker-Netzwerk angegeben.'
api_ip=''
for entry in "${networks[@]}"; do
  read -r name address <<<"$entry"
  if [[ $name == "$network" ]]; then api_ip=$address; break; fi
done
[[ -n $api_ip && $api_ip != '<no value>' ]] || fail "Der Container ist nicht mit '$network' verbunden."

network_config=$(docker network inspect --format '{{range .IPAM.Config}}{{printf "%s %s\n" .Subnet .Gateway}}{{end}}' "$network" 2>/dev/null) || fail "Docker-Netzwerk nicht gefunden: $network"
read -r subnet gateway <<<"$(printf '%s\n' "$network_config" | head -n 1)"
[[ -n $subnet && -n $gateway && $gateway != '<no value>' ]] || fail 'IPv4-Subnetz oder Gateway des Docker-Netzwerks fehlt.'
ip -o -4 addr show | grep -Fq "$gateway/" || fail "Gateway $gateway ist keine IPv4-Adresse dieses Hosts."

printf '\nClamAV lauscht nur auf %s:%s. UFW erlaubt ausschliesslich API-Container %s (%s).\n' "$gateway" "$PORT" "$container" "$api_ip"
printf 'Wenn der Container nach einem Neustart eine andere IP erhaelt, dieses Skript erneut ausfuehren.\n'
read -r -p 'Mit Installation und dieser Firewall-Regel fortfahren? [j/N] ' confirm
[[ $confirm == [jJyY] ]] || { printf 'Abgebrochen; es wurden keine Pakete installiert.\n'; exit 0; }

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y clamav clamav-daemon clamav-freshclam

conf=/etc/clamav/clamd.conf
[[ -f $conf ]] || fail "ClamAV-Konfiguration fehlt: $conf"
backup="${conf}.backup.$(date -u +%Y%m%dT%H%M%SZ)"
cp -a "$conf" "$backup"
tmp=$(mktemp)
awk '
  /^[[:space:]]*#/ { print; next }
  /^[[:space:]]*(Example|TCPSocket|TCPAddr|StreamMaxLength)([[:space:]]|$)/ { next }
  { print }
' "$conf" >"$tmp"
{
  printf '\n# TRS API: Docker-bridge only; UFW restricts the API container IP.\n'
  printf 'TCPSocket %s\n' "$PORT"
  printf 'TCPAddr %s\n' "$gateway"
  printf 'StreamMaxLength 40M\n'
} >>"$tmp"
install -o root -g root -m 0644 "$tmp" "$conf"
rm -f "$tmp"

systemctl stop clamav-freshclam.service 2>/dev/null || true
freshclam
systemctl enable --now clamav-freshclam.service
systemctl enable --now clamav-daemon.service
ufw allow proto tcp from "$api_ip" to "$gateway" port "$PORT" comment "$UFW_COMMENT"

if ! docker exec "$container" node -e '
  const net = require("node:net")
  const [host, rawPort] = process.argv.slice(1)
  const socket = net.createConnection({ host, port: Number(rawPort) })
  socket.setTimeout(4000, () => { console.error("timeout"); process.exit(1) })
  socket.once("connect", () => socket.write(Buffer.from([0x7a, 0x50, 0x49, 0x4e, 0x47, 0x00])))
  socket.once("data", data => { if (data.toString().startsWith("PONG")) process.exit(0); console.error("unexpected clamd reply"); process.exit(1) })
  socket.once("error", error => { console.error(error.message); process.exit(1) })
' "$gateway" "$PORT"; then
  fail "API-Container erreicht clamd nicht auf $gateway:$PORT. Pruefe systemctl status clamav-daemon und die UFW-Regel."
fi

printf '\nClamAV laeuft. Verbindung aus dem Pterodactyl-Container: %s:%s\n' "$gateway" "$PORT"
printf 'Nur diesen Container freigeben; den ClamAV-Port niemals oeffentlich publizieren.\n'
read -r -p 'Pfad zur API-.env auf dem Host (leer = nur Werte anzeigen): ' env_file
if [[ -n $env_file ]]; then
  [[ -f $env_file ]] || fail "Datei nicht gefunden: $env_file"
  grep -q '^TUNNEL_TOKEN=' "$env_file" || fail 'Die Datei enthaelt kein TUNNEL_TOKEN und sieht nicht wie die API-.env aus.'
  env_backup="${env_file}.backup.$(date -u +%Y%m%dT%H%M%SZ)"
  cp -a "$env_file" "$env_backup"
  for key in CLAMAV_HOST CLAMAV_PORT; do
    value=$gateway
    [[ $key == CLAMAV_PORT ]] && value=$PORT
    if grep -q "^${key}=" "$env_file"; then
      sed -i "s|^${key}=.*$|${key}=${value}|" "$env_file"
    else
      printf '\n%s=%s\n' "$key" "$value" >>"$env_file"
    fi
  done
  printf 'API-.env aktualisiert; Sicherung: %s\n' "$env_backup"
  printf 'Den Pterodactyl-Server jetzt neu starten.\n'
else
  printf 'In die API-.env eintragen und den Pterodactyl-Server neu starten:\nCLAMAV_HOST=%s\nCLAMAV_PORT=%s\n' "$gateway" "$PORT"
fi
