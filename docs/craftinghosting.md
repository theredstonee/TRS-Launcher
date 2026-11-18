# CraftingHosting partner servers

Contract between the TRS API (`/v1/craftinghosting/*`, API.md §43) and the launcher. The partner API key only lives on
the TRS API server; the launcher never talks to CraftingHosting directly.

Every user (TRS account) has at most one server. All routes need the TRS bearer token.

## Routes

| Method | Path | Body | Response |
| --- | --- | --- | --- |
| GET | `/v1/craftinghosting/info` | – | `Info` |
| GET | `/v1/craftinghosting/server` | – | `{ "server": Server \| null }` |
| POST | `/v1/craftinghosting/server` | `{ name, serverType, version, access }` | 201 `{ "server": Server }` |
| PATCH | `/v1/craftinghosting/server` | `{ access?, name? }` | `{ "server": Server }` |
| POST | `/v1/craftinghosting/server/start` | `{ acceptEula: boolean }` | `{ "server": Server }` |
| POST | `/v1/craftinghosting/server/heartbeat` | – | `{ "server": Server }` |
| POST | `/v1/craftinghosting/server/stop` | `{ whenEmpty?: boolean }` | `{ "server": Server }` |
| POST | `/v1/craftinghosting/server/command` | `{ command }` | 204 |
| DELETE | `/v1/craftinghosting/server` | – | 204 |
| GET | `/v1/craftinghosting/server/settings` | – | `{ "settings": Settings }` |
| PATCH | `/v1/craftinghosting/server/settings` | partial `Settings` | `{ "settings": Settings }` |
| GET | `/v1/craftinghosting/server/logs?lines=200` | – | `{ "lines": string[] }` (1–1000 lines, newest last) |
| GET | `/v1/craftinghosting/server/stats` | – | `Stats` |
| POST | `/v1/craftinghosting/server/mods` | `{ projects: string[] }` (Modrinth ids or slugs, 1–100) | `ModsResult` |
| POST | `/v1/craftinghosting/server/world/upload` | – | `{ "url": string, "expiresAt": string }` |
| POST | `/v1/craftinghosting/server/world/import` | `{ file: "trs-world.zip" }` | `{ "server": Server }` |

- `name`: 3–22 characters, letters, digits, spaces and `. _ ' -`.
- `serverType` / `version`: values from `Info.serverTypes`.
- `access`: `"friends"` (whitelist with the owner and their TRS friends, kept in sync by the API while the server
  runs) or `"open"`.
- `command`: 1–256 characters, no line breaks, without a leading `/`. Output: read `…/logs` a moment later.
- Settings, mods and world changes need an installed server (`409 server_installing` otherwise) and apply on the
  next start.

```jsonc
// Info
{
  "plan": { "name": "Free 4 GB", "memoryMb": 4096, "cpuPercent": 200, "diskMb": 10240 },
  "serverTypes": [{ "id": "paper", "name": "Paper", "versions": ["latest", "26.3", "1.21.11"] }],
  "limits": {
    "maxServersPerPlayer": 1, "sessionMaxHours": 24, "cooldownHours": 2,
    "heartbeatTimeoutSeconds": 180, "deleteAfterIdleDays": 30, "serverNameMaxLength": 22
  },
  "eulaUrl": "https://www.minecraft.net/eula",
  "full": false // no free places left for new servers
}

// Server
{
  "id": "0b6f6c0e-…",
  "name": "Meine Welt",           // without the " #P7KXGQXL" suffix
  "playerId": "P7KXGQXL",
  "state": "installing",          // installing | offline | starting | running | stopping | failed | suspended
  "address": "node1.example.com:25568",
  "serverType": "paper",
  "version": "latest",
  "resources": { "memoryMb": 4096, "cpuPercent": 200, "diskMb": 10240 },
  "eulaAccepted": false,
  "access": "friends",
  "session": {
    "active": false,
    "startedAt": null,
    "lastHeartbeatAt": null,
    "heartbeatTimeoutSeconds": 180,
    "runSeconds": 0,
    "maxRunSeconds": 86400,
    "remainingSeconds": 86400,
    "cooldownUntil": null,
    "lastStopReason": null        // host_stopped | host_timeout | session_limit | admin | null
  },
  "createdAt": "2026-11-18T15:59:20.000Z",
  "lastStartAt": null,
  "deleteAt": "2026-12-18T15:59:20.000Z", // deleted with all data if not started before; every start pushes it back
  "setup": "pending"              // pending | restarting | done – first setup after the first start (API.md §43.7)
}
// session additionally has "hostPresent": boolean – false = the host's launcher no longer reports in and the server
// only keeps running because players are on it.

// Settings (all optional in PATCH; "whitelist" is not settable here, it follows "access")
{
  "motd": "Mein Server",            // line 1 only, ≤ 60 chars, no § codes; line 2 is the fixed footer
  "maxPlayers": 8,                  // 1–100
  "gamemode": "survival",           // survival | creative | adventure | spectator
  "difficulty": "normal",           // peaceful | easy | normal | hard
  "hardcore": false, "pvp": true, "allowFlight": false,
  "seed": "",                       // ≤ 64 chars, only for a newly generated world
  "levelType": "normal",            // normal | flat | large_biomes | amplified, only for a new world
  "spawnProtection": 16,            // 0–64
  "viewDistance": 10, "simulationDistance": 10, // 3–16
  "whitelist": true                 // read-only
}

// Stats (every number may be null when unknown)
{
  "cpuPercent": 12.5,
  "memory": { "usedBytes": 1073741824, "limitBytes": 4294967296 },
  "disk": { "usedBytes": 524288000, "limitBytes": 10737418240 },
  "network": { "rxBytes": 1000, "txBytes": 2000 },
  "uptimeSeconds": 3600,
  "players": { "online": 2, "max": 20 } // null while the server does not answer
}

// ModsResult – client-only mods (Modrinth server_side = unsupported) are skipped by the API
{
  "installed": ["sodium-extra"],
  "skipped": [{ "project": "sodium", "reason": "client_only" }] // reason: client_only | not_found | unsupported
}
```

### World upload

1. `POST …/world/upload` → short-lived `url` (10 min, host `*.craftinghosting.free`; reject any other host).
2. The launcher zips the instance's world folder so that the archive contains one top-level folder `world/`
   (level.dat inside) and uploads it directly to `url` as `multipart/form-data`, field name `files`, file name
   `trs-world.zip`. Large uploads do not go through the TRS API.
3. `POST …/world/import { "file": "trs-world.zip" }` – the API removes the old `world`, `world_nether` and
   `world_the_end`, extracts the archive and deletes the ZIP. Only while the server is offline
   (`409 server_running` otherwise).

## Errors

Usual TRS error shape `{ "error": { "code", "message", "details"? } }`.

| Status | code | Meaning |
| --- | --- | --- |
| 503 | `craftinghosting_unavailable` | Not configured on this TRS server or the partner key was rejected |
| 503 | `craftinghosting_maintenance` | CraftingHosting maintenance, try later |
| 502 | `craftinghosting_upstream` | CraftingHosting not reachable or overloaded |
| 404 | `no_server` | The user has no server (start/stop/heartbeat/command/patch/delete) |
| 409 | `server_exists` | Create while the user already has a server |
| 409 | `craftinghosting_full` | No free places left for new servers |
| 409 | `server_installing` | Still being set up |
| 409 | `eula_required` | First start without `acceptEula: true` |
| 409 | `cooldown` | Mandatory pause, `details.cooldownUntil` |
| 409 | `not_running` | Heartbeat/command without a running session, `details.reason` = last stop reason |
| 409 | `server_failed` / `server_suspended` | Setup failed / suspended by CraftingHosting |
| 400 | `invalid_name` / `server_type_unavailable` / `version_unavailable` / `invalid_request` | Bad input |
| 400 | `invalid_path` / `file_rejected` | Upload/import rejected |
| 404 | `file_not_found` | Uploaded world ZIP not found |
| 409 | `mods_unsupported` | Server type has no mods/plugins (vanilla) |
| 409 | `server_running` | World import while the server runs |

## Launcher behaviour

- Heartbeat every 45 s while the launcher is open and the server of the active TRS account has an active session
  (after `start`, or when a loaded `Server` has `session.active`). Stop sending on `not_running`, `no_server`, or when
  the account changes.
- On launcher exit: `POST …/stop { "whenEmpty": true }` if a session is active (short timeout) – friends who are
  still playing keep the server running; it stops when the last player leaves.
- Start flow: `start` → poll `GET /server` every 5 s until `state = running` → show the address / join.
- First setup: after the first start the API installs TRS Server Rules with the fixed MOTD footer
  ("Free server: trs-launcher.de × craftinghosting.free") and restarts the server once (`setup: "restarting"`). Keep
  heartbeating while `setup` is `restarting` even though `session.active` is false, and treat it as "not ready yet"
  when joining.
