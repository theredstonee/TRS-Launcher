# TRS API v1: Contract

This is the binding contract for clients: the TRS Launcher (Rust core) and the TRS Client in-game mod (Java).
Every field, status code and error code listed here is implemented and covered by tests.
The German deployment guide is in [README.md](README.md).

- **Base URL:** `https://trs-launcher.theredstonee.de` (the same app serves the website). The old host `https://api.theredstonee.de` keeps answering `/v1` for older launchers and mods; its other paths redirect to the website.
- **Version prefix:** `/v1`
- **Transport:** HTTPS only. Cloudflare terminates TLS and speaks HTTP/1.1, HTTP/2 or HTTP/3 to clients.

---

## 1. Conventions

| Topic | Rule |
|---|---|
| Body format | JSON (`Content-Type: application/json`, UTF-8). The only exceptions are the cape and cosmetic uploads, which send raw `image/png`. |
| Body size | JSON bodies can be at most **16 KiB**. A cape upload can be at most **5 MiB**, a cosmetic upload at most **512 KiB**. Sync bodies are larger (§17): a skin upload at most **192 KiB**, presets at most **96 KiB**. Anything larger gets `413`. |
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
| 502 | `upstream_unavailable` | Mojang is unreachable. Only on `/v1/auth/verify` and `/v1/skins/*`. |
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
| `GET /v1/events/players` connects, per account | 20 / min (and at most 3 open streams) |
| `POST /v1/cosmetics/upload`, per account | 5 / 24 h |
| `POST /v1/cosmetics/{id}/report`, per account | shares the 10 / h bucket with cape reports |
| `POST /v1/redeem` | same buckets as `POST /v1/capes/redeem` (they share them) |
| `POST /v1/emotes/play`, per account | **1 / 2 s** (only valid, unlocked emotes count) |
| `POST /v1/me/skin-changed`, per account | 6 / min |
| `GET /v1/skins/*`, per account | 30 / min |
| Mojang profile requests made by the server (cache misses), total | 100 / min. Beyond that `/v1/skins/*` answers `429`. |
| `DELETE /v1/me`, per account | 3 / h |
| Every `/v1/me/sync*` request, per account | 120 / min (own bucket, does not use the read/write buckets) |
| `PUT /v1/me/sync/skins/{id}`, per account | additionally 30 / min |
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
    "shareServer": false,
    "showCosmeticsToOthers": true
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
| `showCosmeticsToOthers` | `true` | Others see your equipped cosmetics (§11). You always see your own. Emotes are sent regardless. |

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
- uploaded capes and cosmetics and their files
- equipped cosmetics, cape and cosmetic grants
- code redemptions, reports and presence
- all sync data (§17): skins with their images, deletion markers, presets and settings

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
      },
      "cosmetics": {
        "hat": {
          "id": "redstone_crown",
          "template": "crown",
          "url": "https://api.theredstonee.de/v1/cosmetics/redstone_crown.png?v=9b1f0c77aa21",
          "scale": 2,
          "animated": true,
          "frames": 8,
          "frameTimeMs": 120,
          "emissive": true
        },
        "wings": null,
        "back": null,
        "aura": null
      }
    }
  ]
}
```

Only players who **use TRS and show something** (badge, cape or at least one cosmetic) appear in the list. A missing UUID means "no badge, no cape, no cosmetics": render vanilla.

`cosmetics` always has the four keys `hat`, `wings`, `back` and `aura`. Each is `null` or a **LookupCosmetic**. Render it with the template named in `template` from `GET /v1/cosmetics/templates` (§11). The texture and frame fields work exactly like the cape fields.

Privacy rules, enforced server-side:

- Banned accounts are never returned.
- Accounts that **blocked the requester** are never returned.
- `badge` is the player's `showBadge` setting.
- `cape` is set only when the player has an active cape **and** `showCapeToOthers` is on **and** the cape is `approved`.
- For your **own** UUID you also get your own `pending` upload, and your cape even with `showCapeToOthers=false`.
- Cosmetics follow the same rules: others get a slot only if the player has `showCosmeticsToOthers` on **and** the item is `approved`. You always see your own equipped items, including `pending` uploads.

Suggested client behaviour:

- Batch the UUIDs of visible players.
- Cache results for about 5 minutes per UUID.
- Refresh when players join the tab list.
- For live changes (emotes, skin, cape and cosmetic changes), subscribe to the visible players with `GET /v1/events/players` (§13) instead of polling.

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
| `scale` | The resolution factor relative to the vanilla 64×32 layout: 1–8 (uploads and built-in capes). |
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

### 5.6 `POST /v1/capes/upload?name=<optional>&frames=<optional>&frameTimeMs=<optional>`

Auth required. Headers: `Content-Type: image/png`. The body is the raw PNG bytes, at most **5 MiB** (5 242 880 bytes).

Query parameters:

| Parameter | Meaning |
|---|---|
| `name` | Optional, 1–32 characters: letters, digits, spaces and `. , ' ! ? & ( ) + - _`. |
| `frames` | Optional integer 1–16. If given, it must equal the frame count the server reads from the image size, otherwise `400 invalid_dimensions`. |
| `frameTimeMs` | Integer 50–10000. **Required when the image has more than one frame** (`400 frame_time_required`). Ignored for static capes (stored as `null`). |

Invalid parameter values (not an integer, out of range, unknown parameters) return `400 invalid_request`.

**Sizes.** One frame is **64k×32k** (k = 1–8, so 64×32 up to 512×256) **or** the cape-only format **22k×17k** (22×17 up to 176×136). Animated capes stack **1–16 frames vertically**, like built-in capes: the image is `frameWidth × (frameHeight · frames)`, so at most 512×4096. The **width decides the layout** (64k and 22k never collide for k ≤ 8); the height must be a whole number of frames. Anything else returns `400 invalid_dimensions`.

**201**
```json
{ "cape": { "…": "CapeView", "kind": "upload", "unlock": "owner", "status": "pending",
  "width": 512, "height": 256, "scale": 8, "animated": true, "frames": 16, "frameTimeMs": 120 } }
```

`width`/`height` in the response are the size of **one frame** (always 64k×32k). The stored texture (§5.4) is a vertical strip of `frames` frames of that size.

What the server checks:

- The PNG signature and every chunk CRC.
- A chunk whitelist.
- **No APNG** (`animated_png`). Animation only works as a vertical frame strip.
- **No bytes after `IEND`** (polyglot protection).
- The image size (rules above) and at most 512 × 4096 pixels in total. Both are checked on the `IHDR` header **before** anything is decompressed.
- It checks the decompressed size (bomb protection).
- It decodes the image and **re-encodes** it as an RGBA PNG strip of `64k × 32k·frames`. A 22k×17k frame is placed at the top left of its own 64k×32k area; the rest stays transparent. All metadata is dropped and fully transparent pixels are zeroed.
- At least one pixel in the cape area (22k×17k of some frame) must be visible, otherwise `400 empty_cape`.
- Duplicates are detected on the re-encoded PNG.

| HTTP | code |
|---|---|
| 400 | `invalid_png`, `animated_png`, `invalid_dimensions`, `frame_time_required`, `empty_cape`, `invalid_request` |
| 409 | `too_many_pending` (at most 3 pending), `upload_limit` (at most 10 non-rejected), `duplicate_cape` |
| 413 | `payload_too_large` (more than 5 MiB) |
| 415 | `unsupported_media_type` |
| 429 | `rate_limited` (at most 5 uploads per 24 hours) |

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

### 5.9 `POST /v1/redeem` (old path: `POST /v1/capes/redeem`)

Auth required. Both paths behave identically and share the rate-limit buckets. A code unlocks **either** a cape **or** a cosmetic or emote (§11, §12).

```json
{ "code": "7K3QF-M2XPA-9RTVB-C4HJN" }
```

- Codes are case-insensitive. Dashes and spaces are ignored. `O` is read as `0`, and `I` and `L` as `1`.
- The format is 20 Crockford base32 characters.

**200**
```json
{ "kind": "cape", "cape": { "…": "CapeView" }, "cosmetic": null, "alreadyOwned": false }
```
```json
{ "kind": "cosmetic", "cape": null, "cosmetic": { "…": "CosmeticView (§11.6)" }, "alreadyOwned": false }
```
`alreadyOwned: true` means the item was already unlocked. In that case the code is **not** consumed.

> **Compatibility:** for cape codes, the old fields `cape` and `alreadyOwned` are unchanged. A client that only knows capes must treat `cape: null` (a cosmetic code) as "unlocked something else". The redemption itself succeeded.

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

> This stream is about **your own account** (friends, presence). Live events about **other players you can see in-game** (emotes, skin, cape and cosmetic changes) come from a separate stream, `GET /v1/events/players` (§13).

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
| `GET /v1/admin/stats` | – | `{ users:{total,banned,activeLast24h,online}, sessions, capes:{builtin,approved,pending,rejected,reported,activeUsers}, cosmetics:{builtin,emotes,approved,pending,rejected,reported,equippedUsers}, codes:{active,redemptions}, friendships, pendingFriendRequests, eventStreams, playerStreams }` |
| `GET /v1/admin/capes?status=pending\|approved\|rejected\|reported` | – | `{ capes: [CapeView + { owner:{uuid,name}\|null, createdAt, reviewedAt, reviewedBy, rejectReason, reports:{count, reasons:{<reason>:n}}, bytes, ownerStats:{uploads,approved,pending,rejected}\|null }] }`. The default status is `pending`. `bytes` is the size of the stored PNG file (0 if it is missing). `ownerStats` counts **all** uploads of the owner (including this one); `null` without owner. |
| `POST /v1/admin/capes/{id}/approve` | – | `{ cape }`. Also clears open reports. |
| `POST /v1/admin/capes/{id}/reject` | `{ "reason"?: string≤200 }` or none | `{ cape }`. Also takes the cape off its wearer. |
| `DELETE /v1/admin/capes/{id}` | – | 204. Uploads only; built-in capes return `409 builtin_cape`. |
| `GET /v1/admin/codes` | – | `{ codes: [CodeView] }` (the newest 1000) |
| `POST /v1/admin/codes` | `{ capeId` **or** `cosmeticId, maxUses?=1 (1–100000), count?=1 (1–100), expiresAt?: ISO, note?: ≤200 }`. Exactly one of `capeId` and `cosmeticId`; `cosmeticId` can be an emote id. | **201** `{ codes: [CodeView + { code }] }`. **This is the only time the plain code is ever shown.** Free items return `400 cape_is_free` or `400 cosmetic_is_free`; unknown ones `404 cape_not_found` or `404 cosmetic_not_found`. |
| `DELETE /v1/admin/codes/{id}` | – | 204 (revoke). Already unlocked capes stay unlocked. |
| `GET /v1/admin/users/{uuid-or-name}` | – | `{ user: { uuid, name, known, admin, banned:{reason,bannedAt,bannedBy}\|null, createdAt, lastLoginAt, settings, activeCapeId, grantedCapes:[{capeId,source,grantedAt}], uploads, grantedCosmetics:[{cosmeticId,source,grantedAt}], equippedCosmetics:{<slot>:<id>}, cosmeticUploads, friends, sessions, online } }` |
| `GET /v1/admin/users/{uuid}/skin` | – | **200** SkinView (§14), like `GET /v1/skins/by-uuid/{uuid}`; used for the uploader's face on the admin website. Invalid UUID `404 not_found`, unknown account `404 player_not_found`, Mojang down `502 upstream_unavailable`, `429 rate_limited` (30/min per admin). |
| `POST /v1/admin/users/{uuid}/capes` | `{ capeId }` | **201** `{ cape, alreadyOwned }`. The user must have logged in once (`404 user_not_found`); otherwise use a code. |
| `DELETE /v1/admin/users/{uuid}/capes/{capeId}` | – | 204, or `404 grant_not_found`. Takes the cape off if it is active. |
| `POST /v1/admin/users/{uuid}/ban` | `{ "reason"?: string≤200 }` or none | `{ user }`. Revokes sessions, clears presence, closes streams. Also works for UUIDs that never logged in. Admins return `409 cannot_ban_admin`. |
| `DELETE /v1/admin/users/{uuid}/ban` | – | 204, or `404 not_banned` |
| `GET /v1/admin/cosmetics?status=pending\|approved\|rejected\|reported` | – | `{ cosmetics: [CosmeticView + { owner:{uuid,name}\|null, createdAt, reviewedAt, reviewedBy, rejectReason, reports:{count, reasons:{<reason>:n}} }] }`. Uploads only. The default status is `pending`. |
| `POST /v1/admin/cosmetics/{id}/approve` | – | `{ cosmetic }`. Also clears open reports. Watchers of the wearer get a `cosmetics` event. |
| `POST /v1/admin/cosmetics/{id}/reject` | `{ "reason"?: string≤200 }` or none | `{ cosmetic }`. Also takes the item off its wearer. |
| `DELETE /v1/admin/cosmetics/{id}` | – | 204. Uploads only; built-in items return `409 builtin_cosmetic`. |
| `POST /v1/admin/users/{uuid}/cosmetics` | `{ cosmeticId }` (cosmetic or emote) | **201** `{ cosmetic, alreadyOwned }`. `404 user_not_found` or `cosmetic_not_found`, `400 cosmetic_is_free`. |
| `DELETE /v1/admin/users/{uuid}/cosmetics/{cosmeticId}` | – | 204, or `404 grant_not_found`. Takes the item off if it is equipped. |

**CodeView:**

```json
{ "id": 12, "hint": "HJN4", "capeId": "team", "cosmeticId": null, "maxUses": 1, "uses": 0, "expiresAt": null, "revokedAt": null,
  "note": "Discord giveaway", "createdAt": "…", "createdBy": "api-key" }
```

Exactly one of `capeId` and `cosmeticId` is set. **`capeId` can be `null`** for cosmetic and emote codes.

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
6. Cosmetics picker (§11):
   - `GET /v1/cosmetics` gives the templates and the catalog. `GET /v1/me/cosmetics` gives what is equipped.
   - Preview with three.js/skinview3d, following §11.2–§11.5 exactly.
   - Equip with `PUT /v1/me/cosmetics`. Redeem codes with `POST /v1/redeem`.
   - Paint editor: start from `GET /v1/cosmetics/templates/{id}.png?scale=k`, then upload with `POST /v1/cosmetics/upload`.
7. After changing the Mojang skin, call `POST /v1/me/skin-changed` (§13.4).
8. Sync own skins, presets and theme/accent/language across devices (§17), only with consent and the sync switch on.

**Mod (Java):**

1. Use the launcher's TRS token if the launcher passes one. Otherwise run the auth flow with the in-game session, which has the access token.
2. Batch-lookup visible players (at most 100 per call). Cache about 5 minutes. Treat a missing UUID as "no badge, no cape, no cosmetics".
3. Download the texture once per `url`. The `?v=` part changes with the content.
   - Pick the frame with `f = floor(now / frameTimeMs) % frames`.
   - Scale UVs by `scale` (§5.2 for capes, §11.3 for cosmetics).
4. Never send `game.server` unless the user enabled `shareServer`. The server also drops it.
5. Load `GET /v1/cosmetics/templates` once per session and revalidate it with `If-None-Match`. Render `cosmetics` from the lookup with it (§11).
6. Open `GET /v1/events/players?uuids=…` for the players you render (§13). Apply `emote`, `skin`, `cape` and `cosmetics` events live.
7. Emote wheel: `GET /v1/me/cosmetics` → `emotes` lists the unlocked ones. Play one with `POST /v1/emotes/play`, then start the animation locally right away (§12).

---

## 11. Cosmetics

Cosmetics are 3D items worn in four **slots**. Each slot holds at most one item:

| Slot | Items |
|---|---|
| `hat` | hats, crowns, helmets |
| `wings` | wings |
| `back` | backpacks and other items on the back |
| `aura` | halos (small models) **or** particle effects |

In addition, **emotes** (§12) are unlockable items of type `emote`. They are never equipped.

An item is a **template** plus a **texture**:

- The **template** is a fixed 3D shape. It is either a voxel model made of cubes, or a particle definition.
- The **texture** is a PNG laid out to the template's UV net. It can be animated, like capes.

All clients (launcher and mod) render from the same template data, which the server publishes at `GET /v1/cosmetics/templates`.

Unlocking works exactly like capes:

- `free` items: everyone.
- `code` and `admin` items: holders of a grant or a redeemed code. Admins can use every built-in item.
- Own uploads (`owner`): while `pending` or `approved`, never once `rejected`.

### 11.1 Template JSON

`GET /v1/cosmetics/templates` returns this. The source is `api/assets/cosmetics/templates.json`.

```json
{
  "version": 1,
  "templates": [
    {
      "id": "wings", "name": "Flügel", "kind": "model", "slot": "wings",
      "textureWidth": 64, "textureHeight": 32,
      "cubes": [
        { "from": [-16, -13, -3], "to": [-1, 5, -2], "uv": [0, 0],  "attach": "back", "pivot": [-1, 0, -2.5], "anim": "flap" },
        { "from": [1, -13, -3],   "to": [16, 5, -2], "uv": [32, 0], "attach": "back", "pivot": [1, 0, -2.5],  "anim": "flap" }
      ]
    },
    {
      "id": "orbit", "name": "Partikel-Wirbel", "kind": "particles", "slot": "aura",
      "textureWidth": 16, "textureHeight": 8,
      "particles": {
        "pattern": "orbit", "attach": "root", "center": [0, 16, 0], "radius": 12, "count": 8,
        "speedDegPerSec": 60, "height": 10, "periodMs": 4000, "size": 2.5, "orientation": "billboard",
        "sprites": [ { "uv": [0, 0], "size": [8, 8] }, { "uv": [8, 0], "size": [8, 8] } ]
      }
    }
  ]
}
```

| Field | Meaning |
|---|---|
| `id` | `^[a-z][a-z0-9_]{0,31}$`. Stable. |
| `kind` | `model` (cubes) or `particles`. Particle templates always have slot `aura`. |
| `textureWidth`, `textureHeight` | Texture size at **scale 1**, in texels (8–128). A real texture is `textureWidth·scale × textureHeight·scale·frames` pixels. |
| `cubes[]` | 1–32 cubes. See §11.2 and §11.3. |
| `cubes[].from`, `cubes[].to` | Opposite corners `[x, y, z]` in model units (§11.2). Multiples of 0.5. `to − from` is a whole number from 1 to 32 on every axis. |
| `cubes[].uv` | `[u, v]`: top-left corner of the cube's UV net in the texture, at scale 1 (§11.3). |
| `cubes[].attach` | `head`, `body` or `back`: the frame the cube lives in (§11.2). |
| `cubes[].pivot` | `[x, y, z]` in the same frame. Required for `flap` and `spin`. |
| `cubes[].anim` | Optional: `flap`, `bob` or `spin` (§11.4). |
| `particles` | Particle definition (§11.5). |

Templates **never change** after release. A new shape gets a new id, so uploaded textures always stay valid. The server checks at startup that every net lies inside the texture and that no two nets overlap.

`GET /v1/cosmetics/templates` needs no auth. It sends `ETag` and `Cache-Control: public, max-age=300`. Send `If-None-Match` to get `304`.

`GET /v1/cosmetics/templates/{id}` returns `{ "template": { … } }` for one template.

`GET /v1/cosmetics/templates/{id}.png?scale=1..4` returns a **paint guide** PNG (no auth). It is exactly the texture size at that scale. Every used face is filled with a colour (top light blue, bottom dark blue, right orange, front red, left green, back purple, particle sprites yellow) and has a darker 1-pixel border. Everything transparent in the guide is never rendered.

### 11.2 Coordinate system and attach points

One **model unit** is one skin pixel, 1/16 block. The axes are right-handed and fixed to the player:

- **+x** = the player's **left**, −x = the player's right
- **+y** = up
- **+z** = the player's **front**, the direction the face looks

These are the same axes three.js and skinview3d use for the player (the player faces +z, the right arm is at −x). World-space in Minecraft matches them too for a player with body yaw 0 (facing south).

Every cube is given in the frame of its `attach` point. The frame moves and rotates with that body part:

| attach | Origin | Occupied by the vanilla part | Follows |
|---|---|---|---|
| `head` | Head pivot = the neck joint, the centre of the bottom face of the head | Head: x −4…4, y 0…8, z −4…4. The hat layer extends 0.5 further. | head yaw and pitch |
| `body` | Body pivot = the centre of the top face of the torso (also at the neck) | Torso: x −4…4, y −12…0, z −2…2 | body, including sneak lean |
| `back` | The body frame moved to the back surface: body (0, 0, −2) | Torso: z 0…4, so **z < 0 is behind the player** | body |
| `root` | Particles only: the player's feet (entity position). No pitch or roll. | – | body yaw |

In the standing pose, with the feet at y = 0 in `root`:

```
          side view, player looks to the right (+z)

   y=32 ─ ┌────────┐
          │  head  │          head frame:  origin = (0, 24, 0) in root
   y=24 ─ ├──┬─────┤ ◄─ neck  body frame:  origin = (0, 24, 0) in root
          │  │body │          back frame:  origin = (0, 24, −2) in root
          │  │     │                        (= back surface of the torso)
   y=12 ─ ├──┴─────┤
          │  legs  │
    y=0 ─ └────────┘ ◄─ root origin (feet)
          z=−2    z=+2
          ▲ back   ▲ front
```

Examples from the bundled templates:

- `crown`: `[-5, 7, -5]…[5, 11, 5]` on `head`, a 10×4×10 ring around the top of the head.
- `wings`: two 15×18×1 plates at z −3…−2 on `back`, one unit behind the back.

**Conversion to vanilla `ModelPart` space** (y points down, front is −z): `x' = x`, `y' = −y`, `z' = −z`.

- A `head` cube is `texOffs(u, v).addBox(from.x, −to.y, −to.z, w, h, d)` on the head part.
- A `body` cube is the same call on the body part.
- A `back` cube goes on the body part with z shifted: `addBox(from.x, −to.y, 2 − to.z, w, h, d)`.
- Here `w, h, d = to − from`, and the part's texture size is `textureWidth × textureHeight`. Use no mirror.

### 11.3 UV net and texture layout

Every cube uses the **vanilla box UV net**, the same as `ModelPart` `texOffs(u, v)` and the skin's head. For a cube of size **w** (x) × **h** (y) × **d** (z) with `uv = [u, v]`:

```
        u        u+d       u+d+w     u+2d+w    u+2d+2w
   v    ┌─────────┬─────────┬─────────┐
        │ (empty) │   top   │ bottom  │          height d
   v+d  ├─────────┼─────────┼─────────┼─────────┐
        │  right  │  front  │  left   │  back   │  height h
        │  (−x)   │  (+z)   │  (+x)   │  (−z)   │
 v+d+h  └─────────┴─────────┴─────────┴─────────┘
          width d   width w   width d   width w
```

Orientation of each region, as texel `(i, j)` from the region's top-left corner:

| Face | Region (x, y, w, h) | i → (right in the texture) | j ↓ (down in the texture) |
|---|---|---|---|
| top (+y) | (u+d, v, w, d) | +x | +z (towards the front) |
| bottom (−y) | (u+d+w, v, w, d) | +x | +z (same as top; vanilla does this) |
| right (−x) | (u, v+d, d, h) | +z (back → front) | −y |
| front (+z) | (u+d, v+d, w, h) | +x (player's right → left) | −y |
| left (+x) | (u+d+w, v+d, d, h) | −z (front → back) | −y |
| back (−z) | (u+2d+w, v+d, w, h) | −x | −y |

Put another way, looking at a face from outside with up = +y, the region is not mirrored.

Precisely, the centre of texel `(i, j)` at scale `k` lies at:

| Face | Point (x, y, z) |
|---|---|
| front | (x0 + (i+½)/k, y1 − (j+½)/k, z1) |
| back | (x1 − (i+½)/k, y1 − (j+½)/k, z0) |
| right | (x0, y1 − (j+½)/k, z0 + (i+½)/k) |
| left | (x1, y1 − (j+½)/k, z1 − (i+½)/k) |
| top | (x0 + (i+½)/k, y1, z0 + (j+½)/k) |
| bottom | (x0 + (i+½)/k, y0, z0 + (j+½)/k) |

Here `(x0, y0, z0) = from` and `(x1, y1, z1) = to`. The bundled textures are painted with this exact mapping (`scripts/generate-cosmetics.mjs`), and so are the previews.

**Texture file:**

- The file is `textureWidth·scale` × `textureHeight·scale·frames` pixels.
- Animated textures are a vertical strip, with frame 0 at the top.
- `scale` is 1–8 for built-ins and 1–2 for uploads.
- **Normalised UV** of a net coordinate `(u, v)` (scale-1 units) in frame `f`:
  - `u' = u / textureWidth`
  - `v' = (f·textureHeight + v) / (textureHeight·frames)`
  - The scale does not appear in the formula.
- **Frame:** `f = floor(currentTimeMillis / frameTimeMs) mod frames`, the same as for capes.

**Rendering rules for model templates:**

- Alpha test: texels with alpha < 128 are invisible. Everything else is opaque. The server stores model textures with alpha 0 or 255 only.
- Draw **both sides** of every face (no back-face culling), so open shapes like the crown look right from inside. In Minecraft use `RenderType.entityCutoutNoCull`.
- `emissive: true` means render at full brightness and ignore world light: `LightTexture.FULL_BRIGHT` in Minecraft, `MeshBasicMaterial` in three.js. Only built-in items can be emissive.

### 11.4 Animations (`cubes[].anim`)

`t` is the wall clock in milliseconds (`currentTimeMillis`), so every client shows the same phase.

| anim | Effect |
|---|---|
| `bob` | Translate the cube along y by `0.5 · sin(2π · t / 2000)` units. |
| `spin` | Rotate around the vertical axis through `pivot` by `θ = 2π · (t mod 4000) / 4000`. |
| `flap` | Rotate around the vertical axis through `pivot` by `θ = s · 15° · (1 − cos(2π · t / 1600))`. `s = +1` if the cube's centre x is greater than `pivot.x` (left wing), otherwise `−1`. θ goes from 0° to 30°, so the wings fold backwards and open again. |

The rotation is right-handed around +y, applied relative to the pivot:

- `x' = x·cos θ + z·sin θ`
- `z' = −x·sin θ + z·cos θ`

A positive θ turns +x towards −z. In `ModelPart` space (y and z negated), the same rotation is `yRot = −θ`, with the pivot at `(pivot.x, −pivot.y, −pivot.z)` (for `back`: `2 − pivot.z`).

### 11.5 Particle templates (`kind: "particles"`)

The texture holds **sprites** (`sprites[]`, regions in scale-1 units). Each particle is a quad showing one sprite:

- The **longer side** of the quad is `size` model units, and the sprite's aspect ratio is kept.
- It is alpha-blended, not alpha-tested, so soft alpha is allowed.
- It is emissive only if the item is.

`orientation` sets how the quad lies:

- `billboard`: the quad always faces the camera.
- `ground`: the quad lies flat, 0.01 blocks above the feet. The sprite's top edge points in the player's facing direction at spawn. Its left edge (texel column 0) is on the player's left (+x).

Positions are in the frame of `attach` (`root`, `head` or `body`, §11.2). `t` is the wall clock in seconds.

**`ring`**: `count` particles that always exist, on a horizontal circle:

- `pos_i(t) = center + (r · sin φ_i, 0, r · cos φ_i)`
- `φ_i(t) = 2π · i / count + rad(speedDegPerSec) · t`
- `i = 0…count−1`, and `r = radius`.
- φ = 0 is straight ahead (+z) and φ = 90° is the player's left (+x).
- Particle `i` shows `sprites[i mod sprites.length]`.

**`orbit`**: the same as `ring`, plus a vertical wave:

- `pos_i(t).y = center.y + height · sin(2π · t_ms / periodMs + 2π · i / count)`

**`trail`**: particles left behind in the world. They do **not** follow the player.

- Each time the anchor has moved `spacingBlocks` blocks horizontally since the last spawn, spawn particle number `k`, counting per player from 0.
- It spawns at `anchor + offset + (side · sideOffset, 0, 0)` in the anchor frame at that moment. `side = +1` (left) for even `k` and `−1` (right) for odd `k`.
- It uses `sprites[k mod sprites.length]`. For `trail`, sprite 0 is the left foot and sprite 1 the right foot.
- It lives `lifetimeMs` milliseconds. Its alpha fades linearly from 1 to 0.
- At most `maxParticles` are alive per player. The oldest is dropped first.
- Nothing spawns while the player is not moving.

Animated particle textures use the same frame formula as models. All particles of an item show the same frame.

### 11.6 CosmeticView

```json
{
  "id": "redstone_wings",
  "name": "Redstone-Flügel",
  "slot": "wings",
  "kind": "builtin",
  "unlock": "code",
  "status": "approved",
  "template": "wings",
  "texture": {
    "url": "https://api.theredstonee.de/v1/cosmetics/redstone_wings.png?v=1c0ffee0dead",
    "width": 128, "height": 64, "scale": 2,
    "animated": true, "frames": 8, "frameTimeMs": 120
  },
  "emissive": true,
  "emote": null
}
```

| Field | Meaning |
|---|---|
| `id` | `^[a-z0-9][a-z0-9_-]{0,39}$`. Uploads use `c` followed by 20 hex digits. Emotes use their emote id (§12). |
| `slot` | `hat` \| `wings` \| `back` \| `aura` \| `emote` |
| `kind`, `unlock`, `status` | As for capes (§5.1). Emotes are always `builtin`. |
| `template` | Template id, or `null` for emotes. |
| `texture` | `null` for emotes. `width`/`height` are the size of **one frame** in pixels (`textureWidth·scale` × `textureHeight·scale`). `url` works like a cape URL (§5.4). |
| `emissive` | Render at full brightness (§11.3). |
| `emote` | `{ "durationMs", "loop" }` for emotes, otherwise `null`. |

**LookupCosmetic** is the flat form used in the lookup and in events: `{ id, template, url, scale, animated, frames, frameTimeMs, emissive }`.

### 11.7 Endpoints

| Request | Auth | Response |
|---|---|---|
| `GET /v1/cosmetics` | yes | `{ templates: [Template], cosmetics: [CosmeticView + { owned, equipped, rejectReason? }] }`. Lists all built-in items and emotes, plus your own uploads in any status. `rejectReason` is present only for uploads. |
| `GET /v1/cosmetics/templates` | no | See §11.1. |
| `GET /v1/cosmetics/templates/{id}` / `{id}.png?scale=k` | no | One template, or its paint guide (§11.1). `404 template_not_found`. |
| `GET /v1/me/cosmetics` | yes | `{ equipped: { hat, wings, back, aura }, emotes: [emoteId] }`. Each slot is a CosmeticView or `null`, as you see it (including your pending uploads). `emotes` lists the emotes you may play, in list order. |
| `PUT /v1/me/cosmetics` | yes | Body `{ "hat"?: id\|null, "wings"?: id\|null, "back"?: id\|null, "aura"?: id\|null }`, with at least one key. An id equips, `null` takes the item off, and a missing key leaves the slot unchanged. **200** has the same shape as `GET /v1/me/cosmetics`. All changes are checked first and then applied together. |
| `GET /v1/cosmetics/{id}.png` | optional | The texture. Caching, `ETag`/`304` and the pending/private rules are the same as §5.4. Otherwise `404 cosmetic_not_found`. |
| `GET /v1/cosmetics/{id}` | optional | `{ cosmetic: CosmeticView }`. Same visibility as the texture. |
| `POST /v1/cosmetics/upload?template=<id>&name=<opt>&frameTimeMs=<opt>` | yes | Raw PNG body (`Content-Type: image/png`, at most 512 KiB). **201** `{ cosmetic }` with `status: "pending"`. |
| `DELETE /v1/cosmetics/{id}` | yes | Deletes your own upload. **204**, or `404 cosmetic_not_found`. |
| `POST /v1/cosmetics/{id}/report` | yes | `{ reason, note? }` as §5.8. Only `approved` uploads of other users. **204**, or `404 cosmetic_not_found`. |
| `POST /v1/redeem` | yes | §5.9. It also unlocks cosmetics and emotes. |

`PUT /v1/me/cosmetics` errors:

| HTTP | code | Meaning |
|---|---|---|
| 404 | `cosmetic_not_found` | Unknown id, someone else's upload, or an unknown template. |
| 400 | `wrong_slot` | The item belongs to another slot. This includes emotes, which can't be equipped. |
| 403 | `cosmetic_locked` | Not unlocked, a rejected upload, or a retired built-in item you don't wear. |

**Upload rules:**

- `template` is required. An unknown template returns `400 unknown_template`.
- `name` follows the cape name rules (§5.6). The default is `"<template name> (eigene)"`.
- Size: width = `textureWidth · k` with **k = 1 or 2**. Height = `textureHeight · k · frames` with **1–16 frames**. Anything else returns `400 invalid_dimensions`.
- For more than one frame, `frameTimeMs` (50–10000) is required. Without it you get `400 frame_time_required`. For one frame it is ignored.
- The PNG structure checks are the same as for capes (§5.6): `invalid_png`, `animated_png`, no data after `IEND`, bomb protection.
- The server re-encodes the image as RGBA:
  - Every pixel **outside the template's used areas** is set to transparent.
  - Fully transparent pixels are zeroed.
  - For model templates, alpha becomes 0 or 255 (the cutoff is 128).
- If nothing visible remains, you get `400 empty_cosmetic`.
- `409 too_many_pending` (at most 3 pending), `409 upload_limit` (at most 10 that aren't rejected), `409 duplicate_cosmetic` (same texture for the same template).
- Uploads start as `pending`. The uploader sees and wears them right away. Nobody else sees them until an admin approves them.
- Uploads are never emissive.

**Recommended client behaviour** (the server doesn't enforce this):

- Hide the cape while a `back` item is worn.
- Hide `wings` while an elytra is worn.
- Hide `hat` items while a helmet is visible.
- Respect the player's own "hide cosmetics" setting in the mod.

### 11.8 Built-in items

| id | Name | Template | Unlock | Animated |
|---|---|---|---|---|
| `redstone_crown` | Redstone-Krone | `crown` | code | 8 × 120 ms, emissive |
| `team_crown` | Team-Krone | `crown` | **admin** | 8 × 150 ms, emissive |
| `trs_cap` | TRS-Cap | `cap` | free | – |
| `lamp_helmet` | Redstone-Lampen-Helm | `lamp_helmet` | free | 8 × 150 ms, emissive |
| `top_hat` | Zylinder | `tophat` | free | – |
| `redstone_wings` | Redstone-Flügel | `wings` | code | 8 × 120 ms, emissive |
| `dragon_wings` | Drachenflügel | `wings` | free | – |
| `backpack` | Rucksack | `backpack` | free | – |
| `halo` | Heiligenschein | `halo` (aura) | code | 8 × 125 ms, emissive |
| `redstone_aura` | Redstone-Partikel-Aura | `orbit` (aura) | free | 4 × 150 ms, emissive |
| `footprints` | Fußspuren | `trail` (aura) | free | – |

Templates without a built-in item (`ring`) are available for uploads.

---

## 12. Emotes

Emotes are a **fixed list**. Launcher and mod contain the animations; the server only knows the ids. Ids never change. Clients ignore ids they don't know.

| id | Name | Unlock | `durationMs` | `loop` |
|---|---|---|---|---|
| `winken` | Winken | free | 2000 | no |
| `klatschen` | Klatschen | free | 2500 | no |
| `jubeln` | Jubeln | free | 2500 | no |
| `verbeugen` | Verbeugen | free | 2000 | no |
| `facepalm` | Facepalm | free | 2000 | no |
| `schulterzucken` | Schulterzucken | free | 1500 | no |
| `daumen_hoch` | Daumen hoch | free | 1500 | no |
| `tanzen` | Tanzen | code | 6000 | yes |
| `salutieren` | Salutieren | code | 2000 | no |
| `luftgitarre` | Luftgitarre | code | 5000 | yes |
| `redstone_tanz` | Redstone-Tanz | admin | 6000 | yes |

Emotes appear in `GET /v1/cosmetics` with `slot: "emote"` and in `GET /v1/me/cosmetics` → `emotes`. They are unlocked with codes (`cosmeticId` = emote id) or admin grants, like other cosmetics.

### 12.1 `POST /v1/emotes/play`

Auth required.

```json
{ "emote": "winken" }
```

**200**
```json
{ "emote": "winken", "durationMs": 2000, "at": "2026-09-23T18:10:00.000Z" }
```

- The limit is **one emote per 2 seconds** (`429` with `Retry-After`). Unknown or locked emotes don't count against it.
- The server sends an `emote` event to every stream that watches this player (§13).
- The sender's own streams get the event only if they watch the sender's own UUID.

| HTTP | code |
|---|---|
| 404 | `emote_not_found` |
| 403 | `emote_locked` |
| 429 | `rate_limited` |

How to play an emote:

- The sender starts the animation locally right away.
- Receivers start it when the event arrives.
- The animation runs for `durationMs`. When `loop` is set, repeat the animation cycle until `durationMs` is over.
- Stop early when the player moves (horizontal speed > 0.05 blocks/tick), attacks, or starts another emote.

---

## 13. Player events (SSE for the mod)

### 13.1 `GET /v1/events/players?uuids=<uuid>,<uuid>,…`

Auth required. `uuids` holds 1–200 UUIDs, comma-separated, dashed or not. Duplicates count once. Invalid input returns `400 invalid_request`.

The response is `Content-Type: text/event-stream`, in the same frame format as §7. It only delivers events **about the listed players**.

**To change the set**, open a new stream with the new list, then close the old one. Debounce this: at most every 5 s.

| event | data |
|---|---|
| `hello` | `{"type":"hello","keepaliveSec":25,"watching":<n>}` (first message) |
| `ping` | `{}` every 25 s |
| `emote` | `{"type":"emote","uuid":"…","emote":"winken","durationMs":2000,"at":"…"}` |
| `skin` | `{"type":"skin","uuid":"…","at":"…"}`. The player changed their Mojang skin. Load it again from Mojang or `GET /v1/skins/by-uuid/{uuid}`. |
| `cape` | `{"type":"cape","uuid":"…","cape":LookupCape\|null}`. The cape as **you** may see it (§4.1 rules). |
| `cosmetics` | `{"type":"cosmetics","uuid":"…","cosmetics":{"hat":…,"wings":…,"back":…,"aura":…}}`. All four slots as **you** may see them. |

Rules:

- Events are sent **only on changes**:
  - equip or unequip
  - cape change
  - approval or rejection of an item the player wears
  - admin revocation
  - a change of `showCapeToOthers` or `showCosmeticsToOthers`
  - emote
  - `skin-changed`
- A player who has **blocked you** never produces events for you. Banned players produce none.
- For your own UUID you get your own view (including pending uploads).
- A stream lasts **at most 1 hour**. It closes immediately on logout-all, a ban or account deletion.
- Reconnect with backoff (1 s, 2 s, 5 s, … up to 60 s).
- After a reconnect, run a lookup (§4.1) again, because events are not replayed.
- Each account can have at most **3** parallel player streams. A fourth gets `503 too_many_streams`.
- Connects are limited to 20 per minute.

### 13.2 What the mod should watch

Watch the UUIDs of players you currently render (tab list or tracked entities) that appeared in the lookup, plus your own UUID.

### 13.3 Launcher

The launcher can open the same stream for its own UUID, so its preview updates when the mod changes something. This is optional.

### 13.4 `POST /v1/me/skin-changed`

Auth required. The body is empty or `{}`. Returns **204**.

Call it right after the client changed the account's skin at Mojang. The server then:

- drops its cached skin for this account (§14)
- sends a `skin` event to every stream that watches the player

The limit is 6 per minute.

---

## 14. Skins (Mojang proxy with cache)

Launcher and mod can also call Mojang directly. These endpoints exist so that many clients don't send the same Mojang requests.

| Request | Response |
|---|---|
| `GET /v1/skins/by-name/{name}` | **200** SkinView |
| `GET /v1/skins/by-uuid/{uuid}` | **200** SkinView |

**SkinView:**

```json
{ "uuid": "5ce0000000000000000000000000abcd", "name": "Skinny", "model": "slim",
  "textureUrl": "https://textures.minecraft.net/texture/5ce1…", "capeUrl": null }
```

- Auth is required. The limit is 30 per minute per account.
- `name` must be a valid Minecraft name, and `uuid` a UUID. Otherwise the answer is `404 not_found`.
- `model` is `classic` or `slim`.
- `textureUrl` and `capeUrl` are always `https://textures.minecraft.net/texture/<hex>`. The server rewrites Mojang's `http://` and drops anything else.
- `textureUrl: null` means the player uses a default skin. Pick it from the UUID like vanilla does.
- `capeUrl` is the **Mojang** cape, not the TRS one.

Errors:

- `404 player_not_found`: there is no such account.
- `502 upstream_unavailable`: Mojang is down.
- `429 rate_limited`: your account limit, or the global Mojang budget, is used up.

Caching:

- Names are cached for 10 minutes and profiles for 2 minutes. Unknown names and UUIDs are cached for 2 minutes.
- `POST /v1/me/skin-changed` drops the cached profile.
- Parallel requests for the same key share one Mojang call.
- The cache lives in memory only.

---

## 15. Website sign-in (admins, confirmed in the launcher)

Admins sign in to the website without a password: the website shows a short code, and the admin confirms it in the TRS Launcher, which is already signed in with their Minecraft account.

| Request | Body | Response |
|---|---|---|
| `POST /v1/web-login/start` | – | `{ code: "7K3P-QX9M", pollSecret, expiresAt }`. The code is valid for **5 minutes**. Limit: 10 per 10 minutes per IP. |
| `POST /v1/web-login/approve` | `{ code: string≤20 }` | **204**. Bearer auth (launcher token). `403 not_admin` for non-admins, `400 invalid_code` for a malformed code, `404 invalid_code` for an unknown or used code, `410 expired`. Limit: 10 per 10 minutes per account. |
| `POST /v1/web-login/poll` | `{ pollSecret }` | `{ status: "pending" }`, `{ status: "expired" }` or `{ status: "approved", name, csrf, expiresAt }`. On approval the session is set as cookie `trs_admin` (httpOnly, Secure, SameSite=Strict, 8 hours). Each code yields one session only. Limit: 90 per minute per IP. |
| `GET /v1/web-login/me` | – | `{ name, uuid, csrf }` for a valid cookie session, else `401 unauthorized`. |
| `POST /v1/web-login/logout` | – | **204**. Needs the `X-CSRF-Token` header. |

- Codes use Crockford Base32 (no I, L, O, U). Input is normalised: case and dashes don't matter, `O` reads as `0`, `I`/`L` as `1`.
- Only hashes of the code, the poll secret and the session token are stored.
- With the cookie, every `/v1/admin/*` endpoint works as for an admin bearer token. **Mutating requests need `X-CSRF-Token`** (`403 csrf_failed` otherwise). A request with an `Authorization` or `X-Admin-Key` header ignores the cookie.
- The cookie also lets the admin see `pending` and `rejected` cape textures (`GET /v1/capes/{id}.png`), read-only.
- Losing admin rights or getting banned ends the session on the next request.

## 16. Website data

Public, cached for 5 minutes (`Cache-Control: public, max-age=300`). Used by the website itself.

| Request | Response |
|---|---|
| `GET /v1/site/releases` | `{ release: { version, tag, publishedAt, pageUrl, assets: [{ platform: "windows"|"appimage"|"deb"|"rpm", name, url, size }] } | null }`. The newest `v*` GitHub release (not drafts, not the `updater`/`client-mod` channels). Only GitHub download URLs of this repository. |
| `GET /v1/site/blog` | `{ posts: [{ version, date, title: { en, de } | null, headlines: { en: [], de: [] } }] }` from `CHANGELOG.md` on `main`, newest first, released versions only. |
| `GET /v1/site/blog/{version}` | `{ post: … + blocks: { en: PostBlock[], de: PostBlock[] } }`, or `404 not_found`. A PostBlock is `{ kind: "text", markdown }` or `{ kind: "image", src, caption }`; image `src` points to `raw.githubusercontent.com`. |
| `GET /v1/site/capes` | `{ capes: [{ id, name, unlock, url, scale, frames, frameTimeMs }] }`. Approved built-in capes only; `url` is relative (`/v1/capes/<id>.png?v=…`). |

If GitHub is unreachable, the last good answer is kept. Without one, `release` is `null` and `posts` is empty.

---

## 17. Sync (own skins, presets, theme and language)

Keeps the launcher's own skin library ("My skins"), the user's own mod presets and the launcher settings **theme (with accent colour) and language** the same on every device of a Minecraft account. Java and memory options are **never** synced.

The launcher only calls these endpoints when the TRS services are on (consent) **and** the switch "Sync with TRS account" is on (Settings → Privacy, on by default).

- Auth: Bearer token like every `/v1/me/*` route.
- Rate limit: every `/v1/me/sync*` request counts against its own bucket of **120 / min** per account; skin uploads (`PUT …/skins/{id}`) additionally **30 / min**. The regular read/write buckets are not touched, so a big first sync can't block other actions.
- Only the owner can read their data. There is no admin route for it.
- `DELETE /v1/me` deletes all of it (§3.3).

### 17.1 `GET /v1/me/sync`

```json
{
  "skins": [ { "id": "a1b2c3d4e5f6", "name": "Mein Skin", "variant": "slim", "sha256": "…64 hex…", "updatedAt": "2026-09-25T10:00:00.000Z" } ],
  "deletedSkins": [ { "id": "0a1b2c3d4e5f", "deletedAt": "2026-09-24T08:00:00.000Z" } ],
  "presets": { "data": { … }, "updatedAt": "…" },
  "settings": { "data": { "theme": "dark", "accent": "redstone", "language": "de" }, "updatedAt": "…" }
}
```

- `skins`: metadata only, ordered by `updatedAt`. Download the image with §17.2.
  - `id`: 12 lowercase hex digits (the launcher library's id).
  - `variant`: `classic` or `slim`.
  - `sha256`: hex SHA-256 of the **re-encoded** PNG the server stores and serves. Compare it with the hash of the downloaded file, not of the file you uploaded.
  - `updatedAt`: server time of the last `PUT` or `PATCH`.
- `deletedSkins`: deletion markers (tombstones) of the last **30 days**, so other devices can delete the skin locally too. At most 500 per account (the oldest are dropped). An id is never in both lists.
- `presets` / `settings`: `null` until first written. `updatedAt` is the client time sent with the last accepted write.

### 17.2 Skins

| Request | Body | Response |
|---|---|---|
| `GET /v1/me/sync/skins/{id}.png` | – | **200** `image/png`, `Cache-Control: private, no-store`, `ETag: "<sha256>"`. `404 skin_not_found` for unknown ids and for ids of other accounts. |
| `PUT /v1/me/sync/skins/{id}` | `{ name, variant, png }` | **200** `{ skin: { id, name, variant, sha256, updatedAt } }`. Creates or replaces (idempotent). Removes a tombstone with the same id. |
| `PATCH /v1/me/sync/skins/{id}` | `{ name?, variant? }` (at least one) | **200** `{ skin }`. Rename or change the variant without sending the image again. `404 skin_not_found` if unknown. |
| `DELETE /v1/me/sync/skins/{id}` | – | **204**. Deletes the skin and records a tombstone. An unknown id also gets **204** with a tombstone. |

- A malformed `{id}` (anything but `^[0-9a-f]{12}$`) gets `404 not_found` (`skin_not_found` on the `.png` route).
- `name`: 1–48 characters after trimming, no control characters (C0/C1, line/paragraph separators, bidi controls). Emojis are fine.
- `png`: the PNG file as standard Base64 with padding (no line breaks, no `data:` prefix).
  - **64×64**, or the old **64×32** format (kept as 64×32), at most **128 KiB** as a file.
  - The server checks the structure (like cape uploads, §5.6: CRCs, chunk whitelist, no APNG, nothing after `IEND`) and **re-encodes** it as 8-bit RGBA without any metadata. Pixels stay unchanged, including the colour of transparent pixels.
- At most **60 skins** per account. A new id beyond that gets `409 skin_limit`; replacing an existing id always works. Tombstones don't count.
- The request body can be at most 192 KiB.

**Skin errors:**

| HTTP | code | When |
|---|---|---|
| 400 | `invalid_request` | Schema failed: bad name, variant, Base64, unknown field, empty `PATCH`. |
| 400 | `invalid_png` | Not a PNG, broken, truncated, bad CRC, data after `IEND`, forbidden chunk. |
| 400 | `animated_png` | APNG. |
| 400 | `invalid_dimensions` | Not 64×64 or 64×32. |
| 404 | `skin_not_found` | `GET …png` or `PATCH` of an unknown skin. |
| 409 | `skin_limit` | 60 skins reached. |
| 413 | `payload_too_large` | PNG over 128 KiB, or body over 192 KiB. |

### 17.3 Presets and settings (last writer wins)

| Request | Body | Response |
|---|---|---|
| `PUT /v1/me/sync/presets` | `{ data: <JSON object>, updatedAt: "<ISO>" }` | **200** `{ presets: { data, updatedAt } }` |
| `PUT /v1/me/sync/settings` | `{ data: { theme?, accent?, language? }, updatedAt: "<ISO>" }` | **200** `{ settings: { data, updatedAt } }` |

- `updatedAt` is the time the client changed the data (ISO 8601 with `Z` or offset). The server stores it as sent (millisecond precision) and returns it in UTC.
- **Last writer wins:** if the stored `updatedAt` is **newer** than the one sent, the write is refused with **`409 stale`** and the stored state:
  ```json
  { "error": { "code": "stale", "message": "A newer version is stored on the server",
    "current": { "data": { … }, "updatedAt": "…" } } }
  ```
  Take over `current` locally. The **same** `updatedAt` overwrites (a retry of the same write).
- `updatedAt` more than **24 hours in the future** (a broken clock that would win every sync) gets `400 invalid_request` with `fields[0].path = "updatedAt"`.
- A `PUT` replaces the whole document; there is no merge.
- **Presets:** `data` is any JSON object (no array, no scalar), at most **64 KiB** serialised (`413 payload_too_large` otherwise; request body at most 96 KiB). The launcher decides its structure: only own presets with mod/pack ids and names, **no files, no paths**.
- **Settings:** only the keys `theme` (≤ 32), `accent` (≤ 32) and `language` (≤ 16), each `^[A-Za-z0-9_-]+$` (for example `dark`, `redstone`, `pt-BR`). Any other key gets `400 invalid_request`. All three are optional; `{}` is allowed.

### 17.4 Launcher flow (summary)

After the TRS login at start, after local changes (debounced about 3 s) and every 5 minutes:

1. `GET /v1/me/sync`.
2. Skins:
   - only remote → download the PNG and add it locally;
   - only local and never synced → `PUT`;
   - remote tombstone → delete locally;
   - deleted locally since the last sync → `DELETE`;
   - name or variant differ → the newer `updatedAt` wins (the launcher keeps a local change time).
3. Presets and settings: the newer `updatedAt` wins; on `409 stale` take over `current`.
4. Errors or offline: stay silent, try again later, never block the UI.
