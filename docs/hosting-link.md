# Hosted worlds: launcher → game (TRS Link)

How the launcher tells a game it started (or one that is already running) to **join a hosted world** of a friend.
Hosting itself, P2P/ICE and the relay are the game's job (API §21, `relay/PROTOCOL.md`). The launcher only does the
access part in the TRS API (`POST /v1/hosting/join` → 200 accepted / 202 requested → waits for
`hosting_join_accepted`) and then hands the game one small instruction over the secured TRS Link v2
(`src-tauri/crates/core/src/link/`).

**No secrets travel this way.** The instruction contains the room ID, the join code (when known) and display data.
The mod gets its own relay token with its own TRS login: `POST /v1/hosting/rooms/{roomId}/connect` (the player is
already `accepted`, so this succeeds; `403 not_accepted` means the host removed them in the meantime). Relay tokens,
STUN lists and `hosting_signal` never reach the launcher's web view or the link.

Nothing is passed on the command line, in files or in environment variables – only over the authenticated link
connection (loopback, HMAC handshake, peer = game process).

## 1. Capability negotiation

| Direction | Where | Value |
|---|---|---|
| launcher → game | `challenge.features` | contains `"hosting.join"` (launcher supports it) |
| game → launcher | `auth.features` **(new, optional)** | the mod adds `"hosting.join"` when it can join hosted worlds |

```json
{"type":"auth","proof":"<hex>","features":["hosting.join"]}
```

- `features` is an optional array of strings in the existing `auth` line (≤ 32 entries are looked at). Older mods
  don't send it – the launcher then treats the game as **not able** to join hosted worlds and never pushes an
  instruction to it (it tells the user "TRS Client too old" instead).
- The whole `auth` line must still fit the 1 KB line limit.

## 2. Push: `hostingJoin` (launcher → game)

Sent as a normal line after the first `state` line, when

- a join is pending for this game when it signs in (the usual case: "Join" started the instance), or
- a join is queued while the game is already connected (the instance was running when the user pressed "Join").

```json
{"type":"hostingJoin","join":{
  "roomId":"h0123456789abcdef0123",
  "code":"K7QM2X",
  "name":"Insel",
  "host":{"uuid":"75c1a6f3112240abbdb57b9d21c64232","name":"Theredstonee"},
  "mcVersion":"1.21.11",
  "loader":"fabric"
}}
```

| Field | Type | Notes |
|---|---|---|
| `roomId` | string | `^h[0-9a-f]{20}$` – **use this** for `…/rooms/{roomId}/connect` and signalling |
| `code` | string \| null | `^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}$`, upper case, no dash. `null` when the user joined from the list or an invite (guests don't see the code). Display only (`K7Q-M2X`). |
| `name` | string | world name, one line, ≤ 32 chars, already sanitised; may be `""` |
| `host` | `{uuid,name}` \| null | host player (32 hex UUID, no dashes) |
| `mcVersion` | string | `^[0-9A-Za-z][0-9A-Za-z._+ -]{0,31}$` |
| `loader` | string | `vanilla` \| `fabric` \| `forge` \| `neoforge` \| `quilt` |

Rules:

- **Exactly once.** The launcher removes the instruction from the session when it writes it. It is only sent to a
  connection whose `auth` listed `hosting.join`.
- A newer instruction replaces an older one that was not delivered yet. A new game start (new link session) drops any
  old one. After 10 minutes without a game connecting it expires.
- The mod should **validate** the fields again (IDs/regexes above) and ignore unknown fields.
- When to act is up to the mod: e.g. keep it until the title screen (or the current world) is ready, ask
  "Leave the current world to join Insel?" if the player is already playing, then connect.

## 3. Pull: request `hosting.join` (game → launcher, optional)

For robustness (e.g. the mod reconnects to the launcher) the mod may ask for a pending instruction:

```json
{"type":"req","id":7,"op":"hosting.join"}
```
```json
{"type":"res","id":7,"ok":true,"join":{ …same object as above… }}
{"type":"res","id":7,"ok":true,"join":null}
```

- Same "exactly once" rule: a pulled instruction is gone afterwards.
- Throttled to one request per 250 ms (`"error":"rate_limited"`). Only for protocol v2 connections (`not_allowed`
  otherwise).

## 4. What the mod does with it

1. `POST /v1/hosting/rooms/{roomId}/connect` with its own TRS bearer token → `ConnectInfo {role, relay, stun}`.
   - `404 room_not_found` → world closed; `403 not_accepted` → host removed the player; show a short message.
2. Connect as described in API §21.4/§21.7 (P2P via `signal`, relay as fallback).
3. Nothing needs to be reported back to the launcher. (The launcher shows "The game is joining …" as soon as the
   instruction was written to the link.)

## 5. Launcher side (for reference)

| Piece | Where |
|---|---|
| Queue + delivery state (`pending`/`delivered`/`unsupported`/`expired`) | `TrsLink::queue_hosting_join`, `TrsLink::hosting_delivery` (`link/mod.rs`) |
| Launch with a world | `Launcher::launch(id, Some(Join::World(&world)))` – checks version/loader, TRS Client present, not a legacy (≤ 0.5.0) mod; if the instance runs already, hands the instruction to that game |
| Tauri | `launch_instance(…, joinWorld)`, `hosting_join`, `hosting_friends_rooms`, `hosting_my_rooms`, `hosting_room`, `hosting_leave`, `hosting_delivery` |
| Tests (fake game peer) | `link/tests.rs` (`welt_beitritt_*`, `alte_mod_ohne_merkmal_*`) |

Test vector for a fake peer (Rust helper `v2_login_with(&handoff, false, Some(&["hosting.join"]))`): after the
handshake the game reads `{"type":"state",…}` and then the `hostingJoin` line above.
