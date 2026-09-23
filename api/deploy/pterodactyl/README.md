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

1. `cd api && pnpm build`
2. `.output/` (und bei Bedarf `start.sh`) als Zip hochladen und im Datei-Manager entpacken –
   `.env` und `data/` **nicht** überschreiben.
3. Server neu starten.
