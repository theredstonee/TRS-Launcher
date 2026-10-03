# Push notifications for the TRS apps (operations)

The contract is in [API.md §33](../API.md). This page is about running it.

## What the TRS API needs

Only three environment variables (see README, `.env`-Schlüssel):

```
node scripts/vapid-keys.mjs        # prints VAPID_PUBLIC_KEY / VAPID_PRIVATE_KEY / VAPID_SUBJECT
```

Put the values into the server's `.env` (Pterodactyl: startup variables). Never commit them. Without them,
UnifiedPush registration answers `503 push_unavailable`; poll devices (iOS) keep working.

The API does **not** run a push server. It only POSTs encrypted messages (RFC 8291, `aes128gcm`) with a VAPID
header (RFC 8292) to the **endpoint URL** a phone registered. The push server can never read the content.
The API needs outbound HTTPS (port 443, or the port in the endpoint) to the push servers – nothing inbound.

## Where the endpoint comes from (Android, UnifiedPush)

UnifiedPush lets the user pick a **distributor** app on the phone. The TRS app asks the distributor for an endpoint
(passing our `vapidPublicKey` from `GET /v1/push/config`) and registers it with `POST /v1/push/devices`.
Options for players, none of which need anything from us:

| Distributor | Push server | Notes |
|---|---|---|
| **ntfy** app (F-Droid/Play) | `https://ntfy.sh` (default, free) | Easiest. ntfy.sh only sees encrypted blobs. |
| ntfy app | own ntfy server | see below |
| NextPush | a Nextcloud with the UnifiedPush app | for people who already run Nextcloud |
| Sunup / other distributors | Mozilla autopush etc. | works the same way (Web Push endpoint) |

The API accepts any public `https://` endpoint (private/loopback/link-local addresses, `.local`/`.internal` names and
our own hosts are refused, DNS is checked at registration **and** again when connecting).

## Running an own ntfy server (optional, not deployed)

Only needed if we want an "official" TRS push server instead of ntfy.sh. Sketch (Docker, behind nginx/Cloudflare,
HTTPS + HSTS like every other domain, ntfy bound to `127.0.0.1` only):

```yaml
# docker-compose.yml
services:
  ntfy:
    image: binwiederhier/ntfy:v2   # pin an exact version for production
    command: serve
    restart: unless-stopped
    environment:
      NTFY_BASE_URL: https://push.theredstonee.de
      NTFY_LISTEN_HTTP: ":80"
      NTFY_BEHIND_PROXY: "true"
      NTFY_CACHE_FILE: /var/cache/ntfy/cache.db
      NTFY_CACHE_DURATION: "12h"
      NTFY_AUTH_FILE: /var/lib/ntfy/user.db
      # Anyone may receive on random UnifiedPush topics ("up…"), nobody may read/write other topics.
      NTFY_AUTH_DEFAULT_ACCESS: deny-all
      NTFY_ENABLE_LOGIN: "false"
      NTFY_VISITOR_REQUEST_LIMIT_BURST: "60"
      NTFY_VISITOR_REQUEST_LIMIT_REPLENISH: "5s"
    volumes:
      - ./cache:/var/cache/ntfy
      - ./lib:/var/lib/ntfy
    ports:
      - "127.0.0.1:2586:80"
```

With `deny-all` nothing is public, so open the random UnifiedPush topics once:
`docker compose exec ntfy ntfy access everyone 'up*' read-write` (the topic name is the secret part of the endpoint
URL; the phone subscribes to it, the TRS API publishes to it, all other topics stay closed; per-visitor rate limits
apply). Alternatively give players ntfy accounts and only grant `everyone 'up*' write-only`. Then:

1. nginx vhost `push.theredstonee.de` → `http://127.0.0.1:2586` (with `proxy_http_version 1.1`, `Upgrade`/
   `Connection` headers for WebSockets, long `proxy_read_timeout`), certbot `--redirect`, HSTS from the global snippet.
2. Players choose "custom server" `https://push.theredstonee.de` in the ntfy app.
3. Nothing changes in the TRS API: the endpoint URLs simply point to our server.

Limits to keep in mind: ntfy accepts messages up to 4096 bytes (we send at most 4096 bytes including encryption
overhead) and caches undelivered messages for `NTFY_CACHE_DURATION`; we also send `TTL` per message (§33.5).

## iOS (sideloaded, no APNs)

No push server is possible without APNs. The app registers a `poll` device and calls
`GET /v1/push/pending?device=…&since=…` from its background fetch; iOS decides how often (often 15 min to hours).
Entries stay at most 72 hours (shorter for time-bound kinds like world invites).

## Troubleshooting

- `GET /v1/push/devices` shows `failing: true` and `lastSuccessAt` per device.
- A push server answering 404/410 removes the device; 25 failures in a row (4xx other than 404/410, or retries
  exhausted) remove it as well. The app re-registers on its next start.
- Changing the VAPID key pair breaks distributors that bind the key: all apps must register again.
