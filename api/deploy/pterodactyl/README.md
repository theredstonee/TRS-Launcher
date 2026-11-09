# TRS API auf Pterodactyl

So läuft die API auf `panel.theredstonee.de` (Node GER-Schliersee, Server „TRS API“):

- Egg **Website → Nuxt.js v3**, Image `ghcr.io/parkervcp/yolks:nodejs_24`
- Variable `USER_UPLOAD=1`, Startbefehl `bash /home/container/start.sh`
- Im Server-Ordner liegen nur `.output/` (fertig gebaut mit `pnpm build`), `start.sh` und `.env`.
  Keine `package.json` im Wurzelordner – sonst baut das Egg beim Start selbst.
- `start.sh` lädt `.env`, holt `cloudflared` in fester Version (SHA-256 geprüft) und startet den
  Tunnel im selben Container. Die API hört nur auf `127.0.0.1:$SERVER_PORT` und ist von außen
  ausschließlich über den Tunnel erreichbar.
- Der Tunnel nutzt **HTTP/2** (`--protocol http2`), weil ausgehendes UDP (QUIC, Port 7844) aus
  dem Container blockiert ist.

## Cloudflare

- Zero Trust → Networks → Tunnels: Public Hostname `api.theredstonee.de` → Service `HTTP`,
  URL `127.0.0.1:<Server-Port>`. Den Tunnel-Token in `.env` als `TUNNEL_TOKEN=` eintragen.
- SSL/TLS: „Always Use HTTPS“ und HSTS an. DPA (AVV) im Cloudflare-Dashboard bestätigen.

## Update einspielen

1. Vor dem Deploy die proprietären Kosmetik-Dateien hochladen: den Inhalt von
   `E:\ai\trs-private-assets\` nach `<DATA_DIR>/private-assets` kopieren.
   `start.sh` setzt `DATA_DIR` sonst auf `/home/container/data`, also
   `/home/container/data/private-assets`. Ohne diesen Ordner starten die
   öffentlichen Teile normal; Code- und Team-Teile fehlen, bis die Dateien da
   sind. Besitz-Einträge bleiben erhalten. `data/` beim Entpacken nicht überschreiben.
2. `cd api && pnpm build`
3. `.output/` (und bei Bedarf `start.sh`) als Zip hochladen und im Datei-Manager entpacken –
   `.env` und `data/` **nicht** überschreiben.
4. Server neu starten.

## Optionale Schlüssel in `.env`

- `CURSEFORGE_API_KEY=` – für empfohlene Modpacks von CurseForge (API.md §37). Ohne Schlüssel gehen nur
  Modrinth-Links; CurseForge-Links antworten mit `503 curseforge_unavailable`. Nach dem Eintragen den Server neu
  starten. Der Schlüssel gehört nur in `.env`, nie ins Repo.

## ClamAV für Bewerbungsdateien

Der Pterodactyl-Container enthält nur Node.js. ClamAV wird deshalb auf dem Ubuntu-Host installiert und die API
verbindet sich über das private Docker-Netz. Kopiere `install-clamav.sh` auf die Ubuntu-VM und starte es dort in
einer Root-Shell oder mit `sudo` (nicht im Pterodactyl-Container). Das interaktive Skript zeigt die laufenden Container und Netzwerke,
bindet `clamd` an das Gateway des ausgewählten Netzes und erlaubt UFW den TCP-Port 3310 nur von der aktuellen IP des
API-Containers:

```bash
sudo bash install-clamav.sh
```

Voraussetzungen sind Ubuntu, Docker und bereits aktive UFW-Regeln. Das Skript aktiviert die Firewall nicht selbst,
damit es SSH oder andere Dienste nicht versehentlich aussperrt. Es sichert `clamd.conf`, installiert ClamAV und die
Signaturaktualisierung, startet beide Dienste und prüft `PING`/`PONG`. Auf Wunsch aktualisiert es die API-`.env`
nach einer Sicherung mit `CLAMAV_HOST` und `CLAMAV_PORT`; andernfalls zeigt es die Werte zum manuellen Eintragen.

`clamd` hat selbst keine Authentifizierung und das Protokoll ist unverschlüsselt. Den Port niemals öffentlich
freigeben oder pauschal für das ganze Pterodactyl-Netz erlauben. Die Regel gilt für die IP des ausgewählten API-
Containers; falls Pterodactyl ihm nach einem Neustart eine neue IP gibt, das Skript erneut ausführen und die alte
UFW-Regel entfernen.
