# TRS API v1: Contract

This is the binding contract for clients: the TRS Launcher (Rust core) and the TRS Client in-game mod (Java).
Every field, status code and error code listed here is implemented and covered by tests.
The German deployment guide is in [README.md](README.md).

- **Base URL:** `https://api.theredstonee.de`
- **Version prefix:** `/v1`
- **Transport:** HTTPS only. Cloudflare terminates TLS and speaks HTTP/1.1, HTTP/2 or HTTP/3 to clients.

---

## 1. Conventions

| Topic | Rule |
|---|---|
| Body format | JSON (`Content-Type: application/json`, UTF-8). The only exception is the cape upload, which sends raw `image/png`. |
| Body size | JSON bodies can be at most **16 KiB**. A cape upload can be at most **256 KiB**. Anything larger gets `413`. |
| Unknown fields | They are **rejected** with `400 invalid_request`. All request objects are strict. |
| UUIDs | Requests accept 32 hex digits with or without dashes, in any case. **Responses always use 32 lowercase hex digits without dashes**, for example `75c1a6f3112240abbdb57b9d21c64232`. |
| Minecraft names | `^[A-Za-z0-9_]{1,16}$` |
| Timestamps | ISO 8601 in UTC, for example `2026-09-23T18:04:59.167Z` |
| Auth header | `Authorization: Bearer <token>`, where the token matches `^trs_[A-Za-z0-9_-]{43}$`. Treat it as a secret: store it encrypted (DPAPI) and never log it. |
| Caching | JSON responses carry `Cache-Control: no-store`. Textures carry long-lived caching headers (see §5.4). |
| Request id | Every response has an `X-Request-Id` header. Quote it in bug reports. |
| CORS | Only for origins listed in `CORS_ORIGINS`. The launcher core and the mod make plain HTTP calls and don't need CORS. |

### 1.1 Error format

Every error has the same shape:

```json
{ "error": { "code": "not_joined", "message": "Mojang did not confirm the session join" } }
```

- `code` is stable and machine-readable. Branch on it.
- `message` is English text for logs and fallback UI. Don't parse it.
- Validation errors (`400 invalid_request`) also carry a `fields` array:
  ```json
  { "error": { "code": "invalid_request", "message": "Request validation failed",
    "fields": [ { "path": "serverId", "message": "must be the serverId returned by /v1/auth/challenge" } ] } }
  ```
- A `429` response has a `Retry-After` header (seconds) and `error.retryAfter` (same value).
- A `401` response has the header `WWW-Authenticate: Bearer`.
- Unexpected server errors return `500 internal_error` with no further details. The details go to the server log only.

### 1.2 Error codes used by every endpoint

| HTTP | code | Meaning |
|---|---|---|
| 400 | `invalid_request` | Schema validation failed. See `fields`. |
| 400 | `invalid_json` | The body is not valid JSON. |
| 401 | `unauthorized` | Token missing, malformed, unknown or expired. Log in again. |
| 403 | `banned` | The account is banned. Every feature is blocked. |
| 403 | `forbidden` | Admin endpoint called by a non-admin. |
| 403 | `cors_forbidden` | Preflight from an origin that isn't allowed. |
| 404 | `not_found` | Unknown route, or a malformed path parameter. |
| 405 | `method_not_allowed` | The route exists but not for this method. |
| 413 | `payload_too_large` | The body exceeds the size limit. |
| 415 | `unsupported_media_type` | Wrong `Content-Type`. |
| 429 | `rate_limited` | Too many requests. Honour `Retry-After`. |
| 500 | `internal_error` | Server bug. |
| 502 | `upstream_unavailable` | The Mojang session server is unreachable. Only on `/v1/auth/verify`. |
| 503 | `database_unavailable` / `too_many_streams` | Temporary. Retry later. |

### 1.3 Rate limits

All limits use a token bucket that refills evenly across the window.

| Scope | Limit |
|---|---|
| Any request, per client IP | 300 / min |
| `POST /v1/auth/challenge`, per IP | 20 / min |
| `POST /v1/auth/verify`, per IP | 10 / min |
| `POST /v1/auth/verify`, per username | 10 / 10 min |
| Successful logins, per UUID | 20 / h |
| Authenticated reads (GET), per account | 120 / min |
| Authenticated writes, per account | 30 / min |
| `POST /v1/players/lookup`, per account | 120 / min |
| `POST /v1/presence`, per account | 6 / min |
| Friend and block mutations, per account | 30 / min |
| `POST /v1/capes/redeem`, per account | 10 / min |
| **Failed** redemptions, per account | 5 / 15 min (after that every attempt gets `429`, even a correct code) |
| **Failed** redemptions, per IP | 20 / 15 min |
| `POST /v1/capes/upload`, per account | 5 / 24 h |
| `POST /v1/capes/{id}/report`, per account | 10 / h |
| `GET /v1/events` connects, per account | 10 / min (and at most 3 open streams) |
| `DELETE /v1/me`, per account | 3 / h |
| Admin, per admin (or API key) | 240 / min |

---

## 2. Authentication (Mojang session, like a Minecraft server)

The client proves that it owns the Minecraft account with the same handshake a Minecraft server uses.
The API never sees the Minecraft access token.

```
Client                                  TRS API                         Mojang
  | POST /v1/auth/challenge  ------------->|
  |<------------- 201 { serverId, expiresAt }   (single use, 60 s)
  | POST sessionserver.mojang.com/session/minecraft/join ------------------->|
  |   { accessToken, selectedProfile, serverId }                            |
  |<----------------------------------------------------------------- 204 --|
  | POST /v1/auth/verify { username, serverId } ->|
  |                                              |-- GET hasJoined?username&serverId -->|
  |                                              |<------------ 200 { id, name } ------|
  |<----------- 200 { token, expiresAt, user }   |
```

### 2.1 `POST /v1/auth/challenge`

No auth. The body is empty or `{}`.

**201**
```json
{ "serverId": "3f9a0c1d2e4b5a69788796a5b4c3d2e1f0a9b8c7", "expiresAt": "2026-09-23T18:06:00.000Z" }
```
`serverId` always matches `^[0-9a-f]{40}$`. It is valid **once** and for **60 seconds**.

### 2.2 Mojang join (client side, no TRS API call)

```http
POST https://sessionserver.mojang.com/session/minecraft/join
Content-Type: application/json

{ "accessToken": "<Minecraft access token>", "selectedProfile": "<uuid without dashes>", "serverId": "<serverId from 2.1>" }
```

- Mojang answers `204` on success.
- Pass `serverId` **exactly as received**. Do not hash it: unlike the real server login, there is no shared secret here.
- If the join fails with `403` (token expired), refresh the Minecraft token and retry.

### 2.3 `POST /v1/auth/verify`

No auth.

```json
{ "username": "Theredstonee", "serverId": "3f9a0c1d2e4b5a69788796a5b4c3d2e1f0a9b8c7" }
```

**200**
```json
{
  "token": "trs_Q2hhbmdlTWUtdGhpcy1pcy1hbi1leGFtcGxlLXRva2Vu",
  "expiresAt": "2026-10-23T18:05:02.000Z",
  "user": { "…": "same shape as GET /v1/me" }
}
```

- The token is valid for **30 days**. It doesn't slide: log in again when it expires or on any `401`.
- The server stores only the SHA-256 hash of the token.
- Each account keeps at most 10 sessions. Older ones are dropped.
- The challenge is consumed even when verification fails. Start over with a new challenge.

Errors:

| HTTP | code | Meaning |
|---|---|---|
| 401 | `invalid_challenge` | The challenge is unknown, expired or already used. |
| 401 | `not_joined` | Mojang didn't confirm the join, or the name doesn't match the account. |
| 403 | `banned` | The account is banned. |
| 429 | `rate_limited` | See §1.3. |
| 502 | `upstream_unavailable` | Mojang is down. Retry with backoff. |

### 2.4 `POST /v1/auth/logout`

Auth required. Body: none, `{}`, or `{ "all": true }`.

- Without `all`, only the current token is revoked.
- With `all: true`, every session of the account is revoked, presence is cleared and open event streams are closed.

Returns **204**.

### 2.5 Admins

An admin is an account whose UUID is in `ADMIN_UUIDS`. `user.admin` is `true` for them.

Scripts can use the header `X-Admin-Key: <ADMIN_API_KEY>` instead of a bearer token. The key is compared in constant time.
If the header is present and wrong, the request fails with `401`. It does not fall back to the bearer token.

---

## 3. Profile

### 3.1 `GET /v1/me`

Auth required.

**200**
```json
{
  "uuid": "75c1a6f3112240abbdb57b9d21c64232",
  "name": "Theredstonee",
  "admin": true,
  "createdAt": "2026-09-23T18:05:02.000Z",
  "settings": {
    "showBadge": true,
    "showCapeToOthers": true,
    "presenceVisibility": "friends",
    "shareServer": false
  },
  "activeCape": null
}
```
`activeCape` is either `null` or a **CapeView** (§5.1).

| Setting | Default | Effect |
|---|---|---|
| `showBadge` | `true` | Others see the TRS badge in the lookup. |
| `showCapeToOthers` | `true` | Others see the active cape. You always see your own. |
| `presenceVisibility` | `"friends"` | `"friends"`: friends see your online state. `"nobody"`: you always appear offline. |
| `shareServer` | `false` | Friends see the server address while you're `in-game`. |

### 3.2 `PATCH /v1/me`

Auth required. Send any non-empty subset of `settings`:

```json
{ "shareServer": true, "presenceVisibility": "nobody" }
```

**200** returns the same shape as `GET /v1/me`.

- Turning `shareServer` off drops the stored server address immediately.
- When visibility changes, friends get a `presence` event.

### 3.3 `DELETE /v1/me` (GDPR Art. 17)

Auth required. Deletes everything immediately:

- account, sessions, friendships, requests and blocks (both directions)
- uploaded capes and their files
- code redemptions, reports and presence

Only an existing **ban record** survives (keyed by UUID) so a ban can't be escaped by re-registering.

Returns **204**. The token is invalid afterwards.

### 3.4 `PUT /v1/me/cape`

Auth required.

```json
{ "capeId": "team" }
```
Send `{ "capeId": null }` to take the cape off.

**200**
```json
{ "activeCape": { "…": "CapeView" } }
```
It is `{ "activeCape": null }` after taking the cape off.

Errors:

| HTTP | code | Meaning |
|---|---|---|
| 404 | `cape_not_found` | Unknown cape, or someone else's upload. |
| 403 | `cape_locked` | The cape isn't unlocked for this account. |

Who can wear a cape:

- **free** capes: everyone.
- **code** and **admin** capes: holders of a grant or a redeemed code. Admins can wear every built-in cape.
- **Own uploads:** while `pending` or `approved`, but never once `rejected`.

---

## 4. Players: badge and cape lookup (for the mod)

### 4.1 `POST /v1/players/lookup`

Auth required. Send 1–100 UUIDs, dashed or not. Duplicates are ignored.

```json
{ "uuids": ["b0b0b0b0-b0b0-b0b0-b0b0-b0b0b0b0b0b0", "ffffffffffffffffffffffffffffffff"] }
```

**200**
```json
{
  "players": [
    {
      "uuid": "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0",
      "badge": true,
      "cape": {
        "id": "team",
        "url": "https://api.theredstonee.de/v1/capes/team.png?v=61749d72f375",
        "scale": 2,
        "animated": true,
        "frames": 8,
        "frameTimeMs": 150
      }
    }
  ]
}
```

Only players who **use TRS and show something** appear in the list. A missing UUID means "no badge, no cape": render vanilla.

Privacy rules, enforced server-side:

- Banned accounts are never returned.
- Accounts that **blocked the requester** are never returned.
- `badge` is the player's `showBadge` setting.
- `cape` is set only when the player has an active cape **and** `showCapeToOthers` is on **and** the cape is `approved`.
- For your **own** UUID you also get your own `pending` upload, and your cape even with `showCapeToOthers=false`.

Suggested client behaviour:

- Batch the UUIDs of visible players.
- Cache results for about 5 minutes per UUID.
- Refresh when players join the tab list.

### 4.2 `POST /v1/presence` (heartbeat)

Auth required. Send it **at least every 60 s**. Presence expires **180 s** after the last heartbeat. The limit is 6 per minute.

```json
{ "state": "in-game", "game": { "version": "1.21.1", "loader": "fabric", "server": "play.example.net:25565" } }
```

| Field | Values |
|---|---|
| `state` | `online` (launcher open), `in-game`, `offline` (clears the presence immediately) |
| `game.version` | `^[0-9A-Za-z._+ -]{1,32}$` |
| `game.loader` | `vanilla` \| `fabric` \| `quilt` \| `forge` \| `neoforge` |
| `game.server` | Optional host or IPv4, with an optional `:port`. No scheme, no path. |

- `game.server` is **stored only if** the user has `shareServer=true` and `state` is `in-game`. Otherwise it is silently dropped.
- The server address is stored lower-cased.

**200**
```json
{ "state": "in-game", "expiresInSec": 180 }
```
For `offline`, `expiresInSec` is `0`.

Presence lives only in server memory. There is no history.

---

## 5. Capes

### 5.1 CapeView

This object is used everywhere a cape is returned.

```json
{
  "id": "team",
  "name": "TRS Team",
  "kind": "builtin",
  "unlock": "admin",
  "status": "approved",
  "url": "https://api.theredstonee.de/v1/capes/team.png?v=61749d72f375",
  "width": 128,
  "height": 64,
  "scale": 2,
  "animated": true,
  "frames": 8,
  "frameTimeMs": 150
}
```

| Field | Meaning |
|---|---|
| `id` | `^[a-z0-9][a-z0-9_-]{0,39}$`. Built-in capes have readable ids such as `redstone` or `team`. Uploads use `u` followed by 20 hex digits. |
| `kind` | `builtin` \| `upload` |
| `unlock` | `free` \| `code` (unlockable with a code or an admin grant) \| `admin` (admin grant or code only) \| `owner` (an upload, only for its uploader) |
| `status` | `approved` \| `pending` \| `rejected`. Built-in capes are always `approved`. |
| `url` | Absolute texture URL. `?v=` changes whenever the content changes. **Use the URL as given.** |
| `width`, `height` | Size of **one frame** in pixels. Always `64·scale × 32·scale`. |
| `scale` | 1–4. The resolution factor relative to the vanilla 64×32 layout. |
| `animated` | `frames > 1` |
| `frames` | 1–64 |
| `frameTimeMs` | Duration of each frame (20–10000), or `null` for a static cape. |

### 5.2 Texture layout (important for the mod)

The PNG at `url` is **`width` × (`height` · `frames`)** pixels: all frames stacked **vertically**, frame 0 at the top.

Inside each frame, the standard vanilla cape UV layout applies, with every coordinate **multiplied by `scale`**.
The values below are for `scale` 1, as x, y, width, height.

| Part | Region |
|---|---|
| Outer face (seen from behind the player) | (1, 1, 10, 16) |
| Inner face (towards the player's back) | (12, 1, 10, 16) |
| Side | (0, 1, 1, 16) |
| Side | (11, 1, 1, 16) |
| Top | (1, 0, 10, 1) |
| Bottom | (11, 0, 10, 1) |
| Elytra (box 10×20×2) | offset (22, 0) |

How to render it:

- **Pixel region of frame `f`:** x ∈ [0, width), y ∈ [f·height, (f+1)·height).
- **Normalised V for a vanilla UV `(u, v)`:**
  - `u' = u / 64`
  - `v' = (f·32 + v) / (32·frames)`
  - With `scale`, the fractions stay the same because the texture is scaled uniformly.
- **Which frame to show:** use the wall clock so every player sees the same frame.
  `f = floor(currentTimeMillis / frameTimeMs) mod frames`.
- **Static capes:** `frames = 1` and `frameTimeMs = null`. The texture is a normal single cape image.

### 5.3 `GET /v1/capes`

Auth required. Returns the catalog from the user's point of view: every built-in cape, plus the user's own uploads in any status.

**200**
```json
{
  "capes": [
    { "id": "redstone", "name": "Redstone", "kind": "builtin", "unlock": "free", "status": "approved",
      "url": "https://api.theredstonee.de/v1/capes/redstone.png?v=0c1d…", "width": 128, "height": 64, "scale": 2,
      "animated": false, "frames": 1, "frameTimeMs": null, "owned": true, "active": false },
    { "id": "u3f9a0c1d2e4b5a697887", "name": "Mein Umhang", "kind": "upload", "unlock": "owner", "status": "rejected",
      "url": "…", "width": 64, "height": 32, "scale": 1, "animated": false, "frames": 1, "frameTimeMs": null,
      "owned": false, "active": false, "rejectReason": "Urheberrecht" }
  ]
}
```

- `owned` means the user can wear the cape right now.
- `rejectReason` is present only for uploads, and is `null` when there is no reason.

### 5.4 `GET /v1/capes/{id}.png`

No auth needed for `approved` capes. Returns the PNG with these headers:

- `Content-Type: image/png`
- `ETag: "<sha256>"`. Send `If-None-Match` to get `304`.
- `Cache-Control: public, max-age=31536000, immutable` when `?v=` matches the current content (or is absent). With a stale `?v=` the response is `public, max-age=300` instead.
- `Cross-Origin-Resource-Policy: cross-origin`

`pending` or `rejected` uploads are served only to the uploader or an admin, and only with an `Authorization` header. Those responses carry `Cache-Control: private, no-store`. Everyone else gets `404 cape_not_found`.

On this route an invalid or expired token never causes a `401`. The request is simply treated as anonymous.

### 5.5 `GET /v1/capes/{id}`

Returns the metadata without the texture. The visibility rules are the same as §5.4.

**200**
```json
{ "cape": { "…": "CapeView" } }
```

### 5.6 `POST /v1/capes/upload?name=<optional>`

Auth required. Headers: `Content-Type: image/png`. The body is the raw PNG bytes, at most 256 KiB.

`name` is optional, 1–32 characters: letters, digits, spaces and `. , ' ! ? & ( ) + - _`.

**201**
```json
{ "cape": { "…": "CapeView", "kind": "upload", "unlock": "owner", "status": "pending" } }
```

What the server checks:

- The PNG signature and every chunk CRC.
- A chunk whitelist.
- **No APNG**, so uploads are always static.
- **No bytes after `IEND`** (polyglot protection).
- The size must be **64k×32k** (k = 1–4, up to 256×128) **or** the cape-only format **22k×17k**, which is placed into a 64k×32k canvas.
- It checks the decompressed size (bomb protection).
- It decodes the image and **re-encodes** it as RGBA PNG. All metadata is dropped and fully transparent pixels are zeroed.
- The cape area must not be fully transparent.

| HTTP | code |
|---|---|
| 400 | `invalid_png`, `animated_png`, `invalid_dimensions`, `empty_cape` |
| 409 | `too_many_pending` (at most 3 pending), `upload_limit` (at most 10 non-rejected), `duplicate_cape` |
| 413 | `payload_too_large` |
| 415 | `unsupported_media_type` |

Uploads start as `pending`. An admin approves or rejects them. Until then only the uploader sees the upload, in the catalog and in their own lookup.

### 5.7 `DELETE /v1/capes/{id}`

Auth required. Deletes one of your own uploads. Returns **204**, or `404 cape_not_found`.

### 5.8 `POST /v1/capes/{id}/report`

Auth required. Reports an `approved` upload by **another** user.

```json
{ "reason": "inappropriate", "note": "optional, ≤200 chars" }
```

`reason` is one of `inappropriate`, `copyright`, `impersonation` or `other`.

Returns **204**. Reporting the same cape again updates your existing report.
Built-in capes, pending uploads and your own uploads return `404 cape_not_found`.

### 5.9 `POST /v1/capes/redeem`

Auth required.

```json
{ "code": "7K3QF-M2XPA-9RTVB-C4HJN" }
```

- Codes are case-insensitive. Dashes and spaces are ignored. `O` is read as `0`, and `I` and `L` as `1`.
- The format is 20 Crockford base32 characters.

**200**
```json
{ "cape": { "…": "CapeView" }, "alreadyOwned": false }
```
`alreadyOwned: true` means the cape was already unlocked. In that case the code is **not** consumed.

| HTTP | code |
|---|---|
| 404 | `invalid_code` (unknown or revoked) |
| 410 | `code_expired`, `code_used_up` |
| 429 | Brute-force lock (§1.3) |

---

## 6. Friends

Only **TRS users** (accounts that logged in at least once) can be befriended.
A target that is banned, unknown, or **has blocked you** returns `404 player_not_found`.

### 6.1 `GET /v1/friends`

Auth required.

**200**
```json
{
  "friends": [
    { "uuid": "b0b0…", "name": "Bob", "since": "2026-09-23T18:05:10.000Z",
      "presence": { "state": "in-game", "game": { "version": "1.21.1", "loader": "fabric", "server": "play.example.net" },
                    "updatedAt": "2026-09-23T18:06:00.000Z" } }
  ],
  "requests": {
    "incoming": [ { "uuid": "…", "name": "Alex", "createdAt": "…" } ],
    "outgoing": [ { "uuid": "…", "name": "Steve", "createdAt": "…" } ]
  }
}
```

- `presence` is `null` when the friend is offline or has `presenceVisibility=nobody`.
- `game` can be `null`. `game.server` is present only if the friend shares it.
- Friends are sorted by name.
- **Polling is the baseline:** call this every 30–60 s while the friends UI is visible. SSE (§7) is optional.

### 6.2 `POST /v1/friends/requests`

Auth required. `target` is a Minecraft name (the name last seen by TRS) or a UUID.

```json
{ "target": "Bob" }
```

**201**
```json
{ "status": "sent", "user": { "uuid": "b0b0…", "name": "Bob" } }
```

**200**
```json
{ "status": "accepted", "user": { "uuid": "…", "name": "…" } }
```
This happens when the other player had already sent you a request: you become friends immediately.

| HTTP | code |
|---|---|
| 404 | `player_not_found` |
| 400 | `cannot_target_self` |
| 409 | `blocked` (you blocked them; unblock first), `already_friends`, `already_requested`, `too_many_requests` (≥50 outgoing), `target_inbox_full` (≥100 incoming), `friend_limit` / `target_friend_limit` (200 friends) |

### 6.3 Answer, cancel and remove

| Request | Success | Errors |
|---|---|---|
| `POST /v1/friends/requests/{uuid}/accept` | **200** `{ "friend": { "uuid", "name", "since", "presence" } }` | `404 request_not_found`, `409 friend_limit` / `target_friend_limit` |
| `POST /v1/friends/requests/{uuid}/decline` | **204**. The sender is not notified. | `404 request_not_found` |
| `DELETE /v1/friends/requests/{uuid}` | **204**. Cancels your own outgoing request. | `404 request_not_found` |
| `DELETE /v1/friends/{uuid}` | **204** | `404 friend_not_found` |

### 6.4 Blocks

**`GET /v1/blocks`** returns **200**:
```json
{ "blocked": [ { "uuid": "…", "name": "…", "since": "…" } ] }
```

**`POST /v1/blocks`** takes:
```json
{ "target": "Bob" }
```
and returns **201**:
```json
{ "blocked": { "uuid": "…", "name": "Bob" } }
```

- Blocking removes the friendship and all requests in both directions.
- The blocked player can no longer find you (`player_not_found`) and no longer gets your badge or cape in the lookup.
- They only see `friend_removed`, never a block notice.

**`DELETE /v1/blocks/{uuid}`** returns **204**, or `404 block_not_found`.

---

## 7. Events (optional SSE)

`GET /v1/events` requires auth (`Authorization` header).
The response is `Content-Type: text/event-stream`. Each event has the form:

```
event: <type>
data: <JSON>

```

| event | data |
|---|---|
| `hello` | `{"type":"hello","keepaliveSec":25}` (first message) |
| `ping` | `{}` every 25 s (keep-alive) |
| `friend_request` | `{"type":"friend_request","from":{"uuid":"…","name":"…"}}` |
| `friend_request_cancelled` | `{"type":"friend_request_cancelled","uuid":"…"}` |
| `friend_added` | `{"type":"friend_added","friend":{"uuid":"…","name":"…"}}` |
| `friend_removed` | `{"type":"friend_removed","uuid":"…"}` |
| `presence` | `{"type":"presence","uuid":"…","presence":{…PresenceView…}\|null}`. `null` means offline or hidden. |

- A stream stays open for **at most 1 hour**.
- It closes immediately on logout-all, a ban or account deletion.
- Reconnect with backoff: 1 s, 2 s, 5 s, … up to 60 s.
- Each account can have at most 3 parallel streams. A fourth gets `503 too_many_streams`.
- Presence events are sent **only on changes**. Repeated identical heartbeats are silent.

---

## 8. Admin

Auth: an admin bearer token **or** `X-Admin-Key`. Every mutation is recorded in the audit log.

| Method and path | Body | Response |
|---|---|---|
| `GET /v1/admin/stats` | – | `{ users:{total,banned,activeLast24h,online}, sessions, capes:{builtin,approved,pending,rejected,reported,activeUsers}, codes:{active,redemptions}, friendships, pendingFriendRequests, eventStreams }` |
| `GET /v1/admin/capes?status=pending\|approved\|rejected\|reported` | – | `{ capes: [CapeView + { owner:{uuid,name}\|null, createdAt, reviewedAt, reviewedBy, rejectReason, reports:{count, reasons:{<reason>:n}} }] }`. The default status is `pending`. |
| `POST /v1/admin/capes/{id}/approve` | – | `{ cape }`. Also clears open reports. |
| `POST /v1/admin/capes/{id}/reject` | `{ "reason"?: string≤200 }` or none | `{ cape }`. Also takes the cape off its wearer. |
| `DELETE /v1/admin/capes/{id}` | – | 204. Uploads only; built-in capes return `409 builtin_cape`. |
| `GET /v1/admin/codes` | – | `{ codes: [CodeView] }` (the newest 1000) |
| `POST /v1/admin/codes` | `{ capeId, maxUses?=1 (1–100000), count?=1 (1–100), expiresAt?: ISO, note?: ≤200 }` | **201** `{ codes: [CodeView + { code }] }`. **This is the only time the plain code is ever shown.** Free capes return `400 cape_is_free`. |
| `DELETE /v1/admin/codes/{id}` | – | 204 (revoke). Already unlocked capes stay unlocked. |
| `GET /v1/admin/users/{uuid-or-name}` | – | `{ user: { uuid, name, known, admin, banned:{reason,bannedAt,bannedBy}\|null, createdAt, lastLoginAt, settings, activeCapeId, grantedCapes:[{capeId,source,grantedAt}], uploads, friends, sessions, online } }` |
| `POST /v1/admin/users/{uuid}/capes` | `{ capeId }` | **201** `{ cape, alreadyOwned }`. The user must have logged in once (`404 user_not_found`); otherwise use a code. |
| `DELETE /v1/admin/users/{uuid}/capes/{capeId}` | – | 204, or `404 grant_not_found`. Takes the cape off if it is active. |
| `POST /v1/admin/users/{uuid}/ban` | `{ "reason"?: string≤200 }` or none | `{ user }`. Revokes sessions, clears presence, closes streams. Also works for UUIDs that never logged in. Admins return `409 cannot_ban_admin`. |
| `DELETE /v1/admin/users/{uuid}/ban` | – | 204, or `404 not_banned` |

**CodeView:**

```json
{ "id": 12, "hint": "HJN4", "capeId": "team", "maxUses": 1, "uses": 0, "expiresAt": null, "revokedAt": null,
  "note": "Discord giveaway", "createdAt": "…", "createdBy": "api-key" }
```

`hint` is the last 4 characters of the normalised code. The full code is stored only as an HMAC-SHA256 hash.

---

## 9. Misc

| Request | Response |
|---|---|
| `GET /v1/health` | `{ "status": "ok", "version": 1, "time": "…" }`, or `503 database_unavailable` |
| `GET /` | A tiny HTML status page |
| `GET /robots.txt` | `Disallow: /` |

---

## 10. Client checklist

**Launcher (Rust):**

1. Log in:
   - Call `challenge`.
   - Call Mojang `join` with the active account's access token.
   - Call `verify`.
   - Store the token encrypted, per Minecraft account.
2. On any `401`, log in again once. Show `403 banned` in the UI.
3. Send presence `online` every 60 s while the launcher runs.
   - Send `in-game` with game info while a game runs.
   - Send `offline` on exit.
4. Friends view: poll `GET /v1/friends` and optionally open the SSE stream.
5. Cape picker: `GET /v1/capes`, `PUT /v1/me/cape`, upload, redeem.

**Mod (Java):**

1. Use the launcher's TRS token if the launcher passes one. Otherwise run the auth flow with the in-game session, which has the access token.
2. Batch-lookup visible players (at most 100 per call). Cache about 5 minutes. Treat a missing UUID as "no badge, no cape".
3. Download the texture once per `url`. The `?v=` part changes with the content.
   - Pick the frame with `f = floor(now / frameTimeMs) % frames`.
   - Scale UVs by `scale` (§5.2).
4. Never send `game.server` unless the user enabled `shareServer`. The server also drops it.
