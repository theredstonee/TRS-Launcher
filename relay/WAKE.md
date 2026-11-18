# TRS Wake – wake-on-join for CraftingHosting servers

Every CraftingHosting server created through the TRS Launcher has its own address `<name>.trs-launcher.de`. The TRS
API manages its DNS:

- `A <name>.trs-launcher.de` → the relay's public IP (always).
- `SRV _minecraft._tcp.<name>.trs-launcher.de` (TTL 60):
  - server **running** → target = CraftingHosting node `host:port` (players connect directly, real IP),
  - otherwise → target = `<name>.trs-launcher.de:25565`, i.e. this wake listener.

So the wake listener only sees players while a server sleeps (or while a client still has the old DNS answer cached).
It never forwards game traffic.

## Listener

TCP `WAKE_PORT` (default `25565`, container port = Pterodactyl allocation). Speaks just enough of the Minecraft Java
protocol: handshake, status (server list ping incl. legacy 1.6-and-older ping → minimal answer), login start,
configuration phase keep-alive and transfer.

- Handshake `server address`: strip a trailing `.`, anything after `\0` (Forge/FML markers) and lower-case it. Only
  `<name>.<HOSTING_DOMAIN>` (default `trs-launcher.de`) is valid; everything else → status "unknown server" / login
  disconnect "Unknown server".
- Per-IP limits (connections per minute, concurrent connections), handshake/login read timeouts (5 s), max packet
  size small (e.g. 32 KiB). No logging of player IPs beyond in-memory rate limiting.

## Status (server list)

Ask the API (`resolve`, cached ~10 s per host). Answer:
- `version`: `{ name: "TRS Wake", protocol: <client's protocol> }` so the client shows it as compatible,
- `players`: `{ max: maxPlayers, online: 0 }`,
- `description` (legacy `§` text is fine, two lines):
  - sleeping: line 1 `§a● §fSchläft – Beitreten startet ihn` (`state` offline/stopping) or `§e● §fStartet …`
    (`starting`) or `§c● §fPause bis HH:MM` (`cooldown`, local time Europe/Berlin) or `§c● §fNicht verfügbar`,
  - line 2 always the fixed footer `§7Free server: §ctrs-launcher.de §8× §bcraftinghosting.free`.
- `favicon`: none.
- Answer `ping` with `pong`.

## Login

- Protocol ≥ 766 (Minecraft 1.20.5+): offline-style login (no encryption – the real server does authentication):
  Login Start → `wake` (if not running) → Login Success (UUID from Login Start, empty properties) → wait for Login
  Acknowledged → configuration phase: send configuration keep-alive every 10 s while polling `resolve` every 3 s
  until `state = running` with a `target`, then send **Transfer** (`host`, `port` of the target). Give up after
  150 s with a configuration-phase disconnect "Server konnte nicht starten – versuch es gleich noch mal".
  The real server must accept transfers – the API sets `accepts-transfers=true` on every server.
- Older protocols: `wake` (if not running), then login disconnect (JSON text component):
  - woken / starting: "§aServer startet …§r\n§7In etwa einer Minute erneut verbinden."
  - already running (stale DNS): "§aServer läuft§r\n§7Bitte erneut verbinden."
  - errors below → the matching message.
- `wake` error codes → messages: `not_allowed` (friends only, name not on the list) "§cNur Freunde des Besitzers
  können diesen Server starten.", `cooldown` "§cPflichtpause bis HH:MM.", `eula_required` "§cDer Besitzer muss den
  Server einmal im TRS Launcher starten.", `rate_limited` "§cBitte kurz warten.", anything else "§cServer gerade nicht
  verfügbar.".

## API (TRS API, base `TRS_API_URL`, default `https://trs-launcher.theredstonee.de`)

Live the relay uses `TRS_API_URL=https://api.trs-launcher.de` (same tunnel, zone without Bot Fight Mode – on
theredstonee.de Cloudflare's Bot Fight Mode answers requests from the relay's datacenter IP with 403).

Both requests are signed with the relay secret (`RELAY_SECRET`, first secret signs, API accepts all):
headers `X-Relay-Time: <unix seconds>` and `X-Relay-Sig: <hex HMAC-SHA256(secret, time + "\n" + METHOD + "\n" +
path-with-query + "\n" + sha256hex(body or ""))>`; the API rejects times off by more than 60 s.

- `GET /v1/internal/hosting/resolve?host=<fqdn>` → `200 { name, state, target: { host, port } | null,
  maxPlayers, cooldownUntil | null }` with `state` = `running | starting | offline | stopping | cooldown | unavailable`;
  `404 { error: { code: "unknown_host" } }`.
- `POST /v1/internal/hosting/wake` body `{ host, player: { name, uuid } }` (uuid as sent in Login Start, unverified)
  → `202 { state }` or `409 { error: { code, details? } }` with the codes above (`cooldown` has
  `details.cooldownUntil`), `429 rate_limited`.

The API decides who may wake (friends-only servers: name on the whitelist; open servers: anyone), rate-limits wakes
per server, and switches the SRV record to the node once the server runs.
