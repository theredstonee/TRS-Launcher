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
  ("Free server by craftinghosting.free × trs-launcher.de") and restarts the server once (`setup: "restarting"`). Keep
  heartbeating while `setup` is `restarting` even though `session.active` is false, and treat it as "not ready yet"
  when joining.

## Own address `<name>.trs-launcher.de`

Every server gets its own address. The API keeps a DNS SRV record `_minecraft._tcp.<name>.trs-launcher.de` pointing at
the partner node (`host:port`, fixed per server), so players connect directly to CraftingHosting with their real IP.

- Name rules: 3–24 characters, `a-z`, `0-9` and `-`, no `-` at the start or end, lower case (the launcher lowercases
  input). Reserved names (`www`, `api`, `play`, `docs`, `wiki`, `mail`, `admin`, …) and names that already exist in DNS
  are not available.
- `GET /v1/craftinghosting/subdomain?name=<name>` -> `{ "name": "<normalized>", "available": boolean,
  "reason": null | "invalid" | "reserved" | "taken" }` (own current name counts as available).
- `POST /v1/craftinghosting/server` additionally takes `subdomain` (required in the launcher's form; the API accepts a
  missing one and then derives one from the server name).
- `PATCH /v1/craftinghosting/server` additionally takes `subdomain` (change the address; the old one stops working).
- `Server` gains `"domain": "meinewelt.trs-launcher.de" | null` (null = no address yet, e.g. DNS not set up on this
  TRS server). Show `domain` as the main address (copy button) and use it for "Join"; show the node address
  (`address`) only small as a fallback/detail.
- `Server` also has `"ip": "1.2.3.4:25011" | null` (numeric node IPv4 and port). The launcher shows `domain` by default and
  lets the user switch the displayed/copied address to `ip` ("IP & port") or `address` ("CraftingHosting address");
  "Join" always uses `domain`, falling back to `address`.
- New error codes: `400 invalid_subdomain`, `409 subdomain_taken`, `409 subdomain_reserved`,
  `503 dns_unavailable` (DNS not configured on the TRS server – creating still works, `domain` stays null).

## Files, all server properties, plugins & mods (launcher only)

All paths are absolute inside the server (`/server.properties`, `/plugins/Foo/config.yml`), no `..`, ≤ 512 chars.
Changes to files, properties and add-ons apply on the next server start (say so in the UI).

- `GET /v1/craftinghosting/server/files?dir=/` → `{ dir, entries: [{ name, path, type: "file" | "directory", size,
  modifiedAt, editable, protected }] }` – `editable` = text file the editor may open (≤ 1 MB, known text extension or
  no extension), `protected` = managed by TRS (can't be edited/deleted: the TRS Server Rules jar, its hosting file,
  `whitelist.json` while access is "friends").
- `GET /v1/craftinghosting/server/files/content?path=` → `{ path, content }` (`415 file_not_text`, `413 file_too_large`).
- `PUT /v1/craftinghosting/server/files/content?path=` body `{ content }` (≤ 1 MB) → `{ path, size }`.
  `/server.properties` is post-processed: locked keys keep their old value, `accepts-transfers=true`, MOTD rule.
- `POST /v1/craftinghosting/server/files/delete` body `{ paths: [] }` (≤ 50) → 204. `POST …/files/folder` body
  `{ path }` → 204. Protected paths → `403 file_protected`.
- `GET /v1/craftinghosting/server/properties` → `{ properties: { "<key>": "<value>" }, locked: ["server-port", …] }`
  (all keys from server.properties as strings; `motd` is line 1 only, like settings). `PATCH` body
  `{ properties: { "<key>": "<value>" } }` (keys `^[a-z0-9.-]{1,64}$`, values ≤ 500 chars, no line breaks) →
  same shape. Locked keys → `400 property_locked`. Build the UI from the keys present, with nice labels/types for the
  well-known ones (booleans as switches, numbers as number fields, difficulty/gamemode/level-type as selects, the rest
  as text) and a search field; unknown keys as text. Keep the existing quick settings form too or merge it – your call.
- `GET /v1/craftinghosting/server/addons` → `{ kind: "plugins" | "mods" | null, dir, items: [{ file, size,
  modifiedAt, protected }] }` (`null` for vanilla). `DELETE /v1/craftinghosting/server/addons?file=<jar name>` → 204
  (`403 file_protected` for TRS Server Rules).
- Install from Modrinth: existing `POST …/server/mods` `{ projects: [id | slug] }`. The launcher searches Modrinth
  itself (its existing Modrinth client) with the right facets: Paper/Purpur → project type plugin with categories
  paper/purpur/spigot/bukkit; Fabric → mod + fabric; Forge → mod + forge; always the server's Minecraft version
  (`latest` = newest release) and `server_side != unsupported`.
- Config editing: from an installed plugin/mod open its config folder in the file browser (`/plugins/<Name>/`,
  `/config/`).
- New error codes: `file_not_text`, `file_too_large`, `file_protected`, `property_locked`, `invalid_path`.

## Worlds (launcher only)

A world is a top-level folder with `level.dat` (Paper: `<name>_nether`, `<name>_the_end` next to it). Names
`^[A-Za-z0-9_-]{1,32}$`. Changing worlds needs a stopped server (`409 server_running`).

- `GET /v1/craftinghosting/server/worlds` → `{ active, exists, worlds: [{ name, active, dimensions: [] }] }`.
- `PATCH /v1/craftinghosting/server/worlds` `{ active }` → same shape (sets `level-name`; a missing world is generated
  on the next start, with seed/level-type from the settings).
- `DELETE /v1/craftinghosting/server/worlds?name=` → same shape (all dimensions; the active one = reset).
- **Download** (done by the Rust core, files never pass the TRS API): `GET …/server/world/files?name=` →
  `{ name, files: [{ path, size }], truncated }`, then in batches of ≤ 100 `POST …/server/files/download-urls`
  `{ paths }` → `{ urls: [{ path, url }], expiresAt }` (https, host `*.craftinghosting.free`, valid 10 min – fetch
  them just before downloading). Download with limited concurrency (e.g. 4) into a ZIP whose entries keep the server
  paths without the leading `/` (e.g. `world/level.dat`, `world_nether/DIM-1/region/…`), progress events, cancel.
  Save to a user-chosen file (save dialog) – default name `<server>-<world>-<date>.zip`.
- **Upload** as a named world: existing upload + `POST …/server/world/import` `{ file: "trs-world.zip", name,
  activate }` – the ZIP's top-level folder must be `name` (zip the instance world folder as `<name>/…`).
- Version/type change: NOT available yet (CraftingHosting will add it). Show it in the settings as a disabled
  "Version wechseln – kommt bald" row, nothing else.

## Read-only server software, pinned Modrinth versions

- The API marks server software as `protected` (read-only: no edit/delete/new folder → `403 file_protected`): every
  `*.jar` in `/`, `eula.txt`, `start.sh`, `run.sh`, `run.bat`, `user_jvm_args.txt`, `.env`, `usercache.json`,
  `version_history.json`, and everything under `/libraries`, `/versions`, `/cache`, `/.cache`, `/bundler`, `/.fabric`,
  `/.paper-remapped`, `/plugins/.paper-remapped`, `/logs`, `/crash-reports`. Text files there can still be **viewed**
  (open read-only in the editor, no save button). Configs stay editable.
- World names may not be `plugins`, `mods`, `config`, `libraries`, `versions`, `cache`, `logs`, `crash-reports`,
  `bundler`, `defaultconfigs`, `kubejs`, `scripts` (case-insensitive).
- `POST …/server/mods` also takes `versions: [{ project, version }]` (Modrinth version id, ≤ 20) to install a specific
  version (`projects` optional then).

## Feature switches (no launcher update for new partner functions)

`GET /v1/craftinghosting/info` also returns `features: { files, properties, addons, worlds, worldDownload, ownAddress,
wakeOnJoin, versionChange, backups }` (booleans; unknown/missing keys = false) and `notice: string | null`.
The launcher shows each section only when its switch is true, and shows `notice` as an info banner at the top of the
hosting tab. Already implement (hidden while the switch is false):

- **Version wechseln** (settings): dialog with server type cards + version select from `info.serverTypes`, option
  "Welt behalten" (default on), clear warning (plugins/mods may not fit the new version; the server restarts) →
  `POST /v1/craftinghosting/server/version { serverType, version, keepWorld }` → `{ server }`; then poll like after a
  start. While `versionChange` is false show nothing (no "kommt bald" row needed – or keep it subtle, your call).
- **Backups** (own section in Welten or own tab): list `GET …/server/backups` → `{ backups: [{ id, name, size,
  createdAt }], max }`, "Backup erstellen" `POST …/server/backups`, "Wiederherstellen" `POST …/backups/{id}/restore`
  (confirm, server must be offline), "Herunterladen" `GET …/backups/{id}/download` → `{ url, expiresAt }` (https,
  `*.craftinghosting.free` only, download via core with save dialog), "Löschen" `DELETE …/backups/{id}`.
- Error `501 feature_unavailable` → friendly "Noch nicht verfügbar" (no error toast spam) and reload `info`.

## Team control: notice (Markdown), maintenance, more switches, blocked servers

- `info.notice` is now an object or null: `{ markdown, level: "info" | "warning" | "success", updatedAt }` (render the
  Markdown safely with the existing MarkdownView/DOMPurify, colour by level). Tolerate a plain string too (old API).
- `info.maintenance: { active, message }` → banner "Wartung" with the message; disable create/start/join while active.
- New switches `create` (creating servers) and `console` (command field) in `info.features`.
- `Server.blocked: { reason, since } | null` → red banner "Vom TRS-Team gesperrt: <reason>", start/join disabled.
- New errors: `503 hosting_maintenance` (`details.message`), `403 server_blocked` (`details.reason`),
  `403 feature_disabled` (`details.feature`) → friendly messages, reload `info`/server.

## Slow start and EULA in the wizard (API §43.16)

- The create wizard has a required checkbox for the Minecraft EULA; `POST /v1/craftinghosting/server` sends
  `acceptEula: true`. The API stores it, reports `eulaAccepted: true` from then on and sends the EULA consent with the
  first start itself – the launcher shows no EULA dialog, and TRS Wake can start the server right away.
- If CraftingHosting is still bringing the node up (`503 service_unavailable`), `start` answers with
  `state: "starting"` and the API retries in the background for up to 3 minutes. The console then returns the single
  line "Server is starting …" instead of an error. No launcher change was needed for this part.

## Runtime rules (API §43.17)

The TRS API enforces its own runtime rules (defaults, the team can change them): 24 h runtime in total over all
sessions, then a 2 h break; 3 days unused = full quota again; Mon–Sun (Berlin) at least 8 h offline; warning 30 min
before the stop (launcher toast + `tellraw` in the game). `Server.quota` (`usedSeconds`, `budgetSeconds`,
`remainingSeconds`, `weekUsedSeconds`, `weekCapSeconds`, `weekResetsAt`, `idleResetAt`, `cooldownUntil`) and
`info.rules` (`budgetHours`, `cooldownHours`, `idleResetDays`, `weeklyOfflineHours`, `warnMinutes`) carry the values;
the core passes them through (`Quota`, `Rules`), the header shows time left / week / reset, the plan tiles explain the
rules. A start during the break answers `409 cooldown` with `cooldownUntil` (shown as "start again in …").
