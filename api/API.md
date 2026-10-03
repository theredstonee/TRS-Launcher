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
| Body format | JSON (`Content-Type: application/json`, UTF-8). The only exceptions are the cape and cosmetic uploads, which send raw `image/png`, chat images (§18.7) and shared screenshots (§23), which send raw `image/png`, `image/jpeg` or `image/webp`, and circuit files (§25.6), which send the raw file as `application/octet-stream`. |
| Body size | JSON bodies can be at most **16 KiB**. A cape upload can be at most **5 MiB**, a cosmetic upload at most **512 KiB**, a chat image at most **5 MiB**, a shared screenshot at most **10 MiB**. Sync bodies are larger (§17): a skin upload at most **192 KiB**, presets at most **96 KiB**, notes (§17.5) at most **512 KiB**. Circuit bodies (§25) at most **300 KiB**, circuit files at most **2 MiB**. Anything larger gets `413`. |
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
| 403 | `banned` | The account is banned. Every feature is blocked. Carries `until` and `sanction` (§22.2). |
| 403 | `sanctioned` | A sanction blocks this feature (social, upload or hosting ban). Carries `until` and `sanction` (§22.2). |
| 403 | `forbidden` | Team endpoint called by a non-member. |
| 403 | `admin_only` | Team endpoint or action that only admins may use (§22.1). |
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
| Cape sharing mutations (offer, accept, decline, revoke), per account | 30 / min (on top of the write bucket) |
| Chat: send and edit messages, per account | 30 / min **and** 5 / 5 s (§20.4) |
| Chat: reactions / typing / read-unread-mute, per account | 60 / min / 40 / min / 120 / min |
| Chat: open DMs and group changes, per account | 20 / min |
| `POST /v1/chat/attachments`, per account | 40 / 10 min |
| `GET /v1/chat/attachments/{id}`, per account | 600 / min |
| `GET /v1/servers/status`, per account | 30 / min |
| `POST /v1/reports`, per account | 10 / h |
| `GET /v1/events/me` connects, per account | 20 / min (and at most 5 open streams) |
| Every `/v1/hosting/*` request, per account | 240 / min (own bucket); details in §21.9 |
| Admin, per admin (or API key) | 240 / min |
| Team: create/lift/change sanctions, decide appeals | 60 / min per team member (§22) |
| Team: `GET /v1/admin/search` / bulk actions | 60 / min / 10 / min per team member |
| `POST /v1/me/sanctions/{id}/appeal`, per account | 5 / h (one appeal per sanction) |
| `GET /v1/me/sanctions`, per account | 30 / min |
| `GET /v1/achievements`, per IP | 60 / min |
| `POST /v1/me/achievements/report` / `GET /v1/players/{uuid}/achievements`, per account | 30 / min / 60 / min (§31) |

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
| 403 | `banned` | The account is banned. Details and a one-hour `appealToken` for the appeal routes: §22.3. |
| 429 | `rate_limited` | See §1.3. |
| 502 | `upstream_unavailable` | Mojang is down. Retry with backoff. |

### 2.4 `POST /v1/auth/logout`

Auth required. Body: none, `{}`, or `{ "all": true }`.

- Without `all`, only the current token is revoked.
- With `all: true`, every session of the account is revoked, presence is cleared and open event streams are closed.

Returns **204**.

### 2.5 Admins

An admin is an account whose UUID is in `ADMIN_UUIDS` (fixed) or that an admin gave the role `admin`; admins can also appoint **moderators** (§22.1). `user.admin` is `true` for admins, `user.role` is `"admin"`, `"moderator"` or `null`.

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
  "role": "admin",
  "createdAt": "2026-09-23T18:05:02.000Z",
  "settings": {
    "showBadge": true,
    "showCapeToOthers": true,
    "presenceVisibility": "friends",
    "shareServer": false,
    "showCosmeticsToOthers": true,
    "chatReadReceipts": true,
    "chatTypingIndicator": true
  },
  "activeCape": null,
  "events": []
}
```
`activeCape` is either `null` or a **CapeView** (§5.1). `events` lists the ids of the events that are **active for you** – switched on globally **or** you were allowed individually (§32), e.g. `["halloween"]`. Older servers don't send it: treat a missing field as `[]`. Changes arrive as `events_changed` on `GET /v1/events/me` (§19).

| Setting | Default | Effect |
|---|---|---|
| `showBadge` | `true` | Others see the TRS badge in the lookup **while you're playing with TRS** (live badge, §4.1). |
| `showCapeToOthers` | `true` | Others see the active cape. You always see your own. |
| `presenceVisibility` | `"friends"` | `"friends"`: friends see your online state. `"nobody"`: you always appear offline in the friends list. This does **not** affect the live badge – that is `showBadge`. |
| `shareServer` | `false` | Friends see the server address while you're `in-game`. |
| `showCosmeticsToOthers` | `true` | Others see your equipped cosmetics (§11). You always see your own. Emotes are sent regardless. |
| `chatReadReceipts` | `true` | Send read receipts in chat (§18.5). **Mutual:** turned off, you also don't see other people's read receipts. |
| `chatTypingIndicator` | `true` | Send "is typing" (§18.5). **Mutual:** turned off, you also don't see others typing. |

### 3.2 `PATCH /v1/me`

Auth required. Send any non-empty subset of `settings`:

```json
{ "shareServer": true, "presenceVisibility": "nobody" }
```

**200** returns the same shape as `GET /v1/me`.

- Turning `shareServer` off drops the stored server address immediately.
- When visibility changes, friends get a `presence` event.
- Your other devices get a `settings` event (§19).

### 3.3 `DELETE /v1/me` (GDPR Art. 17)

Auth required. Deletes everything immediately:

- account, sessions, friendships, requests and blocks (both directions)
- uploaded capes and cosmetics and their files
- equipped cosmetics, cape and cosmetic grants
- cape shares (§5.10): capes friends shared with you (and everything you re-shared from them), and every share of your own uploads
- code redemptions, reports and presence
- all sync data (§17): skins with their images, deletion markers, presets and settings, notes (§17.5)
- chat (§18.8): all DMs of the account for **both** sides, own messages, reactions and images in groups, pending uploads; owned groups go to the longest member, empty groups are deleted
- world hosting (§21.9): hosted worlds are closed, memberships in other worlds and the account's own and foreign ban-list entries are removed
- shared screenshots (§23): all links of the account and their images
- achievements (§31): unlocks, counters, playtime and streak

Only an existing **ban record** and an **active chat mute** survive (keyed by UUID) so they can't be escaped by re-registering. Chat reports **against** the account stay with their evidence until their retention ends (§20.3).

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
- **Capes a friend shared with you** (§5.10): after you accepted the offer, while the cape is `approved` and until the share is revoked.

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
          "format": 2,
          "model": "https://api.theredstonee.de/v1/cosmetics/redstone_crown/model.json?v=9b1f0c77aa21",
          "url": "https://api.theredstonee.de/v1/cosmetics/redstone_crown.png?v=9b1f0c77aa21",
          "scale": 8,
          "animated": false,
          "frames": 1,
          "frameTimeMs": null,
          "glow": "https://api.theredstonee.de/v1/cosmetics/redstone_crown/glow.png?v=9b1f0c77aa21",
          "glowFrames": 12,
          "glowFrameTimeMs": 140,
          "hash": "9b1f0c77aa21"
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

`cosmetics` always has the four keys `hat`, `wings`, `back` and `aura`. Each is `null` or a **LookupCosmetic**. A format-1 item has `template`: render it with that template from `GET /v1/cosmetics/templates` (§11). It also has `templateUrl` (`/v1/cosmetics/<id>/template.json?v=<hash>`, §11.1) so a client can load that one template when it is not bundled. Older clients ignore `templateUrl` and keep using `template` with the copy they already have. A **format-2** item (`"format": 2`, 3D model, §11.9) has **no** `template`, no `templateUrl` and no `emissive`, but `model`, `glow`, `glowFrames`, `glowFrameTimeMs` and `hash`. The texture and frame fields work exactly like the cape fields. Clients that only know templates must skip items without a known `template` (older TRS Clients do).

Privacy rules, enforced server-side:

- Banned accounts are never returned.
- Accounts that **blocked the requester** are never returned.
- `badge` is **live**, see below.
- `cape` is set only when the player has an active cape **and** `showCapeToOthers` is on **and** the cape is `approved`.
- For your **own** UUID you also get your own `pending` upload, and your cape even with `showCapeToOthers=false`.
- Cosmetics follow the same rules: others get a slot only if the player has `showCosmeticsToOthers` on **and** the item is `approved`. You always see your own equipped items, including `pending` uploads.

**Live badge.** The badge means "plays with TRS right now". `badge` is `true` only if **all** of these hold:

1. The player has `showBadge=true`.
2. The player is **in game with TRS** right now: their presence (§4.2) is `in-game`, reported either by the TRS Client (`via: "client"`, while in a world or on a server) or by the TRS Launcher (`via: "launcher"`, while a game it started for this account runs). A launcher that is merely open (`online`) does **not** count, so a TRS user who plays with another client shows no badge.
3. The **requester is in game themselves** (their own presence is `in-game`). Otherwise others' badges are always `false`. This keeps "is playing right now" between people who play at the same time – typically players on the same server. Your **own** badge only needs rules 1 and 2.

Because of rule 3 the mod must send its own `in-game` heartbeat **before** the first lookup in a world, and look everyone up again once it is in game. `presenceVisibility` is not involved: it only controls what friends see in the friends list.

Suggested client behaviour:

- Batch the UUIDs of visible players.
- Cache results for about 5 minutes per UUID.
- Refresh when players join the tab list.
- For live changes (emotes, skin, cape, cosmetic and badge changes), subscribe to the visible players with `GET /v1/events/players` (§13) instead of polling.
- Players who join often send their first heartbeat a moment after you see them. Ask again once, about 10 s after a new player appeared without a badge.

### 4.2 `POST /v1/presence` (heartbeat)

Auth required. Send it **at least every 60 s**. A report expires **180 s** after its last heartbeat. The limit is 6 per minute.

```json
{ "state": "in-game", "via": "client", "game": { "version": "1.21.1", "loader": "fabric", "server": "play.example.net:25565" } }
```

| Field | Values |
|---|---|
| `state` | `online` (launcher open), `in-game`, `offline` (clears immediately, see below) |
| `via` | Optional. `launcher` (TRS Launcher) or `client` (TRS Client mod). |
| `game.version` | `^[0-9A-Za-z._+ -]{1,32}$` |
| `game.loader` | `vanilla` \| `fabric` \| `quilt` \| `forge` \| `neoforge` |
| `game.server` | Optional host or IPv4, with an optional `:port`. No scheme, no path. |

- `game.server` is **stored only if** the user has `shareServer=true` and `state` is `in-game`. Otherwise it is silently dropped.
- The server address is stored lower-cased.

**Two sources.** The launcher and the mod report separately; each report has its own 180 s expiry and they never overwrite each other. The visible presence is `in-game` if either source says `in-game` (the mod's report wins, because it knows the server), otherwise `online`. `offline` with `via` clears only that source.

| Who | Sends |
|---|---|
| TRS Launcher | `online` + `via: "launcher"` every 60 s while it runs and no game of this account runs. While a game **it started** for this account runs: `in-game` + `via: "launcher"` with version and loader (no server) every 60 s. `offline` + `via: "launcher"` on exit or account switch. |
| TRS Client | `in-game` + `via: "client"` every 60 s **while in a world or on a server**. `offline` + `via: "client"` when leaving the world, turning the online features off and on quit. |

**Old clients (no `via`).** Launchers up to 0.5.x send `online`/`offline` without `via`, and mods up to 0.5.x send `in-game` without `via` all the time the game runs. Without `via`, `in-game` counts as the mod's report, `online` as the launcher's report that **also** clears the mod's report (old launchers send `online` only when no game of this account runs), and `offline` clears **both**. So old clients keep working; old mods show badges while the game runs (also on the title screen), but don't update badges live (they ignore the `badge` event and ask again after their cache runs out).

**200**
```json
{ "state": "in-game", "expiresInSec": 180 }
```
For `offline`, `expiresInSec` is `0`.

Presence lives only in server memory. There is no history. For achievements (§31.7) the server keeps only the
total in-game time, the current and longest session and the day streak per account.

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
| `unlock` | `free` \| `code` (unlockable with a code or an admin grant) \| `admin` (admin grant or code only) \| `owner` (an upload, only for its uploader) \| `event` (free to claim while an event is active for you, §32; the event id is in `event`) |
| `event` | Only on event capes (`unlock: "event"`), e.g. `"halloween"`. |
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
- `rejectReason` is present only for uploads, and is `null` when there is no reason (always `null` for shared capes).
- The list also contains capes **friends shared with you** (accepted, §5.10). They come last, have `kind: "upload"`, `unlock: "owner"` and `status: "approved"`, and carry `shared`.

Sharing fields (every entry has them):

| Field | Meaning |
|---|---|
| `shareable` | `true` if you may offer this cape to friends (§5.10): your own `approved` upload, or a shared cape you accepted. |
| `shared` | `null`, or for a cape a friend shared with you: `{ "from": { "uuid", "name" }, "creator": { "uuid", "name" } }`. `from` gave it to you, `creator` uploaded it (they differ after a re-share). |
| `holders` | How many players you see as holders (§5.10): for your own upload all holders, for a shared cape the ones in your branch. `0` if not shareable. |

Old clients that don't know these fields see a shared cape as an upload they own. Deleting it with `DELETE /v1/capes/{id}` returns `404`; give it back with `DELETE /v1/capes/{id}/holders/{own uuid}` instead.

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

### 5.9a `POST /v1/me/capes/{id}/claim`

Auth required. Claims an **event cape** (`unlock: "event"`, §32) for free. **200** `{ "owned": true }`. It is idempotent: whoever already owns the cape gets `owned: true` even after the event ended – claimed items are **kept for good**. Event capes are listed in `GET /v1/capes` only while the event is active for you or once you own them.

| HTTP | code | Meaning |
|---|---|---|
| 403 | `event_inactive` | The event is not active for you (neither on globally nor allowed individually). |
| 400 | `not_claimable` | Not an event cape. |
| 404 | `cape_not_found` | Unknown, retired or an upload. |

### 5.10 Sharing capes with friends

You can give a cape you made to friends. They get an **offer**, and once they accept, the cape is in their collection: they can wear it like an unlocked cape, and others see it on them like on you.

**What can be shared.** Only your **own uploads** that are **`approved`** (not `pending` or `rejected`), never built-in, code or event capes. A friend who accepted a shared cape may **re-share** it to their own friends with the same flow.

**Rules.**

- The target must be your friend (§6), not blocked in either direction, and not banned.
- Each cape can have at most **20 holders** besides its creator. Accepted holders and open offers both count, re-shares included (`share_limit`).
- A player can have at most **50 open offers** at a time (`offer_inbox_full`).
- The shares of a cape form a tree with the creator as the root. **Revoking** a holder also revokes everything that holder re-shared, all the way down. A player who loses a cape they were wearing wears no cape afterwards (watchers get a `cape` event, §13).
- **Who can revoke:** the creator any holder, a holder anyone in their own branch (players they gave it to, directly or through others), and everyone themselves (give the cape back or throw away an offer).
- **Unfriending or blocking** cancels open offers between the two players (both directions). Accepted capes **stay** until someone revokes them.
- **Deleting** the cape (owner or admin), an **admin rejection** and **deleting the creator's account** remove every share of the cape. Deleting a holder's account removes that holder's share and their whole branch.
- Holders see the creator's and the giver's Minecraft names. The creator sees every holder (also the ones a friend re-shared to). A holder sees only their own branch.

**`POST /v1/cape-offers`**, auth required. Offers one of your capes to a friend.

```json
{ "capeId": "u3f9a0c1d2e4b5a697887", "friend": "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0" }
```

**201**
```json
{ "offer": { "cape": { "…": "CapeView" }, "to": { "uuid": "b0b0…", "name": "Bob" }, "createdAt": "…" } }
```

The friend gets the SSE event `cape_offer` (§7).

| HTTP | code |
|---|---|
| 400 | `cape_not_shareable` (built-in cape), `cannot_target_self`, `invalid_request` |
| 404 | `cape_not_found` (unknown, or an upload you neither made nor hold), `friend_not_found` (not a friend, blocked, banned, unknown) |
| 409 | `cape_not_approved` (your own upload is still `pending` or was `rejected`), `already_shared` (the friend already has the cape or an offer for it, or made it), `share_limit`, `offer_inbox_full` |

**`GET /v1/cape-offers`**, auth required. Open offers to you and from you, newest first.

**200**
```json
{
  "incoming": [
    { "cape": { "…": "CapeView" }, "from": { "uuid": "…", "name": "Bob" }, "creator": { "uuid": "…", "name": "Alex" },
      "createdAt": "2026-09-25T18:00:00.000Z" }
  ],
  "outgoing": [
    { "cape": { "…": "CapeView" }, "to": { "uuid": "…", "name": "Cleo" }, "createdAt": "…" }
  ]
}
```

The cape texture of an offer is public (the cape is `approved`), so the preview works without extra rights. `GET /v1/friends` also returns the number of incoming offers as `capeOffers` (§6.1), so a client that polls friends knows when to fetch this list.

**`POST /v1/cape-offers/{capeId}/accept`**, auth required. **200** `{ "cape": { "…": "CapeView" } }`. The cape is now in `GET /v1/capes` with `owned: true` and can be worn with `PUT /v1/me/cape`. The giver gets the SSE event `cape_offer_accepted`. `404 offer_not_found` if there is no open offer, or if it became invalid (the giver is no longer your friend or was banned, the cape is no longer approved); an invalid offer is removed.

**`POST /v1/cape-offers/{capeId}/decline`**, auth required. **204**. The giver is not notified; the offer just disappears from their outgoing list. `404 offer_not_found`.

**`GET /v1/capes/{id}/holders`**, auth required, for the creator and for holders of the cape.

**200**
```json
{
  "holders": [
    { "uuid": "…", "name": "Bob", "status": "accepted", "grantedBy": { "uuid": "…", "name": "Alex" },
      "createdAt": "…", "acceptedAt": "…" },
    { "uuid": "…", "name": "Cleo", "status": "offered", "grantedBy": { "uuid": "…", "name": "Bob" },
      "createdAt": "…", "acceptedAt": null }
  ],
  "count": 2,
  "limit": 20
}
```

- `status` is `offered` (open offer) or `accepted`.
- The creator gets every holder; a holder gets the players in their own branch (without themselves).
- `count` is the number of **all** holders of the cape (what counts against `limit`), even if you only see your branch.
- `404 cape_not_found` if you neither made nor hold this cape, or it isn't `approved`.

**`DELETE /v1/capes/{id}/holders/{uuid}`**, auth required. Revokes a share or withdraws an open offer, together with everything that holder re-shared. With **your own** UUID you give a shared cape back (or throw away an offer to you). **204**. Every player who lost the cape gets the SSE event `cape_share_removed` (except yourself).

| HTTP | code |
|---|---|
| 404 | `holder_not_found` (you made or hold the cape, but this player has no share in your reach), `cape_not_found` |

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
  },
  "capeOffers": 1
}
```

- `presence` is `null` when the friend is offline or has `presenceVisibility=nobody`.
- `game` can be `null`. `game.server` is present only if the friend shares it.
- Friends are sorted by name.
- `capeOffers` is the number of open cape offers to you (§5.10). Fetch `GET /v1/cape-offers` for the details.
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
| `DELETE /v1/friends/{uuid}` | **204**. Open cape offers between you two are cancelled (§5.10); accepted capes stay. | `404 friend_not_found` |

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

- Blocking removes the friendship and all requests in both directions, and cancels open cape offers between you two (accepted capes stay, §5.10).
- The blocked player can no longer find you (`player_not_found`) and no longer gets your badge or cape in the lookup.
- They only see `friend_removed`, never a block notice.

**`DELETE /v1/blocks/{uuid}`** returns **204**, or `404 block_not_found`.

---

## 7. Events (optional SSE)

> This stream is about **your own account** (friends, presence). Live events about **other players you can see in-game** (emotes, skin, cape and cosmetic changes) come from a separate stream, `GET /v1/events/players` (§13).
>
> **New clients use `GET /v1/events/me` (§19) instead:** it carries these events plus chat, report feedback and moderation, with event ids and resume. This stream stays unchanged for older clients.

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
| `cape_offer` | `{"type":"cape_offer","offer":{…incoming offer, §5.10…}}`: a friend offers you a cape. |
| `cape_offer_accepted` | `{"type":"cape_offer_accepted","capeId":"…","by":{"uuid":"…","name":"…"}}`: your offer was accepted. |
| `cape_share_removed` | `{"type":"cape_share_removed","capeId":"…"}`: an offer to you was withdrawn or cancelled, or a shared cape was revoked, deleted or rejected. Reload `GET /v1/capes` and `GET /v1/cape-offers`. |

- A stream stays open for **at most 1 hour**.
- It closes immediately on logout-all, a ban or account deletion.
- Reconnect with backoff: 1 s, 2 s, 5 s, … up to 60 s.
- Each account can have at most 3 parallel streams. A fourth gets `503 too_many_streams`.
- Presence events are sent **only on changes**. Repeated identical heartbeats are silent.

---

## 8. Admin

Auth: a team bearer token (admin or moderator), the website session **or** `X-Admin-Key`. Every mutation is recorded in the audit log. Which role may do what: §22.1 (moderators may read everything here, review capes/cosmetics and use the moderation endpoints; deleting uploads, grants, bans, codes and the word filter are admin-only).

| Method and path | Body | Response |
|---|---|---|
| `GET /v1/admin/stats` | – | `{ users:{total,banned,activeLast24h,online}, sessions, capes:{builtin,approved,pending,rejected,reported,activeUsers}, cosmetics:{builtin,emotes,approved,pending,rejected,reported,equippedUsers}, codes:{active,redemptions}, friendships, pendingFriendRequests, eventStreams, playerStreams }` |
| `GET /v1/admin/capes?status=pending\|approved\|rejected\|reported` | – | `{ capes: [CapeView + { owner:{uuid,name}\|null, createdAt, reviewedAt, reviewedBy, rejectReason, reports:{count, reasons:{<reason>:n}}, bytes, ownerStats:{uploads,approved,pending,rejected}\|null }] }`. The default status is `pending`. `bytes` is the size of the stored PNG file (0 if it is missing). `ownerStats` counts **all** uploads of the owner (including this one); `null` without owner. |
| `POST /v1/admin/capes/{id}/approve` | – | `{ cape }`. Also clears open reports. |
| `POST /v1/admin/capes/{id}/reject` | `{ "reason"?: string≤200 }` or none | `{ cape }`. Also takes the cape off its wearers and removes every share of it (§5.10). |
| `DELETE /v1/admin/capes/{id}` | – | 204. Uploads only; built-in capes return `409 builtin_cape`. Removes every share of it. |
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

Chat moderation (reports, mutes, warnings, word filter, audit log) is in §20.5; roles, all sanction kinds, player file, dashboard, search, bulk actions and appeals in §22.

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
| `GET /` | The website (server-rendered pages, see §16) |
| `GET /robots.txt` | Crawling rules: `/v1/` and `/admin` disallowed except `/v1/site/` and `/v1/capes/*.png`; links the sitemap |
| `GET /sitemap.xml` | All pages and blog posts in every language with hreflang alternates (details: `docs/seo.md`) |

---

## 10. Client checklist

**Launcher (Rust):**

1. Log in:
   - Call `challenge`.
   - Call Mojang `join` with the active account's access token.
   - Call `verify`.
   - Store the token encrypted, per Minecraft account.
2. On any `401`, log in again once. Show `403 banned` in the UI.
3. Send presence `online` + `via: "launcher"` every 60 s while the launcher runs.
   - While a game the launcher started runs, send `in-game` + `via: "launcher"` with version and loader for that account instead.
   - Send `offline` + `via: "launcher"` on exit.
4. Friends view: poll `GET /v1/friends` and optionally open the SSE stream.
5. Cape picker: `GET /v1/capes`, `PUT /v1/me/cape`, upload, redeem. Sharing (§5.10): offer `shareable` capes to friends, show holders with revoke, show incoming offers (`capeOffers` in `GET /v1/friends` tells when) with accept/decline.
6. Cosmetics picker (§11):
   - `GET /v1/cosmetics` gives the templates and the catalog. `GET /v1/me/cosmetics` gives what is equipped.
   - Preview with three.js/skinview3d, following §11.2–§11.5 exactly.
   - Equip with `PUT /v1/me/cosmetics`. Redeem codes with `POST /v1/redeem`.
   - Paint editor: start from `GET /v1/cosmetics/templates/{id}.png?scale=k`, then upload with `POST /v1/cosmetics/upload`.
7. After changing the Mojang skin, call `POST /v1/me/skin-changed` (§13.4).
8. Sync own skins, presets and theme/accent/language across devices (§17), only with consent and the sync switch on.
9. Keep **one** `GET /v1/events/me` stream (§19) open while signed in; resume with `Last-Event-ID`, reload state on `resync`. Drive the social page, toasts (message, invite, friend request, cape offer, "X is online") and badges from it; poll only as fallback.
10. Chat (§18): conversation list, message pages with `before`/`after`, send with `nonce`, images via `POST /v1/chat/attachments` (then attach up to 10), invites with `GET /v1/servers/status`, reactions, edit/delete, read/unread/mute, typing. Reports via `POST /v1/reports` (§20).

**Mod (Java):**

1. Use the launcher's TRS token if the launcher passes one. Otherwise run the auth flow with the in-game session, which has the access token.
2. Batch-lookup visible players (at most 100 per call). Cache about 5 minutes. Treat a missing UUID as "no badge, no cape, no cosmetics".
3. Download the texture once per `url`. The `?v=` part changes with the content.
   - Pick the frame with `f = floor(now / frameTimeMs) % frames`.
   - Scale UVs by `scale` (§5.2 for capes, §11.3 for cosmetics).
4. Presence: `in-game` + `via: "client"` every 60 s while in a world or on a server, first **before** looking anyone up there; `offline` + `via: "client"` when leaving the world and on quit (§4.2). Never send `game.server` unless the user enabled `shareServer`. The server also drops it.
5. Load `GET /v1/cosmetics/templates` once per session and revalidate it with `If-None-Match`. Render `cosmetics` from the lookup with it (§11).
6. Open `GET /v1/events/players?uuids=…` for the players you render (§13). Apply `emote`, `skin`, `cape`, `cosmetics` and `badge` events live.
7. Emote wheel: `GET /v1/me/cosmetics` → `emotes` lists the unlocked ones. Play one with `POST /v1/emotes/play`, then start the animation locally right away (§12).
8. Wardrobe: shared capes come from `GET /v1/capes` like your own; sharing works like in the launcher (§5.10).
9. Social screen and quick reply: the same chat API (§18) and **one** `GET /v1/events/me` stream (§19) next to `events/players`. Images in chat are JPEG or PNG (decode JPEG with ImageIO, not with `NativeImage`, which only reads PNG). Render text as plain text.

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

`GET /v1/cosmetics/templates` returns this. The public source is `api/assets/cosmetics/templates.json`. Proprietary templates are not in the repository. They live in `<PRIVATE_ASSETS_DIR>/cosmetics/templates.private.json` with the same schema (`{ "version": 1, "templates": [ … ] }`; a bare array of templates is accepted too). At startup the server appends them to the public list. A missing file logs one warning and skips every private template. One invalid entry logs one warning and is skipped. Startup does not crash, and a broken **public** file still stops startup. An id that the public file already has is skipped. The merged list is what `GET /v1/cosmetics/templates` returns.

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
| `cubes[].pivot` | `[x, y, z]` in the same frame. Required for `flap`, `spin`, `quack` and `wing`. |
| `cubes[].anim` | Optional: `flap`, `bob` or `spin` (§11.4); with a `rig` also `look`, `quack`, `blink`, `wing` (§11.4.1). |
| `rig` | Optional animal rig: `{ "type": "duck", "neck": [x, y, z] }`. The whole model reacts to the wearer (§11.4.1). |
| `particles` | Particle definition (§11.5). |

Templates **never change** after release. A new shape gets a new id, so uploaded textures always stay valid. The server checks at startup that every net lies inside the texture and that no two nets overlap.

`GET /v1/cosmetics/templates` needs no auth. It sends `ETag` and `Cache-Control: public, max-age=300`. Send `If-None-Match` to get `304`.

`GET /v1/cosmetics/templates/{id}` returns `{ "template": { … } }` for one template.

`GET /v1/cosmetics/{id}/template.json` returns the same template object (not wrapped) for one **format-1** cosmetic. The id is the cosmetic id; the server resolves it through the item's `template`. No auth. `ETag` is the full sha256 of the body. `templateUrl` uses the first 12 hex digits of that hash as `?v=`. With a matching `?v=` (the 12 digits, or any longer prefix of the hash) the response is `Cache-Control: public, max-age=31536000, immutable`; a wrong `?v=` is cached for 5 minutes. `If-None-Match` returns `304`. Unknown ids, emotes and format-2 items return `404 cosmetic_not_found`. The route is public, including hidden items: the URL is only handed out in the catalog and the lookup.

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

#### 11.4.1 Animal rigs (`rig`)

A template with a `rig` is animated by the client from the **wearer's movement**, not only the wall clock. Clients without rig support draw the model still. The only rig so far is `duck` (template `duck`, the rubber duck):

| Part | Behaviour |
|---|---|
| whole model | Waddles (rolls ±7°, bobs) while the wearer walks, bobs gently while idle, lags a little behind fast head turns and springs back, squashes briefly on landing, leans forward while sprinting. |
| `look` | Turns around `rig.neck`: follows the wearer's view with a slight delay and glances around now and then (±35° yaw, ±15° pitch). |
| `quack` | Rotates around `pivot` (x axis) up to 28°: when the wearer starts sneaking, while an emote plays, and randomly every 10–20 s. Follows `look`. |
| `blink` | Scales the cube's height to 10 % around its centre for 150 ms every 2.5–6 s. Follows `look`. |
| `wing` | Rotates around `pivot` (z axis, outwards) while the wearer is in the air (fast flaps), tucked while sprinting. |

Timing is per client and not synchronised – only the look is.

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
  "templateUrl": "/v1/cosmetics/redstone_wings/template.json?v=1c0ffee0dead",
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
| `slot` | `hat` \| `wings` \| `back` \| `aura` \| `companion` \| `emote`. `companion` (since Halloween 2026, format 2 only) is worn **in addition to** a hat. |
| `kind`, `unlock`, `status` | As for capes (§5.1). Emotes are always `builtin`. `unlock` can also be `event` (§32): free to claim while the event is active for you; then the extra field `event` holds the event id (e.g. `"halloween"`). |
| `template` | Template id, or `null` for emotes. |
| `templateUrl` | Format 1 only. Path (no host) `/v1/cosmetics/<id>/template.json?v=<hash>`. `hash` is 12 hex digits of the sha256 over that template's JSON, the body of the route in §11.1. Absent on emotes and format 2. Older clients ignore it. |
| `texture` | `null` for emotes. `width`/`height` are the size of **one frame** in pixels (`textureWidth·scale` × `textureHeight·scale`). `url` works like a cape URL (§5.4). |
| `emissive` | Render at full brightness (§11.3). |
| `emote` | `{ "durationMs", "loop" }` for emotes, otherwise `null`. |

**LookupCosmetic** is the flat form used in the lookup and in events (`cosmetics` has the keys `hat`, `wings`, `back`, `aura` and `companion`, each an item or `null`): `{ id, template, templateUrl, url, scale, animated, frames, frameTimeMs, emissive }` for format 1, `{ id, format: 2, model, url, scale, animated, frames, frameTimeMs, glow, glowFrames, glowFrameTimeMs, hash }` for format 2 (§11.9). `templateUrl` is the same path as on CosmeticView.

A **format-2** CosmeticView has `template: null`, `emissive: false` and these extra fields (all absent on format-1 items):

| Field | Meaning |
|---|---|
| `format` | `2` |
| `model` | URL of `model.json` (§11.9), `?v=<hash>`. |
| `glow` | URL of the glow strip, or `null`. |
| `card` / `cardNight` | Preview images (three-quarter view, day / night), at most 512 px, own `?v=`. |
| `frames`, `frameTimeMs` | Base texture strip (usually 1 / `null`). The same values are in `texture`. |
| `glowFrames`, `glowFrameTimeMs` | Glow strip (0 = none). Frame = `floor(now / glowFrameTimeMs) % glowFrames`. |
| `hash` | 12 hex digits of the sha256 over `model.json`, texture and glow strip. It is the `?v=` of `model`, `texture.url` and `glow`. |

`texture` stays an object as for format 1 (`url` = the v2 base texture, `width`/`height` = one frame in pixels, `scale` = texels per unit) so older clients can still parse the catalog.

### 11.7 Endpoints

| Request | Auth | Response |
|---|---|---|
| `GET /v1/cosmetics` | yes | `{ templates: [Template], cosmetics: [CosmeticView + { owned, equipped, rejectReason? }] }`. Lists all built-in items and emotes, plus your own uploads in any status. `rejectReason` is present only for uploads. |
| `POST /v1/me/cosmetics/{id}/claim` | yes | Claims an **event item** (`unlock: "event"`) for free. **200** `{ "owned": true }`; idempotent (owners keep it after the event). `403 event_inactive` if the event isn't active for you, `400 not_claimable` for other items, `404 cosmetic_not_found`. See §32. |
| `GET /v1/cosmetics/templates` | no | See §11.1. |
| `GET /v1/cosmetics/templates/{id}` / `{id}.png?scale=k` | no | One template, or its paint guide (§11.1). `404 template_not_found`. |
| `GET /v1/cosmetics/{id}/template.json` | no | Format 1 only (§11.1): that cosmetic's template JSON. `ETag` = full sha256; with the matching `?v=` `Cache-Control: public, max-age=31536000, immutable`, otherwise 5 min. `If-None-Match` → `304`. Unknown ids, emotes and format 2 → `404 cosmetic_not_found`. |
| `GET /v1/me/cosmetics` | yes | `{ equipped: { hat, wings, back, aura, companion }, emotes: [emoteId] }`. Each slot is a CosmeticView or `null`, as you see it (including your pending uploads). `emotes` lists the emotes you may play, in list order. |
| `PUT /v1/me/cosmetics` | yes | Body `{ "hat"?: id\|null, "wings"?: id\|null, "back"?: id\|null, "aura"?: id\|null, "companion"?: id\|null }`, with at least one key. An id equips, `null` takes the item off, and a missing key leaves the slot unchanged. Format-2 items are equipped the same way (each one in the slot given by its `slot`: `hat` or `companion`; `companion` takes only format-2 items with `slot: "companion"` and only items you own). **200** has the same shape as `GET /v1/me/cosmetics`. All changes are checked first and then applied together. |
| `GET /v1/cosmetics/{id}.png` | optional | The texture (format 2: the v2 base texture, `ETag` = v2 hash). Caching, `ETag`/`304` and the pending/private rules are the same as §5.4. Otherwise `404 cosmetic_not_found`. |
| `GET /v1/cosmetics/{id}` | optional | `{ cosmetic: CosmeticView }`. Same visibility as the texture. |
| `GET /v1/cosmetics/{id}/model.json` | no | Format 2 only (§11.9): the model, byte-for-byte as exported by TRS Studio. `ETag` = full hash; with the matching `?v=` `Cache-Control: public, max-age=31536000, immutable`, otherwise 5 min. `If-None-Match` → `304`. Format-1/unknown ids → `404 cosmetic_not_found`. |
| `GET /v1/cosmetics/{id}/glow.png` | no | Format 2: glow strip (same caching). `404` if the item has none. |
| `GET /v1/cosmetics/{id}/card.png`, `…/card-night.png` | no | Format 2: preview images (≤ 512 px, own hash as `ETag`/`?v=`). |
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
| `redstone_crown` | Redstone-Krone | **format 2** (hat) | code | glow 12 × 140 ms, idle animation |
| `team_crown` | Team-Krone | **format 2** (hat) | **admin** | glow 12 × 200 ms, idle animation |
| `trs_cap` | TRS-Cap | **format 2** (hat) | free | glow 12 × 150 ms |
| `lamp_helmet` | Redstone-Lampen-Helm | **format 2** (hat) | free | glow 12 × 160 ms, idle animation |
| `top_hat` | Zylinder | **format 2** (hat) | free | glow 12 × 150 ms, idle animation |
| `redstone_wings` | Redstone-Flügel | `wings` | code | 8 × 120 ms, emissive |
| `dragon_wings` | Drachenflügel | `wings` | free | – |
| `backpack` | Rucksack | `backpack` | free | – |
| `halo` | Heiligenschein | **format 2** (hat) | code | glow 12 × 150 ms, idle animation |
| `redstone_aura` | Redstone-Partikel-Aura | `orbit` (aura) | free | 4 × 150 ms, emissive |
| `footprints` | Fußspuren | `trail` (aura) | free | – |
| `rubber_duck` | Quietscheente | `duck` (rig) | code, **hidden** | – (animated by the rig) |
| `witch_hat` | Hexenhut | **format 2** (hat) | **event** `halloween` | glow 12 × 160 ms |
| `pumpkin_head` | Kürbiskopf | **format 2** (hat) | **event** `halloween` | glow 12 × 120 ms |
| `bat_buddy` | Fledermaus-Begleiter | **format 2** (**companion**) | **event** `halloween` | glow 12 × 150 ms, orbit animation |

Templates without a built-in item (`ring`, and since format 2 also `crown`, `cap`, `lamp_helmet`, `tophat`, `halo`) are available for uploads. The six format-2 items kept their ids, so owners and codes still work; a `halo` equipped in `aura` moved to `hat` at the first start with format 2 (taken off if `hat` was already in use).

**Proprietary built-ins.** Items with unlock `code` or `admin` are not in this repository: capes `trs`, `team`, `tester`, `content-team`, `veteran`, `ideengeber` and cosmetics `redstone_crown`, `team_crown`, `halo`, `redstone_wings`, `rubber_duck`. Their textures, models, glow maps and cards live only on the server in `PRIVATE_ASSETS_DIR` (default `<DATA_DIR>/private-assets`). Layout: `capes/`, `cosmetics/`, `cosmetics/v2/`, plus `capes/catalog.private.json`, `cosmetics/catalog.private.json` (same entry schema as the public catalogs, with the original `sort` index) and `cosmetics/templates.private.json` (same schema as `templates.json`; the `duck` rig lives only there). At startup the server merges the private catalogs and `templates.private.json` with the public built-ins. Ids, hashes, routes, cards, models, glow maps, lookup, codes and ownership stay the same. If the directory is missing, it logs one warning and skips every private item. If one item's files are missing or invalid, it logs one warning and skips that item. Startup does not crash, and `user_capes` / `user_cosmetics` rows are not deleted: the item is usable again on the next start once the files exist. A broken **public** catalog still stops startup. `node api/scripts/import-cosmetics-v2.mjs [--private <dir>]` (or `PRIVATE_ASSETS_DIR`, default `E:/ai/trs-private-assets`) writes the proprietary models there and the free/event models to `api/assets`. `generate-capes.mjs` and `generate-cosmetics.mjs` take the same `--private` flag.

**Hidden items** (`hidden: true` in `catalog.json`, only together with `unlock: "code"`) never appear in `GET /v1/cosmetics` for anyone who has not unlocked them – not even as locked, and not for admins. Once redeemed, they are listed and wearable like any other item, and others see them on the wearer through the lookup. Admins find them for code creation under `GET /v1/admin/cosmetics/builtin` (staff): every built-in item and emote that is not `free`, with `hidden`.

### 11.9 Format 2: 3D models

Built-in head items can be **real 3D models** instead of template + texture. Source of truth is the TRS Studio format description (`trs-studio/cosmetic-format.md`); the short form:

- **Files:** `model.json` + base texture (RGBA, alpha 0/255; vertical strip if `texture.frames > 1`) + optional glow strip (additive, black = nothing). Free and event models ship in `api/assets/cosmetics/v2/<id>.json`, `<id>.png`, `<id>-glow.png`, plus `<id>-card.png` / `<id>-card-night.png` (previews ≤ 512 px). Code and team models are proprietary and are loaded from `PRIVATE_ASSETS_DIR` (§11.8), not from the repository. `catalog.json` lists the public items as `{ "id", "name", "format": 2, "unlock", "frames", "frameTimeMs"?, "glowFrames", "glowFrameTimeMs"?, "hidden"? }` (`unlock` is `free`, `code`, `admin` or `{ "type": "event", "event": "<id>" }` – stored as `unlock = admin` plus the column `event`, shown as `unlock: "event"`; capes in `assets/capes/catalog.json` take the same form). The slot comes from the model (`"slot": "hat"` or `"companion"`, both with `attach: "head"`); the frame values must match the model. Import: `node api/scripts/import-cosmetics-v2.mjs` (copies the approved exports unchanged and makes the cards; proprietary ids go to `--private`).
- **Start check:** every model is validated with the format rules (TypeScript port of `validateModel`, `app/utils/cosmetic-v2/format.ts`) including the image sizes. An invalid **public** model **stops the server start**. An invalid or missing private item is skipped with one warning and does not stop startup.
- **Units and axes:** 1 unit = 1 skin pixel. **+x = the player's left, +y = up, +z = front** (same as three.js/skinview3d). `attach: "head"`: origin = head pivot (neck, centre of the head's bottom). `ModelPart` space: `x' = x, y' = −y, z' = −z`.
- **Bones:** `{ id, parent?, pivot, rotation? }`, parents before children, pivot in model space, rotation in degrees, order **ZYX** (matrix `Rz·Ry·Rx`). Local matrix `T(pivot + pos) · R(rotation + rot) · S(scale) · T(−pivot)`, world `W = W_parent · L`.
- **Cubes:** `{ id?, bone, from, to, inflate?, material?, faces }` in model space. `faces.<north|south|east|west|up|down> = { uv: [u0,v0,u1,v1] (units), rotation?: 0|90|180|270, material? }`; a missing face is not drawn. Corner order and UV mapping per face are fixed (see the Studio description, §5); one axis may be 0 thick (drawn double-sided).
- **Materials:** `cutout` (alpha test 0.5), `emissive` (full bright), `translucent` (alpha blend, no depth write). All faces double-sided, nearest filtering, **no mipmaps**.
- **Glow:** `glow.png` drawn additively over all faces with the same UVs, full bright, no depth write, small polygon offset. Frame = `floor(t / frameTimeMs) % frames`.
- **Halos:** additive camera-facing squares (`size`, `pos` on a bone, `color`, `intensity`, optional `normal` + `pulse`), pushed `size/2` towards the camera, radial falloff `(1 − r)^2.5`, pulse `min + (max − min)·(0.5 − 0.5·cos(2π·phase))`, faded out when `normal` points away.
- **Animations:** tracks `rotation` (degrees, added), `position` (units, added), `scale` (multiplied) with `linear` / `smooth` (`u²(3 − 2u)`) / `step`, **wall clock** (`t = now + offsetMs`, `mod lengthMs` when looping) so every client shows the same phase. Driver `idle` always runs; `walk`, `sneak`, `jump`, `air` are reserved.
- **Limits:** 64 cubes, 32 bones, 1024 px per image edge, strip ≤ 4096 px, 16 frames, 8 animations, 16 halos, coordinates ±48 on a 0.125 grid, scale 1/2/4/8/16 (the server accepts up to 8).

Reference renderer (three.js + skinview3d, pixel-identical to the Studio workbench, shared with the launcher): `api/app/utils/cosmetic-v2/` (README there). Uploads stay format 1.

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
| `party` | Party | admin (reward for `friends_10`, §31) | 6000 | yes |

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
| `badge` | `{"type":"badge","uuid":"…","badge":true\|false}`. The live badge (§4.1) turned on or off: the player started or stopped playing with TRS, or changed `showBadge`. `true` is sent only to viewers who are in game themselves; `false` goes to every viewer. |

Rules:

- Events are sent **only on changes**:
  - equip or unequip
  - cape change
  - approval or rejection of an item the player wears
  - admin revocation
  - a change of `showCapeToOthers`, `showCosmeticsToOthers` or `showBadge`
  - the player starting or stopping to play with TRS (live badge)
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

## 15. Website sign-in (removed)

**Removed.** The website signs in with Microsoft only (§24.1). Every `/v1/web-login/*` route answers **`410 web_login_removed`** – launchers that still call `POST /v1/web-login/approve` (`trs_web_login_approve`) should hide that feature and point to the website sign-in. The session cookie is now `trs_session` (was `trs_admin`).

## 16. Website data

Public, cached for 5 minutes (`Cache-Control: public, max-age=300`). Used by the website itself.

| Request | Response |
|---|---|
| `GET /v1/site/releases` | `{ release: { version, tag, publishedAt, pageUrl, assets: [{ platform: "windows"|"appimage"|"deb"|"rpm", name, url, size }] } | null }`. The newest `v*` GitHub release (not drafts, not the `updater`/`client-mod` channels). Only GitHub download URLs of this repository. |
| `GET /v1/site/blog` | `{ posts: [{ version, date, title: { en, de } | null, headlines: { en: [], de: [] }, banner: { accent, motif } | null, gallery: { en: PostShot[], de: PostShot[] } }] }` from `CHANGELOG.md` on `main`, newest first, released versions only. A PostShot is `{ src, caption }` (caption in that language or `""`); `src` points to `raw.githubusercontent.com`. The gallery comes from the `shots:` comment below the banner line, plus older `![…](/news/…)` lines in the text; at most 8. |
| `GET /v1/site/blog/{version}` | `{ post: … + markdown: { en, de }, contributors: string[] }` (the text without image lines and without a thanks section), or `404 not_found`. `contributors` are GitHub logins (without `@`) of the people thanked in the release, see below; `[]` if there are none. |
| `GET /v1/site/capes` | `{ capes: [{ id, name, unlock, url, scale, frames, frameTimeMs }] }`. Approved built-in capes only; `url` is relative (`/v1/capes/<id>.png?v=…`). |
| `GET /v1/site/cosmetics` | `{ hats: [{ id, name, unlock, achievement, texture, animated, format: 2, model, glow, card, cardNight, frames, frameTimeMs, glowFrames, glowFrameTimeMs, hash }] }`. Built-in format-2 head items (§11.9), never hidden or retired ones; all URLs relative. `achievement` = title `{en,de,es}` if an achievement grants the item, else `null`. |

If GitHub is unreachable, the last good answer is kept. Without one, `release` is `null` and `posts` is empty.

**Contributors (`contributors`).** Read from the text of the GitHub release `v{version}` (the same cached request as
`/v1/site/releases`, last 30 releases, drafts ignored): preferably from the machine-readable comment
`<!-- contributors: login1,login2 -->`, otherwise from the `@login` mentions in the section whose heading is
`Thanks to`, `Danke an` or `Gracias a` (any level, e.g. `## Thanks to / Danke an`). A thanks section inside the
changelog text of that version counts too (it is removed from `markdown`). Every login must match
`^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$`; anything else is dropped. Duplicates (case-insensitive) are removed, the order is
kept, at most 100 names. If GitHub is unreachable, the post is still returned with the contributors from the changelog
only (usually `[]`). The website links each name to `https://github.com/{login}` (no avatars – the CSP stays as it is).

---

## 17. Sync (own skins, presets, theme and language, TRS Client)

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
  "settings": { "data": { "theme": "dark", "accent": "redstone", "language": "de" }, "updatedAt": "…" },
  "client": { "data": { … }, "updatedAt": "…" },
  "wardrobe": { "data": { … }, "updatedAt": "…" }
}
```

- `skins`: metadata only, ordered by `updatedAt`. Download the image with §17.2.
  - `id`: 12 lowercase hex digits (the launcher library's id).
  - `variant`: `classic` or `slim`.
  - `sha256`: hex SHA-256 of the **re-encoded** PNG the server stores and serves. Compare it with the hash of the downloaded file, not of the file you uploaded.
  - `updatedAt`: server time of the last `PUT` or `PATCH`.
- `deletedSkins`: deletion markers (tombstones) of the last **30 days**, so other devices can delete the skin locally too. At most 500 per account (the oldest are dropped). An id is never in both lists.
- `presets` / `settings` / `client` / `wardrobe`: `null` until first written. `updatedAt` is the client time sent with the last accepted write.

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
| `PUT /v1/me/sync/client` | `{ data: <JSON object>, updatedAt: "<ISO>" }` | **200** `{ client: { data, updatedAt } }` |
| `PUT /v1/me/sync/wardrobe` | `{ data: <JSON object>, updatedAt: "<ISO>" }` | **200** `{ wardrobe: { data, updatedAt } }` |

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
- **Client** (TRS Client mod): its own settings – modules, HUD layout, TRS key bindings, introduction/"NEW" state. Any JSON object, at most **64 KiB** serialised (like presets). The client decides the structure; **no files, paths, tokens or server addresses**.
- **Wardrobe** (TRS Client mod): outfits (skin id from §17.2 + cape/cosmetic ids) and favourites. Any JSON object, at most **64 KiB** serialised (like presets).
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

### 17.5 Notes (TRS Client: notes per server and world)

The TRS Client keeps notes per multiplayer server or singleplayer world. They sync per note (last writer wins per note),
with a change cursor so a device only fetches what changed. Same rules as §17: Bearer token, the sync bucket
(**120 / min**, `requireUser(event, 'sync')`), owner only, deleted with the account.

**NoteView** and **tombstone**:

```json
{ "id": "0f1e2d3c4b5a6978", "world": { "type": "server", "address": "play.example.net" },
  "title": "Base", "text": "Coords 100 64 -20\nNether hub",
  "createdAt": "2026-09-28T10:00:00.000Z", "updatedAt": "2026-09-28T10:05:00.000Z" }
{ "id": "0f1e2d3c4b5a6978", "world": { "type": "world", "id": "abcdef0123456789", "name": "My world" },
  "deleted": true, "updatedAt": "2026-09-28T11:00:00.000Z" }
```

| Field | Rules |
|---|---|
| `id` | 16 lowercase hex, chosen by the client, unique per account |
| `world` | `{ "type": "server", "address" }` – host with optional `:port`, 1–255 characters, no whitespace, `/` or `\`, stored lower-case; or `{ "type": "world", "id", "name"? }` – `id` 16 hex (stored lower-case), `name` ≤ 64 characters |
| `title` | ≤ 64 characters (code points), may be empty, no control characters |
| `text` | ≤ 20,000 characters (code points), may be empty, no control characters except `\n` |
| `createdAt`, `updatedAt` | ISO 8601 (with `Z` or offset) like §17.3; returned in UTC with milliseconds |

"Control characters" = U+0000–001F, U+007F–009F, U+2028, U+2029 (tab and `\r` included – send spaces and `\n`).

#### `GET /v1/me/sync/notes?since=<cursor>&limit=200`

→ `200 { "notes": [NoteView | tombstone], "cursor": "n1.42", "more": false, "reset": false }`

- Without `since`: everything, **tombstones included**. With `since`: only notes changed after that cursor, in the order
  of the changes. `limit` 1–500 (default 200). `more: true` → call again with the returned `cursor`.
- `cursor` is opaque (≤ 256 characters, currently `n1.<n>`); store it after applying the page. A malformed `since` →
  `400 invalid_request`.
- `reset: true` (rare): the cursor is older than the removed tombstones (> 90 days) or unknown. The page then is the
  **full list** from the start – notes you synced before that are missing from it were deleted elsewhere.

#### `POST /v1/me/sync/notes`

Body (≤ **512 KiB**, 1–50 entries):

```json
{ "changes": [
  { "id": "…", "world": {…}, "title": "…", "text": "…", "createdAt": "…", "updatedAt": "…" },
  { "id": "…", "world": {…}, "deleted": true, "updatedAt": "…" }
] }
```

→ `200 { "results": [ … ] }`, one result per entry **in the same order**:

| Result | Meaning |
|---|---|
| `{ id, status: "ok" }` | stored (or deleted) |
| `{ id, status: "stale", current: NoteView \| tombstone }` | the stored note is **newer** – take over `current` |
| `{ id, status: "note_limit" }` | 200 notes in this server/world or 2,000 notes on the account (only counted for new notes, restored notes and notes moved to another world; updates and deletions always work) |
| `{ id, status: "invalid" }` | the entry breaks a rule above, has unknown fields, or its `updatedAt` is more than **24 h in the future** |

- **Last writer wins per note** by `updatedAt`: a stored newer state → `stale`; the **same** `updatedAt` overwrites
  (a retry). A tombstone is a normal change: it wins or loses like an edit, and a newer edit restores the note.
- Deleting an unknown id stores a tombstone (so other devices learn it). A change may move a note to another world.
- Only the envelope can fail as a whole: every entry must be an object with a valid `id`, 1–50 entries, no other
  top-level field → otherwise `400 invalid_request`. Body too big → `413`.
- After at least one `ok` your devices get `notes_changed` `{ cursor }` on `/v1/events/me` (§19): fetch with your own
  cursor.

**Storage.** Tombstones are kept **90 days** (server time of the deletion), at most 5,000 per account (the oldest go
first); then an older cursor gets `reset` (see above). Migration 19: tables `sync_notes` and `sync_note_state` (change
counter and tombstone horizon per account).

---

## 18. Chat (direct messages and groups)

Chat between TRS users. **Who may write:** friends (direct messages, "DM") and members of a group. Everything below needs auth.

**Rules at a glance**

- A DM exists at most once per pair. It can only be **opened** while you are friends. After unfriending or blocking it stays **readable but read-only** (`canWrite: false`, `readOnlyReason: "not_friends"`); befriending again re-enables it.
- A group is created by one player (the **owner**) with **their own friends** as members. Only the owner adds, removes, renames, transfers ownership or deletes. Members can leave; if the owner leaves, the longest member becomes owner; if the last member leaves, the group is deleted. At most **25 members** per group, **20 owned** and **100 joined** groups per player.
- Members added later only see messages **from their joining** on (the `member_added` system message is their first).
- In groups, **links and server invites** are only accepted from the owner or from a player who is friends with **every** other member (`422 links_not_allowed`). DMs have no such limit.
- Messages from players **you blocked** are delivered with `hidden: true` and without content (text, images, invite, reply preview are `null`/empty). They don't count as unread.
- A player who is **muted by moderation** (§20.4) can read but not send, edit, react, type, rename or create groups: `403 chat_muted` with `until` (ISO or `null` = until review). Conversations show `readOnlyReason: "chat_muted"`.
- **Every** access checks membership. Unknown ids and conversations you are not in both return `404 conversation_not_found` / `message_not_found` / `attachment_not_found` – you can't probe whether something exists.
- Storage: messages are kept **until deleted** (no automatic expiry). Text, invites and group names are **encrypted at rest** (AES-256-GCM, §18.9); images are re-encoded and stored encrypted. `DELETE /v1/me` removes the account's chat data (§3.3).

### 18.1 Views

**ConversationView**
```json
{
  "id": "c1f0e2d3c4b5a69788990",
  "kind": "group",
  "name": "Bau-Crew",
  "owner": "75c1a6f3112240abbdb57b9d21c64232",
  "members": [ { "uuid": "75c1…", "name": "Theredstonee", "role": "owner", "joinedAt": "…" }, { "uuid": "b0b0…", "name": "Bob", "role": "member", "joinedAt": "…" } ],
  "peer": null,
  "canWrite": true,
  "readOnlyReason": null,
  "lastMessage": { …MessageView… },
  "lastSeq": 42,
  "unread": 3,
  "markedUnread": false,
  "readSeq": 39,
  "muted": false,
  "mutedUntil": null,
  "reads": [ { "uuid": "b0b0…", "seq": 41, "at": "…" } ],
  "createdAt": "…",
  "updatedAt": "…"
}
```

- `id` matches `^c[0-9a-f]{20}$`. `kind` is `dm` or `group`. DMs: `name` and `owner` are `null`, `peer` is the other player `{uuid, name}`.
- `members` is sorted owner first, then by joining. `role` is `owner` or `member` (DMs: both `member`).
- `readOnlyReason`: `null`, `"not_friends"` (DM without friendship) or `"chat_muted"`.
- `lastSeq` is the highest message number of the conversation. `readSeq` is **your** read position (can go back with "mark unread"). `unread` counts text messages after `readSeq` that are not yours, not deleted and not from players you blocked.
- `muted` / `mutedUntil`: **your** notification mute of this conversation (not moderation). `mutedUntil: null` with `muted: true` = until you unmute.
- `reads`: read positions of the **other** members – only members who share read receipts, and only if **you** share them too (mutual, see `chatReadReceipts` in §3.1). Otherwise `[]`.
- `updatedAt` = last activity (message or change); the list is sorted by it.

**MessageView**
```json
{
  "id": "m0a1b2c3d4e5f60718293",
  "conversationId": "c1f0…",
  "seq": 42,
  "kind": "text",
  "sender": { "uuid": "b0b0…", "name": "Bob" },
  "text": "Hallo!",
  "invite": { "address": "play.example.net:25566", "name": "Survival" },
  "world": null,
  "waypoint": null,
  "attachments": [ …AttachmentView… ],
  "replyTo": { "id": "m…", "seq": 40, "sender": { "uuid": "…", "name": "…" }, "preview": "first 120 characters", "attachments": 0, "invite": false, "deleted": false },
  "system": null,
  "reactions": [ { "emoji": "fire", "count": 2, "users": ["b0b0…", "75c1…"] } ],
  "createdAt": "…",
  "editedAt": null,
  "deleted": false,
  "deletedBy": null,
  "hidden": false,
  "nonce": null
}
```

- `seq` increases by 1 per conversation (system messages included). Deleted accounts leave gaps.
- `text` is sanitised plain text (no markup; render as text, never as HTML), up to **2000 characters** (Unicode code points). Line breaks `\n` are kept (at most one empty line in a row).
- `invite`: a server invite card, see §18.6. `null` if none.
- `world`: a world card (hosted singleplayer world), see §21.8. `null` if none. `replyTo.world` is `true` when the reply target is a world card.
- `waypoint`: a shared waypoint, see §18.10. `null` if none. `replyTo.waypoint` is `true` when the reply target is a waypoint card.
- `replyTo.preview` is `null` if the target was deleted or its sender is hidden for you; `deleted: true` then.
- `kind: "system"`: group events. `sender` is the actor (`null` for automatic ones), `text` is `null`, and
  `system = { "event": "group_created"|"member_added"|"member_removed"|"member_left"|"renamed"|"owner_changed", "actor": {uuid,name}|null, "target": {uuid,name}|null, "name": "new name"|null }`. Render them as a centred line, e.g. "Bob added Carl".
- **Deleted for everyone:** `deleted: true`, `deletedBy`: `"sender"`, `"owner"` (group owner) or `"admin"` (moderation); `text`, `invite`, `attachments`, `reactions`, `replyTo` are empty. Show "Message deleted".
- **Edited:** `editedAt` is set; show "(edited)".
- `nonce` is only filled for the sender's own messages (your idempotency key, §18.4).
- `reactions[].emoji` is one of the fixed ids below; `users` lists who reacted (groups are small).

**Reactions (fixed set):** `thumbs_up` 👍, `heart` ❤️, `laugh` 😂, `wow` 😮, `sad` 😢, `angry` 😡, `party` 🎉, `fire` 🔥, `eyes` 👀, `check` ✅. Every player can set each reaction at most once per message.

**AttachmentView**
```json
{ "id": "a0123456789abcdef01234567", "mime": "image/jpeg", "width": 1920, "height": 1080, "bytes": 312345,
  "path": "/v1/chat/attachments/a0123…",
  "thumb": { "mime": "image/jpeg", "width": 400, "height": 225, "bytes": 21034, "path": "/v1/chat/attachments/a0123…?thumb=1" } }
```
`path` is relative to the API base URL and needs the Bearer token.

### 18.2 Conversations

| Request | Body | Response |
|---|---|---|
| `GET /v1/chat/conversations?limit=50&cursor=…` | – | `{ conversations: [ConversationView], nextCursor: string\|null }`. Newest activity first. `limit` 1–100 (default 50). Pass `nextCursor` for the next page; `400 invalid_cursor` for garbage. |
| `GET /v1/chat/conversations/{id}` | – | `{ conversation }` |
| `POST /v1/chat/dms` | `{ "uuid": "<friend>" }` | **200** `{ conversation }` – opens the DM or returns the existing one. Without friendship: `403 not_friends` (also for unknown UUIDs); an **existing** DM is returned read-only. Yourself: `400 cannot_target_self`. |
| `GET /v1/chat/unread` | – | `{ total, conversations: [{ id, unread, markedUnread, muted }] }` – only conversations with unread messages or the "unread" mark. `total` sums non-muted conversations (a mark without messages counts 1). Use it as polling fallback for badges. |

### 18.3 Groups

| Request | Body | Response / errors |
|---|---|---|
| `POST /v1/chat/groups` | `{ "name": "Bau-Crew", "members": ["<uuid>", …] }` (0–24 friends) | **201** `{ conversation }`. `403 not_friends` (+ `uuids`), `409 group_full`, `409 group_limit` (you own 20 / are in 100), `409 target_group_limit` (+ `uuids`), `400 invalid_name`, `422 message_blocked` (word filter). |
| `PATCH /v1/chat/groups/{id}` | `{ "name": "…" }` | `{ conversation }`. Owner only (`403 not_owner`). Adds a `renamed` system message. |
| `POST /v1/chat/groups/{id}/members` | `{ "members": ["<uuid>", …] }` (1–24) | `{ conversation }`. Owner only; only the owner's friends (`403 not_friends` + `uuids`); already present members are ignored; `409 group_full`, `409 target_group_limit`. One `member_added` system message per new member. |
| `DELETE /v1/chat/groups/{id}/members/{uuid}` | – | **204**. Owner removes a member (`404 member_not_found`). With your **own** UUID it is the same as leaving. |
| `POST /v1/chat/groups/{id}/leave` | – | **204**. The owner hands the group to the longest member (`owner_changed`); the last member deletes the group. |
| `POST /v1/chat/groups/{id}/owner` | `{ "uuid": "<member>" }` | `{ conversation }`. Owner only. |
| `DELETE /v1/chat/groups/{id}` | – | **204**. Owner only. Deletes all messages and images for everyone. |

Group names: 1–32 characters after sanitising (no control characters, one line).

### 18.4 Messages

| Request | Body | Response / errors |
|---|---|---|
| `GET /v1/chat/conversations/{id}/messages?limit=50` | – | `{ messages: [MessageView], hasMore }` – the newest `limit` (1–100) messages, **ascending by `seq`**. `hasMore` = older ones exist. |
| `…/messages?before=<seq>&limit=50` | – | Older page: messages with `seq < before`, ascending. `hasMore` = even older ones exist. |
| `…/messages?after=<seq>&limit=100` | – | Catch-up: messages with `seq > after`, ascending. `hasMore` = newer ones exist (call again). `before` and `after` together → `400`. |
| `POST /v1/chat/conversations/{id}/messages` | `{ "text"?, "replyTo"?, "attachments"?, "invite"?, "world"?, "nonce"? }` | **201** `{ message }`; **200** `{ message }` if the `nonce` was already used (same message, nothing new sent). |
| `PATCH /v1/chat/messages/{id}` | `{ "text": "…" }` | `{ message }` with `editedAt`. Own text messages only (`403 not_sender`); deleted → `409 message_deleted`. The text may become empty only if the message has images or an invite. Images and invite can't be edited. |
| `DELETE /v1/chat/messages/{id}` | – | `{ message }` (the tombstone). Own messages; in groups the owner may also delete others' (`deletedBy: "owner"`). Otherwise `403 not_sender`. Images are deleted with it. Idempotent. |
| `PUT /v1/chat/messages/{id}/reactions/{emoji}` | – | `{ reactions: [ReactionView] }` (all of the message). Idempotent. Unknown emoji id → `404`. |
| `DELETE /v1/chat/messages/{id}/reactions/{emoji}` | – | `{ reactions }` |

**Send body**

- `text`: up to 8000 raw characters in the request; after sanitising at most **2000** code points (`400 message_too_long`). Control, bidi-override and invisible format characters are removed (emoji joiners stay).
- `replyTo`: a message id of the same conversation that you can see and that isn't deleted (`404 message_not_found`).
- `attachments`: up to **10** attachment ids from `POST /v1/chat/attachments`, uploaded by **you** and not used yet (`404 attachment_not_found`, `400 too_many_attachments`). Order is kept.
- `invite`: `{ "address": "host[:port]", "name"?: "≤32 chars" }` (§18.6).
- `world`: `{ "roomId": "h…" }` – a world card of **your** hosted world (§21.8). Not together with `invite` (`400 invite_conflict`).
- `waypoint`: a waypoint card (§18.10). Only one card per message: invite, world or waypoint (`400 invite_conflict`).
- At least one of text / attachments / invite / world / waypoint, else `400 empty_message`.
- `nonce`: optional `^[A-Za-z0-9_-]{8,64}$`, generated by the client per message (e.g. a UUID). Retrying with the same nonce returns the stored message instead of a duplicate. A nonce used in another conversation → `409 nonce_reused`.

**Content errors (send and edit):** `422 message_blocked` (admin word filter, block mode), `422 spam_detected` (§20.4), `422 links_not_allowed` (groups), `403 not_friends`, `403 chat_muted`.

Word filter entries in **mask** mode don't reject: the word is replaced by `*` in the stored text.

### 18.5 Read state, unread mark, mute, typing

| Request | Body | Response |
|---|---|---|
| `POST /v1/chat/conversations/{id}/read` | `{ "seq": 42 }` | `{ conversation }`. Read up to `seq` (clamped to `lastSeq`, never goes back). Clears the unread mark. Sends a read receipt if you share them. |
| `POST /v1/chat/conversations/{id}/unread` | none, or `{ "seq": 40 }` | `{ conversation }`. Sets the unread mark. With `seq`, your read position moves back to `seq - 1` ("unread from this message"). Read receipts others saw are **not** taken back. |
| `PUT /v1/chat/conversations/{id}/mute` | `{ "muted": true, "until"?: ISO }` / `{ "muted": false }` | `{ conversation }`. Notification mute for **you** (toasts, badge total). Without `until` = until unmuted. `until` in the past → `400 invalid_until`. |
| `POST /v1/chat/conversations/{id}/typing` | `{ "typing": true\|false }` | **204** always (also when nothing is sent). |

**Typing:** send `true` at most every **3 s** while the user types, `false` when the input is cleared or the conversation closed. Sending a message ends typing automatically. Receivers get `chat_typing` (§19) and **must hide it after `expiresInMs` (8000)** without a refresh. Players with `chatTypingIndicator: false` send nothing and receive nothing (mutual); a player who blocked you never sees your typing.

### 18.6 Server invites

An invite is a card with the server address (`host[:port]`, hostname or IPv4, like the Minecraft "Server address" field; stored lower-case) and an optional label. **Icon, player count and MOTD are not part of the message** – clients fetch them live:

`GET /v1/servers/status?address=play.example.net:25566` → **200**
```json
{ "status": {
  "address": "play.example.net:25566",
  "online": true,
  "reason": null,
  "version": { "name": "Paper 1.21.4", "protocol": 769 },
  "players": { "online": 12, "max": 100 },
  "motd": "Welcome to Survival",
  "icon": "data:image/png;base64,…",
  "latencyMs": 38,
  "checkedAt": "…" } }
```

- **The TRS server pings** (Minecraft Server List Ping), not the receivers: a foreign server never learns the receivers' IP addresses, and the sender can't fake player counts or icons.
- Cached **60 s** (online) / **30 s** (offline) per address; at most 8 pings at once; 3 s timeout; answer ≤ 256 KiB.
- Without a port, the SRV record `_minecraft._tcp.<host>` is used (like the game), else 25565.
- **SSRF protection:** DNS is resolved once and the checked IP is used; only public unicast addresses and ports 1024–65535. Private/LAN addresses are never pinged.
- `online: false` with `reason`: `private_address` (LAN/local/blocked – show the card without status), `unresolvable`, `timeout`, `refused`, `invalid_response`, `busy` (try again shortly), `disabled` (feature off on the server).
- `motd` is plain text (colour codes removed, ≤ 256 chars). `icon` is a 64×64 PNG data URL, re-encoded by the server, or `null`.
- Limit: 30 / min per account. "Join" is up to the client (launcher: start an instance with `joinAddress`; mod: connect to the address).

### 18.7 Images

`POST /v1/chat/attachments` with the raw image as body and `Content-Type: image/png`, `image/jpeg` or `image/webp` → **201** `{ attachment: AttachmentView }`.

- At most **5 MiB** per file (`413 payload_too_large`). The type must match the file's magic bytes (`415 unsupported_media_type`). Animated WebP → `400 animated_image`; broken files → `400 invalid_image`.
- At most **8192 px** per side and **24 megapixels** (checked in the header before decoding, `400 image_too_large`).
- The server **decodes and re-encodes** every image: no metadata survives (EXIF/GPS, comments, colour profiles, text chunks). JPEG EXIF orientation is applied. Output: **JPEG** (quality 85) when fully opaque, **PNG** when it has transparency; scaled down to at most **2048 px** per side. A thumbnail (≤ 400 px, JPEG 75 / PNG) is created too.
- An upload is private to you until you send it in a message (up to 10 per message). Unused uploads are deleted after **1 hour**. At most 30 unsent uploads at once (`409 too_many_pending_attachments`).
- Quota: **250 MB** of images per account (`409 storage_quota`); the server has a global limit (`507 storage_full`).
- Limit: 40 uploads / 10 min per account.

`GET /v1/chat/attachments/{id}` (full image) or `?thumb=1` (thumbnail) → the image bytes (`image/jpeg` or `image/png`). Allowed for the uploader and for members who can see the message; else `404 attachment_not_found`. The content of an id never changes: `Cache-Control: private, max-age=31536000, immutable` + `ETag` (`If-None-Match` → 304). Limit: 600 / min.

**Picking images (clients):** the user chooses from "All", "Favourites" or "Uploads" (screenshots/clips folders of the instance, launcher favourites) – that is client-side; the API only sees the uploaded bytes.

### 18.8 Blocking, unfriending, account deletion

| Event | Effect on chat |
|---|---|
| Unfriend | The DM stays readable, `canWrite: false` for both (`chat_conversation` event). Groups are not affected. |
| Block | Ends the friendship (as above). In groups the blocker gets the blocked player's messages as `hidden`. The blocked player doesn't see your typing. |
| Befriend again | The same DM becomes writable again (`chat_conversation`). |
| `DELETE /v1/me` | All DMs of the account are deleted **for both sides** (`chat_conversation_removed` with `reason: "deleted"`). In groups all own messages, reactions and images are removed (`chat_reload` to the members); owned groups go to the longest member; groups left empty are deleted. Pending uploads are deleted. |
| Ban | The account can't log in; its messages stay visible. Admins can delete them via moderation (§20). |

### 18.9 Encryption at rest and key handling

- Message bodies (text, invite, system data), group names, report evidence and notes are stored as **AES-256-GCM** ciphertext (random 96-bit IV per record, the record id as authenticated data). Images (full + thumbnail) and evidence copies are encrypted files in `<DATA_DIR>/chat/`.
- **Key:** env `CHAT_KEYS` = comma-separated `id:base64(32 bytes)` (`id` = `[A-Za-z0-9_-]{1,16}`), the **first** key encrypts new data, the others only decrypt. Generate one with `openssl rand -base64 32`. Without `CHAT_KEYS` the server derives a key from `SECRET_KEY` (HKDF-SHA256, id `s1`) and logs a warning – set `CHAT_KEYS` in production.
- **Rotation:** put the new key in front (`CHAT_KEYS=k2:NEW,k1:OLD`; to move away from the derived key use `CHAT_KEYS=k1:NEW,s1:derived` and keep `SECRET_KEY` unchanged) and restart. A background job re-encrypts old messages, group names, reports and images every minute and logs `chat key rotation done` when finished; only then remove the old key. **Losing a key makes the data encrypted with it unreadable.** Keys are never in the database or backups of `DATA_DIR`; back them up separately.
- Metadata stays in plaintext for queries: who is in which conversation, sender, times, sequence numbers, image sizes, reactions, read positions.

### 18.10 Waypoint cards

A waypoint from the TRS Client ("📍 Base · 100 64 -20 · Overworld · play.example.net"). **No free text** except the name; everything else is strictly typed.

Send (`waypoint` in the send body, §18.4):
```json
{ "waypoint": {
    "name": "Base",
    "x": 100, "y": 64, "z": -20,
    "dimension": "minecraft:overworld",
    "world": { "type": "server", "address": "play.example.net:25566" },
    "color": 14690334 } }
```

| Field | Rule |
|---|---|
| `name` | 1–32 characters after sanitising (one line, control/bidi/invisible characters removed, whitespace collapsed); word filter applies (`mask` / `422 message_blocked`). Empty or longer → `400 invalid_waypoint`. |
| `x`, `z` | integers −30 000 000 … 30 000 000 |
| `y` | integer −2048 … 4096 |
| `dimension` | namespaced id `^[a-z0-9_.-]{1,32}:[a-z0-9_./-]{1,64}$`, e.g. `minecraft:overworld`, `minecraft:the_nether`, `minecraft:the_end` (clients map legacy 0 / −1 / 1 to these) |
| `world` | `{ "type": "server", "address": "host[:port]" }` (like a server invite, stored lower-case) **or** `{ "type": "world", "id": "<16 hex>" }` for singleplayer: the first 16 hex digits of SHA-256 of the UTF-8 string `"sp:" + <world folder name>` – the world name itself is never sent |
| `color` | optional integer 0 … 0xFFFFFF (`null` in the view when missing) |

- In **groups**, a waypoint with `world.type = "server"` counts like a server invite (`422 links_not_allowed` unless you own the group or are friends with every member). Singleplayer waypoints don't.
- The view (`MessageView.waypoint`) repeats the fields: `{ name, x, y, z, dimension, world, color }`. Deleted or hidden messages have `waypoint: null`. Editing only changes the text; a message with a waypoint may have an empty text.
- **Clients:** "Take over" creates the waypoint only when the current world matches (same server address – port 25565 equals no port – or the same singleplayer hash), otherwise they explain where it belongs. "Show" opens the map at x/z. The launcher shows the card with "Copy coordinates".
- Report evidence (§20.3) contains the card.

---

## 19. Realtime: `GET /v1/events/me` (one stream per user)

The launcher and the mod each keep **one** stream open while signed in. It carries **every** event about your account within **≤ 3 s** (in practice immediately): chat, friends, presence, cape offers, report feedback, moderation, settings from your other devices. REST endpoints stay the source of truth and the fallback.

`GET /v1/events/me` with `Authorization: Bearer …` (and optionally `Last-Event-ID`). Response: `text/event-stream`, frames like §7 plus an `id:` line:

```
id: mfz2k1a3b4c.1842
event: chat_message
data: {"type":"chat_message","conversationId":"c…","message":{…}}

```

**Resume:** every event (except typing) has an id `<epoch>.<n>`. On reconnect send the last id you received as header `Last-Event-ID` (EventSource does this automatically) or as `?lastEventId=`. The server then first sends `hello` with `resumed: true` and replays everything you missed. It keeps **the last 300 events per account for 10 minutes** after a disconnect. If it can't fill the gap (server restarted, too many or too old events, unknown id) you get `event: resync` with `{"type":"resync","reason":"restart"|"gap"|"invalid"}`: **reload your state via REST** (`GET /v1/friends`, `GET /v1/chat/conversations`, `GET /v1/chat/unread`, `GET /v1/cape-offers`, open message lists with `after=<last seq>`) and continue with the stream.

| event | data |
|---|---|
| `hello` | `{"type":"hello","keepaliveSec":20,"resumed":bool,"replayWindowSec":600}`. First frame. On a fresh start it carries an `id` so that resuming works even without events. |
| `resync` | `{"type":"resync","reason":…}` (see above) |
| `ping` | `{}` every 20 s. No ping for 60 s → reconnect. |
| `chat_message` | `{conversationId, message: MessageView}` – new message (also your own, for your other devices; match `message.nonce`). System messages come this way too. |
| `chat_message_edited` | `{conversationId, message}` |
| `chat_message_deleted` | `{conversationId, message}` (tombstone) |
| `chat_reactions` | `{conversationId, messageId, reactions: [ReactionView]}` – full current list |
| `chat_typing` | `{conversationId, uuid, typing, expiresInMs: 8000}` – **no id, not replayed** |
| `chat_read` | `{conversationId, uuid, seq, at}` – a member's read receipt (mutual rule) or **your own** from another device (`uuid` = you) |
| `chat_state` | `{conversationId, unread, markedUnread, readSeq, muted, mutedUntil}` – only to you, after read/unread/mute/sending |
| `chat_conversation` | `{conversation: ConversationView}` – created, renamed, members/owner changed, write permission changed (friendship) |
| `chat_conversation_removed` | `{conversationId, reason: "left"\|"removed"\|"deleted"}` – drop it locally |
| `chat_reload` | `{conversationId}` – messages were removed server-side (account deleted); reload the message list |
| `friend_request` | `{from:{uuid,name}}` |
| `friend_request_cancelled` | `{uuid}` |
| `friend_added` | `{friend:{uuid,name}}` – also to your other devices when **you** accepted |
| `friend_removed` | `{uuid}` – also to your other devices when **you** removed/blocked |
| `friends_changed` | `{}` – your own list changed by an action on another device (request sent/declined/cancelled, block, unblock): reload `GET /v1/friends` |
| `presence` | `{uuid, presence: PresenceView\|null}` – a friend's presence changed (§7) |
| `friend_online` | `{friend:{uuid,name}, presence}` – a friend just came online (was offline or hidden): use it for the "X is online" toast |
| `cape_offer`, `cape_offer_accepted`, `cape_share_removed` | as in §7 / §5.10 |
| `report_update` | `{report:{id, kind, status, outcome, updatedAt}}` – feedback on **your** report (§20.2) |
| `moderation` | `{action: "warn"\|"mute"\|"unmute", reason: string\|null, until: ISO\|null}` – a moderation decision about you. `mute` with `until: null` = until review / lifted. |
| `settings` | `{settings}` – your settings were changed (by another device) |
| `sanction_added`, `sanction_updated`, `appeal_decided` | moderation v2 – see §22.9 |
| `application_updated` | `{application: MyApplicationView}` – your team application changed (§24.3) |
| `hosting_*` | world hosting: `hosting_invite`, `hosting_invite_revoked`, `hosting_join_request`, `hosting_join_accepted`, `hosting_join_declined`, `hosting_kicked`, `hosting_room`, `hosting_room_updated`, `hosting_room_closed`, `hosting_signal` – see §21.5 |
| `achievement_unlocked` | `{achievement: AchievementView, at, reward: {kind, id}\|null}` – you unlocked an achievement (§31.6) |
| `notes_changed` | `{cursor}` – your synced notes changed (§17.5); fetch `GET /v1/me/sync/notes?since=<your cursor>` |
| `events_changed` | `{events}` – the events active for you changed (§32); `events` is the full list (`[]` = none) |

`?pushDevice={id}` (optional, §33): set by the mobile app on its push device. While such a stream is open (and 60 s after), that device gets no push notifications. Streams without it count as desktop (§33.6).

**Rules**

- At most **5** `events/me` streams per account (`503 too_many_streams`), connects limited to 20 / min. A stream lasts at most **1 hour**, then reconnect (with `Last-Event-ID`, so nothing is lost).
- It closes immediately on logout-all, ban or account deletion (the replay buffer is dropped then).
- **Back-pressure:** if a client doesn't read and more than 512 KiB pile up, the server closes the stream; reconnect with `Last-Event-ID`.
- Reconnect with backoff 1 s, 2 s, 5 s, … up to 30 s; reset after a successful `hello`.
- Clients must ignore unknown event types (new ones will be added).
- Events are addressed per account: a chat event only reaches members of that conversation; presence only reaches friends who may see it.

**Relation to the other streams**

- `GET /v1/events` (§7) stays for older clients. It only carries the friend/presence/cape events listed in §7 and has no ids. New clients use `events/me` instead – **not both**.
- `GET /v1/events/players` (§13) stays separate: it is about **other players you render in-game** (emotes, skins, capes, cosmetics, badges). The mod keeps both: `events/me` (account) + `events/players` (world).
- **Polling fallback** when the stream is down: `GET /v1/chat/unread` every 30–60 s, `GET /v1/friends` every 60 s; open conversations: `…/messages?after=<last seq>` every 10 s.

---

## 20. Reports and moderation (chat)

### 20.1 Reporting (players)

`POST /v1/reports` → **201** `{ report: MyReportView }`

```json
{ "kind": "message", "reason": "insult_hate", "note": "optional, ≤ 500 chars", "messageId": "m…" }
```

| `kind` | Needs | Target (reported player) | Evidence snapshot |
|---|---|---|---|
| `message` | `messageId` you can see | the sender | 10 messages before + the message + up to 10 after (as far as **you** can see them), images of the message are copied |
| `image` | `attachmentId` of a message you can see | the sender | as `message`, only this image is copied |
| `player` | `uuid` (TRS user), optional `conversationId` you share with them | the player | with `conversationId`: its last 20 messages; else none |
| `group` | `conversationId` of a group you're in | the owner | the last 20 messages, name and members |
| `share` | `shareId` of an active shared screenshot (§23) | the owner | a copy of the image and its dates, no messages |

`reason`: `insult_hate` (Beleidigung/Hass), `spam`, `inappropriate` (Unangemessen), `scam_phishing` (Betrug/Phishing), `harassment` (Belästigung), `other` (Sonstiges).

Errors: `404 message_not_found` / `attachment_not_found` / `conversation_not_found` / `player_not_found` (also for things you can't see), `400 cannot_target_self`, `400 not_reportable` (system message), `409 message_deleted`, `409 already_reported` (same thing, still open), `409 too_many_open_reports` (≥ 20 open), `429` (10 reports / h).

`GET /v1/reports` → `{ reports: [MyReportView] }` (your last 100):
```json
{ "id": "r0123456789abcdef", "kind": "message", "reason": "spam", "status": "open", "outcome": null, "createdAt": "…", "updatedAt": "…" }
```
`status`: `open` → `in_review` → `resolved`; `outcome` (when resolved): `actioned` (something was done) or `dismissed`. Reporters never learn which sanction was taken.

### 20.2 Feedback

The reporter gets `report_update` (§19) when the status changes (in review, resolved). Clients show e.g. "Thanks – we took action" / "We reviewed your report".

### 20.3 What the evidence contains and how long it is kept

- The snapshot is taken **at report time** and stored **encrypted**. Later edits or deletions of the messages don't change it; copies of the reported images are kept even if the sender deletes them.
- Kept while the report is open or in review, and **90 days** after it was resolved (for appeals); then the snapshot, notes and image copies are deleted. The report itself (type, reason, status, outcome – no content) is deleted **1 year** after resolution.
- If the **reporter** deletes their account, the report stays without reporter. If the **reported** player deletes theirs, reports against them and their evidence stay until the periods above end (legitimate interest: moderation can't be escaped by deleting). An active chat mute survives account deletion (like a ban); warnings and ended mutes are deleted with the account.

### 20.4 Automatic protection

| Mechanism | Rule |
|---|---|
| Send rate | 30 messages / min and 5 per 5 s per account (`429 rate_limited`, `Retry-After`) |
| Spam brake | The same text (normalised) 3× within 2 minutes, or more than 5 links in one message → `422 spam_detected`. Three spam hits within 10 minutes → **automatic 10-minute mute** (`moderation` event, `auto: "spam"`). |
| Link/invite filter | Groups: see §18 (`422 links_not_allowed`). |
| Word filter | Optional, maintained by admins (§20.5): `mask` replaces the word with `*`, `block` rejects the message (`422 message_blocked`). Compared case-, accent- and simple-leetspeak-insensitively, per word or "contains"; no regular expressions. |
| Auto-mute pending review | When **3 different** trusted reporters report the same player within **24 h** (open reports or confirmed ones), the player is muted **until an admin reviews** (`until: null`, `auto: "reports"`). When all reports against the player are resolved without a mute/ban decision, the automatic mute is lifted. |
| Report abuse | 10 reports / h, max 20 open, one open report per reporter and item. Reporters with ≥ 3 dismissed reports and more than twice as many dismissed as confirmed are **low trust**: their reports are still reviewed (flagged `lowTrust`) but don't count for the auto-mute. |

`GET /v1/me/moderation` → `{ mute: { until, reason, auto } | null, warnings: [{ reason, at }] }` (warnings of the last 90 days). Show a banner "You are muted in chat until …" and disable the input.

### 20.5 Admin API

Auth like §8 (admin bearer token, `X-Admin-Key`, or the website session cookie with `X-CSRF-Token` on mutations). Every mutation is written to the audit log.

| Method and path | Body | Response |
|---|---|---|
| `GET /v1/admin/reports?status=active\|open\|in_review\|resolved\|all&kind=&target=<uuid>&limit=50&cursor=` | – | `{ reports: [AdminReportSummary], nextCursor, counts: { open, in_review, resolved } }`. Default `active` (= open + in review). Open lists are oldest first, the others newest first. |
| `GET /v1/admin/reports/{id}` | – | `{ report: AdminReportDetail }` |
| `POST /v1/admin/reports/{id}/status` | `{ "status": "open"\|"in_review" }` | `{ report }`. `in_review` assigns you. Resolved reports → `409 report_resolved`. |
| `POST /v1/admin/reports/{id}/actions` | `{ "action", "reason"?, "minutes"?, "keepOpen"?, "includeRelated"? }` | `{ report }` |
| `POST /v1/admin/reports/{id}/notes` | `{ "text": "≤ 2000" }` | `{ report }` (notes are internal, encrypted) |
| `GET /v1/admin/reports/{id}/images/{attachmentId}` | – | The kept image copy (`private, no-store`). |
| `GET /v1/admin/moderation/users/{uuid}` | – | `{ moderation: { uuid, name, mute, sanctions: [Sanction], reportsAgainst: {total,open,actioned,dismissed}, reportsFiled: {…, lowTrust} } }` |
| `POST /v1/admin/moderation/users/{uuid}/mute` | `{ "minutes"?: 5–525600, "reason"? }` | `{ moderation }`. Without `minutes` = until lifted. Admins → `409 cannot_moderate_admin`. |
| `DELETE /v1/admin/moderation/users/{uuid}/mute` | – | `{ moderation }` or `404 not_muted` |
| `POST /v1/admin/moderation/users/{uuid}/warn` | `{ "reason": "…" }` | `{ moderation }` |
| `GET /v1/admin/chat/word-filter` | – | `{ words: [{ id, word, mode: "word"\|"contains", action: "mask"\|"block", createdAt, createdBy }] }` |
| `POST /v1/admin/chat/word-filter` | `{ "word", "mode"?: "word", "action"?: "mask" }` | **201** `{ word }`. Stored normalised (2–48 letters/digits, `400 invalid_word`); `409 word_exists`; at most 2000 entries. |
| `DELETE /v1/admin/chat/word-filter/{id}` | – | 204 or `404 word_not_found` |
| `GET /v1/admin/audit?ref=<reportId>&target=<uuid>&before=<id>&limit=100` | – | `{ entries: [{ id, at, actor, actorName, action, target, targetName, detail, ref }], nextBefore }` – newest first. |

**Actions** (`POST …/actions`):

| `action` | Effect |
|---|---|
| `delete_message` | Deletes the reported message for everyone (`deletedBy: "admin"`, images removed). Only for message/image reports (`409 no_message`). |
| `delete_share` | Deletes the reported shared screenshot (§23) for everyone. Only for share reports (`409 no_share`). |
| `warn` | Warning to the target (`moderation` event with `reason`). |
| `mute` | Chat mute for `minutes` (5–525600) or until lifted; replaces an automatic mute. |
| `ban` | Bans the account (§8, all TRS features). |
| `dismiss` | Resolves as `dismissed` (counts against the reporter's trust). |
| `resolve` | Resolves as `actioned` without a further action (after earlier actions with `keepOpen`). |

- `delete_message`, `warn`, `mute` and `ban` resolve the report as `actioned` unless `keepOpen: true` (then it moves to `in_review`), so several actions can be combined.
- `includeRelated: true` resolves the other open reports about the same message (or, for player/group reports, the same target and kind) with the same outcome; each reporter gets feedback.
- Actions that need a player on a report without target → `409 no_target`.

**AdminReportSummary**: `{ id, kind, reason, status, outcome, reporter:{uuid,name}|null, target:{uuid,name}|null, conversationId, messageId, attachmentId, shareId|null, anonymous (website report without account, §23.4), preview (≤ 140 chars of the reported text)|null, images, lowTrust, assignedTo:{uuid,name}|null, targetOpenReports, createdAt, updatedAt, resolvedAt, resolvedBy, evidencePurged }`

**AdminReportDetail** = summary + `note` + `evidence: { capturedAt, reporter, target, conversation: {id, kind, name, owner, members:[{uuid,name}]}|null, focus: messageId|null, messages: [{ id, seq, kind, sender, text, invite, world?, waypoint?, system:{event,target,name}|null, attachments:[{id,width,height,mime}], replyTo, createdAt, editedAt, deleted }], images: [{ id, width, height, mime, path }], share?: { id, width, height, mime, createdAt, expiresAt }, anonymous? } | null` + `notes: [{id, at, actor, actorName, text}]` + `audit: [{at, actor, actorName, action, detail}]` + `targetModeration: { mute, sanctions, reports:{total,open,actioned,dismissed} } | null` + `reporterStats: { actioned, dismissed, low, open } | null` + `related: [AdminReportSummary]` (other reports against the target, newest 20).

**Sanction** (chat view, only warnings and chat mutes of the unified sanctions, §22): `{ id, kind: "warn"|"mute", reason, reportId, auto: "reports"|"spam"|null, createdAt, createdBy, expiresAt, liftedAt, liftedBy, active }`. Warnings given through these routes now end after 30 days; moderators need `minutes` ≤ 10080 for mutes.

`GET /v1/admin/stats` additionally returns `chat: { conversations, groups, messages, messagesLast24h, images, storageBytes, storageLimitBytes }` and `reports: { open, inReview, resolved, activeMutes }`.

The website admin page has the tab **Reports** (list with filters, review dialog with context and actions, word filter, moderation log); the launcher's admin page uses the same endpoints.

---

## 21. World hosting (play a singleplayer world with friends)

A player opens their singleplayer world (the game's integrated server) for friends – **without port forwarding**. The TRS API only does **access control** (invites, join requests, kick/ban) and **signalling** (exchanging connection candidates over `/v1/events/me`). Game data never goes through the API:

1. **Direct (P2P) first:** host and guest learn their public address from STUN (`stun` list), exchange candidates via `POST …/signal` → `hosting_signal`, punch a UDP hole and run a reliable stream on top (client side).
2. **Relay as fallback:** the TRS Relay (own server, TCP + UDP) forwards the bytes. It only accepts connections with a short-lived **relay token** issued here (§21.6).

Everything needs auth. The whole feature answers `503 hosting_unavailable` when the server has no relay configured (`RELAY_SECRET`/`RELAY_HOST` unset). "Public link" (anyone with a link can join) is **not** part of this API: the client uses the third-party service e4mc for it (see the privacy note in §21.9).

**Rules at a glance**

- A host has **at most one** world (room) at a time. Opening a new one closes the old one (`hosting_room_closed` with `reason: "replaced"`).
- A room lives while the host sends a **heartbeat** at least every **90 s** (recommended: every 30 s). Otherwise it closes (`reason: "expired"`).
- `maxPlayers` counts the host: **2–10** (the relay enforces 10 as well). At most `maxPlayers − 1` guests can be **accepted**.
- **Invites** go to **friends** only. An invited player joins **immediately** (no further confirmation). Everybody else sends a **join request** that the host accepts or declines.
- **Who can see a room:** its members (invited / requested / accepted) and – when the room is `open` and has `visibility: "friends"` – all friends of the host. Everybody else gets `404 room_not_found` (you can't probe for rooms). Banned players and blocks (either direction) never see it.
- **Join code:** 6 characters from `ABCDEFGHJKMNPQRSTUVWXYZ23456789` (no `0/O`, `1/I/L`). Show it as e.g. `ABC-DEF`; input is case-insensitive, spaces and dashes are ignored. A code alone **never grants access**: it only lets any TRS user send a join request (or join directly if already invited).
- `open: false` = no new join requests (`409 world_closed`); invited and accepted players can still (re)join.

### 21.1 Views

**HostRoomView** (only the host sees this)
```json
{
  "id": "h0123456789abcdef0123",
  "code": "K7QM2X",
  "name": "Meine Welt",
  "host": { "uuid": "75c1…", "name": "Theredstonee" },
  "mcVersion": "1.21.4",
  "loader": "fabric",
  "maxPlayers": 4,
  "gameMode": "survival",
  "pvp": true,
  "cheats": false,
  "open": true,
  "visibility": "friends",
  "players": 2,
  "createdAt": "…",
  "expiresAt": "…",
  "members": [ { "uuid": "b0b0…", "name": "Bob", "state": "accepted", "since": "…" } ],
  "content": { "mods": 12, "required": 5, "fromHost": 2, "manual": 1, "pack": { "name": "Faithful", "size": 3145728, "sha1": "…" } }
}
```

- `id` matches `^h[0-9a-f]{20}$`. `loader`: `vanilla`, `fabric`, `forge`, `neoforge`, `quilt`. `gameMode`: `survival`, `creative`, `adventure`, `spectator`.
- `mcVersion`: `^[0-9A-Za-z][0-9A-Za-z._+ -]{0,31}$`. Guests should use the same version and loader.
- `players` = players currently in the world **including the host**, as reported by the heartbeat (starts at 1).
- `expiresAt` = when the room closes without a further heartbeat.
- `members[].state`: `invited`, `requested`, `accepted`, `banned` (banned in this room). `since` = last state change.
- `content` = what the world shares (§21.10) in short form, `null` = nothing: number of mods, how many are required,
  come directly from the host or are only a hint ("get it yourself"), and the resource pack (name, size, SHA-1) or `null`.
  In every view (host, friends, events, admin). The full list: `GET …/content`.

**RoomView** (friends, invitees, requesters, guests) = the same without `code`, `visibility`, `expiresAt` and `members`, plus
`"myState": "invited" | "requested" | "accepted" | null` (your own state; `null` = you only see it as the host's friend).

**ConnectInfo** (in create, join and connect answers)
```json
{
  "role": "guest",
  "relay": { "host": "relay.theredstonee.de", "tcpPort": 25503, "udpPort": 25504, "token": "trsr1.eyJ2Ijox….Qm9i…", "expiresAt": "…" },
  "stun": [ "relay.theredstonee.de:25504" ]
}
```
`relay.token` is only valid for **connecting** until `expiresAt` (≤ 2 min). Get a fresh one with `POST …/connect` before every (re)connect.

### 21.2 Host

| Request | Body | Response / errors |
|---|---|---|
| `POST /v1/hosting/rooms` | `{ "name", "mcVersion", "loader", "maxPlayers"?: 8, "gameMode"?: "survival", "pvp"?: true, "cheats"?: false, "open"?: true, "visibility"?: "friends" }` | **201** `{ room: HostRoomView, role: "host", relay, stun }`. Name: 1–32 chars after sanitising, one line (`400 invalid_name`); word filter like chat (`422 message_blocked`). An existing room of yours closes (`replaced`). |
| `GET /v1/hosting/rooms/mine` | – | `{ rooms: [HostRoomView] }` (0 or 1) – e.g. after a restart of the launcher/game. |
| `PATCH /v1/hosting/rooms/{id}` | any of the create fields, at least one | `{ room: HostRoomView }`. Fewer seats than accepted guests + 1 → `409 room_too_small`. Also counts as heartbeat. Friends who can no longer see the room (closed / `visibility: "invited"`) get `hosting_room_closed` with `reason: "hidden"`. |
| `POST /v1/hosting/rooms/{id}/heartbeat` | none, or `{ "players": 1–10 }` | `{ expiresAt }`. Every ~30 s. A changed `players` count is pushed as `hosting_room_updated`. |
| `DELETE /v1/hosting/rooms/{id}` | – | **204**. Everybody who saw it gets `hosting_room_closed` (`reason: "closed"`). |
| `POST /v1/hosting/rooms/{id}/invites` | `{ "uuid": "<friend>", "chat"?: true }` | **201** `{ member: MemberView, chatMessageId: string\|null }`. Friends only (`403 not_friends`); banned → `409 player_banned`; `409 too_many_invites` (50 open). Inviting a player who already **requested** accepts them. Inviting again re-sends `hosting_invite`. With `chat: true` a world card (§21.8) is posted in your DM as well – `chatMessageId: null` if that wasn't possible (e.g. chat mute); the invite stands anyway. |
| `DELETE /v1/hosting/rooms/{id}/invites/{uuid}` | – | **204**, invitee gets `hosting_invite_revoked`. `404 invite_not_found`. |
| `POST /v1/hosting/rooms/{id}/requests/{uuid}/accept` | – | `{ member }` – guest gets `hosting_join_accepted`. `404 request_not_found`, `409 room_full`. |
| `POST /v1/hosting/rooms/{id}/requests/{uuid}/decline` | – | **204** – requester gets `hosting_join_declined`. |
| `POST /v1/hosting/rooms/{id}/members/{uuid}/kick` | none, or `{ "ban"?: false, "remember"?: false }` | **204**. Removes the player (any state). `ban: true` = banned in this room (can't request/join again, doesn't see it). `remember: true` = additionally on your **permanent ban list** for all future worlds (≤ 500, `409 ban_limit`). Without ban: `404 member_not_found` if the player isn't there. The player gets `hosting_kicked {banned}`. **The game must disconnect the player itself** (and can tell the relay, §21.7 `KICK`). |
| `DELETE /v1/hosting/rooms/{id}/bans/{uuid}` | – | **204** – lifts the ban in this room (`404 ban_not_found`). A permanent ban still applies. |
| `GET /v1/hosting/bans` | – | `{ bans: [{ uuid, name, since }] }` – your permanent ban list. |
| `DELETE /v1/hosting/bans/{uuid}` | – | **204** / `404 ban_not_found`. |
| `PUT /v1/hosting/rooms/{id}/content` | `{ "mods": [SharedMod], "pack": SharedPack \| null }` (≤ 256 KB) | `{ room: HostRoomView }` – share mods and a resource pack (§21.10). Replaces the whole list; empty list + `null` pack = nothing shared. Everybody who can see the room gets `hosting_room_updated`. |
| `DELETE /v1/hosting/rooms/{id}/content` | – | **204** – stop sharing. |

`MemberView` = `{ uuid, name, state, since }`. Your other devices get the full `hosting_room` event after every change.

### 21.3 Guests

| Request | Body | Response / errors |
|---|---|---|
| `GET /v1/hosting/friends-rooms` | – | `{ rooms: [RoomView] }` – open rooms of your friends (`visibility: "friends"`) **plus** every room where you are invited, have requested or are accepted. Newest first. For "Friends' worlds" lists and after `resync`. |
| `GET /v1/hosting/invites` | – | `{ rooms: [RoomView] }` – only the ones with `myState: "invited"`. |
| `GET /v1/hosting/rooms/{id}` | – | `{ room }` – `HostRoomView` for the host, `RoomView` for others who may see it, else `404 room_not_found`. Use it to show the live state of a world card. |
| `POST /v1/hosting/join` | `{ "roomId": "h…" }` **or** `{ "code": "K7Q-M2X" }` | **200** `{ status: "accepted", room, role: "guest", relay, stun }` if you were invited or already accepted; **202** `{ status: "requested", room }` otherwise (host gets `hosting_join_request`; wait for `hosting_join_accepted`/`hosting_join_declined`). Requesting again is idempotent. Errors: `404 room_not_found`, `403 banned_from_world`, `409 world_closed`, `409 room_full`, `409 too_many_requests` (20 waiting), `400 cannot_join_own_world`. |
| `POST /v1/hosting/rooms/{id}/leave` | – | **204**. Leaves the world, withdraws a request or declines an invite. Your other devices get `hosting_room_closed` with `reason: "left"`. |
| `POST /v1/hosting/rooms/{id}/connect` | – | `ConnectInfo` with a fresh token. Host (`role: "host"`) or accepted guest; others `403 not_accepted` / `404 room_not_found`. |
| `GET /v1/hosting/rooms/{id}/content` | – | `{ roomId, mods: [SharedMod], pack: SharedPack \| null }` – the full list of shared mods and the resource pack (§21.10), for everybody who can see the room (host, members, friends of an open world); else `404 room_not_found`. |

By `roomId` you can only join rooms you may see (§21, rules). By `code` anyone with a TRS account can send a request. **Unknown codes count as failed attempts**: 10 / 10 min per account and 30 / 10 min per IP – after that every code attempt gets `429` (also a correct one).

### 21.4 Signalling (ICE)

`POST /v1/hosting/rooms/{id}/signal`
```json
{ "to": "<uuid>", "kind": "offer" | "answer" | "candidate" | "bye", "sid": "optional session id", "data": "opaque string" }
```
→ **200** `{ delivered: bool }` (`false` = the receiver has no open `events/me` stream right now; the event is still buffered for their resume).

- Only between the **host and an accepted guest** of this room: a guest can only signal the host (`404 peer_not_found` otherwise), the host only accepted guests (`404 peer_not_found`). Not accepted → `403 not_accepted`; strangers → `404 room_not_found`.
- `data` is opaque to the server (e.g. JSON with ICE ufrag/pwd/candidates, a key fingerprint). At most **4096 characters** (`400 signal_too_large`). Don't put secrets in it that the other side may not see.
- `sid` (`^[A-Za-z0-9_-]{1,32}$`) lets clients tell connection attempts apart; ignore signals with an old `sid`.
- The receiver gets `hosting_signal { roomId, from, kind, sid, data }` over `/v1/events/me` within ≤ 3 s (usually immediately).
- Limits: 120 / min and 30 / 5 s per account.

**Suggested flow:** guest `POST /join` (or `connect`) → gathers candidates (STUN `stun` list, local addresses) → `signal offer` → host answers with `signal answer` → both send `candidate`s as they come → UDP hole punching; after ~5–8 s without a working pair both fall back to the relay (§21.7). `bye` = stop this attempt.

### 21.5 Events (`/v1/events/me`, §19)

| event | to | data |
|---|---|---|
| `hosting_invite` | invitee | `{ room: RoomView, from: {uuid,name} }` – show a toast "X invites you to their world" with Join (= `POST /join {roomId}`). |
| `hosting_invite_revoked` | invitee | `{ roomId }` – invite withdrawn or friendship ended. |
| `hosting_join_request` | host | `{ roomId, from: {uuid,name} }` – toast with Accept / Decline. |
| `hosting_join_accepted` | guest | `{ room: RoomView }` – now `POST …/connect` (or use the tokens from `join`). |
| `hosting_join_declined` | guest | `{ roomId }` |
| `hosting_kicked` | guest | `{ roomId, banned }` |
| `hosting_room` | host (all devices) | `{ room: HostRoomView }` – after every change (members, settings, players). |
| `hosting_room_updated` | everybody who can see it | `{ room: RoomView }` – settings, open/closed, players count, new room of a friend. |
| `hosting_room_closed` | everybody who saw it, host devices | `{ roomId, reason: "closed"\|"expired"\|"replaced"\|"host_unavailable"\|"hidden"\|"left" }` – drop it locally. `hidden` = you may no longer see it (visibility, banned, unfriended, blocked). |
| `hosting_signal` | host or guest | `{ roomId, from, kind, sid, data }` (§21.4) |

All hosting events only go to `/v1/events/me` (not the legacy `/v1/events`) and are replayable like other events. Polling fallback: `GET /v1/hosting/friends-rooms` (guests), `GET /v1/hosting/rooms/mine` (host) every 30 s.

### 21.6 Relay token

`trsr1.<payload>.<signature>` (≤ 512 chars)

- `payload` = base64url (no padding) of the UTF-8 JSON
  `{"v":1,"r":"<roomId>","u":"<uuid>","h":"<host uuid>","role":"host"|"guest","m":<maxPlayers>,"iat":<unix s>,"exp":<unix s>,"n":"<16 hex nonce>"}`
- `signature` = base64url(HMAC-SHA256(`RELAY_SECRET`, `"trsr1." + payload`)), 43 characters.
- `exp − iat` ≤ 120 s. The relay checks signature (any of the configured secrets – rotation), expiry (5 s clock skew), role (`host` ⇒ `u == h`, `guest` ⇒ `u != h`) and the room limits. The token only matters for **opening** a connection; an established connection lives on.
- Clients treat the token as opaque and secret (never log it).

### 21.7 Relay protocol (TRS Relay, TCP 25503 + UDP 25504)

Byte-exact details: `relay/PROTOCOL.md` in the launcher repository. Summary:

**TCP** – every connection starts with the preamble `"TRSR"` + version `0x01`, then frames `type:u8, length:u16 BE, payload` (≤ 1024 bytes) until the connection switches to raw piping.

| Frame | Dir | Payload | Meaning |
|---|---|---|---|
| `0x01 HOST_HELLO` | host → relay | token (ASCII) | This is the room's **control** connection (a new one replaces the old: `ERROR replaced`). |
| `0x02 GUEST_HELLO` | guest → relay | token (ASCII) | Guest data connection. The relay tells the host (`GUEST_OPEN`) and waits ≤ 10 s for the host's `PAIR`. |
| `0x03 PAIR` | host → relay (new connection) | pairId (16 bytes) | Host data connection for that guest. |
| `0x81 WELCOME` | relay → client | small JSON | Handshake OK. On guest + host data connections **raw bytes** (the Minecraft stream) follow in both directions. |
| `0x8F ERROR` | relay → client | ASCII code | Then the relay closes. Codes: `bad_preamble`, `bad_frame`, `bad_token`, `expired`, `host_offline`, `host_timeout`, `unknown_pair`, `room_full`, `too_many_connections`, `rate_limited`, `replaced`, `kicked`, `server_full`, `shutting_down`, `idle_timeout`, `buffer_overflow`. |
| `0x20 GUEST_OPEN` | relay → host control | pairId (16) + guest UUID (16 raw bytes) | Open a new TCP connection, send `PAIR pairId`, then connect it to the integrated server. |
| `0x21 GUEST_CLOSED` | relay → host control | pairId (16) | |
| `0x22 CLOSE_GUEST` | host control → relay | pairId (16) | |
| `0x23 KICK` | host control → relay | UUID (16) | Closes all of the player's connections and refuses new ones while this control connection lives. |
| `0x30 PING` / `0x31 PONG` | both | ≤ 8 bytes echoed | Host must send something at least every 45 s (recommended PING every 15 s). |

**UDP** (25504): a standard **STUN Binding** responder (RFC 5389, XOR-MAPPED-ADDRESS) – any STUN library works – plus an optional datagram relay: packets start with `"TRSU"` + type: `0x01 BIND` + token → `0x81 BOUND` + 8-byte key; `0x02 DATA` + key + (host only: target UUID 16 bytes) + payload ≤ 1200 bytes → delivered as `0x82 DATA_FROM` + sender UUID + payload; `0x03 PING` + key → `0x83 PONG`; `0x04 UNBIND`; `0x8F ERROR` (≤ 1 / s per address). A binding is tied to the source address that sent `BIND`; bindings expire after 30 s without packets (PING every ~10 s). A guest can only bind while the host is connected (TCP control or UDP binding), else `host_offline`. At most 30 BINDs / min per IP.

**Limits:** max **10 players** per world (guests ≤ `m − 1`), ≤ 3 connections per guest, **20 Mbit/s** per world (TCP + UDP, both directions), handshake 10 s, idle pipes 5 min, per-IP connection and failure limits. The relay never logs or stores payloads.

### 21.8 World cards in chat

A chat message can carry a **world card** instead of a server invite (§18.4 send body `"world": { "roomId": "h…" }`):

- Only the **host of that room** may send it (else `404 room_not_found`); not together with `invite` (`400 invite_conflict`). In groups the link rule applies (`422 links_not_allowed`, §18).
- On sending, every recipient who is **friends with the host** (and not banned) is **invited** (`hosting_invite`). The others can still request via the code.
- `MessageView.world` = `{ roomId, code, name, mcVersion, loader, host: {uuid,name} }` (a snapshot; `null` for normal messages). `ReplyView.world` = `true` when replying to a card.
- **Join button** on the card: `POST /v1/hosting/join { "code": world.code }` (invited → in immediately, else request). Live state: `GET /v1/hosting/rooms/{roomId}` (`404` = world closed / not visible → show "World closed").
- Older clients don't know `world` and show an empty message; send a short `text` with the card if that matters.

### 21.9 Privacy, limits, deletion

- **Stored** (plaintext metadata, only while the room exists): room settings and name, join code, host, members with state and times, heartbeat time, reported player count, the shared mod list and resource pack info (§21.10 – metadata only, never files). Rooms and their members are **deleted** when the room closes (closing, 90 s without heartbeat, new room). **Kept:** the host's permanent ban list (until the host removes entries or deletes the account).
- **Signals** are passed through and kept only in the short replay buffer of `/v1/events/me` (≤ 10 min, RAM), never in the database. The API never sees game data.
- **IP addresses:** in P2P mode host and guest necessarily learn each other's public IP address (through the exchanged candidates) – clients should say so ("Direct connection shows your IP address to the other player"). Over the relay only the relay sees the IPs; it keeps them only in RAM for rate limits and never logs payloads. The STUN responder sees the address that asks.
- **Public link (e4mc):** a separate third-party service (e4mc, run by its own operators); when a player enables it, the game connects to e4mc's relay and anyone with the link can join. The TRS API isn't involved. Clients must show their own warning and privacy note (see launcher privacy text).
- **Account deletion (`DELETE /v1/me`):** closes your worlds (`host_unavailable`), removes you from others' worlds and deletes your ban list and your entries on others' ban lists. **Account ban:** same, except bans others set against you stay.
- **Blocking** removes the other player from your worlds and you from theirs (`hidden`, no hint about the block). **Unfriending** drops open invites between you; players already in the world stay.
- **Rate limits:** every `/v1/hosting/*` request 240 / min per account (own bucket), creating rooms 10 / 10 min, managing (invite, answer, kick, settings, content) 60 / min, join 20 / min, connect 30 / min, signals 120 / min + 30 / 5 s, failed codes see §21.3.

### 21.10 Mods and resource pack

The host can share **mods** and a **resource pack** with the guests of a world. The API stores only the **list**
(metadata); **files never go through TRS servers**: store mods come from Modrinth/CurseForge, everything else goes
directly from the host's game to the guest over the hosting connection (P2P or relay, a separate file channel –
`docs/hosting-files.md` in the launcher repository). Both are off by default in the client.

**SharedMod**
```json
{
  "name": "Sodium", "version": "0.6.0", "file": "sodium-fabric-0.6.0.jar", "size": 1200000, "required": false,
  "source": "modrinth", "projectId": "AANobbMI", "fileId": "Yp8wLY1P",
  "sha1": "<40 hex>", "sha512": "<128 hex>", "sha256": "<64 hex>", "fingerprint": 123456789
}
```

| Field | Rules |
|---|---|
| `name` | 1–64 characters, no control/format characters (sanitised; a blocked word turns it into the file name) |
| `version` | 0–64 characters, same rules (default `""`) |
| `file` | plain `.jar` file name, 5–128 characters, `^[A-Za-z0-9][A-Za-z0-9 ._+()[\]{}'!,&~@#$%=-]*\.jar$`, no `..` – never a path |
| `size` | bytes, 1 … 512 MB; `host` ≤ **64 MB** |
| `required` | required for joining (the guest can't deselect it) or optional |
| `source` | `modrinth` · `curseforge` · `host` (directly from the host, **not verified**) · `manual` (in no store and not sent – "get it yourself") |
| `projectId` / `fileId` | only store mods: Modrinth project + version id (`^[A-Za-z0-9]{8}$`), CurseForge mod + file id (digits, 1–10) |
| `sha1` | 40 lower-case hex – required, unique within the list (used for "present/missing") |
| `sha512` | 128 hex – required for `modrinth` |
| `sha256` | 64 hex – required for `host` (the file channel asks by it; guests check it) |
| `fingerprint` | CurseForge Murmur2 fingerprint (optional) |

**SharedPack** = `{ "name": 1–64, "size": 1 … 250 MB, "sha1": 40 hex, "sha256": 64 hex }` (SHA-1 as Minecraft's
server-resource-pack check wants it).

**Limits:** at most **300 mods**; `host` files together ≤ **512 MB**; request body ≤ 256 KB; unknown fields → `400`
(strict). Guests validate the list again (same rules).

**Who sees it:** the short form (`content`, §21.1) is part of every room view; the full list (`GET …/content`) is visible
to everybody who can see the room. Relay tokens (`…/connect`) still go to accepted guests only – the host's game serves
host files only to accepted members of the room.

---

## 22. Moderation v2 (roles, sanctions, player file, dashboard, appeals)

This section extends §8 and §20. Everything in it is implemented and covered by `tests/moderation-v2.test.ts` and the smoke test. Older endpoints (§8 ban/unban, §20.5 mute/warn, `GET /v1/me/moderation`) keep working and write into the same data.

### 22.1 Roles and permissions

> **Superseded by §24.2:** fine-grained permissions, default + custom roles and the rank rule replace the admin/moderator grid below. The table stays as the history of the old model; `role` in `GET /v1/me` is still sent (admin from rank 900, else moderator).

| Role | Who |
|---|---|
| `admin` | Every UUID in `ADMIN_UUIDS` (fixed, cannot be removed or sanctioned) and accounts an admin gave the role. |
| `moderator` | Accounts an admin gave the role. |

- `GET /v1/me` and the login response now carry `role: "admin" | "moderator" | null`. `admin` stays `true` only for admins.
- Team access to `/v1/admin/**`: website session cookie (+ `X-CSRF-Token` on mutations), bearer token of an admin **or moderator**, or `X-Admin-Key` (counts as admin). The website sign-in (§15) works for both roles.
- The role is checked on **every** request. Removing a role ends the website session at once.
- ~~`GET /v1/web-login/me`~~ → now `GET /v1/web/me` with `team: MyTeamView` (§24.1).

**Permission matrix** (server-side, `403 admin_only` / `403 forbidden` otherwise):

| Action | Moderator | Admin |
|---|---|---|
| Dashboard, search, player list and file, audit log (read) | ✓ | ✓ |
| Reports: list, detail, status, notes, actions, bulk dismiss/resolve | ✓ | ✓ |
| Warn (≤ 30 days), chat mute / social / upload / hosting ban (≤ 7 days) | ✓ | ✓ |
| Permanent sanctions, any duration > 7 days (warnings > 30 days) | – (`403 duration_not_allowed`) | ✓ |
| Account ban (`account_ban`, any duration; also report action `ban`, §8 ban/unban) | – (`403 admin_only`) | ✓ |
| Sanction team members | – (`403 cannot_moderate_staff`) | moderators only; admins never (`409 cannot_moderate_admin`) |
| Lift / shorten / extend sanctions | own and other moderators' (limits above) | ✓ |
| Change or lift sanctions **given by an admin** | – (`403 admin_only`) | ✓ |
| Decide appeals | ✓ except on own sanctions (`403 own_sanction`); lift/shorten of admin sanctions `403 admin_only` | ✓ |
| Capes/cosmetics: list, approve, reject, bulk | ✓ | ✓ |
| Capes/cosmetics: delete, grant/revoke to players | – | ✓ |
| Codes, word filter: read | ✓ | ✓ |
| Codes, word filter: create / change | – | ✓ |
| Roles | – | ✓ |
| Close hosted worlds | ✓ | ✓ |
| Player notes: add / delete own | ✓ | ✓ (also others') |

Nobody can sanction themselves (`400 cannot_target_self`).

**Roles API** (admins only):

| Method and path | Body | Response |
|---|---|---|
| `GET /v1/admin/roles` | – | `{ roles: [RoleView] }` |
| `PUT /v1/admin/roles/{uuid}` | `{ role: "admin"\|"moderator", note?: ≤200 }` | `{ roles }`. `409 role_locked` (ADMIN_UUIDS), `400 cannot_change_self`, `404 user_not_found` (must have signed in once). |
| `DELETE /v1/admin/roles/{uuid}` | – | `{ roles }`, `404 role_not_found`. |

`RoleView = { uuid, name, role, source: "env"|"db", grantedAt, grantedBy: {uuid,name}|null, note }`. Every change is audited (`role.set`, `role.remove`).

### 22.2 Sanctions

One table for everything. **Kinds:**

| kind | Effect (enforced server-side) | Error to the client |
|---|---|---|
| `warn` | Nothing blocked; counts in the history (shown in `/v1/me/moderation` for 90 days as before). | – |
| `chat_mute` | No chat messages, group renames, new groups (§18). | `403 chat_muted` |
| `social_ban` | No friend requests (send **and** accept), no new groups, no adding members, no server invites or world cards in chat, no world invites, no cape offers. Existing friendships and plain chat messages stay. | `403 sanctioned` |
| `upload_ban` | No cape uploads (§5.6), no cosmetic uploads (§11.7), no shared screenshots (§23). Existing uploads and links stay. | `403 sanctioned` |
| `hosting_ban` | No new worlds, no joining, no world invites, no relay tokens (`connect`). On creation all worlds of the player close (`hosting_room_closed`, `host_unavailable`) and they are removed from others' worlds. | `403 sanctioned` |
| `account_ban` | Every TRS online feature: sessions (also website) end, login is refused, streams close, presence ends, worlds close, lookups and player events no longer deliver the badge, cape or cosmetics (watchers get `badge:false`, `cape:null`, empty `cosmetics` at once). | `403 banned` |

**Duration** presets: `1h`, `6h`, `1d`, `3d`, `7d`, `30d`, `permanent`, `custom` (+ `minutes`, 5 – 5 256 000). The end is `createdAt + duration`; `null` = permanent (for automatic mutes: until review).

**Reason templates** (`reasonCode`, required): `spam`, `insult_hate`, `harassment`, `inappropriate_content`, `inappropriate_name`, `scam_phishing`, `impersonation`, `copyright`, `cheating`, `ban_evasion`, `other`. Set only by the system: `auto_spam`, `auto_reports`, `legacy` (taken over from before v2). Clients translate them.
Optional **public reason** (`reason`, ≤ 500, shown to the player) and **internal note** (`note`, ≤ 2000, never sent to players).

**Error details** (stable, for launcher and mod): every `sanctioned`, `chat_muted` and `banned` error carries

```json
{ "error": { "code": "sanctioned", "message": "…", "until": "2026-09-28T15:52:14.904Z",
  "sanction": { "id": 5, "kind": "upload_ban", "reasonCode": "copyright", "reason": "Cape with a foreign logo",
    "startsAt": "…", "endsAt": "…", "status": "active", "liftedAt": null, "appeal": null, "appealable": true } } }
```

`sanction` is a **MySanctionView** (§22.8) – never the note or the moderator. `until` = `sanction.endsAt` (kept for older chat clients).

**Admin API** (team; rate limit 60 / min per team member for create/lift/change/decide):

| Method and path | Body / query | Response |
|---|---|---|
| `GET /v1/admin/sanctions` | `?status=active\|expired\|lifted\|all` (default `active`), `kind`, `uuid`, `actor` (uuid, `api-key`, `system`), `from`, `to` (ISO, on `createdAt`), `sort=newest\|oldest`, `cursor`, `limit` (1–100, 50) | `{ sanctions: [AdminSanctionView], nextCursor }` |
| `POST /v1/admin/sanctions` | `{ uuid, kind, duration, minutes? (only custom), reasonCode, reason?, note?, reportId? }` | **201** `{ sanction }` |
| `GET /v1/admin/sanctions/{id}` | – | `{ sanction }` / `404 sanction_not_found` |
| `POST /v1/admin/sanctions/{id}/lift` | `{ reason: ≤500 }` | `{ sanction }` / `409 sanction_not_active` |
| `POST /v1/admin/sanctions/{id}/duration` | `{ endsAt: ISO \| null, reason: ≤500 }` (`null` = permanent) | `{ sanction }`. `400 invalid_duration` (end not in the future – lift instead), `409 no_change`, `409 sanction_not_active`. Shorter = `shorten`, longer = `extend`. |

`AdminSanctionView = { id, player:{uuid,name}, kind, reasonCode, reason, note, reportId, auto: "reports"|"spam"|null, createdAt, createdBy:{uuid,name}, createdRole: "admin"|"moderator"|"system", endsAt, permanent, status: "active"|"expired"|"lifted", liftedAt, liftedBy, liftReason, changes: [{ at, actor:{uuid,name}, action: "shorten"|"extend"|"lift", oldEndsAt, newEndsAt, reason }], appeal: AdminAppeal|null, migrated }`.
Lifted and expired sanctions stay in all lists (the website shows lifted ones struck through). `liftReason: "replaced"` = an automatic mute was replaced by a team decision.

**Report actions** (§20.5) additionally accept `action: "sanction"` with `kind`, `duration`, `minutes?`, `reasonCode?` (default: mapped from the report reason), `reason?`, `note?`, `keepOpen?`, `includeRelated?`. `warn` (now 30 days), `mute` and `ban` (= permanent `account_ban`, admins only) still work.

**Audit actions:** `chat.warn`, `chat.mute`, `chat.automute.spam|reports`, `sanction.social_ban|upload_ban|hosting_ban`, `user.ban`, `share.delete` (§23), `sanction.shorten|extend|lift`, `chat.unmute`, `chat.unmute.auto`, `user.unban`, `appeal.create`, `appeal.lifted|shortened|upheld`, `role.set|remove`, `player.note|player.note.delete`, `hosting.close`, plus the existing ones. `ref` = report id, `s<id>` (sanction) or `a<id>` (appeal).

### 22.3 Enforcement details

- Temporary bans end by themselves; the next login works again.
- **Login of a banned account:** `POST /v1/auth/verify` → `403 banned` with the details above **plus** `appealToken` (`trs_…`) and `appealTokenExpiresAt` (1 hour). This token only works for `GET /v1/me/sanctions` and `POST /v1/me/sanctions/{id}/appeal`; everything else answers `403 banned`. After the ban ended it answers `401` (log in again). Older clients just see `403 banned` as before.
- A new sanction publishes `sanction_added` (§22.9); a chat mute or warning also the old `moderation` event.

### 22.4 Player file

`GET /v1/admin/players/{uuid}` → `{ file: PlayerFile }` (`404 user_not_found` if the UUID has no account, sanction or report).

```
PlayerFile = {
  player: { uuid, name, known, role, online, firstLoginAt, lastLoginAt, friends, sessions, banned },
  names: [{ name, firstSeen, lastSeen }],            // name history since v2 (current name included)
  sanctions: [AdminSanctionView],                    // active + history, newest first (≤ 200)
  warnings: { total, active },
  reports: {
    against: { counts: {total, open, actioned, dismissed}, recent: [AdminReportSummary] },   // newest 20
    filed:   { counts: {…}, recent: [AdminReportSummary] },
    reporterScore: { actioned, dismissed, low, score }  // score = % confirmed, null without decided reports
  },
  capes: [CapeView + { createdAt, reports, source: "upload"|"code"|"admin" }],
  cosmetics: [CosmeticView + { createdAt, reports, source }],
  worlds: [AdminRoomView],                           // open worlds hosted by or joined by the player
  notes: [{ id, at, actor:{uuid,name}, text, deletable }],
  can: { sanction: bool, reason: null|"self"|"admin"|"staff", limits: { kinds, maxMinutes, maxWarnMinutes, permanent } }
}
```

Notes: `POST /v1/admin/players/{uuid}/notes` `{ text: 1–2000, line breaks allowed }` → **201** `{ notes }`; `DELETE /v1/admin/players/{uuid}/notes/{id}` → `{ notes }` (own notes; admins all – else `403 admin_only`). The audit log records only that a note was added, not its text.

`GET /v1/admin/players?q=<name prefix, also former names>&status=all|sanctioned|banned|staff|reported&sort=last_login|created&cursor&limit` → `{ players: [{ uuid, name, role, online, createdAt, lastLoginAt, activeSanctions: [kind], openReports }], nextCursor }`.

`AdminReportSummary` gained `priority: "high" | "normal"`: **high** = reason `insult_hate`, `harassment` or `scam_phishing` from a trusted reporter, **or** ≥ 3 open reports against the same player.

### 22.5 Dashboard

`GET /v1/admin/dashboard` (team) – numbers only, no content:

```
{ reports: { open, inReview, highPriority, oldestOpenAt },
  appeals: { open, oldestOpenAt },
  sanctions: { warn, chat_mute, social_ban, upload_ban, hosting_ban, account_ban },   // active, distinct players
  uploads: { capesPending, capesReported, cosmeticsPending, cosmeticsReported },
  users: { total, new24h, new7d, active24h, active7d, online },
  hosting: { openRooms, players },
  chat: { messages24h },
  series: { days: ["YYYY-MM-DD" × 30], newUsers: [n × 30], messages, reports, sanctions },  // UTC days, oldest first
  server: { version, node, uptimeSec, startedAt, dbBytes, disk: { freeBytes, totalBytes } | null },
  recentAudit: [AuditEntry × 12] }
```

### 22.6 Worlds (hosting) for the team

- `GET /v1/admin/hosting/rooms` → `{ rooms: [AdminRoomView] }` with `AdminRoomView = { id, code, name, host:{uuid,name}, mcVersion, loader, maxPlayers, players, open, visibility, members: { accepted, invited, requested, banned }, createdAt, heartbeatAt }`.
- `DELETE /v1/admin/hosting/rooms/{id}` `{ reason?: ≤200 }` or no body → 204. Everyone gets `hosting_room_closed` with `reason: "closed"`. To keep a player out use a `hosting_ban`.

### 22.7 Search, filters, bulk actions

- `GET /v1/admin/search?q=` (1–64 chars; 60 / min per team member) → `{ players: [{ uuid, name, role, matched }], reports: [AdminReportSummary], capes: [{ id, name, kind, status, owner }], cosmetics: [{ id, name, kind, status, slot, owner }], sanctions: [AdminSanctionView] }`. Finds: name prefix (current and former names, `matched` = former name), UUID (with or without dashes), report id `r…`, sanction `#12`/`s12`, cape/cosmetic id or part of the name. At most 8 per group.
- **Filters with cursor pages** (cursor = opaque string, pass `nextCursor` back):
  - `GET /v1/admin/reports` additionally: `reason`, `assigned=me|none|<uuid>`, `priority=high`, `from`, `to`, `sort=oldest|newest`. `counts` gained `highPriority` (open + in review).
  - `GET /v1/admin/capes` and `GET /v1/admin/cosmetics` additionally: `owner=<uuid>`, `q=<part of name>`, `from`, `to`, `sort`, `cursor`, `limit` (1–500, default 200). Response adds `nextCursor`. Queues (`pending`, `reported`) are oldest first.
  - `GET /v1/admin/audit` additionally: `actor=<uuid>|api-key|system`, `action=<prefix>` (e.g. `sanction.`), `from`, `to`.
  - `GET /v1/admin/sanctions`, `/appeals`, `/players` see above.
- **Bulk actions** – at most **50 ids** per request (`400 invalid_request` / `400 bulk_too_large`), **one transaction** (all or nothing), 10 requests / min per team member:
  - `POST /v1/admin/reports/bulk` `{ ids: [reportId], action: "dismiss"|"resolve" }` → `{ updated: [id], skipped: [id] }` (already resolved or unknown ids are skipped). Reporters get `report_update`; automatic mutes end when nothing is open any more.
  - `POST /v1/admin/capes/bulk` and `POST /v1/admin/cosmetics/bulk` `{ ids, action: "approve"|"reject", reason?: ≤200 }` → `{ updated, skipped }` (skipped = unknown, built-in or already in that state).

### 22.8 Appeals

**Players** (bearer token or appeal token, §22.3):

- `GET /v1/me/sanctions` (30 / min) → `{ active: [MySanctionView], past: [MySanctionView] }` (newest first; past = expired or lifted, kept as long as the retention in §22.10).
- `POST /v1/me/sanctions/{id}/appeal` `{ text: 20–1000 chars, line breaks allowed }` → **201** `{ sanction: MySanctionView }`. Exactly **one** appeal per sanction (`409 appeal_exists`), only for active sanctions (`409 sanction_not_active`), only your own (`404 sanction_not_found`). Rate limit 5 / h per account.

```
MySanctionView = { id, kind, reasonCode, reason, startsAt, endsAt, status: "active"|"expired"|"lifted", liftedAt,
                   appeal: { id, status: "open"|"lifted"|"shortened"|"upheld", createdAt, decidedAt, response } | null,
                   appealable }
```
No internal note, no moderator name.

**Team:**

- `GET /v1/admin/appeals?status=open|decided|all&cursor&limit` → `{ appeals: [AdminAppeal], nextCursor, open }` (open ones oldest first). `AdminAppeal = { id, status, text, createdAt, decidedAt, decidedBy, response, sanction: AdminSanctionView }`.
- `POST /v1/admin/appeals/{id}/decide` `{ decision: "lift"|"shorten"|"uphold", response: 1–1000 (sent to the player), endsAt?: ISO (only and required for shorten, earlier than the current end) }` → `{ appeal }`. `409 appeal_decided`, `403 own_sanction`, `403 admin_only` (moderator lifting/shortening an admin sanction).

### 22.9 Events (`/v1/events/me`, §19)

| event | data |
|---|---|
| `sanction_added` | `{ sanction: MySanctionView }` – new sanction against you (also warnings). For `account_ban` the stream closes right after. |
| `sanction_updated` | `{ sanction: MySanctionView }` – lifted, shortened, extended, or your appeal was filed (other devices). |
| `appeal_decided` | `{ sanctionId, appeal: { id, status, createdAt, decidedAt, response }, sanction: MySanctionView }` |

Clients: on `sanction_added`/`sanction_updated` update banners and disable the affected features (chat input, friend requests, uploads, hosting) until `endsAt`; show the `appeal` button while `appealable`. The old `moderation` event (§20.4) is still sent for warnings and chat mutes.

### 22.10 Data, retention, migration

- **Tables:** `sanctions` (all kinds), `sanction_changes` (who/when/why for shorten/extend/lift), `sanction_appeals` (one per sanction), `staff_roles`, `player_notes`, `name_history`; `sessions.scope` (`full` | `appeal`).
- **Retention:** sanctions with their changes and appeal are deleted **2 years after they ended** (expired or lifted); active and permanent ones stay. Internal notes: **2 years**. Former names: **2 years** after last use (the current name stays). Audit entries without report reference: **2 years**; entries of a report go with the report (§20.3).
- **Account deletion:** warnings and ended sanctions (with appeals) are deleted; **active sanctions stay** (a ban can't be escaped by deleting); notes stay only while a sanction is active. Name history and role go with the account.
- **Migration 9** copies `chat_sanctions` (warn → `warn` ending 90 days after it was given; mute → `chat_mute`, auto reasons → `auto_spam`/`auto_reports`, others `legacy`) and `bans` (→ permanent `account_ban`, `legacy`) into `sanctions` without losing rows (`legacy_source`/`legacy_id`, `migrated: true`), then drops the old tables. It is idempotent (a second run changes nothing). Existing account names seed `name_history`.

---

## 23. Shared screenshots (public links)

A player uploads a screenshot and gets a link `https://trs-launcher.theredstonee.de/s/<id>` that **anyone** with the link can open for **30 days**. The launcher (screenshot gallery) and the TRS Client ("Clips & pictures", the screenshot chat line) offer "Share as link". Nothing is shown publicly about the owner – no name, no UUID.

### 23.1 ShareView

```json
{ "id": "Qm9vLWJhei1xdXV4LTEyMw",
  "url": "https://trs-launcher.theredstonee.de/s/Qm9vLWJhei1xdXV4LTEyMw",
  "imageUrl": "https://trs-launcher.theredstonee.de/v1/shares/Qm9vLWJhei1xdXV4LTEyMw/image",
  "thumbUrl": "https://trs-launcher.theredstonee.de/v1/shares/Qm9vLWJhei1xdXV4LTEyMw/image?thumb=1",
  "mime": "image/jpeg", "width": 1920, "height": 1080, "bytes": 412345,
  "createdAt": "…", "expiresAt": "…" }
```

- `id` matches `^[A-Za-z0-9_-]{22}$` (128 random bits, base64url) – it can't be guessed or enumerated.
- `url`, `imageUrl` and `thumbUrl` are absolute and use the website address (`SITE_URL`). `expiresAt` = `createdAt` + 30 days.
- The **public** view (`GET /v1/shares/{id}`) has the same fields **without** `thumbUrl` and `bytes`.

### 23.2 Requests

| Request | Body | Response |
|---|---|---|
| `POST /v1/shares` (auth) | the raw image, `Content-Type: image/png`, `image/jpeg` or `image/webp` | **201** `{ share: ShareView }` |
| `GET /v1/shares` (auth) | – | `{ shares: [ShareView], limits: { active, maxActive, uploadsToday, maxPerDay } }` – your active links, newest first |
| `DELETE /v1/shares/{id}` (auth) | – | **204**. Only your own; unknown, expired and foreign links → `404 share_not_found` |
| `GET /v1/shares/{id}` (public) | – | `{ share: PublicShareView }` or `404 share_not_found` (unknown, expired or deleted). `Cache-Control: public, max-age=60`, `X-Robots-Tag: noindex, nofollow` |
| `GET /v1/shares/{id}/image` (public), `?thumb=1` for the preview | – | The image bytes (`image/jpeg` or `image/png`), `Cache-Control: public, max-age=600`, `ETag` (`If-None-Match` → 304), `Cross-Origin-Resource-Policy: cross-origin` (embeds in chat apps work) |
| `POST /v1/shares/{id}/report` (public, used by the website) | `{ "reason": "<§20.1 reason>" }` | **202** `{ ok: true }` – see §23.4 |

**Upload rules**

- At most **10 MiB** (`413 payload_too_large`). The type must match the magic bytes (`415 unsupported_media_type`), animated WebP → `400 animated_image`, broken → `400 invalid_image`, more than 8192 px per side or 24 megapixels → `400 image_too_large`.
- The server **decodes and re-encodes** the image like chat images (§18.7): no metadata survives, JPEG orientation is applied. Output: **JPEG quality 90** when opaque, **PNG** with transparency, at most **4096 px** per side; preview ≤ 480 px.
- Limits per account: **50 active links** (`409 shared_image_limit` with `max`), **20 uploads per 24 hours** – deleting a link does not give the upload back (`429 share_daily_limit` with `retryAfter` and `Retry-After` = seconds until the oldest upload of the window is 24 h old), and 10 requests per minute (`429 rate_limited`).
- An active **upload ban** (§22) → `403 sanctioned` with `until` and `sanction`. Banned accounts can't sign in at all.
- The server has a global storage limit (`SHARE_STORAGE_MAX_MB`, default 1024) → `507 storage_full`. While two large images are being encoded, further uploads get `503 busy` with `Retry-After: 3`.
- Panoramas: an equirectangular PNG is just an image; the six cube faces are shared one by one. Clients shrink files above 10 MiB or 4096 px locally before uploading.

### 23.3 Website page `/s/<id>`

The page shows the image, the upload date, the expiry date, a link to the full image and a "Report" button – nothing else (no name, no UUID, no other shares of the same player). It is `noindex, nofollow` (meta tag and `X-Robots-Tag`), sends `Referrer-Policy: no-referrer`, and carries Open Graph / Twitter tags (`og:image` = `imageUrl` with width and height, `twitter:card = summary_large_image`) so Discord and other chat apps show a preview. Expired or deleted links show "This link has expired or was deleted" with status **404**. The page CSP is unchanged (images come from the same origin).

### 23.4 Reports and moderation

- **Players:** `POST /v1/reports` with `{ "kind": "share", "shareId": "<id>", "reason", "note"? }` (§20.1). The target is the owner; `404 share_not_found`, `400 cannot_target_self`, `409 already_reported`.
- **Anyone (website):** `POST /v1/shares/{id}/report` without an account. At most **5 per hour per IP address**; the address is only used for this limit in memory and never stored. There is at most **one open anonymous report per link** – further ones are merged silently (still `202`). Anonymous reports never count for automatic mutes. At most 200 open anonymous reports overall.
- **Evidence:** the report keeps an encrypted **copy of the image** (like reported chat images, §20.3) and `evidence.share = { id, width, height, mime, createdAt, expiresAt }`; `evidence.anonymous = true` for website reports. Admins get the copy via `GET /v1/admin/reports/{id}/images/{shareId}`, also after the link expired or was deleted.
- **Action** `delete_share` (`POST /v1/admin/reports/{id}/actions`) deletes the link and the image for everyone (`409 no_share` for other report kinds). `includeRelated` also resolves the other open reports about the same link. Sanctions (`warn`, `sanction` with `upload_ban` …) work as for chat reports.
- The admin report list filters by `kind=share`; summaries carry `shareId` and `anonymous`.

### 23.5 Storage and retention

- Table `shared_images` (owner UUID, size, type, SHA-256 of the output, created/expires) and `shared_image_uploads` (owner + time, for the daily limit, deleted after 24 h). Files: `<DATA_DIR>/shares/<xx>/<id>.jpg|png` plus `.t.` preview – **not encrypted** (the content is public by design).
- A background job deletes expired links and their files every 10 minutes. `DELETE /v1/me` deletes all links of the account and their files immediately (§3.3).
- Copies kept as report evidence follow the report retention (§20.3: 90 days after the report was resolved).
- **Migration 12** creates the two tables and rebuilds `chat_reports` (new kind `share`, column `share_id`) together with `chat_report_notes` and `chat_evidence_files` – all rows are kept.

---

## 24. Website sign-in with Microsoft, team roles and permissions, team applications

This section replaces the launcher-code sign-in (§15) and the admin/moderator grid of §22.1. Tests: `tests/microsoft.test.ts` (OAuth flow against test doubles), `tests/team.test.ts` (permission matrix, rank rule, migration 13), `tests/applications.test.ts`, smoke test section "Microsoft sign-in, roles, applications".

### 24.1 Website sign-in with Microsoft

The website signs in **only** with the Microsoft account that owns Minecraft: Java Edition. Server-side Authorization Code flow with PKCE (S256) as a **confidential client** (client secret only on the server) against tenant `consumers`, scope `XboxLive.signin`.

| Request | Response |
|---|---|
| `GET /auth/microsoft/login?return=/path` | **302** to `https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize?…` (client_id, redirect_uri, `response_mode=query`, `scope=XboxLive.signin`, `state`, `code_challenge`, `code_challenge_method=S256`, `prompt=select_account`). Sets cookie `trs_oauth` (random handle, httpOnly, Secure, **SameSite=Lax**, `Path=/auth/microsoft`, 10 minutes). `return` must be a relative path of this site (else `/applications`). Limit 20 / 10 min per IP. Disabled → **302** `/login?error=ms_disabled`. |
| `GET /auth/microsoft/callback?code&state` | Checks `state` against the cookie handle (one use, constant-time, 10 min), then Microsoft token → Xbox Live `user.auth.xboxlive.com` (`RpsTicket: d=<token>`) → XSTS `xsts.auth.xboxlive.com` (`RelyingParty: rp://api.minecraftservices.com/`) → `POST api.minecraftservices.com/authentication/login_with_xbox` → `GET /minecraft/profile`. Success: creates/updates the TRS account (UUID, name), new website session (rotation: a session presented in the cookie is deleted), cookie `trs_session` (httpOnly, Secure, **SameSite=Strict**, `Path=/`, 8 hours), deletes the old `trs_admin` cookie, answers **200** with a tiny HTML page that forwards by meta refresh to `return` (a same-site navigation, so the Strict cookie is sent). Errors → **302** `/login?error=<code>`. Limit 30 / 10 min per IP, plus the per-account login limit (§2). |
| `GET /v1/web/login` | `{ microsoft: boolean }` – is the sign-in configured? |
| `GET /v1/web/me` | `{ uuid, name, skin, csrf, expiresAt, team: MyTeamView \| null }` for the cookie session, else `401`. `skin` = texture URL (textures.minecraft.net) or `null`. |
| `POST /v1/web/logout` | **204**, session deleted on the server. Needs `X-CSRF-Token`. |

**Error codes** (`/login?error=`): `ms_disabled`, `ms_cancelled` (user cancelled at Microsoft), `ms_state` (state/cookie missing, expired, replayed or tampered), `ms_failed` (Microsoft/Xbox/Minecraft not reachable or rejected, wrong secret – details only in the server log), `no_xbox` (XSTS 2148916233 or no Xbox profile), `child_account` (XSTS 2148916238), `xbox_region` (2148916235), `xbox_verification` (2148916236/7), `xbox_banned` (2148916227), `no_minecraft` (profile 404 = no Java Edition or no profile name), `banned` (active TRS `account_ban`), `rate_limited`.

**Stored:** only the session (SHA-256 of the token, CSRF token, expiry; at most 5 per account) and the TRS account. Microsoft, Xbox and Minecraft tokens live in memory for the few seconds of the chain and are discarded; `state` and the PKCE verifier are kept in memory only (max. 5000 pending, 10 minutes). Sign-ins of team members are audited (`web.login`).

**Session rules:** every mutating `/v1` request with the cookie needs `X-CSRF-Token` (`403 csrf_failed`); a request with `Authorization` or `X-Admin-Key` ignores the cookie; a ban ends all website sessions (`403 banned`). Team rights are **not** part of the session – they are read on every request.

**Configuration (.env):** `MS_CLIENT_ID` (GUID of the Azure app), `MS_CLIENT_SECRET`, `MS_REDIRECT_URI` (default `https://trs-launcher.theredstonee.de/auth/microsoft/callback`, path must be `/auth/microsoft/callback`). Without ID or secret the sign-in is off. For tests only: `MS_AUTHORITY_URL`, `XBOX_USER_AUTH_URL`, `XBOX_XSTS_URL`, `MINECRAFT_SERVICES_URL` + `ALLOW_INSECURE_MS_URLS=true` (test double: `scripts/ms-mock.mjs`).

**Removed:** `POST /v1/web-login/start|poll|approve|logout`, `GET /v1/web-login/me` → every `/v1/web-login/*` answers **`410 web_login_removed`**. Table `web_logins` is dropped, old `trs_admin` sessions end with migration 13.

### 24.2 Roles and permissions

**Permissions** (server-side on every request; `403 missing_permission` with `permission`):

| Permission | Allows |
|---|---|
| `dashboard.view` | Overview (`GET /v1/admin/dashboard`, parts per permission, see below) |
| `stats.view` | Statistics: accounts, messages, 30-day series, server (`/v1/admin/stats`, dashboard `users`/`series`/`server`/`chat`) |
| `audit.view` | Audit log |
| `reports.view` | Report list and detail (metadata) |
| `reports.content` | Reported chat texts, invites, world and waypoint cards, images (`/v1/admin/reports/{id}/images/*`). Without it the server sends `contentHidden: true`, `preview: null`, messages with `text/invite/world/waypoint: null, attachments: [], hidden: true`, `images: []`. |
| `reports.handle` | Report status, notes, actions incl. `delete_message` and `delete_share` (§23.4), bulk |
| `sanctions.warn` / `.mute` / `.social` / `.upload` / `.hosting` / `.ban` | Give the matching kind (`warn`, `chat_mute`, `social_ban`, `upload_ban`, `hosting_ban`, `account_ban`); `extend` of that kind |
| `sanctions.permanent` | Permanent sanctions |
| `sanctions.lift` | Lift (also old unmute/unban routes); `shorten` needs `lift` **or** the kind's permission |
| `appeals.handle` | Appeals (list, decide) |
| `players.view` | Player list/file, sanction lists, search for players; sanction history in applications |
| `players.notes` | Write/delete player notes |
| `uploads.review` | Capes/cosmetics review + bulk; see pending textures |
| `uploads.delete` | Delete capes/cosmetics |
| `items.grant` | Give/take capes and cosmetics |
| `worlds.view` / `worlds.close` | Hosted worlds list / close |
| `codes` | Codes (list, create, revoke) |
| `events.manage` | Events (§32): switch on/off, allow single players |
| `wordfilter` | Word filter (list, add, remove) |
| `roles.manage` | Roles and members (§24.2 API), old `/v1/admin/roles` |
| `applications.view` / `.review` / `.manage` / `.decide` | See applications and positions / vote, notes, status new–review–interview / edit positions and forms / accept, reject, reopen, give the linked role |

The dashboard returns `null` for every block the viewer may not see (`reports` needs `reports.view`, `appeals` `appeals.handle`, `sanctions` `players.view` or `appeals.handle`, `uploads` `uploads.review`, `hosting` `stats.view` or `worlds.view`, `users/series/server/chat` `stats.view`, `applications` `applications.view`, `recentAudit` = `[]` without `audit.view`). Search returns only allowed groups.

**Default roles** (`builtin`, not deletable; permissions, colour, rank, name and visibility editable – except Owner):

| id | Rank | Default permissions | Longest temporary sanction |
|---|---|---|---|
| `owner` | 1000 | all (fixed) – every UUID in `ADMIN_UUIDS`, cannot be assigned, removed or sanctioned | – |
| `admin` | 900 | all | unlimited |
| `senior_moderator` | 700 | moderator + `stats.view`, `sanctions.ban`, `applications.view`, `applications.review` | 30 days |
| `moderator` | 500 | `dashboard.view`, `audit.view`, `reports.*`, `sanctions.warn/mute/social/upload/hosting/lift`, `appeals.handle`, `players.view`, `players.notes`, `uploads.review`, `worlds.view`, `worlds.close` | 7 days |
| `supporter` | 300 | `dashboard.view`, `reports.view`, `players.view`, `sanctions.warn`, `worlds.view` | 1 day |
| `content` | 200 | `dashboard.view`, `uploads.review`, `codes` | – |
| `recruiter` | 150 | `dashboard.view`, `applications.view`, `applications.review` | – |

**Custom roles:** name (2–32), colour `#rrggbb`, rank 1–999 (unique), permissions, `maxSanctionMinutes` (null = unlimited; warnings may always last 30 days), `public` (show on the team page). At most 50.

**Several roles per member:** the highest-ranked role is the **main role** (rank, colour, team page); further roles only **add** permissions (union). The longest temporary sanction is the maximum of the roles that can sanction.

**Rank rule:** nobody changes roles or members at or above their own rank, gives roles at or above it, or grants permissions they don't have themselves (`403 rank_too_low`, `403 missing_permission`, `403 duration_not_allowed` for a longer maximum than their own). Sanctions: team members can only be sanctioned by someone with a higher rank (`403 cannot_moderate_staff`; owners `409 cannot_moderate_admin`). Changing a sanction given by a **higher** rank → `403 rank_too_low` (same rank is allowed; own sanctions always). Sanctions store `created_rank` (`createdRank` in `AdminSanctionView`). Nobody changes their own roles (`400 cannot_change_self`); owners are fixed (`409 role_locked`).

`MyTeamView = { owner, rank, roles: [{ id, name|null, color, builtin }], permissions: [string], limits: { kinds, maxMinutes, maxWarnMinutes, permanent } }` – in `GET /v1/me` (`team`, next to the old `admin` and `role`) and `GET /v1/web/me`. `role` stays for older clients: `admin` from rank 900, `moderator` for every other team member, else `null`. Names of default roles are `null` → clients translate the `id`.

**API** (`roles.manage`; changes 60 / min):

| Method and path | Body | Response |
|---|---|---|
| `GET /v1/admin/team` | – | `{ roles: [RoleView], members: [MemberView], permissionGroups: [{ id, permissions }], me: MyTeamView }` |
| `POST /v1/admin/team/roles` | `{ name, color, rank, permissions?, maxSanctionMinutes?, public? }` | **201** `{ role }`. `409 rank_taken`, `409 name_taken`, `409 role_limit`, `400 invalid_permission/invalid_color/invalid_name/invalid_rank` |
| `PATCH /v1/admin/team/roles/{id}` | any of the fields (`name: null` = default name of a default role) | `{ role }`. Owner: only name, colour, `public` (`409 role_locked`) |
| `DELETE /v1/admin/team/roles/{id}` | – | **204**. Default roles `409 role_builtin`. Members lose the role, positions lose the link. |
| `PUT /v1/admin/team/members/{uuid}` | `{ roles: [id] (≤10), note?: ≤200 }` | `{ member, members }` – the complete list; `[]` removes from the team. `404 user_not_found` (must have signed in once). |
| `DELETE /v1/admin/team/members/{uuid}` | – | `{ members }` |

`RoleView = { id, name, color, rank, permissions, maxSanctionMinutes, builtin, locked, public, members, editable }`, `MemberView = { uuid, name, source: "env"|"db", rank, roles: [RoleRef], primary: RoleRef|null, grantedAt, grantedBy, note, editable }`.

The old `GET/PUT/DELETE /v1/admin/roles` (§22.1) still work with `roles.manage`: `admin`/`moderator` map to the default roles of the same id, other roles of the member stay. Audit: `role.create`, `role.update`, `role.delete`, `role.set`, `role.remove`, `role.grant.application`, `web.login`.

**Public:** `GET /v1/site/team` → `{ team: { roles: [{ id, name, color, builtin, members: [{ uuid, name, skin }] }] }, jobs: [JobView] }` (60 s cache). Only roles with `public`; each member only under their highest public role; banned accounts hidden.

### 24.3 Team applications

**Positions** (`JobView = { id, status: "draft"|"open"|"closed", texts: { en?, de?, es?: { title, summary, description, tasks: [], requirements: [] } }, form: [FormField], cooldownDays, role: RoleRef|null, createdAt, updatedAt }`), texts are plain text (no HTML/Markdown).

`FormField = { id: [a-z][a-z0-9_]{0,31}, type: "short"|"long"|"single"|"multi"|"yesno"|"number", required, label: {en?,de?,es?}, help?, min?, max?, options?: [{ id, label }] }` – `min/max` = characters (short ≤ 200, long ≤ 4000; defaults 100/1000), value (number) or selections (multi). Choices need 2–20 options. At most 30 fields. Reserved ids: `discord`, `agegroup`, `age_group`, `website`, `name`, `uuid`. **Always asked:** Discord name and age group (`under14`, `14-15`, `16-17`, `18+` – never a birth date). Minecraft name and UUID come from the sign-in.

| Method and path | Auth | Body / response |
|---|---|---|
| `GET /v1/site/jobs/{id}` | public | `{ job }` (open or closed; drafts `404 job_not_found`) |
| `GET /v1/team/jobs/{id}/eligibility` | web session or bearer | `{ eligibility: { canApply, reason: null|"closed"|"open_application"|"cooldown"|"member"|"too_many_open", retryAt, applicationId } }` |
| `POST /v1/team/jobs/{id}/applications` | web session (+CSRF) or bearer | `{ discord, ageGroup, answers: { fieldId: value }, lang: "en"|"de"|"es", website?: honeypot }` → **201** `{ application: MyApplicationView }`. `400 invalid_answers` with `fields: [{ id, error: required|too_short|too_long|invalid|too_few|too_many|too_small|too_large }]`, `409 application_open`, `409 cooldown` (+`retryAt`), `409 job_closed`, `409 already_member`, `409 too_many_open`, `403 sanctioned` (social ban). 5 / h per account, 20 / h per IP. |
| `GET /v1/me/applications` | web session or bearer | `{ applications: [MyApplicationView] }` |
| `POST /v1/me/applications/{id}/withdraw` | web session (+CSRF) or bearer | `{ application }`, `409 application_closed` |

`MyApplicationView = { id, job: { id, title: {en?,de?,es?}, open }, status: "new"|"review"|"interview"|"accepted"|"rejected"|"withdrawn", response: string|null, createdAt, updatedAt, decidedAt, canWithdraw }` – never votes, notes or who decided.

**Rules:** one open application (new/review/interview) per position and account; at most 3 open overall; after a rejection the position's waiting time (default 30 days, 0–365); after withdrawing 24 hours; answers are cleaned (control characters, short text without line breaks) and unknown fields are rejected; the form is stored with the application (later edits of the position don't change it).

**Team:**

| Method and path | Permission | Body / response |
|---|---|---|
| `GET /v1/admin/jobs` | `applications.view` or `.manage` | `{ jobs: [JobView + applications: { open, total }], roles: [{ id, name, color, builtin, rank }] }` |
| `POST /v1/admin/jobs` | `applications.manage` | `{ id?, status, roleId, sort, cooldownDays, texts, form }` → **201** `{ job }` (id from the title if empty; `409 job_exists`, `409 job_limit` at 50, `400 invalid_role` for owner) |
| `PUT /v1/admin/jobs/{id}` | `applications.manage` | same without `id` → `{ job }` |
| `DELETE /v1/admin/jobs/{id}[?withApplications=1]` | **Owner only** (`403 owner_only`) | **204**; with applications only with `withApplications=1` (otherwise `409 job_has_applications` + `details.applications`) – deletes them too (notes, votes, history; the rejection cooldown ends) |
| `GET /v1/admin/applications?status=open|new|review|interview|accepted|rejected|withdrawn|all&job&q=<name prefix>&cursor&limit` | `applications.view` | `{ applications: [{ id, job, applicant: {uuid,name}, ageGroup, status, votes: {up,down,mine}, notes, createdAt, updatedAt }], nextCursor, counts }` |
| `GET /v1/admin/applications/{id}` | `applications.view` | `{ application }` + `discord, lang, form, answers, response, decidedAt, decidedBy, roleGranted, jobRole, voteList, noteList, history, player: { rank, sanctions, active }|null (players.view), can: { review, decide, grantRole, vote } }` |
| `POST /v1/admin/applications/{id}/status` | `.review` (new/review/interview) or `.decide` (accepted/rejected, reopening) | `{ status, response?: ≤2000, grantRole?: true }` → `{ application }`. `grantRole` needs `accepted`, a linked role and the rank rule (`403 rank_too_low`, `409 job_without_role`). Withdrawn: `409 application_closed`. |
| `PUT /v1/admin/applications/{id}/vote` | `applications.review` | `{ vote: 1|-1, comment?: ≤500 }` – one vote per member, changeable while open |
| `DELETE /v1/admin/applications/{id}/vote` | `applications.review` | removes the own vote |
| `POST /v1/admin/applications/{id}/notes` | `applications.review` | `{ text: 1–2000 }` → **201** |

Team members never see or vote on their **own** application (`403 own_application`; the list hides it). Status/vote/note changes 60 / min per team member. History (`history`) records submitted, votes, status, answer and role; audit: `job.create|update|delete`, `application.<status>`, `application.response`.

**Event** (`/v1/events/me`, §19): `application_updated` `{ application: MyApplicationView }` – after submitting, every status or answer change and withdrawing (also to other devices).

**Retention:** rejected/withdrawn → deleted **6 months after the decision**; accepted → kept while the account has a team role, deleted **6 months after leaving** (`retain_until`, set by the 6-hourly job); account deletion deletes all applications with votes, notes and history (CASCADE).

### 24.4 Data and migration 13

Tables `team_roles`, `team_members` (uuid + role_id), `team_jobs`, `team_applications` (unique open application per job and account), `team_application_votes`, `team_application_notes`, `team_application_history`; column `sanctions.created_rank`. **Migration 13** creates the default roles (existing adjustments stay), copies every `staff_roles` row to `team_members` (`admin` → `admin`, `moderator` → `moderator`, with time, granter and note) and drops `staff_roles`, backfills `created_rank` (admin 900, moderator 500, system 0), drops `web_logins` and deletes all website sessions. Idempotent (`migrateTeamV3`).

### 24.5 What clients have to change

**TRS Launcher:**
- Remove the website sign-in (`trs_web_login_approve`, `TrsWebLoginDialog`, the entries in /admin, settings and Ctrl+K). Old launchers get `410 web_login_removed` – show "Sign in on the website with Microsoft".
- Team area: show pages and buttons by `me.team.permissions` (and `limits`) instead of `role === 'admin'`; hide the area when `me.team` is `null`. Sanction change buttons: rank rule via `createdRank` ≤ own `team.rank` (or own sanction). New error codes `missing_permission` (+`permission`), `rank_too_low`. Report detail may come with `contentHidden: true`.
- Optional: roles/members via `/v1/admin/team*` (the old `/v1/admin/roles` keeps working).
- Applications: handle `application_updated` (toast "Your application for X: <status>" + answer) and optionally a "My applications" view (`GET /v1/me/applications`, withdraw) – bearer tokens work for all player routes of §24.3.

**TRS Client:** only `application_updated` (optional toast); nothing else changes.

## 25. Circuit library

The redstone circuit library of the TRS Client lives on the server: the mod ships no circuits, checks the index once per game start and downloads only new or changed circuits – new circuits need no mod update. The circuit JSON, the block catalogue, all limits and the client behaviour are specified in [`docs/circuit-format.md`](../docs/circuit-format.md) (main branch); this section adds the server side. The server validates with the same rules as the client (`shared/circuits.ts` ↔ `Circuit.parse`) and is stricter in one point: properties are only allowed on the blocks that have them (e.g. `delay` only on `repeater`).

### 25.1 Index (public)

`GET /v1/circuits/index` – no auth, rate limit 240 / min per IP (plus the global limit).

```json
{ "version": "3b1f…(32 hex)", "circuits": [
  { "id": "not_gate", "rev": 1, "updatedAt": "2026-09-27T12:00:00.000Z", "minVersion": "1.8" },
  { "id": "t_flipflop_copper", "rev": 3, "updatedAt": "…", "minVersion": "1.21", "maxVersion": "1.21.4" } ] }
```

- Only **published** circuits, in library order (`sort`, then id).
- `rev` is an integer that grows with **every** change of the circuit (content, texts, status, creator removed). Clients treat it as an opaque token (the mod stores it as a string).
- `minVersion` = the effective minimum (highest of `since` and the blocks, e.g. observer → 1.11), `maxVersion` = `until` (missing = no limit).
- Strong `ETag: "<version>"` (`version` = first 32 hex of SHA-256 over the entries). `If-None-Match` with the same tag (also in a list or as `W/`) → **304** with an empty body. `Cache-Control: public, max-age=60, stale-while-revalidate=600`. The server caches the index in memory until the next change.

### 25.2 One circuit (public)

`GET /v1/circuits/{id}?rev={rev}` – no auth, same rate limit.

- Body = exactly the client format (§2 of circuit-format.md) plus `rev`, `updatedAt` and `author` (`{ uuid, name }` of the creator of an accepted submission, `null` = TRS team):
  ```json
  { "format": 1, "id": "and_gate", "rev": 2, "category": "basics", "difficulty": 2, "server": "ok",
    "texts": { "en": { "name": "AND gate", "desc": "…" }, "de": { … }, "es": { … } },
    "palette": { … }, "layers": [ … ], "tests": [ … ], "updatedAt": "…", "author": null }
  ```
- `rev` = the current one → **200**, `Cache-Control: public, max-age=31536000, immutable`, `ETag: "c-<id>-<rev>"` (304 on `If-None-Match`).
- `rev` = any other number → **404 `rev_mismatch`** with `details.rev` = current rev and `Cache-Control: no-store`. There is deliberately **no redirect**: an immutable URL must never return different content. Clients reload the index.
- Without `rev` → 200, `Cache-Control: public, max-age=60` (website, tools).
- Not published / unknown → `404 circuit_not_found`. A malformed `rev` → `400 invalid_request`.

`GET /v1/circuits/{id}/export?format=nbt|json` (public, published only): download as a vanilla **structure file** (`.nbt`, gzip, loadable with a structure block – copy to `<world>/generated/minecraft/structures/`) or as the JSON above. `DataVersion` = oldest version the circuit runs in (1.13.2 = 1631 for classic circuits, 1.21 = 3953 for copper bulbs); empty cells are saved as air, `solid` as stone, markers and flags are dropped.

### 25.3 Website data

`GET /v1/site/circuits` → `{ circuits: [SiteCircuit] }`, `GET /v1/site/circuits/{id}` → `SiteCircuit` (public, `max-age=60`): `{ circuit: <body of 25.2>, size: {x,y,z}, blockCount, materials: [{ key, item, count }], minVersion, maxVersion, publishedAt }`. Pages: `/circuits` (list, filter, search), `/circuits/{id}` (3D preview, materials, download, report), `/circuits/submit`, `/circuits/mine` (both noindex, browser only). The sitemap lists `/circuits` and every published circuit with hreflang alternates.

### 25.4 Team: editor (`circuits.manage`)

New permission **`circuits.manage`** (group "content"). Default roles: owner, admin, senior moderator and content (migration 14 adds it to the existing default roles; other adjustments stay). Every route checks the permission on the server; all changes go to the audit log with `ref = circuit:<id>`.

| Route | Body / query | Answer |
|---|---|---|
| `GET /v1/admin/circuits?status=all\|draft\|published\|hidden&category&q` | – | `{ circuits: [AdminCircuitDetail], counts: { pendingSubmissions, published, total } }` |
| `GET /v1/admin/circuits/{id}` | – | `{ circuit: AdminCircuitDetail, history: [audit entries] }` (history only with `audit.view`) |
| `POST /v1/admin/circuits` | `{ circuit, status?: draft (default)\|published\|hidden, sort? }` | **201** `{ circuit }`; `409 circuit_exists`, `400 invalid_circuit` (`errors: [..]`) |
| `PUT /v1/admin/circuits/{id}` | `{ circuit, status?, sort?, baseRev? }` | `{ circuit }` (rev + 1); the id cannot change; `409 stale` (+ `rev`) if `baseRev` is outdated |
| `POST /v1/admin/circuits/{id}/status` | `{ status, baseRev? }` | `{ circuit }` (rev + 1 when the status changes) |
| `DELETE /v1/admin/circuits/{id}` | – | **204**; the id is kept as a tombstone so the seed never re-adds it (it can be created again on purpose) |
| `POST /v1/admin/circuits/import?name=<file name>` | raw file bytes (`application/octet-stream`, ≤ 2 MB) | `{ format, circuit, warnings, size, blockCount }` – nothing is stored |
| `POST /v1/admin/circuits/export?format=nbt\|json` | `{ circuit }` (unsaved editor state) | file |
| `GET /v1/admin/circuits/{id}/export?format=nbt\|json` | – | file (any status) |

`AdminCircuitDetail` = `{ id, rev, status, category, difficulty, names: {lang: name}, author, source: seed|team|submission, edited, size, blockCount, minVersion, maxVersion, sort, createdAt, updatedAt, publishedAt, circuit }`. Rate limit 120 / min per team member for changes, imports and exports.

**Built-in circuits (seed).** At start the server reads `assets/circuits/index.json` + `<id>.json` (copies of `data/circuits` in main) and inserts missing ones as published (rev 1). A built-in circuit that was never changed in the team area is updated when its file changes (rev + 1); edited (`edited = 1`), foreign (same id, other source) and deleted ones stay untouched. Invalid files are skipped with a warning.

### 25.5 Submissions

**Player routes** (bearer token of the launcher/mod **or** the website session with CSRF):

- `POST /v1/circuits/submissions` `{ circuit, name: 1–64, category, description: 1–1200, lang: en|de|es, format?: json|litematic|schem|nbt }` → **201** `{ id, status: "pending", submission: MySubmission }`. The server takes palette, layers and tests from `circuit` and sets the id (a valid, non-reserved `circuit.id` or a slug of the name), the category and `texts[lang] = { name, desc }`; name and description go through the word filter (`422 message_blocked`). Checks in this order:
  1. `403 sanctioned` – active **upload ban** (moderation v2), with `until` + `sanction`;
  2. `429 rate_limited` – at most **5 per 24 h** per account (decided ones count too; `Retry-After`, `max`), plus 20 / h per account in memory;
  3. `400 invalid_circuit` – `errors: ["…"]` (same rules as the client); `400 invalid_request` for the body;
  4. `409 circuit_duplicate` – the same build (canonical content hash: occupied cells relative to the smallest corner, without markers and flags – palette characters and air don't matter) exists as a circuit (any status; `circuitId`) or as a pending/approved submission.
- `GET /v1/me/circuit-submissions` → `{ submissions: [MySubmission], limits: { today, maxPerDay } }`.
- `POST /v1/circuits/convert?name=<file name>` (signed in, 30 / h): raw file → `{ format, circuit, warnings, size, blockCount }` for the preview before submitting (the website uses it; nothing is stored).

`MySubmission` = `{ id: "cs<16 hex>", name, category, lang, status: pending|approved|rejected, reason (rejection reason, visible to the creator), circuitId (after approval), createdAt, updatedAt, decidedAt }`.

**Team** (`circuits.manage`):

| Route | Body | Answer |
|---|---|---|
| `GET /v1/admin/circuit-submissions?status=pending\|approved\|rejected\|all` | – | `{ submissions: [AdminSubmission], pending, counts }` (pending = oldest first) |
| `GET /v1/admin/circuit-submissions/{id}` | – | `{ submission, suggestedId }` |
| `POST /v1/admin/circuit-submissions/{id}/accept` | `{ circuit?: edited circuit, status?: published (default)\|draft, sort? }` | `{ submission, circuit }` – creates the circuit with `author = { uuid, name }` of the submitter; `409 submission_decided`, `409 circuit_exists` |
| `POST /v1/admin/circuit-submissions/{id}/reject` | `{ reason: 3–500 }` | `{ submission }` |

`AdminSubmission` = `MySubmission` + `{ submitter: {uuid, name}, description, format, circuit, size, blockCount, decidedBy, submitterStats: { pending, approved, rejected } }`.

**Event** (`/v1/events/me`, §19): `circuit_submission_updated` `{ submission: MySubmission }` – after submitting (other devices), approval (with `circuitId`) and rejection (with `reason`).

**Retention:** decided submissions are deleted **90 days after the decision** (6-hourly job); pending ones stay until decided. The uploaded file is never stored (only the converted circuit). Account deletion deletes all submissions (CASCADE) and removes `author` from the account's circuits (rev + 1, the circuit stays).

### 25.6 File import

Formats are detected by content, not by extension; at most **2 MB** upload.

| Format | Read |
|---|---|
| Litematica `.litematic` (gzip NBT) | all regions (negative sizes; overlapping regions: later ones win), `BlockStatePalette` + packed `BlockStates` (values may span two longs); `TileEntities`/`Entities` are ignored |
| Sponge schematic `.schem` v1/v2/v3 | `Palette` + `BlockData` (v1/v2) or `Blocks.Palette` + `Blocks.Data` (v3), varint indices; `BlockEntities` are ignored. Old MCEdit `.schematic` (numeric ids) → `400 invalid_circuit` |
| Structure block `.nbt` | `size`, `palette` (or `palettes[0]`), `blocks[{pos,state}]`; `blocks[].nbt` and `entities` are ignored |
| TRS JSON | the circuit format itself (a UTF-8 BOM is allowed), ≤ 256 KB |

Conversion keeps only block states. Catalogue blocks and their aliases are taken as they are (only the allowed properties survive – `powered`, `power`, `lit`, wire connections … are dropped). Similar blocks are converted with a warning (`*_button` → `stone_button`, `*_pressure_plate` → `stone_pressure_plate`, `*_stained_glass` → `glass`, `*_sign` → `oak_sign`, blast furnace/smoker → `furnace`, …), full blocks (stone, planks, wool, concrete, ores, …) become `solid`, everything else is left out with a warning. Air around the build is trimmed; the rest must fit **16 × 16 × 16**. `warnings: [{ block, count, action: solid|converted|skipped, as? }]`.

**NBT safety:** the gzip/zlib stream is inflated with a hard limit of **8 MiB** (zip bombs → `400 invalid_circuit`), nesting ≤ 24, ≤ 400 000 nodes, every list/array length is checked against the remaining bytes before memory is allocated (negative lengths and truncated data → 400), compounds are prototype-free objects, raw volume ≤ 1 048 576 cells, palettes ≤ 65 536 entries.

### 25.7 Reports

Report kind **`circuit`** in `POST /v1/reports` (§20): `{ kind: "circuit", circuitId, reason, note? }` – only published circuits (`404 circuit_not_found`), not your own (`400 cannot_target_self`). `POST /v1/reports` now also accepts the website session (cookie + CSRF). The target is the creator (`null` for team circuits). Evidence: `circuit: { id, rev, name, author }`; report summaries have `circuitId`. New admin action **`hide_circuit`** (needs `reports.handle` **and** `circuits.manage`, otherwise `403 missing_permission`) hides the circuit (rev + 1); `includeRelated` closes the other open reports about the same circuit. Migration 14 rebuilds the report tables for the new kind (like migration 12, children first).

### 25.8 Data and migration 14

Tables `circuits` (id, rev, status, category, difficulty, min/max version, sort, data = circuit JSON, content_hash, author uuid/name, source, seed_hash, edited, timestamps, created_by/updated_by), `circuit_tombstones`, `circuit_submissions`; `chat_reports.circuit_id` + kind `circuit`; permission `circuits.manage` for the default roles. Idempotent (`migrateCircuits`). When merging: renumber if a parallel branch also added a migration 14.

### 25.9 What clients have to do

- **TRS Client** (done in main): index with `If-None-Match`, circuits per `rev`, submissions with the bearer token, `GET /v1/me/circuit-submissions`, the error codes above. Optional: a toast on `circuit_submission_updated`, "Report" with `kind: circuit`.
- **TRS Launcher:** nothing required. Optional: a toast on `circuit_submission_updated`; a team-area link to `/admin/circuits` for `circuits.manage`.

## 26. Team page and player heads

### 26.1 Public team page

`GET /v1/site/team` → `{ team: { groups }, jobs }` (cacheable 60 s). Only people added in the team area (§26.2) appear –
roles alone put nobody on the page. Groups are the **public** roles by rank (Owner first); banned accounts are hidden.

```json
{ "team": { "groups": [ { "id": "owner", "name": null, "color": "#facc15", "builtin": true, "members": [
  { "uuid": "…", "name": "Theredstonee",
    "skin": { "url": "https://textures.minecraft.net/texture/…", "model": "classic" },
    "cape": { "id": "…", "url": "/v1/capes/….png?v=…", "frames": 1, "frameTimeMs": null },
    "titles": { "en": "Founder", "de": "Gründer" }, "discord": "theredstonee",
    "links": [ { "label": "GitHub", "url": "https://github.com/theredstonee" } ] } ] } ] },
  "jobs": [ … ] }
```

`skin: null` = not looked up yet, `skin.url: null` = default skin. `cape` only for an approved TRS cape the person shows
to others. Skins come from the database (§26.3); at most 8 missing or older than 6 hours are looked up per request.

### 26.2 Team area (`team.page`)

| Route | Body | Notes |
|---|---|---|
| `GET /v1/admin/team-page` | – | `{ groups: [{ role, members }], ungrouped, editable }` – all roles by rank, also empty ones |
| `POST /v1/admin/team-page/members` | `{ player, roleId? }` | `player` = UUID or Minecraft name; without `roleId` the person's main role. 201 |
| `PATCH /v1/admin/team-page/members/{uuid}` | `{ roleId?, titles?, discord?, links? }` | titles EN/DE/ES ≤ 60, Discord username (a–z 0–9 _ ., 2–32), ≤ 3 links (`https://` only, label ≤ 30) |
| `DELETE /v1/admin/team-page/members/{uuid}` | – | roles stay unchanged |
| `PUT /v1/admin/team-page/order` | `{ groups: [{ roleId, uuids }] }` | full order per group after drag & drop; people not listed keep their place |

Every route answers with the new admin view. Errors: `user_not_found` (404), `already_on_team_page` (409),
`group_required` (400, no team role and no `roleId`), `team_page_full` (409, 200 people), `not_on_team_page` (404),
`duplicate_member` (400), `role_not_found` (404). Rate limit 60/min per team member; all changes go to the audit log
(`teampage.add|update|remove|reorder`). Permission `team.page` (Owner/Admin by default; group "team").

### 26.3 Heads for team lists

`POST /v1/admin/heads` `{ uuids: [≤ 120] }` (any team member, 40/min) → `{ heads: { uuid: { url, model } | null }, pending }`.
Stored skins come from the database; missing or older than a day are looked up at Mojang, at most 6 per call – the rest
comes back in `pending`, and the website asks again a moment later. Every successful skin lookup (also `/v1/skins/…`,
`/v1/web/me`) stores the texture address (`users.skin_url`, `skin_model`, `skin_at`; only `textures.minecraft.net`).

### 26.4 Data and migration 15

`users.skin_url|skin_model|skin_at`, table `team_page_members` (uuid, role_id = group, sort, titles JSON, discord, links
JSON, added/updated), permission `team.page` for Owner/Admin. Idempotent (`migrateTeamPage`). The old automatic member
list of `GET /v1/site/team` (everyone with a public role) is replaced by the manual list.

## 27. Shared modpacks

A player shares an instance as a Modrinth pack (`.mrpack`): the launcher exports it (files under `mods/`,
`resourcepacks/`, `shaderpacks/` and datapacks that Modrinth, CurseForge or GitHub can serve go in as download links
in `modrinth.index.json`; configs, options, custom jars and anything that could not be resolved go into `overrides/`)
and uploads the file. Others install it by **code** (`TRS-XXXX-XXXX`, 8 characters Crockford base32), by **link**
(`https://trs-launcher.theredstonee.de/p/TRS-XXXX-XXXX`) or from the list of packs friends sent them. A new version
keeps the code and raises `revision` – that is how launchers detect updates. The uploaded file may be up to
`PACK_MAX_MB` (default **1024**). A single request stays at most **100 MB** (Cloudflare); larger packs use the
chunked upload in §27.7. Older launchers keep sending the file in one request.

### 27.1 Pack view

```json
{ "id": "22 chars", "code": "TRS-7K2M-Q9XA", "url": "https://…/p/TRS-7K2M-Q9XA",
  "name": "Redstone Pack", "summary": "Tech & Redstone", "packVersion": "1.2.0", "revision": 3,
  "mcVersion": "1.21.1", "loader": { "kind": "fabric", "version": "0.16.9" },
  "modrinthFiles": 42, "ownJars": 1, "otherFiles": 57, "bytes": 812345, "sha256": "…",
  "owner": { "uuid": "…", "name": "Alex" },
  "createdAt": "…", "updatedAt": "…", "expiresAt": "… | null" }
```

Own packs (`/v1/me/packs`, upload answers) add `duration` (`1d|7d|30d|forever`), `installs` (downloads by others) and
`sentTo` (friends it was sent to). `expiresAt: null` = no expiry. `modrinthFiles` counts every file listed in the
index (Modrinth, CurseForge or GitHub). The name stays so older clients keep working. `ownJars` > 0 means jar files
stored in the pack (not a download link) – launchers show a trust warning before installing.

### 27.2 Routes

| Route | Auth | Notes |
|---|---|---|
| `POST /v1/packs?duration=7d` | user | raw `.mrpack` body (`application/x-modrinth-modpack+zip`, `application/zip` or `application/octet-stream`), ≤ min(`PACK_MAX_MB`, 100 MB) → 201 `{ pack }` (own view). `Content-Type: application/json` with `{ uploadToken }` uses a finished chunked upload (§27.7) instead; `duration` stays a query parameter |
| `PUT /v1/packs/{id}/file` | owner | new version, same code, `revision + 1`; recipients get `pack_updated`. Same body rules as the create route (raw file or `{ uploadToken }`). `pack_unchanged` (409) for the same file |
| `PATCH /v1/packs/{id}` | owner | `{ duration }` – counts from now |
| `DELETE /v1/packs/{id}` | owner | 204; code and link stop working, recipients get `pack_removed` |
| `GET /v1/me/packs` | user | `{ packs, limits: { active, maxActive, uploadsToday, maxPerDay, maxBytes } }` |
| `GET /v1/packs/code/{code}` | public | `{ pack }` – code case-insensitive, with or without `TRS-`/dashes, O→0, I/L→1. 60/min per IP without account |
| `GET /v1/packs/code/{code}/contents` | public | `{ contents: { mods, resourcePacks, shaderPacks } }` – each `[{ name, file, source: "modrinth"\|"curseforge"\|"github"\|"pack", projectId }]` (§27.6); same limits as the pack view, does not count an install |
| `GET /v1/packs/code/{code}/file` | user | the `.mrpack`, streamed, with `Range` (§27.8). Headers `X-Pack-Revision`, `X-Pack-Sha256`, `Accept-Ranges: bytes`, `Content-Length`. Counts an install only when the range starts at byte 0 and you are not the owner |
| `POST /v1/packs/uploads` | user | start a chunked upload (§27.7) → 201 `{ uploadId, chunkSize, expiresAt }` |
| `PUT /v1/packs/uploads/{uploadId}/chunks/{index}` | user | one chunk, raw bytes, header `X-Chunk-Sha256` → 204 |
| `GET /v1/packs/uploads/{uploadId}` | user | `{ received, size, chunkSize }` – which chunks are already stored |
| `POST /v1/packs/uploads/{uploadId}/complete` | user | assemble and check the file → `{ uploadToken }` |
| `POST /v1/packs/lookup` | user | `{ codes: [≤ 100] }` → `{ packs }` (update check; unknown/expired codes are missing) |
| `POST /v1/packs/{id}/send` | user | `{ to: [uuid ≤ 20] }` → `{ sent: [PlayerRef], skipped: [uuid] }`. Anyone who can see a pack may send it to **their own friends**; non-friends and blocks are skipped, `no_recipients` (400) if nobody was left |
| `GET /v1/me/pack-inbox` | user | `{ packs: [{ pack, from, sentAt }] }` – newest first, without dismissed/expired |
| `DELETE /v1/me/pack-inbox/{packId}` | user | hide from the list (the pack stays) |

Packs of banned accounts are hidden (404). Errors: `invalid_pack` (422, with a readable message and sometimes
`details.path`), `pack_not_found` (404), `pack_limit` (409, `details.max`), `pack_daily_limit` (429, `Retry-After`),
`payload_too_large` (413), `storage_full` (507), `sanctioned` (403, upload ban for uploads, social ban for sending).
Chunked uploads add `upload_not_found` (404), `upload_expired` (410), `upload_limit` (409, `details.max`),
`upload_incomplete` (409, `details.missing` up to 32 indexes and `details.missingCount`), `upload_closed` (409),
`upload_used` (409), `chunk_conflict` (409), `invalid_chunk` (400), `checksum_mismatch` (422) and
`range_not_satisfiable` (416, header `Content-Range: bytes */<size>`).

### 27.3 What the server checks

Only real Modrinth packs: a zip (no ZIP64, no encryption) with `modrinth.index.json` (`formatVersion` 1, `game`
`minecraft`, `dependencies.minecraft` plus at most one of `forge`, `neoforge`, `fabric-loader`, `quilt-loader`) and
files only under `overrides/`, `client-overrides/`, `server-overrides/`. All paths relative without `..`, backslashes
or drive letters; no file twice. Every index `downloads[]` entry must be **https**, with no userinfo, no backslash and no
port other than 443, and the hostname (lower case, one trailing dot ignored) must be exactly one of
`cdn.modrinth.com`, `edge.forgecdn.net`, `mediafilez.forgecdn.net`, `github.com`, `raw.githubusercontent.com`.
Lookalikes (`www.github.com`, `objects.githubusercontent.com`, `release-assets.githubusercontent.com`) are rejected.
Each file needs `hashes.sha1`, `hashes.sha512`, `fileSize` and 1–5 of those URLs. The server does **not** fetch the
URLs and does **not** follow redirects. At most 20,000 zip entries, 5,000 index files and 1 GB unpacked. The server
never unpacks anything to disk. ZIP64 is rejected.

The pack page links to `trs-launcher://pack/TRS-XXXX-XXXX` (“Open in TRS Launcher”, launcher ≥ 0.12.0). The launcher
only accepts exactly this form and opens its “Modpack by code” preview – it never installs without a click.

### 27.4 Events (`/v1/events/me`)

- `pack_shared` `{ pack, from, sentAt }` – a friend sent you a pack.
- `pack_updated` `{ pack }` – a pack in your list has a new version.
- `pack_removed` `{ packId }` – deleted by its owner or the team.

### 27.5 Limits, reports, data

Per account: 10 active packs (`maxSharedPacks`), 30 uploads (new packs and versions) per 24 h, 100 unread packs in the
inbox; all packs together ≤ `PACK_STORAGE_MAX_MB` (default **51200**). One pack file ≤ `PACK_MAX_MB` (default
**1024**, allowed 1–2048). A direct upload body is capped at min(that, **100 MB**); above 100 MB the client uses
§27.7. Open upload sessions count toward the storage limit. The daily counter increments when a pack is created or
updated, not when a chunk session starts. `GET /v1/me/packs` → `limits.maxBytes` is `PACK_MAX_MB` in bytes.
Rate limits: upload 6/min (also starts and completes a chunked upload), chunk bytes 120/min, manage 60/min (also the
upload status), lookup 60/min, download 20/min per account, public page 60/min per IP.

Reports: `POST /v1/reports` with `{ kind: "pack", packId, reason }`. Evidence keeps name, code, description, revision,
Minecraft version, loader, own-jar count, checksum and owner (not the file). Team action `delete_pack`.

Migration 16: tables `shared_packs` (file `<DATA_DIR>/packs/<xx>/<id>.<revision>.mrpack`, only the current version is
kept), `shared_pack_recipients`, `shared_pack_uploads`; report kind `pack` + column `pack_id`. Migration 22: tables
`pack_upload_sessions` and `pack_upload_chunks` (bytes live under `<DATA_DIR>/packs/tmp/<uploadId>/`, not in the
database). Expired packs and expired upload sessions are removed every 10 minutes; orphaned pack files every 6 hours
(the sweeper skips `packs/tmp`). Account deletion removes all own packs, pack files and upload temp directories.

### 27.6 Contents on the pack page

`/p/<code>` lists what is inside, read from the stored file (only the zip directory and the index, nothing is
unpacked): **mods** (`mods/*.jar`), **resource packs** (`resourcepacks/*.zip` or a folder) and **shaders**
(`shaderpacks/*.zip` or a folder) – from the index (`source`: `modrinth`, `curseforge` or `github`, from the first
download host) and from `overrides/`/`client-overrides/` (`source: "pack"`, marked as own file). `projectId` and
`versionId` are filled only for `cdn.modrinth.com` addresses of the form `/data/<8>/versions/…` (then linked to
`https://modrinth.com/project/<id>`). Files for servers only (`env.client` `unsupported`, `server-overrides/`) are
left out. At most 1,000 per list; `name` is the file name without extension. Cached per pack revision.

Each entry also has `versionId` (from the CDN address), `title` and `version` (looked up on Modrinth's public API –
`/v2/projects?ids=` and `/v2/versions?ids=`, only IDs from the pack, cached for a day, file names as fallback when
Modrinth is down), `url` (project page on modrinth.com) and `icon` (`/v1/modrinth/icon/{projectId}` – the server
fetches the icon from `cdn.modrinth.com` itself, only PNG/JPEG/GIF/WebP ≤ 512 KB, only for projects that appeared in a
pack list; 600/min per IP, `Cache-Control: public, max-age=86400`). Sorted by title (else name). CurseForge and GitHub
entries have no Modrinth project page; the website shows them as remote files, not as own files.

### 27.7 Chunked upload

Cloudflare rejects a request body above 100 MB, so a pack between 100 MB and `PACK_MAX_MB` is uploaded in pieces.
The session lasts **24 hours**. At most **3** unexpired sessions per account (finished ones that still hold a token
count). A fourth returns `409 upload_limit`.

`POST /v1/packs/uploads` with `{ "size": <bytes>, "sha256": "<64 hex>", "name"?: "<1–64>" }` → **201**

```json
{ "uploadId": "22 chars", "chunkSize": 33554432, "expiresAt": "…" }
```

`size` must be ≥ 1 and ≤ `PACK_MAX_MB`. `chunkSize` comes from `PACK_CHUNK_BYTES` (default 33,554,432, at most that).
Clients must use the returned value. More than 4,096 chunks is `400 invalid_request`.

`PUT /v1/packs/uploads/{uploadId}/chunks/{index}` – raw bytes, header `X-Chunk-Sha256` (64 hex). `index` starts at 0.
Every chunk except the last is exactly `chunkSize` bytes; the last is the remainder. → **204**. Sending the same
bytes again is fine. Different bytes for an index that is already stored → `409 chunk_conflict`. A hash that does
not match the body → `422 checksum_mismatch` (nothing stored). A short or out-of-range index → `400 invalid_chunk`.
A body longer than that chunk → `413 payload_too_large`. After complete, further chunks → `409 upload_closed`.

`GET /v1/packs/uploads/{uploadId}` → `{ "received": [0, 2], "size": …, "chunkSize": … }` (indexes in order) so a
client can resume. Unknown, foreign or already used ids are `404 upload_not_found`. Past `expiresAt` is
`410 upload_expired` on status, chunk and complete.

`POST /v1/packs/uploads/{uploadId}/complete` assembles the chunks under `packs/tmp/<uploadId>/assembled.mrpack`,
checks the total size and SHA-256, then runs the same pack checks as a direct upload (§27.3, including the download
hosts). → `{ "uploadToken": "up_" + 43 base64url characters }`. The token is returned **once**. A second complete is
`409 upload_closed` – the client must have saved the token. Missing chunks → `409 upload_incomplete` with
`missing` (at most 32 indexes) and `missingCount`; those chunks can be sent again. If a stored chunk's bytes no
longer match its row, that index is dropped and returned in `missing`. A wrong total hash or size →
`422 checksum_mismatch` and the chunks stay (start a new session if the declared hash was wrong). An invalid pack
deletes the session and returns `422 invalid_pack`.

`POST /v1/packs?duration=` and `PUT /v1/packs/{id}/file` accept `Content-Type: application/json` and
`{ "uploadToken": "up_…" }` and then behave like a normal create or update (same answer, same daily limit, same
`pack_updated` event). The token works once. Using it again, or a token from another account, is
`404 upload_not_found` (a non-owner update is still `404 pack_not_found`). An expired token is `410 upload_expired`.
Temp files are removed when the token is used, when the session expires, or when the account is deleted.

### 27.8 Download and what the installer checks

`GET /v1/packs/code/{code}/file` streams the file. `Content-Length` is the number of bytes in the response.
`Accept-Ranges: bytes`.

- No `Range`, or a header that is not a single `bytes=` range (including multipart `bytes=0-1,2-3`) → **200**, the
  whole file, no `Content-Range`.
- `bytes=<start>-<end>`, `bytes=<start>-` or `bytes=-<suffix>` → **206** with `Content-Range: bytes start-end/total`.
  `end` is inclusive. The stream is that slice.
- A range that starts at or past the end, or an empty file with a range → **416** `range_not_satisfiable` and
  `Content-Range: bytes */<size>`.

An install is counted only when the requester is not the owner, the file is not empty, and the response starts at
byte 0 (a full download or a range such as `bytes=0-…`). A resume from a later byte does not count again.

The **installer** (launcher, not this server) downloads each index file itself. It may follow a redirect only when
the next URL is still https on one of the five hosts in §27.3. Any other host aborts the install. After each
download it checks `fileSize`, `hashes.sha1` and `hashes.sha512`. A mismatch aborts with a clear error and does not
keep the file. The API never requests those URLs.

## 28. Issues & roadmap

A public issue tracker on the website (`/issues`, `/issues/<number>`, `/roadmap`): players report **bugs** and suggest
**features** for the launcher, the TRS Client or the website, vote them **up or down**, comment and follow them. The
team triages (status, priority, assignee, tags, “fixed in”), merges duplicates and writes internal notes. Reading is
public; writing needs an account – a **website session** (§24.1, cookie + `X-CSRF-Token`) or a **Bearer token**
(launcher/TRS Client, e.g. “Report a bug” in the client).

### 28.1 Values

| field | values |
|---|---|
| `type` | `bug`, `feature` |
| `area` | `launcher`, `client`, `website` |
| `status` | `open` (new), `planned`, `in_progress`, `in_review`, `done`, `rejected`, `duplicate` – the last three are **closed** |
| `priority` | `null` (not set), `low`, `medium`, `high`, `critical` |

Limits: title 5–120 characters, description 0–8,000 (Markdown; the website asks for at least 10), comment 1–5,000 (after cleaning: NFC, control/bidi characters
removed, word filter §20.4 – blocked words → `422 message_blocked`). Tags: ≤ 6 per issue, `^[a-z0-9][a-z0-9-]{0,23}$`.
`fixedIn`: a version like `0.13.0` or `0.13.0-beta.1` (`^\d{1,3}\.\d{1,3}\.\d{1,3}(?:[-+][0-9A-Za-z.-]{1,20})?$`).

### 28.2 Views

**IssueView** (lists):

```json
{ "number": 57, "url": "https://trs-launcher.theredstonee.de/issues/57",
  "type": "feature", "area": "launcher", "status": "planned", "title": "Share modpacks by code",
  "author": { "uuid": "…", "name": "Alex" }, "authorTeam": false,
  "score": 12, "up": 14, "down": 2, "comments": 3,
  "priority": "low", "assignee": { "uuid": "…", "name": "Theredstonee" }, "tags": ["ui"], "fixedIn": null,
  "duplicateOf": null, "locked": false, "source": "web",
  "createdAt": "…", "updatedAt": "…", "activityAt": "…", "closedAt": null,
  "myVote": 1 }
```

- `author` is `null` for deleted accounts. `authorTeam`: the author is a team member. Player references in §28
  (author, assignee, comment authors, history actors) also carry `skin` (last seen skin URL on textures.minecraft.net or
  `null`) so the website can draw heads without asking Mojang.
- `score` = `up − down`. `myVote` (`1`, `-1`, `0`) only when the request is signed in.
- `duplicateOf`: `{ number, title, status, url }` when merged into another issue (status `duplicate`).
- `locked`: comments are closed (only the team can still comment). `source`: `web` or `client` (sent from the TRS Client).
- `activityAt`: last comment, status change or creation.

**IssueDetail** (`GET /v1/issues/{number}`) = IssueView plus:

```json
{ "description": "markdown", "attachments": [ImageView], "editedAt": null,
  "meta": { "modVersion": "0.13.0", "mcVersion": "1.21.11", "loader": "fabric", "mods": ["fabric-api 0.110.0", "sodium 0.6.0"], "log": "…" },
  "following": true,
  "can": { "edit": true, "comment": true, "vote": true, "manage": false, "moderate": false },
  "notes": [ { "id": 1, "at": "…", "author": PlayerRef, "text": "…" } ] }
```

- `meta` (“technical info”, `null` if none was sent): versions and mod list are public; `log` is only returned to the
  **author and the team** (`issues.manage` or `issues.moderate`) – for everyone else it is missing (`hasLog: true`
  tells that there is one). The website shows `meta` as a collapsed section, the log as a code block.
- `following` only when signed in. `notes` (internal) only with `issues.manage`. The team also gets `deleted`.
- Markdown is rendered by clients **without raw HTML** (HTML is shown as text), links only `http(s)`/relative with
  `rel="nofollow ugc noopener"`, no remote images.

**ImageView**: `{ "id": "22 chars", "url": "…/v1/issues/uploads/<id>", "thumbUrl": "…?thumb=1", "width": 1920, "height": 1080 }`

**CommentView**:
```json
{ "id": 812, "author": PlayerRef | null, "team": true, "body": "markdown | null", "attachments": [ImageView],
  "createdAt": "…", "editedAt": null, "deleted": false, "deletedBy": null, "mine": false }
```
Deleted comments stay as a placeholder (`body: null`, `attachments: []`, `deleted: true`, `deletedBy`: `author`|`team`).
`team`: written by a team member (shown with a badge; followers are notified).

**HistoryEntry** (public timeline): `{ "at": "…", "actor": PlayerRef | null, "action": "status" | "fixed_in" | "assignee" | "priority" | "type" | "area" | "tags" | "title" | "locked" | "unlocked" | "merged_into" | "merged_from", "from": "… | null", "to": "… | null" }`
(`assignee` values are names, `merged_*` values are issue numbers as text).

### 28.3 Public and player routes

| Route | Auth | Notes |
|---|---|---|
| `GET /v1/issues` | public (optional sign-in for `myVote`) | Query: `sort=top\|new\|activity` (default `top`), `type`, `area`, `status` (comma list), `closed=1` (include closed; a status filter naming closed statuses includes them anyway), `q` (≤ 80, title/description; `#57` also matches the number), `filter` (search syntax, §28.3.1), `page` (1…), `per` (≤ 50, default 20) → `{ issues: [IssueView], total, page, pages, per, errors }` (`errors` = parts of `filter` that were not understood). Without a status/`is:` filter only open issues are listed. Deleted issues are never listed. |
| `GET /v1/issues/roadmap` | public | Board with six columns: `?filter=` (§28.3.1), `per` (≤ 50, default 20) → `{ columns: [{ status, total, issues: [IssueView], hasMore }], per, errors }` in the order `open`, `planned`, `in_progress`, `in_review`, `done`, `rejected` (duplicates are not shown). Open issues by score, work columns by priority then score, done/rejected newest first. `?column=<status>&offset=<n>` → `{ column }` = the next cards of one column (“load more”). |
| `GET /v1/issues/summary` | public (optional sign-in) | Counters for the website sidebar: `{ open, total, byStatus: { <status>: n }, mine?, following?, team?: { new, mine } }` (`mine`/`following` signed in, `team` with `issues.manage`). |
| `GET /v1/issues/{number}` | public (optional sign-in) | `{ issue: IssueDetail, comments: [CommentView], history: [HistoryEntry] }` – `404 issue_not_found` for unknown/deleted issues (the team still sees deleted ones). |
| `POST /v1/issues` | player (website session **or** Bearer token) | `{ type, area, title (5–120), description (≤ 8000, Markdown), attachments?: [uploadId ≤ 6], meta?: { modVersion? (≤ 32), mcVersion? (≤ 32), loader? (≤ 32), mods? ([≤ 300], ≤ 100 chars each), log? (≤ 20000) } }` → **201** `{ issue: IssueDetail }` (has `number` and `url`). The author follows automatically. The server removes tokens, session ids, e-mail addresses, IP addresses, UUIDs and the author's name from `log` again before storing it (defence in depth). `source` is `client` when `meta.modVersion` is set, else `web`. |
| `PATCH /v1/issues/{number}` | author | `{ title?, description?, type? }` – only while the status is `open` (`409 issue_not_editable`). |
| `POST /v1/issues/{number}/vote` | player | `{ vote: 1 \| -1 \| 0 }` (0 = remove) → `{ score, up, down, myVote }`. Closed issues: `409 issue_closed`. |
| `PUT /v1/issues/{number}/follow` · `DELETE …/follow` | player | 204. Following = notifications (§28.6). |
| `POST /v1/issues/{number}/comments` | player | `{ body, attachments?: [uploadId ≤ 4] }` → **201** `{ comment: CommentView }`. Commenting follows the issue. `409 issue_locked` when comments are closed (team excepted). |
| `PATCH /v1/issues/{number}/comments/{id}` | author | `{ body }` → `{ comment }` |
| `DELETE /v1/issues/{number}/comments/{id}` | author | 204 (placeholder stays) |
| `POST /v1/issues/uploads` | player (Bearer or website session) | raw PNG/JPEG/WebP body (`Content-Type` must match the magic bytes), ≤ 8 MiB, ≤ 8192 px / 24 MP → **201** `{ upload: ImageView }` (`id` = 22 chars base64url). Always re-encoded (PNG with transparency, else JPEG q88), ≤ 2560 px, preview ≤ 400 px, no metadata. Valid for **1 hour** until attached, then deleted. |
| `GET /v1/issues/uploads/{id}` | public | the image (`?thumb=1` preview) while its issue/comment is visible; the uploader also sees unused ones. |
| `GET /v1/me/issues` | player | `{ created: [IssueView], following: [IssueView] }` (latest activity first, ≤ 100 each) |

Errors: `invalid_request` (400 with `details.fields`), `issue_not_found` (404), `comment_not_found` (404),
`upload_not_found` (404 when attaching an unknown, expired, foreign or already used upload), `issue_closed`, `issue_locked`, `issue_not_editable` (409), `issue_daily_limit` /
`comment_daily_limit` / `upload_daily_limit` (429 with `Retry-After`), `sanctioned` (403: a **social ban** blocks new
issues and comments, an **upload ban** blocks images; voting and following stay), `message_blocked` (422).

### 28.3.1 Search syntax (`filter`)

One text field for list and board, parsed the same way on the website and the server (`shared/issue-query.ts`, fixed
patterns only – nothing from the input becomes a regex). Tokens are separated by spaces, `"…"` keeps spaces together,
several values with commas (= or), unknown keys are free text. German, English and Spanish keys/values are accepted:

| key | aliases | values |
|---|---|---|
| `status` | `estado` | `open`/`offen`, `planned`/`geplant`/`todo`, `in-progress`/`in-arbeit`, `in-review`/`in-pruefung`, `done`/`erledigt`, `rejected`/`abgelehnt`, `duplicate`/`duplikat` |
| `type` | `typ`, `art`, `tipo` | `bug`/`fehler`, `feature`/`funktion`/`wunsch` |
| `area` | `bereich`, `platform` | `launcher`, `client`, `website`/`web` |
| `priority` | `prio`, `priorität` | `low`/`niedrig`, `medium`/`mittel`, `high`/`hoch`, `critical`/`kritisch`, `none`/`keine` |
| `author` | `autor`, `von` | Minecraft name (optionally `@name`) |
| `assignee` | `zuständig`, `zustaendig` | Minecraft name, `me`/`ich`, `none`/`niemand` |
| `tag` | `label` | a tag |
| `is` | `ist` | `open`/`offen`, `closed`/`geschlossen`, `all`/`alle` |
| `sort` | – | `top`, `new`/`neu`, `activity`/`aktivitaet` (list only) |

Example: `status:geplant,in-arbeit area:client author:Alex "world map"`. At most 300 characters and 30 tokens; free
text ≤ 80 characters (title/description, `#57` = number). Values the parser does not know are ignored and returned in
`errors`.

### 28.4 Team (`/v1/admin/issues…`)

Permissions (§24.2): **`issues.manage`** – triage: status, priority, assignee, tags, type, area, title, “fixed in”,
merge, internal notes, lock/unlock comments. **`issues.moderate`** – delete/restore issues and comments, lock/unlock.
Default roles (migration 17 adds them; customised roles keep everything else): owner, admin, senior moderator: both;
moderator: `issues.moderate`; supporter: `issues.manage`.

| Route | Permission | Notes |
|---|---|---|
| `GET /v1/admin/issues` | manage or moderate | like the public list plus `view=all\|unassigned\|mine\|deleted`, `priority` → items carry `deleted` |
| `GET /v1/admin/issues/staff` | manage | `{ staff: [PlayerRef] }` – team members with `issues.manage` (assignee choices) |
| `PATCH /v1/admin/issues/{number}` | manage (`locked` also moderate) | `{ status?, priority?, assignee? (uuid \| null, must have issues.manage), tags?, fixedIn? (string \| null), type?, area?, title?, locked? }` → `{ issue, history }`. `status: duplicate` only via merge (`400 use_merge`). Setting `fixedIn` does not change the status by itself. |
| `POST /v1/admin/issues/{number}/merge` | manage | `{ into: number }` → `{ issue }` (the target). Votes move to the target (a voter who already voted there keeps that vote), followers are added, the source becomes `duplicate` + closed with `duplicateOf` and is locked, both get a history entry. `409 merge_invalid` (same issue, target deleted, target itself a duplicate or source already merged). |
| `POST /v1/admin/issues/{number}/notes` | manage | `{ text ≤ 2000 }` → `{ notes }` (internal, never public) |
| `DELETE /v1/admin/issues/{number}` | moderate | `{ reason? }` → 204; hidden everywhere, restorable for 90 days, then removed with its images |
| `POST /v1/admin/issues/{number}/restore` | moderate | → `{ issue }` |
| `DELETE /v1/admin/issues/{number}/comments/{id}` | moderate | 204 (placeholder “removed by the team”) |

Every team change is written to the audit log (`issue.*`).

### 28.5 Reports

`POST /v1/reports` (§20.1) with `{ kind: "issue", issueNumber, reason }` or `{ kind: "issue_comment", commentId, reason }`.
Target = the author (none for deleted accounts). Evidence keeps number, title, description (or the comment text) and
the author. Team action `delete_issue` deletes the reported comment or issue (needs `issues.moderate` in addition to
`reports.handle`); sanctions work as for every report.

### 28.6 Events (`/v1/events/me`, §19)

`issue_updated` goes to everyone following the issue (never to the one who made the change):

```json
{ "type": "issue_updated", "change": "status | team_comment | fixed | merged",
  "issue": { "number": 57, "title": "…", "type": "feature", "area": "launcher", "status": "done", "url": "https://…/issues/57" },
  "by": { "uuid": "…", "name": "…" } | null, "status": "done", "fixedIn": "0.13.0 | null",
  "mergedInto": { "number": 12, "title": "…", "url": "…" } | null, "excerpt": "first 140 chars of the team comment | null", "at": "…" }
```

- `status`: the status changed (not to `duplicate`). `fixed`: “fixed in” was set or changed (when the status changed
  in the same request only this event is sent). `team_comment`: a team member commented. `merged`: the issue was
  merged into `mergedInto` (its followers now follow the target).
- Launchers show a social toast with a “View” button that opens `issue.url` in the browser (only if it is on the
  API's own site).

### 28.7 Data, limits, migration 17

- Tables: `issues` (number = `id`), `issue_votes`, `issue_follows`, `issue_comments`, `issue_uploads` (files
  `<DATA_DIR>/issues/<xx>/<id>.<jpg|png>` + `.t.` preview; public content, not encrypted), `issue_tags`,
  `issue_notes`, `issue_history`, `issue_actions` (daily limits). Reports: kinds `issue` / `issue_comment` + columns
  `issue_id`, `issue_comment_id` (report tables rebuilt as in migration 16).
- Per account and 24 h: 10 issues, 60 comments, 30 uploads. Rate limits: create 5/h, comment 10/5 min, vote 60/min,
  follow 60/min, image upload 20/10 min, edit 30/10 min; public reads 240/min per IP.
- Account deletion: votes and follows go; issues and comments stay **without author** (`author: null`), their
  logs and all images uploaded by the account are deleted.
- Deleted issues are removed completely 90 days after deletion.
- Migration 17 is idempotent and only adds permissions to the default roles.

## 29. Website sign-in with the TRS Launcher

A second way to sign in on the website (next to Microsoft, §24.1): the website shows a short code, the launcher confirms it
with its TRS account. The result is exactly the same website session as after the Microsoft sign-in (cookie `trs_session`,
httpOnly, Secure, SameSite=Strict, 8 h, session rotation, team rights read per request). **Nothing is confirmed
automatically** – the launcher only answers after a click.

```
Browser                         Server                              Launcher (Bearer token of the chosen account)
POST /v1/web/launcher-login ──▶ request (2 min): link token, code,
◀── token, code, link           cookie trs_llogin (browser value)
opens trs-launcher://web-login/<token>  ─────────────────────────▶  POST /v1/launcher-login/lookup {token} (or {code})
POST …/poll every 2 s  ──────▶  pending                        ◀──  shows site, code, browser, time, account
                                                               ◀──  POST /v1/launcher-login/approve {id, code}  (click)
POST …/poll  ────────────────▶  approved → trs_session (once)
```

### 29.1 Start (website, no session)

`POST /v1/web/launcher-login` `{ "return"?: "/admin" }` → `201`

```json
{ "token": "<43 chars>", "code": "K7Q-2MX", "link": "trs-launcher://web-login/<token>", "expiresAt": "…", "expiresIn": 120, "pollMs": 2000 }
```

- Sets cookie `trs_llogin` (random browser value; httpOnly, Secure, SameSite=Strict, path `/v1/web/launcher-login`). Only
  this browser can redeem the request. A new start in the same browser replaces its older request.
- `token`: 32 random bytes (base64url) – only for the link and the polling. `code`: 6 characters from
  `23456789ABCDEFGHJKMNPQRSTVWXYZ` (no 0/O, 1/I/L, U), shown as `XXX-XXX`, unique among open requests.
- Same origin only (`Sec-Fetch-Site: same-origin` or a matching `Origin`, else `403 cross_site`). `return` like §24.1
  (own relative paths only, otherwise `/applications`).
- Limits: 10 per 10 min and IP (`429`), at most 2,000 open requests in total (`503 busy`).

### 29.2 Poll / cancel (website)

`POST /v1/web/launcher-login/poll` `{ "token" }` (with the cookie) →

- `{ "status": "pending", "expiresAt" }`
- `{ "status": "approved", "returnTo": "/admin" }` – exactly once: sets `trs_session` (a previous session cookie of this
  browser is ended = rotation), deletes `trs_llogin` and the request. Team members get an audit entry `web.login` with
  detail `launcher`.
- `{ "status": "denied", "reason": "denied" | "banned" }` – declined in the launcher, or the account was banned before
  redeeming.
- `{ "status": "expired" }` – unknown, expired, already used, or wrong/missing cookie (no hint which).

120 polls per minute and IP. `POST /v1/web/launcher-login/cancel` `{ "token" }` → `204` (the “back” button; always 204).

### 29.3 Launcher routes (Bearer token, full scope)

- `POST /v1/launcher-login/lookup` `{ "token" }` **or** `{ "code": "k7q 2mx" }` (any spelling) →
  `{ "request": { "id", "code": "K7Q-2MX", "site": "trs-launcher.theredstonee.de", "browser": "Firefox · Windows" | null, "createdAt", "expiresAt" } }`.
  Errors: `404 login_request_expired`, `409 login_request_used`, `400 invalid_code` (malformed code). Wrong codes count
  strictly: 5 per 10 min and account, 20 per 10 min and IP (`429` with `Retry-After`), plus 30 lookups per 10 min and account.
- `POST /v1/launcher-login/approve` `{ "id", "code" }` → `204`. The code must be the one shown for this request. Once only
  (`409 login_request_used`), only while open (`404 login_request_expired`). Banned accounts cannot approve (`403 banned` /
  `401`). After approval the browser has at least 30 s to redeem.
- `POST /v1/launcher-login/deny` `{ "id", "code" }` → `204`.

Limit for approve/deny: 20 per 10 min and account.

`GET /v1/web/login` now also returns `launcher: true`. The old code sign-in (`/v1/web-login/*`, §15) stays `410`.

### 29.4 Data

Table `launcher_logins` (migration 18): id, SHA-256 of the link token and of the browser value, the code, a coarse browser
label from a fixed list (“Chrome · Windows” – never the full User-Agent), return path, status, the approving UUID, times.
No IP address. Rows disappear on redeeming/denial and with the regular sweep after expiry.

## 30. Blog: news posts

The team writes news posts; the automatic update posts from `CHANGELOG.md` (`/v1/site/blog` → `posts`, §site) stay
unchanged. The website shows both in one list by date (filter “All / Updates / News”); news also appear in the launcher's
news feed.

### 30.1 Public

- `GET /v1/site/blog` → `{ "posts": [update posts, unchanged], "news": [NewsSummary] }` (newest first).
- `GET /v1/site/news?limit=10` (1–20) → `{ "posts": [NewsPost] }` – for the launcher (with Markdown).
- `GET /v1/site/news/{slug}` → `{ "post": NewsPost }` or `404 post_not_found`.
- `GET /v1/site/blog/media/{id}.{jpg|png}` (preview `{id}.t.{ext}`) – images; public (`Cache-Control: public, max-age=604800,
  immutable`) as soon as their post is public, before that only for team members with a blog permission (website
  session, `private, no-store`), otherwise `404`. 600 per minute and IP.

```ts
NewsSummary = {
  kind: 'news', slug: string, publishedAt: ISO, updatedAt: ISO,
  langs: ('en' | 'de' | 'es')[],            // translated languages, English always first
  title: { en: string, de?: string, es?: string },
  summary: { en: string, … },               // short text; empty → start of the text
  cover: { url, thumbUrl, width, height } | null,   // relative to the website
  author: { uuid, name, skin: string | null } | null // null = “TRS Team”
}
NewsPost = NewsSummary & { markdown: { en: string, de?: string, es?: string } }
```

A post is public when it is published and its time has come (`publish_at <= now`) – scheduled posts appear without any
background job. Missing languages fall back to English (the page shows a hint); only translated languages get their own
`?lang=` address, hreflang and sitemap entry. Markdown is rendered without raw HTML, links only http(s)/own paths,
images only `/v1/site/blog/media/…` (`app/utils/markdown.ts`, `blogImages: true`).

Slugs: `[a-z0-9]+(-[a-z0-9]+)*`, 3–80 characters, at least one letter – so `/blog/<slug>` never collides with
`/blog/<version>` of the update posts. Sitemap, RSS feed (`/feed.xml`) and JSON-LD (`BlogPosting` with author) include news.

### 30.2 Team (`requireStaff`, website session + CSRF or Bearer)

Permissions: `blog.write` (drafts, images) and `blog.publish` (publish, schedule, unpublish, change or delete published
and scheduled posts). Defaults: Owner and Admin both, Content `blog.write` (migration 18 only adds them to the default
roles – changes made by admins stay).

| Route | Permission | |
| --- | --- | --- |
| `GET /v1/admin/blog` | write or publish | all posts (`AdminBlogListItem`), drafts first |
| `GET /v1/admin/blog/authors` | write or publish | team members to choose as author |
| `POST /v1/admin/blog` | write | `{ slug?, texts?, author? }` → new draft (slug from the English title) |
| `GET /v1/admin/blog/{id}` | write or publish | `AdminBlogPost` incl. `rev`, `media` |
| `PATCH /v1/admin/blog/{id}` | write (+publish if not a draft) | `{ rev, slug?, texts?, coverId?, author? }` |
| `POST /v1/admin/blog/{id}/publish` | publish | `{ rev, at? }` – `at` missing/past = now, future = scheduled (≤ 1 year) |
| `POST /v1/admin/blog/{id}/unpublish` | publish | `{ rev }` → draft again |
| `DELETE /v1/admin/blog/{id}` | write (+publish if not a draft) | post and all its images |
| `POST /v1/admin/blog/{id}/media` | write (+publish if not a draft) | raw PNG/JPEG/WebP ≤ 8 MiB → `201 { media }` |
| `DELETE /v1/admin/blog/{id}/media/{mediaId}` | write (+publish if not a draft) | removes the cover too if it was one |

`texts` = `{ en?, de?, es? }` with `{ title ≤ 120, summary ≤ 300, body ≤ 40,000 }` each; a completely empty language is
removed. Publishing needs an English title and text; other languages must be complete or empty
(`400 english_required`, `400 translation_incomplete` with `lang`). `rev` protects against overwriting (`409 stale`).
Other errors: `409 slug_taken`, `400 invalid_author` (not a team member), `400 invalid_cover` (image of another post),
`409 media_limit` (40 per post), `507 storage_full` (2 GiB), `503 busy` (image processing).
Images are always re-encoded (no metadata, ≤ 2400 px, preview ≤ 720 px) like chat images.
Audit: `blog.create`, `blog.update`, `blog.publish`, `blog.schedule`, `blog.unpublish`, `blog.delete`.
Limits: 120 changes and 30 uploads per minute and team member.

Migration 18: tables `blog_posts` (texts as JSON, status `draft`/`published` + `publish_at`, `rev`), `blog_media`
(files in `<DATA_DIR>/blog/<xx>/<id>.<ext>`). Deleting an author's account keeps the post (author → “TRS Team”).

---

## 31. Achievements

Launcher achievements in four categories: **playtime** (time played and starts), **launcher** (features), **community**
and **secret**. The catalog is defined by the server (ids never change; new achievements are only added). Friends can
see each other's unlocked achievements; a few achievements grant a cosmetic or cape.

**Where progress comes from.** Most progress is counted by the server from things it already knows (`verified: true`):
in-game heartbeats (§4.2), friends, chat, issues, votes, circuits, shared packs, hosted worlds, capes and cosmetics.
A few facts only the launcher knows are **reported** by it (`verified: false`, §31.4) – those never grant a reward.
Unlocking is idempotent: an achievement is unlocked once per account and stays unlocked (also when the condition later
stops being true, e.g. a friend is removed).

### 31.1 AchievementView

```json
{
  "id": "play_10h",
  "category": "playtime",
  "secret": false,
  "hidden": false,
  "title": { "en": "Settling in", "de": "…", "es": "…" },
  "description": { "en": "Play for 10 hours", "de": "…", "es": "…" },
  "icon": "clock",
  "points": 20,
  "rarity": "uncommon",
  "goal": 600,
  "unit": "minutes",
  "verified": true,
  "reward": null,
  "order": 5
}
```

| Field | Meaning |
|---|---|
| `id` | `^[a-z0-9_]{1,40}$`, stable. Secret achievements have neutral ids (`secret_01`, …). |
| `category` | `playtime` \| `launcher` \| `community` \| `secret` |
| `secret` | Secret achievement (always `category: "secret"`; the visible meta achievement `all_secrets` is in that category too, with `secret: false`). |
| `hidden` | `true` = secret **and not unlocked by the viewer**: `title`, `description`, `reward`, `goal` and `unit` are `null`, `icon` is `"secret"`. Show it as "???". |
| `title`, `description` | `{ en, de, es }` (all three always present) or `null` when `hidden`. Pick the UI language, fall back to `en`. |
| `icon` | Icon key; the client maps it to its own icon set: `rocket`, `repeat`, `clock`, `hourglass`, `trophy`, `flame`, `calendar`, `puzzle`, `boxes`, `package`, `share`, `download`, `film`, `clapperboard`, `globe`, `cape`, `hat`, `camera`, `wrench`, `truck`, `user-plus`, `users`, `message`, `messages`, `bug`, `thumbs-up`, `vote`, `lightbulb`, `bug-off`, `circuit`, `duck`, `moon`, `key`, `timer`, `crown`, `secret`. Unknown keys → a generic trophy. |
| `points` | Points for unlocking (5–100). |
| `rarity` | `common` \| `uncommon` \| `rare` \| `epic` \| `legendary` (fixed by the server, not computed). |
| `goal` | Target value for achievements with a counter, otherwise `null` (a single event unlocks it). |
| `unit` | `count` \| `minutes` \| `days`, or `null` when `goal` is `null`. Playtime is counted in minutes (show hours). |
| `verified` | `true` = counted by the server, `false` = reported by the launcher (§31.4). |
| `reward` | `{ "kind": "cape" \| "cosmetic", "id": "<cape or cosmetic id>" }` or `null`. Only on verified achievements. |
| `order` | Sort key (ascending). |

### 31.2 Catalog: `GET /v1/achievements`

Public (no auth, 60 / min per IP) → `200 { "achievements": [AchievementView] }`, sorted by `order`. Secret
achievements are always `hidden` here.

### 31.3 Own achievements: `GET /v1/me/achievements`

Auth required (read bucket). Evaluates all server-side conditions first (so it is always current) and grants rewards
whose item became available since the unlock (§31.6).

```json
{
  "achievements": [AchievementView],
  "unlocked": [ { "id": "first_launch", "at": "2026-09-28T10:00:00.000Z" } ],
  "progress": { "play_10h": 134, "friends_10": 3 },
  "points": 45,
  "totalPoints": 940,
  "visibleToFriends": true
}
```

- `achievements`: the catalog **as the viewer sees it** – secret achievements the viewer unlocked have `hidden: false`
  with their texts and reward.
- `unlocked`: every unlocked achievement (known ids only), oldest first.
- `progress`: current value for every achievement with a `goal` that is not `hidden`, capped at `goal` (unlocked
  ones report `goal`). Achievements without a goal are not listed.
- `points`: sum of the points of the unlocked achievements; `totalPoints`: sum over the whole catalog.
- `visibleToFriends`: the account's setting (§31.5), default `true`.

### 31.4 Launcher reports: `POST /v1/me/achievements/report`

Auth required. For facts only the launcher knows. Send one report per event (fire and forget; don't retry a `429`).

```json
{ "kind": "launch", "hour": 3 }
{ "kind": "mod_installed", "count": 12 }
```

| Field | Rules |
|---|---|
| `kind` | `launch` (a game was started from the launcher), `mod_installed` (mods were added to an instance), `modpack_installed` (a modpack was installed or imported), `clip_recorded` (a clip was saved), `crash_fixed` (the crash helper applied a fix), `launcher_import` (instances were imported from another launcher) |
| `hour` | Optional integer 0–23: the **local** hour of the start. Only with `launch` (otherwise `400 invalid_request`). |
| `count` | Optional integer 1–100, default 1: how many at once (e.g. mods added together). Only with `mod_installed` and `clip_recorded` (otherwise `400 invalid_request`). |

→ `200 { "newlyUnlocked": [ { "id": "first_mod", "at": "…" } ] }` (usually empty). Every newly unlocked
achievement also arrives as `achievement_unlocked` on `/v1/events/me` – a client with an open stream shows the toast
from the event only.

- Counters only count up to the highest goal that uses them (more reports change nothing); `crash_fixed`,
  `launcher_import` and the local hour are flags.
- Limit: **30 / min per account** (`429`). Like every call, only with the TRS consent given.

### 31.5 A player's achievements: `GET /v1/players/{uuid}/achievements`

Auth required (60 / min per account). Only for **yourself** or an **accepted friend**. Everyone else – unknown, not a
friend, banned, or blocked in either direction – gets `404 player_not_found`.

```json
{ "hidden": false, "unlocked": [ { "id": "first_friend", "at": "…" } ], "points": 25 }
```

If the friend hid their achievements (setting below), the answer is `200 { "hidden": true, "unlocked": [], "points": 0 }`
– show "hidden by the player". You always see your own (`hidden: false`).

Titles come from the viewer's catalog (`GET /v1/me/achievements` → `achievements`): a friend's secret achievement stays
"???" unless the viewer unlocked it too.

**Setting:** `PATCH /v1/me/achievements/settings` `{ "visibleToFriends": false }` → `200 { "visibleToFriends": false }`.
Auth required, write bucket (30 / min). Strict body (`400 invalid_request` for anything else). Default for every account:
`true`. It only affects friends' views; unlocking, events and your own view stay the same.

### 31.6 Event and rewards

`/v1/events/me` (§19), only to the account itself (all its devices):

```json
{ "type": "achievement_unlocked", "achievement": AchievementView, "at": "2026-09-28T10:00:00.000Z",
  "reward": { "kind": "cape", "id": "ideengeber" } }
```

- `achievement` is never `hidden` (secret ones come with their texts).
- `reward`: the item **granted with this unlock** (now owned: reload `GET /v1/me/cosmetics` or `GET /v1/capes`), or
  `null` (no reward, or the item is not available yet).
- Rewards are normal grants (they look like an admin grant). If the reward item does not exist yet, the unlock still
  happens and the item is granted once it exists (§31.8: server start, every 10 min, next `GET /v1/me/achievements`).

### 31.7 How the server counts

| Metric | Source |
|---|---|
| Playtime | In-game heartbeats (§4.2, mod or launcher): each heartbeat adds the time since the previous heartbeat of the account – only while it is `in-game`, and only if that gap is at most the 180 s expiry (a longer gap starts a new session and adds nothing). Two sources never count twice (wall-clock time). Stored: the total, the current and the longest session. |
| Day streak | Days (UTC) with at least one heartbeat (`online` or `in-game`); the best streak counts. |
| Friends | Current number of friends. |
| Chat | Own text messages (chat §18). |
| Issues | Own issues (not deleted); "from inside the game" = created by the TRS Client; "done" = status `done` or "fixed in" set; up-votes received from other accounts; own votes. |
| Circuits | Library circuits credited to you (accepted submissions, §25). |
| Packs | Packs shared as a link (§27); installs = downloads of your packs by other accounts. |
| Worlds, screenshots | Worlds opened for friends (§21), screenshots shared as a link (§23). |
| Capes, cosmetics, codes | A cape put on, a cosmetic equipped, a hidden cosmetic owned, a code redeemed. |

### 31.8 Catalog (v1)

| id | category | goal | points | rarity | verified | reward |
|---|---|---|---|---|---|---|
| `first_launch` | playtime | – | 5 | common | no | |
| `launches_50` | playtime | 50 | 15 | uncommon | no | |
| `launches_500` | playtime | 500 | 40 | rare | no | |
| `play_1h` | playtime | 60 min | 10 | common | yes | |
| `play_10h` | playtime | 600 min | 20 | uncommon | yes | |
| `play_50h` | playtime | 3000 min | 40 | rare | yes | |
| `play_100h` | playtime | 6000 min | 60 | epic | yes | cape `veteran` |
| `play_500h` | playtime | 30000 min | 100 | legendary | yes | |
| `streak_7` | playtime | 7 days | 25 | uncommon | yes | |
| `streak_30` | playtime | 30 days | 60 | epic | yes | |
| `first_mod` | launcher | – | 5 | common | no | |
| `mods_50` | launcher | 50 | 20 | uncommon | no | |
| `first_modpack` | launcher | – | 10 | common | no | |
| `first_pack_shared` | launcher | – | 15 | uncommon | yes | |
| `pack_installs_10` | launcher | 10 | 40 | rare | yes | |
| `first_clip` | launcher | – | 10 | common | no | |
| `clips_25` | launcher | 25 | 20 | uncommon | no | |
| `first_world_hosted` | launcher | – | 20 | uncommon | yes | |
| `cape_worn` | launcher | – | 5 | common | yes | |
| `cosmetic_equipped` | launcher | – | 5 | common | yes | |
| `screenshot_shared` | launcher | – | 10 | common | yes | |
| `crash_fixed` | launcher | – | 10 | common | no | |
| `launcher_import` | launcher | – | 10 | common | no | |
| `first_friend` | community | – | 10 | common | yes | |
| `friends_10` | community | 10 | 30 | rare | yes | emote `party` |
| `first_message` | community | – | 5 | common | yes | |
| `messages_500` | community | 500 | 30 | rare | yes | |
| `first_issue` | community | – | 10 | common | yes | |
| `votes_10` | community | 10 | 10 | common | yes | |
| `upvotes_10` | community | 10 | 30 | rare | yes | |
| `bug_squashed` | community | – | 25 | uncommon | yes | |
| `idea_implemented` | community | – | 50 | epic | yes | cape `ideengeber` |
| `circuit_approved` | community | – | 40 | rare | yes | |
| `secret_01` … `secret_05` | secret (`secret: true`) | – / 360 min | 10–30 | uncommon–epic | mixed | |
| `all_secrets` | secret (visible, `secret: false`) | 5 (= number of secret achievements) | 50 | legendary | yes | – |

- `all_secrets` is a visible meta achievement: its progress is the number of unlocked **secret** achievements, its goal
  grows when secret achievements are added (an account that already had it keeps it). It is counted by the server, but
  one of the secrets (`secret_02`) comes from a launcher report.
- The reward items are the capes `veteran` and `ideengeber` and the emote `party` (§12). Capes are built-in items
  added with the catalog. Until an item exists the unlock happens without it (`reward: null` in the event, the
  catalog still shows the planned reward) and the server logs once per item. **Everyone who already unlocked the
  achievement gets the item** as soon as it exists: after every server start (once the built-in capes and cosmetics are
  seeded), every 10 minutes, and on the account's next `GET /v1/me/achievements`. No event is sent for such a late
  grant; the item simply appears in the account's capes/cosmetics.
- The texts live in `server/lib/achievement-catalog.ts`.

### 31.9 Data, privacy, migration 19

- Tables: `achievement_unlocks` (account, id, time, reward granted), `achievement_stats` (counters and flags per
  account), `achievement_playtime` (total in-game time, time of the last in-game heartbeat, current and longest session,
  last active day, current and best day streak). No history of sessions or heartbeats. Column
  `users.achievements_visible` (default 1) for the friends setting.
- Account deletion (§3.3) removes all three (`ON DELETE CASCADE`); granted reward items go with the other grants.
- Visible to the account itself and its accepted friends (§31.5) only – unless the account hides them from friends.
  Nothing is public.
- Limits: catalog 60 / min per IP, `GET /v1/players/{uuid}/achievements` 60 / min, reports 30 / min per account.

## 32. Events (Halloween 2026)

A team member (permission **`events.manage`**, default roles owner and admin; migration 21 adds it to the existing default roles) switches an **event** on or off **globally** and can additionally allow **single players** (also while the event is off globally). The first event is `halloween`. For a player an event is **active** when it is on globally **or** the player is on its list. Clients (launcher, TRS Client) show the event theme when it is active for the signed-in player; the website shows it when it is on globally.

Event items (cosmetics and capes with `unlock: "event"`, §5.1/§11.6) are **free to claim while the event is active for you** and **kept afterwards**.

### 32.1 Public state: `GET /v1/events`

No auth. `Cache-Control: public, max-age=60`. Only the **global** state:

```json
{ "events": [ { "id": "halloween", "active": true } ] }
```

> `GET /v1/events` is also the old friends stream (§7). The server tells them apart by `Accept`: **`Accept: text/event-stream`** (needs auth) returns the old stream, anything else the JSON above. New clients use `GET /v1/events/me` (§19) for streams anyway.

### 32.2 For the signed-in player

- `GET /v1/me` → `events: string[]` (§3.1): the events active for **you**.
- `events_changed` on `GET /v1/events/me` (§19): `{ "type": "events_changed", "events": ["halloween"] }`, `events` = what is active for **the receiver now**. Sent to everyone with an open stream when a global switch changes, and to a single player when he is added to or removed from the list. Replayable like other events; after `resync` read `GET /v1/me` again.
- `POST /v1/me/cosmetics/{id}/claim` (§11.7) and `POST /v1/me/capes/{id}/claim` (§5.9a): `200 { "owned": true }` or `403 event_inactive`.
- `PUT /v1/me/cosmetics` takes `companion` (§11.7).
- Catalogs (`GET /v1/cosmetics`, `GET /v1/capes`) list event items while the event is active for you, or when you own them.

### 32.3 Team (`events.manage`)

All changes go to the audit log (`event.enable`, `event.disable`, `event.player_add`, `event.player_remove`, `ref = event:<id>`).

| Request | Body | Response |
|---|---|---|
| `GET /v1/admin/events` | – | `[ { id, enabled, updatedAt, players: [ { uuid, name, addedAt } ] } ]` |
| `PUT /v1/admin/events/{id}` | `{ "enabled": boolean }` | the changed event (same shape). `404 event_not_found` |
| `POST /v1/admin/events/{id}/players` | `{ "name": "<Minecraft name>" }` **or** `{ "uuid": "<uuid>" }` | **201** + the changed event. A name is looked up among TRS accounts first, then at Mojang (`404 player_not_found`, `502 upstream_unavailable`). `409 already_added` |
| `DELETE /v1/admin/events/{id}/players/{uuid}` | – | the changed event. `404 player_not_found`. Already claimed items stay. |

Missing permission → `403 missing_permission`.

### 32.4 Companion slot

`companion` is a new cosmetic slot (format 2 only, `attach: "head"`). It is worn **in addition to** the hat: the model is validated like a hat (format §11.9) and rendered with the same pipeline; animations run on the wall clock. The lookup (`POST /v1/lookup`, `GET /v1/events/players`) returns `cosmetics.companion` in the same flat form as `cosmetics.hat` of format 2. Older clients ignore the unknown key.

### 32.5 Data, migration 21

Tables `events (id, enabled, updated_at, updated_by)` (seeded: `halloween`, off) and `event_players (event_id, player_uuid, player_name, added_at, added_by)`; columns `cosmetics.event` and `capes.event` (event items are stored with `unlock = admin` and the event id); `user_cosmetics`/`user_capes` accept `source = 'event'`, `equipped_cosmetics` accepts the slot `companion` (these three leaf tables are rebuilt, data is kept). The website lists (`GET /v1/site/cosmetics` -> `hats` + `companions`, `GET /v1/site/capes`) show event items only while the event is **globally** on, with `unlock: "event"`, `event`, and (cosmetics) `slot`. Idempotent. When merging: renumber if a parallel branch also added a migration 21.

## 33. Push notifications for the mobile apps

The TRS apps get notifications while they are closed:

- **Android: UnifiedPush** (self-hosted friendly, no Google). The app gets an endpoint URL from the user's distributor app (ntfy, NextPush, …) and registers it here. The server encrypts every message (RFC 8291, `aes128gcm`) and signs with VAPID (RFC 8292), so the push server never sees the content. Operations, ntfy: [docs/push.md](docs/push.md).
- **iOS (sideloaded, no APNs): poll.** The app registers a `poll` device and fetches waiting notifications in its background fetch (§33.4).

Notifications come from the same events as `GET /v1/events/me` (§19). A device belongs to the **session** that registered it: logout, logout everywhere, session expiry (30 days) or a ban remove it; the app registers again after signing in.

All routes need `Authorization: Bearer …` (full scope). Bodies are strict (§1).

### 33.1 `GET /v1/push/config`

```json
{
  "unifiedPush": true,
  "vapidPublicKey": "BOr…(base64url, 65 bytes uncompressed P-256)",
  "categories": ["chat", "friends", "friend_online", "invites", "hosting", "packs", "team", "achievements"],
  "defaults": { "chat": true, "friends": true, "friend_online": false, "invites": true, "hosting": true, "packs": true, "team": true, "achievements": true },
  "maxDevices": 10
}
```

`unifiedPush: false` / `vapidPublicKey: null` = the server has no VAPID keys: only `poll` devices work. Pass `vapidPublicKey` to the distributor when asking for an endpoint (UnifiedPush "VAPID" registration).

### 33.2 Devices

**`POST /v1/push/devices`** (limit 10 / 10 min)

```json
{
  "platform": "android",
  "kind": "unifiedpush",
  "endpoint": "https://ntfy.sh/upAbC123…?up=1",
  "keys": { "p256dh": "<base64url P-256 public key, 65 bytes>", "auth": "<base64url, 16 bytes>" },
  "deviceName": "Pixel 8",
  "appVersion": "1.0.0",
  "locale": "de-DE",
  "categories": { "friend_online": true },
  "preview": false,
  "pushWhilePlaying": false
}
```

| Field | Rule |
|---|---|
| `platform` | `android` \| `ios` |
| `kind` | `unifiedpush` (needs `endpoint` + `keys`) \| `poll` (must not have them) |
| `endpoint` | `https://` only, ≤ 2048 characters, no `user:pass@`, no `#fragment`, port 443 or ≥ 1024. The host must be public: IP literals in private/loopback/link-local/CGNAT/multicast/reserved/IPv4-mapped ranges, `localhost`, single-label names, `.local`, `.internal`, `.lan`, `.home.arpa`, … and the TRS hosts themselves are refused (`400 endpoint_not_allowed`). The name is resolved and **every** address must be public (`400 endpoint_unresolvable` if DNS fails). The check runs again on every connection (no DNS rebinding). |
| `keys.p256dh` | uncompressed P-256 point (on the curve), base64url, else `400 invalid_keys` |
| `keys.auth` | exactly 16 bytes, base64url, else `400 invalid_keys` |
| `deviceName` | 1–64 characters, no control characters |
| `appVersion` | `^[0-9A-Za-z.+_-]{1,32}$` |
| `locale` | e.g. `en`, `de-DE`, `es-419`. Texts exist in `en`, `de`, `es`; anything else gets English. |
| `categories` | optional, any subset of §33.3 with booleans; missing ones use `defaults` |
| `preview` | default `false`: chat notifications show **only the sender and "New message"**. `true`: also the start of the text (≤ 120 characters) and the group name. |
| `pushWhilePlaying` | default `false`: no notifications while you play on the PC (§33.6). |

Responses: **201** + `PushDeviceView` (new) or **200** + `PushDeviceView` when the same `endpoint` is already registered for you (updated, same `id`). An endpoint registered by **another** account moves to you (whoever holds the endpoint is the device). Errors: `400 invalid_request`, `endpoint_invalid`, `endpoint_not_allowed`, `endpoint_unresolvable`, `invalid_keys`; `409 too_many_devices` (10 per account); `503 push_unavailable` (UnifiedPush without VAPID keys).

```json
{
  "id": "d3f9a0c1e2b4d5a6f7e8c",
  "platform": "android",
  "kind": "unifiedpush",
  "endpointHost": "ntfy.sh",
  "deviceName": "Pixel 8",
  "appVersion": "1.0.0",
  "locale": "de-DE",
  "categories": { "chat": true, "friends": true, "friend_online": true, "invites": true, "hosting": true, "packs": true, "team": true, "achievements": true },
  "preview": false,
  "pushWhilePlaying": false,
  "current": true,
  "createdAt": "…", "updatedAt": "…", "lastSeenAt": "…",
  "lastSuccessAt": null,
  "failing": false
}
```

`id` matches `^d[0-9a-f]{20}$`. `endpointHost` is only the host (the full URL is the device's secret and is never returned). `current` = registered with the session of this request. `failing` = the last delivery failed.

| Request | Body | Response |
|---|---|---|
| `GET /v1/push/devices` | – | `{ "devices": [PushDeviceView] }` (all your devices, oldest first) |
| `PATCH /v1/push/devices/{id}` | any of `deviceName`, `appVersion`, `locale`, `categories` (partial, merged), `preview`, `pushWhilePlaying`, and `endpoint` + `keys` **together** (new distributor endpoint, UnifiedPush only, same checks as above) | `PushDeviceView`. `404 device_not_found` (not yours), `400 not_unifiedpush`, `400 invalid_request` (empty body, endpoint without keys) |
| `DELETE /v1/push/devices/{id}` | – | `204`. `404 device_not_found` |

Limit 60 / min for PATCH/DELETE.

### 33.3 Categories and what is sent

| Category | Events (§19) | Notes |
|---|---|---|
| `chat` | `chat_message` | not your own, not in muted conversations, not from blocked players, no system messages |
| `friends` | `friend_request`, `friend_added` | `friend_added` only when the **other** player accepted (not your own accept on another device) |
| `friend_online` | `friend_online` | **off by default** |
| `invites` | `hosting_invite`, `cape_offer` | |
| `hosting` | `hosting_join_request` (you host), `hosting_join_accepted`, `hosting_kicked` | |
| `packs` | `pack_shared` | |
| `team` | `sanction_added`, `appeal_decided`, `report_update` (only `resolved`), `application_updated` (only team decisions, not `new`/`withdrawn`), `circuit_submission_updated` (only `approved`/`rejected`), `issue_updated` | |
| `achievements` | `achievement_unlocked` | |

Every other event type produces no notification. New event types may be added to a category later.

### 33.4 Poll devices: `GET /v1/push/pending?device={id}&since={cursor}&limit={1-100}`

For `kind: "poll"` devices (else `400 not_poll_device`; not yours: `404 device_not_found`). Limit 30 / min. `limit` defaults to 50.

```json
{ "notifications": [PushPayload], "cursor": "1842", "more": false }
```

- `since` = `cursor` of the previous response (`0` at first). Everything up to and including `since` counts as **delivered and is deleted**.
- `more: true`: call again right away with the new `cursor`.
- Entries are kept at most **72 hours** (less for short-lived kinds, see `TTL` in §33.5) and at most **200** per device (oldest dropped). They are stored encrypted like chat messages.
- Each call updates `lastSeenAt`.

### 33.5 Payload

The UnifiedPush message body (after decryption) and each `notifications[]` entry:

```json
{
  "v": 1,
  "id": "mfz2k1a3b4c.1842",
  "type": "chat_message",
  "category": "chat",
  "title": "Alice",
  "body": "Neue Nachricht",
  "target": "/chat/c0123456789abcdef0123",
  "collapse": "chat:c0123456789abcdef0123",
  "at": "2026-10-03T12:00:00.000Z"
}
```

- `id` = the event id from §19 (`<epoch>.<n>`), unique; use it to drop duplicates.
- `title` ≤ 80, `body` ≤ 200 characters, already in the device's language. Names are cut to 32 characters.
- `collapse`: same value = replace/group the visible notification (one per conversation, world, friend …).
- Ignore unknown `type`s and fields; `v` changes only on breaking changes.

| `type` | `target` |
|---|---|
| `chat_message` | `/chat/{conversationId}` |
| `friend_request` | `/friends/requests` |
| `friend_added`, `friend_online` | `/friends` |
| `cape_offer` | `/capes/offers` |
| `hosting_invite`, `hosting_join_accepted` | `/worlds/{roomId}` |
| `hosting_join_request` | `/worlds/{roomId}/requests` |
| `hosting_kicked` | `/worlds` |
| `pack_shared` | `/packs/{packId}` |
| `sanction_added`, `appeal_decided` | `/moderation/sanctions/{sanctionId}` |
| `report_update` | `/moderation/reports` |
| `application_updated` | `/team/applications/{applicationId}` |
| `circuit_submission_updated` | `/circuits/submissions/{submissionId}` |
| `issue_updated` | `/issues/{number}` |
| `achievement_unlocked` | `/achievements/{achievementId}` |

**Sending (UnifiedPush):** `POST <endpoint>` with `Content-Encoding: aes128gcm`, `Content-Type: application/octet-stream`, `TTL`, `Urgency` and `Authorization: vapid t=<ES256 JWT: aud = endpoint origin, exp ≤ 12 h, sub = VAPID_SUBJECT>, k=<vapidPublicKey>`. One record (record size 4096), padded to 64-byte blocks, at most 4096 bytes in total.

| Kind | `Urgency` | `TTL` |
|---|---|---|
| chat | `high` | 1 day |
| world invite / join request, join accepted | `high` | 30 min / 10 min |
| friend online | `low` | 5 min |
| achievements, issues, circuits, reports | `low` | 1 day / 3 days |
| everything else | `normal` | 1 hour to 3 days |

Delivery: at most 8 requests at once, queue of 5000 (more is dropped). Network errors, `408`, `425`, `429` and `5xx` are retried after 5 s, 30 s, 2 min (or `Retry-After`, ≤ 10 min). `404`/`410` remove the device at once; other failures count, 25 in a row remove the device. Responses are read up to 8 KiB and discarded, redirects are not followed, timeout 10 s.

### 33.6 No duplicates

A notification is **not** sent to a device when:

1. its category is off for that device, or
2. **the app itself is open on that device**: it has a `GET /v1/events/me?pushDevice={id}` stream open, or closed less than 60 s ago (the app shows it in-app), or
3. **you are playing on the PC**: a desktop stream (`GET /v1/events/me` without `pushDevice`, i.e. launcher or TRS Client) is open or closed < 60 s ago **and** your presence is `in-game` (the TRS Client shows the toast), unless `pushWhilePlaying: true`.

The launcher merely running in the background does **not** suppress notifications. `pushDevice` with an id that is not yours is ignored (the stream counts neither as app nor as desktop).

### 33.7 Server configuration and data (migration 23)

`VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY` (base64url key pair from `node scripts/vapid-keys.mjs`) and `VAPID_SUBJECT` (`mailto:…` or `https://…`): all three or none. A broken or mismatched pair stops the server at start. Keep the pair stable: apps must register again after a change.

Tables `push_devices` (bound to `users` and `sessions` with `ON DELETE CASCADE`; endpoint unique) and `push_pending` (poll entries, payload encrypted with the chat key, AAD `push:<device id>`, `ON DELETE CASCADE` with the device). Idempotent. When merging: renumber if a parallel branch also added a migration 23.

Why no `web-push` library: `node:crypto` has everything (ECDH P-256, HKDF, AES-128-GCM, ES256 with `ieee-p1363`), the code is about 150 lines and tested against the RFC 8291 appendix A vector, and the SSRF guard needs our own HTTPS request anyway (checked DNS lookup per connection). No new dependency.
